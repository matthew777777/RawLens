// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * PCM16 -> AAC-LC encoder stage for Direct Log. Owns its MediaCodec and
 * thread; input chunks arrive from [AudioPcmRecorder], encoded frames leave
 * via [onOutput] (the recorder routes them to the single muxer writer).
 * Codec-config packets are forwarded with their flag so the writer can skip
 * them (the csd lives in the track format).
 *
 * Never throws across the boundary: failures report false/null and the take
 * stays silent-but-valid.
 */
class AacAudioEncoder(
    private val sampleRate: Int,
    private val channels: Int,
) {
    data class Input(val data: ByteBuffer, val ptsUs: Long)
    data class Encoded(val data: ByteBuffer, val ptsUs: Long, val flags: Int)
    data class Stats(val chunks: Long, val bytes: Long)

    private val inQueue = ArrayBlockingQueue<Any>(64)
    private var thread: Thread? = null
    private var codec: MediaCodec? = null
    @Volatile private var running = false
    private val chunksIn = AtomicLong(0)
    private val bytesOut = AtomicLong(0)

    fun bitrate(): Int = bitrateFor(channels)

    /**
     * @return false when no AAC encoder is available; caller records silent.
     */
    fun start(onFormat: (MediaFormat) -> Unit, onOutput: (Encoded) -> Unit): Boolean {
        check(thread == null) { "Already started" }
        val mc = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            Log.w(TAG, "no AAC encoder: ${e.message}")
            return false
        }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate())
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        }
        try {
            mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mc.start()
        } catch (e: Exception) {
            Log.w(TAG, "AAC configure failed: ${e.message}")
            try { mc.release() } catch (_: Exception) {}
            return false
        }
        codec = mc
        running = true
        thread = Thread({
            val info = MediaCodec.BufferInfo()
            var formatSent = false
            fun drain(waitUs: Long) {
                while (true) {
                    val idx = try {
                        mc.dequeueOutputBuffer(info, waitUs)
                    } catch (_: Exception) {
                        break
                    }
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!formatSent) {
                                formatSent = true
                                try { onFormat(mc.outputFormat) } catch (_: Exception) {}
                            }
                        }
                        idx >= 0 -> {
                            val out = try { mc.getOutputBuffer(idx) } catch (_: Exception) { null }
                            if (out != null && info.size > 0) {
                                val copy = ByteBuffer.allocateDirect(info.size)
                                val dup = out.duplicate()
                                dup.position(info.offset)
                                dup.limit(info.offset + info.size)
                                copy.put(dup)
                                copy.flip()
                                bytesOut.addAndGet(info.size.toLong())
                                try {
                                    onOutput(Encoded(copy, info.presentationTimeUs, info.flags))
                                } catch (_: Exception) {}
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            try { mc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                            if (eos) break
                        }
                    }
                }
            }
            while (running) {
                val item: Any? = try {
                    inQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
                if (item == null) {
                    drain(0)
                    continue
                }
                if (item === Poison) break
                val input = item as Input
                var fed = false
                while (!fed) {
                    val idx = try {
                        mc.dequeueInputBuffer(2000)
                    } catch (_: Exception) {
                        break
                    }
                    if (idx < 0) {
                        drain(0)
                        continue
                    }
                    try {
                        val buf = mc.getInputBuffer(idx)!!
                        buf.clear()
                        val src = input.data.duplicate()
                        val n = minOf(src.remaining(), buf.remaining())
                        val limited = src.duplicate()
                        limited.limit(src.position() + n)
                        buf.put(limited)
                        mc.queueInputBuffer(idx, 0, n, input.ptsUs, 0)
                        chunksIn.incrementAndGet()
                        fed = true
                    } catch (_: Exception) {
                        break
                    }
                }
                drain(0)
            }
            // Flush remaining outputs, then EOS through the codec.
            drain(0)
            try {
                val idx = mc.dequeueInputBuffer(2000)
                if (idx >= 0) mc.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } catch (_: Exception) {}
            val end = System.currentTimeMillis() + 5_000
            var eos = false
            while (!eos && System.currentTimeMillis() < end) {
                val idx = try {
                    mc.dequeueOutputBuffer(info, 1000)
                } catch (_: Exception) {
                    break
                }
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!formatSent) {
                            formatSent = true
                            try { onFormat(mc.outputFormat) } catch (_: Exception) {}
                        }
                    }
                    idx >= 0 -> {
                        val out = try { mc.getOutputBuffer(idx) } catch (_: Exception) { null }
                        if (out != null && info.size > 0) {
                            val copy = ByteBuffer.allocateDirect(info.size)
                            val dup = out.duplicate()
                            dup.position(info.offset)
                            dup.limit(info.offset + info.size)
                            copy.put(dup)
                            copy.flip()
                            bytesOut.addAndGet(info.size.toLong())
                            try {
                                onOutput(Encoded(copy, info.presentationTimeUs, info.flags))
                            } catch (_: Exception) {}
                        }
                        eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        try { mc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                    }
                }
            }
        }, "DirectLogAac").apply { start() }
        return true
    }

    /** Non-blocking; false = queue full, caller counts a dropped chunk. */
    fun queue(input: Input): Boolean = inQueue.offer(input)

    fun stop(): Stats {
        running = false
        try { inQueue.offer(Poison) } catch (_: Exception) {}
        try { thread?.join(8_000) } catch (_: InterruptedException) {}
        thread = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        return Stats(chunksIn.get(), bytesOut.get())
    }

    companion object {
        private const val TAG = "AacAudioEncoder"
        private object Poison

        fun bitrateFor(channels: Int): Int = if (channels >= 2) 128_000 else 64_000
    }
}
