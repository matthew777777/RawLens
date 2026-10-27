// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Audio capture for video: 48 kHz stereo-first from the camcorder mic (mono
 * fallback when stereo init fails). With `preferFloat` (RAW video, `.craw`)
 * it records ENCODING_PCM_FLOAT, which cannot clip digitally (peaks above
 * 0 dBFS survive), falling back to PCM16 where the device lacks float input;
 * without it (AAC paths) it records PCM16. Stereo is the reference.
 *
 * Timestamp model: [AudioRecord] positions live in the audio clock, video
 * frames in boot-time ns. Each chunk's [Chunk.timestampNs] is mapped with
 * [AudioRecord.getTimestamp] ([AudioTimestamp.TIMEBASE_BOOTTIME] =
 * `CLOCK_BOOTTIME`, the same domain as `Image.timestamp` when the sensor
 * uses realtime timestamps): `chunkTs = anchorNs +
 * (chunkStartFrame - anchorFrame) * 1e9 / SAMPLE_RATE`. When the anchor call
 * fails, falls back to read-time minus chunk duration (ms-accurate, fine for
 * video sync). First-chunk ts should land within ~1 video frame of the first
 * frame ts; the record test asserts that loosely, not sample-exactness.
 *
 * Owns its thread and never touches [CrawWriter]: chunks are handed to
 * the recorder's encode thread, which owns all container writes. Chunk
 * timestamps stay boot-time here; the committer rebases them to
 * recording-relative on write (absolute timestamps overflow 32-bit ms
 * math in some readers, breaking audio sync).
 */
internal class AudioPcmRecorder {
    data class Chunk(
        // direct little-endian interleaved samples, position 0,
        // limit frames * channels * bytesPerSample
        val data: ByteBuffer,
        val frames: Int, // per-channel frames
        val channels: Int, // always 2 (mono capture upmixes to dual-mono)
        val timestampNs: Long, // first sample, boot-time ns
        val float: Boolean = false, // F32 when true, else PCM16
    ) {
        val bytesPerSample: Int get() = if (float) 4 else 2
    }

    data class Stats(val chunks: Int, val frames: Long)

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val chunkCount = AtomicLong(0)
    private val frameCount = AtomicLong(0)

    /** Output channel count: always 2 (stereo native, dual-mono upmix). Valid after [start]. */
    var channels = 0
        private set

    /** Latest chunk peak 0..1 (max abs over both channels) for HUD meters. Stale-safe: HUD decays it. */
    @Volatile var lastPeak = 0f
        private set

    /** Latest chunk peaks per channel in dBFS, clamped to [METER_MIN_DB, METER_MAX_DB]. */
    @Volatile var lastPeakDbfsL = METER_MIN_DB
        private set
    @Volatile var lastPeakDbfsR = METER_MIN_DB
        private set

    /** True when the active stream is ENCODING_PCM_FLOAT. Valid after [start]. */
    var isFloat = false
        private set

    /**
     * @return false when the mic is unavailable (no permission, no hardware,
     * mic privacy toggle). Callers record silent video instead — legal .craw.
     */
    fun start(onChunk: (Chunk) -> Unit): Boolean = start(preferFloat = false, onChunk = onChunk)

    fun start(preferFloat: Boolean, onChunk: (Chunk) -> Unit): Boolean {
        check(thread == null) { "Already started" }
        // Stereo first (the reference configuration), mono fallback.
        // Each candidate must fully initialize AND record before it is
        // kept; anything else is released and the next is tried.
        val encodings = if (preferFloat) listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
            else listOf(AudioFormat.ENCODING_PCM_16BIT)
        val configs = encodings.flatMap { enc ->
            listOf(Triple(AudioFormat.CHANNEL_IN_STEREO, 2, enc), Triple(AudioFormat.CHANNEL_IN_MONO, 1, enc))
        }
        var rec: AudioRecord? = null
        var selected = 0
        var selectedFloat = false
        for ((mask, ch, encoding) in configs) {
            val minBuf = try {
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, mask, encoding
                )
            } catch (_: Exception) {
                continue
            }
            if (minBuf <= 0) continue
            val candidate = try {
                AudioRecord(
                    MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE,
                    mask, encoding,
                    minBuf * 4
                )
            } catch (_: Exception) {
                continue
            }
            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                try {
                    candidate.release()
                } catch (_: Exception) {
                }
                continue
            }
            // Establish a real recording stream before declaring audio.
            try {
                candidate.startRecording()
                check(candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            } catch (_: Exception) {
                try {
                    candidate.release()
                } catch (_: Exception) {
                }
                continue
            }
            rec = candidate
            selected = ch
            selectedFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
            break
        }
        if (rec == null) {
            Log.w(TAG, "mic unavailable: no channel config records")
            return false
        }
        channels = 2
        isFloat = selectedFloat
        Log.i(TAG, "mic $selected ch ${if (selectedFloat) "F32" else "PCM16"} -> recording 2 channels")
        val active = rec
        record = active
        anchorWarned = false
        chunkCount.set(0)
        frameCount.set(0)
        running = true
        thread = Thread({
            val ch = selected
            val float = selectedFloat
            val shorts = if (float) ShortArray(0) else ShortArray(CHUNK_FRAMES * ch)
            val floats = if (float) FloatArray(CHUNK_FRAMES * ch) else FloatArray(0)
            val anchor = AudioTimestamp()
            while (running) {
                val read: Int = try {
                    if (float) active.read(floats, 0, floats.size, AudioRecord.READ_BLOCKING)
                    else active.read(shorts, 0, shorts.size)
                } catch (e: Exception) {
                    Log.w(TAG, "audio read failed: ${e.message}")
                    break
                }
                if (read < 0) {
                    Log.w(TAG, "audio read error $read")
                    break
                }
                // Whole frames only: a trailing half-frame would shift
                // every later channel alignment in the container.
                val frames = read / ch
                if (frames == 0) continue
                val tsNs = anchorFor(active, anchor, frames)
                // Mono fallback upmixes to dual-mono stereo: every chunk
                // leaves here as 2ch, so both containers are always stereo.
                val kept = frames * 2
                val direct: ByteBuffer
                var peakL = 0f
                var peakR = 0f
                if (float) {
                    val pcm = if (ch == 1) upmixMonoToStereo(floats, frames) else floats
                    direct = ByteBuffer.allocateDirect(kept * 4).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until kept) {
                        val v = pcm[i]
                        val a = kotlin.math.abs(v)
                        if (i and 1 == 0) { if (a > peakL) peakL = a } else if (a > peakR) peakR = a
                        direct.putFloat(v)
                    }
                } else {
                    val pcm = if (ch == 1) upmixMonoToStereo(shorts, frames) else shorts
                    direct = ByteBuffer.allocateDirect(kept * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until kept) {
                        val v = pcm[i]
                        val a = kotlin.math.abs(v.toInt()) / 32768f
                        if (i and 1 == 0) { if (a > peakL) peakL = a } else if (a > peakR) peakR = a
                        direct.putShort(v)
                    }
                }
                direct.flip()
                lastPeak = maxOf(peakL, peakR).coerceIn(0f, 1f)
                lastPeakDbfsL = peakDbfs(peakL)
                lastPeakDbfsR = peakDbfs(peakR)
                chunkCount.incrementAndGet()
                frameCount.addAndGet(frames.toLong())
                try {
                    onChunk(Chunk(direct, frames, 2, tsNs, float))
                } catch (e: Exception) {
                    Log.w(TAG, "audio chunk dropped: ${e.message}")
                }
            }
            try {
                active.stop()
            } catch (_: Exception) {
            }
        }, "RawVideoAudio").apply { start() }
        return true
    }

    fun stop(): Stats {
        running = false
        try {
            thread?.join(3000)
        } catch (_: InterruptedException) {
        }
        thread = null
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        return Stats(chunkCount.get().toInt(), frameCount.get())
    }

    /**
     * Chunk-start timestamp (boot-time ns) for the just-read [readFrames].
     *
     * Primary: read-completion clock minus chunk duration — each chunk is
     * anchored independently, so scheduling jitter never accumulates and no
     * HAL position semantics are trusted. Refinement: when
     * [AudioRecord.getTimestamp] succeeds *and* agrees with the clock within
     * 500ms, use the timestamp-anchor mapping instead (immune to read-side
     * scheduling jitter). HAL frame positions proved untrustworthy on some
     * stacks (off-seconds offsets), hence the agreement gate — accuracy is
     * ms-level either way, plenty for video sync.
     */
    private fun anchorFor(rec: AudioRecord, anchor: AudioTimestamp, readFrames: Int): Long {
        val endNs = SystemClock.elapsedRealtimeNanos()
        val readBased = endNs - readFrames * 1_000_000_000L / SAMPLE_RATE
        val total = frameCount.get() + readFrames // frames incl. this chunk
        val ok = try {
            rec.getTimestamp(anchor, AudioTimestamp.TIMEBASE_BOOTTIME) ==
                AudioRecord.SUCCESS && anchor.framePosition >= 0
        } catch (_: Exception) {
            false
        }
        if (ok) {
            val startFrame = (total - readFrames).coerceAtLeast(0)
            val anchored = anchor.nanoTime +
                (startFrame - anchor.framePosition) * 1_000_000_000L / SAMPLE_RATE
            if (kotlin.math.abs(anchored - readBased) < 500_000_000L) return anchored
            if (!anchorWarned) {
                anchorWarned = true
                Log.w(TAG, "audio HAL position disagrees with clock; using read-time anchor")
            }
        }
        return readBased
    }

    @Volatile private var anchorWarned = false

    companion object {
        const val SAMPLE_RATE = 48000
        private const val CHUNK_FRAMES = 2048 // ~43ms, ~23 chunks/s
        private const val TAG = "AudioPcmRecorder"

        /** Duplicate each mono sample to L/R (unit-tested). */
        /** Meter range: float input can carry peaks above full scale. */
        const val METER_MIN_DB = -60f
        const val METER_MAX_DB = 12f

        /** Linear peak (1.0 = full scale) to dBFS, clamped to the meter range. */
        internal fun peakDbfs(linear: Float): Float =
            if (!(linear > 0f)) METER_MIN_DB
            else (20f * kotlin.math.log10(linear)).coerceIn(METER_MIN_DB, METER_MAX_DB)

        internal fun upmixMonoToStereo(mono: FloatArray, frames: Int): FloatArray {
            require(frames >= 0 && frames <= mono.size)
            val stereo = FloatArray(frames * 2)
            for (f in 0 until frames) {
                stereo[f * 2] = mono[f]
                stereo[f * 2 + 1] = mono[f]
            }
            return stereo
        }

        internal fun upmixMonoToStereo(mono: ShortArray, frames: Int): ShortArray {
            require(frames >= 0 && frames <= mono.size)
            val stereo = ShortArray(frames * 2)
            for (f in 0 until frames) {
                stereo[f * 2] = mono[f]
                stereo[f * 2 + 1] = mono[f]
            }
            return stereo
        }
    }
}
