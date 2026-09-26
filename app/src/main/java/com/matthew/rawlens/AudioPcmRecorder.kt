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
 * P4 PCM16 audio capture for RAW video: 48 kHz stereo-first from the
 * camcorder mic (mono fallback when stereo init fails), chunked for the
 * `.mcraw` container. Stereo is the reference configuration — PhotonCamera
 * records it and motioncam-decoder's own fixture declares it.
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
 * Owns its thread and never touches [CinemaRawWriter]: chunks are handed to
 * the recorder's encode thread, which owns all container writes. Chunk
 * timestamps stay boot-time here; the committer rebases them to
 * recording-relative on write (absolute timestamps overflow 32-bit ms
 * math in some readers, breaking audio sync).
 */
internal class AudioPcmRecorder {
    data class Chunk(
        // direct PCM16LE, position 0, limit frames*channels*2 (interleaved)
        val data: ByteBuffer,
        val frames: Int, // per-channel frames; shorts = frames*channels
        val channels: Int, // 1 or 2, always matches [AudioPcmRecorder.channels]
        val timestampNs: Long, // first sample, boot-time ns
    )

    data class Stats(val chunks: Int, val frames: Long)

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val chunkCount = AtomicLong(0)
    private val frameCount = AtomicLong(0)

    /** Selected channel count (2 preferred, 1 fallback). Valid after [start]. */
    var channels = 0
        private set

    /** Latest chunk peak 0..1 (mono max abs) for HUD meters. Stale-safe: HUD decays it. */
    @Volatile var lastPeak = 0f
        private set

    /**
     * @return false when the mic is unavailable (no permission, no hardware,
     * mic privacy toggle). Callers record silent video instead — legal .mcraw.
     */
    fun start(onChunk: (Chunk) -> Unit): Boolean {
        check(thread == null) { "Already started" }
        // Stereo first (the reference configuration), mono fallback.
        // Each candidate must fully initialize AND record before it is
        // kept; anything else is released and the next is tried.
        val configs = listOf(
            AudioFormat.CHANNEL_IN_STEREO to 2,
            AudioFormat.CHANNEL_IN_MONO to 1,
        )
        var rec: AudioRecord? = null
        var selected = 0
        for ((mask, ch) in configs) {
            val minBuf = try {
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT
                )
            } catch (_: Exception) {
                continue
            }
            if (minBuf <= 0) continue
            val candidate = try {
                AudioRecord(
                    MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE,
                    mask, AudioFormat.ENCODING_PCM_16BIT,
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
            break
        }
        if (rec == null) {
            Log.w(TAG, "mic unavailable: no channel config records")
            return false
        }
        channels = selected
        Log.i(TAG, "recording $selected channels")
        val active = rec
        record = active
        anchorWarned = false
        chunkCount.set(0)
        frameCount.set(0)
        running = true
        thread = Thread({
            val ch = selected
            val shorts = ShortArray(CHUNK_FRAMES * ch)
            val anchor = AudioTimestamp()
            while (running) {
                val read: Int = try {
                    active.read(shorts, 0, shorts.size)
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
                val kept = frames * ch
                val tsNs = anchorFor(active, anchor, frames)
                var peak = 0
                for (i in 0 until kept) {
                    val a = kotlin.math.abs(shorts[i].toInt())
                    if (a > peak) peak = a
                }
                lastPeak = (peak / 32768f).coerceIn(0f, 1f)
                val direct = ByteBuffer.allocateDirect(kept * 2)
                    .order(ByteOrder.nativeOrder())
                for (i in 0 until kept) direct.putShort(shorts[i])
                direct.flip()
                chunkCount.incrementAndGet()
                frameCount.addAndGet(frames.toLong())
                try {
                    onChunk(Chunk(direct, frames, ch, tsNs))
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
    }
}
