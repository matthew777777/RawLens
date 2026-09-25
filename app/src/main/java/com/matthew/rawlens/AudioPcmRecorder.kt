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
 * P4 PCM16 audio capture for RAW video: 48 kHz mono from the camcorder mic,
 * chunked for the `.mcraw` container.
 *
 * Timestamp model: [AudioRecord] positions live in the audio clock, video
 * frames in boot-time ns. Each chunk's [Chunk.timestampNs] is mapped with
 * [AudioRecord.getTimestamp] ([AudioTimestamp.TIMEBASE_MONOTONIC] =
 * `CLOCK_BOOTTIME`, the same domain as `Image.timestamp` when the sensor
 * uses realtime timestamps): `chunkTs = anchorNs +
 * (chunkStartFrame - anchorFrame) * 1e9 / SAMPLE_RATE`. When the anchor call
 * fails, falls back to read-time minus chunk duration (ms-accurate, fine for
 * video sync). First-chunk ts should land within ~1 video frame of the first
 * frame ts; the record test asserts that loosely, not sample-exactness.
 *
 * Owns its thread and never touches [CinemaRawWriter]: chunks are handed to
 * the recorder's encode thread, which owns all container writes.
 */
internal class AudioPcmRecorder {
    data class Chunk(
        val data: ByteBuffer, // direct, PCM16LE, position 0, limit frames*2
        val frames: Int,
        val timestampNs: Long, // first sample, boot-time ns
    )

    data class Stats(val chunks: Int, val frames: Long)

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val chunkCount = AtomicLong(0)
    private val frameCount = AtomicLong(0)

    /** Latest chunk peak 0..1 (mono max abs) for HUD meters. Stale-safe: HUD decays it. */
    @Volatile var lastPeak = 0f
        private set

    /**
     * @return false when the mic is unavailable (no permission, no hardware,
     * mic privacy toggle). Callers record silent video instead — legal .mcraw.
     */
    fun start(onChunk: (Chunk) -> Unit): Boolean {
        check(thread == null) { "Already started" }
        val minBuf = try {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (e: Exception) {
            Log.w(TAG, "mic unavailable: ${e.message}")
            return false
        }
        if (minBuf <= 0) {
            Log.w(TAG, "mic unavailable: bad min buffer $minBuf")
            return false
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
        } catch (e: Exception) {
            Log.w(TAG, "mic unavailable: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "mic unavailable: state=${rec.state}")
            try {
                rec.release()
            } catch (_: Exception) {
            }
            return false
        }
        record = rec
        chunkCount.set(0)
        frameCount.set(0)
        running = true
        thread = Thread({
            try {
                rec.startRecording()
            } catch (e: Exception) {
                Log.w(TAG, "startRecording failed: ${e.message}")
                running = false
                return@Thread
            }
            val shorts = ShortArray(CHUNK_FRAMES)
            val anchor = AudioTimestamp()
            while (running) {
                val read: Int = try {
                    rec.read(shorts, 0, CHUNK_FRAMES)
                } catch (e: Exception) {
                    Log.w(TAG, "audio read failed: ${e.message}")
                    break
                }
                if (read < 0) {
                    Log.w(TAG, "audio read error $read")
                    break
                }
                if (read == 0) continue
                val tsNs = anchorFor(rec, anchor, read)
                var peak = 0
                for (i in 0 until read) {
                    val a = kotlin.math.abs(shorts[i].toInt())
                    if (a > peak) peak = a
                }
                lastPeak = (peak / 32768f).coerceIn(0f, 1f)
                val direct = ByteBuffer.allocateDirect(read * 2)
                    .order(ByteOrder.nativeOrder())
                for (i in 0 until read) direct.putShort(shorts[i])
                direct.flip()
                chunkCount.incrementAndGet()
                frameCount.addAndGet(read.toLong())
                try {
                    onChunk(Chunk(direct, read, tsNs))
                } catch (e: Exception) {
                    Log.w(TAG, "audio chunk dropped: ${e.message}")
                }
            }
            try {
                rec.stop()
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
            rec.getTimestamp(anchor, AudioTimestamp.TIMEBASE_MONOTONIC) ==
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
