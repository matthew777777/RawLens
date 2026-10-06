// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.GLES20
import android.util.Log
import android.view.Surface

/**
 * Bridge probe: app-allocated AHB (same interop contract as the Vulkan
 * superpixel export: own buffer, EGL-importable) -> EGL blit ->
 * MediaCodec Main10 4K30 input Surface.
 *
 * The AHB is CPU-filled with an animated pattern via
 * [VfEglImport.fillTestPattern] (stand-in for Vulkan compute output), then
 * EGL-imported with the exact [VfEglImport] path the viewfinder uses and
 * textured onto the encoder surface. No camera involved.
 *
 * Proves the AHB->encoder handoff the Direct-Log GPU stage needs; the only
 * later swap is filling the AHB from `VfVulkan.computeNative` instead of
 * the CPU pattern.
 */
object AhbEncoderBridge {
    data class Params(
        val width: Int = LogVideoProbe.WIDTH,
        val height: Int = LogVideoProbe.HEIGHT,
        val fps: Int = LogVideoProbe.FPS,
        val bitrate: Int = LogVideoProbe.BITRATE,
        val frames: Int = 90,
        /** Synthetic export size; blit stretches to encoder size. */
        val ahbWidth: Int = 1920,
        val ahbHeight: Int = 1080,
        val codecName: String? = null,
    )

    data class BridgeReport(
        val codecName: String? = null,
        val framesSubmitted: Int = 0,
        val outputBuffers: Int = 0,
        val outputBytes: Long = 0L,
        val outputProfile: Int? = null,
        val outputFormat: String? = null,
        val elapsedMs: Long = 0L,
        val submitFps: Float = 0f,
        val fillMsAvg: Float = 0f,
        val blitMsAvg: Float = 0f,
        val error: String? = null,
    )

    fun params(): Params = Params()

    fun probe(p: Params = Params()): BridgeReport {
        if (!VfEglImport.available) return BridgeReport(error = "rawLensVfEgl native library unavailable")
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var egl: EglPusher? = null
        var ahb: HardwareBuffer? = null
        var program = 0
        var texture = 0
        return try {
            val name = p.codecName ?: LogVideoProbe.preferredHwEncoder()
                ?: return BridgeReport(error = "no HW HEVC 4K30 Surface encoder")
            val target = LogVideoProbe.Target(
                width = p.width, height = p.height, fps = p.fps, bitrate = p.bitrate
            )
            codec = MediaCodec.createByCodecName(name)
            codec.configure(
                LogVideoProbe.toMediaFormat(target), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            surface = codec.createInputSurface()
            egl = EglPusher(surface)
            codec.start()
            ahb = HardwareBuffer.create(
                p.ahbWidth, p.ahbHeight, HardwareBuffer.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_WRITE_OFTEN
            )
            program = GlBlit.buildProgram()
            texture = GlBlit.genTexture()
            val quad = GlBlit.quadBuffer()
            val bytes = java.util.concurrent.atomic.AtomicLong(0)
            val bufs = java.util.concurrent.atomic.AtomicInteger(0)
            val outProfile = java.util.concurrent.atomic.AtomicReference<Int?>(null)
            val outDump = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val draining = java.util.concurrent.atomic.AtomicBoolean(true)
            val info = MediaCodec.BufferInfo()
            val drainCodec = codec
            val drainThread = Thread {
                try {
                    while (draining.get()) {
                        val idx = drainCodec.dequeueOutputBuffer(info, 2000)
                        when {
                            idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> try {
                                val fmt = drainCodec.outputFormat
                                outProfile.set(runCatching { fmt.getInteger(MediaFormat.KEY_PROFILE) }.getOrNull())
                                outDump.set(dumpOut(fmt))
                            } catch (_: Exception) {}
                            idx >= 0 -> {
                                if (info.size > 0) {
                                    bytes.addAndGet(info.size.toLong())
                                    bufs.incrementAndGet()
                                }
                                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                drainCodec.releaseOutputBuffer(idx, false)
                                if (eos) break
                            }
                        }
                    }
                } catch (_: Exception) {}
            }.apply { start() }
            var fillAvg = 0f
            var blitAvg = 0f
            val t0 = android.os.SystemClock.elapsedRealtime()
            var submitted = 0
            repeat(p.frames) { i ->
                val f0 = android.os.SystemClock.elapsedRealtimeNanos()
                val fillRc = VfEglImport.fillTestPattern(ahb, p.ahbWidth, p.ahbHeight, i)
                if (fillRc != 0) return BridgeReport(codecName = name, error = "fillTestPattern rc=$fillRc")
                val f1 = android.os.SystemClock.elapsedRealtimeNanos()
                val image = VfEglImport.createEGLImage(ahb)
                if (image == 0L) return BridgeReport(codecName = name, error = "createEGLImage failed at frame $i")
                try {
                    val bindErr = VfEglImport.bindEGLImageToTexture2D(image, texture)
                    if (bindErr != GLES20.GL_NO_ERROR) {
                        return BridgeReport(codecName = name, error = "egl-bind=0x${bindErr.toString(16)} at frame $i")
                    }
                    GLES20.glViewport(0, 0, p.width, p.height)
                    GLES20.glUseProgram(program)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
                    GlBlit.drawQuad(program, quad)
                    GLES20.glFinish()
                } finally {
                    VfEglImport.destroyEGLImage(image)
                }
                val f2 = android.os.SystemClock.elapsedRealtimeNanos()
                egl.setPresentationTime(LogVideoProbe.framePtsNs(i, p.fps))
                egl.swap()
                submitted++
                val fillMs = (f1 - f0) / 1e6f
                val blitMs = (f2 - f1) / 1e6f
                fillAvg = if (fillAvg == 0f) fillMs else fillAvg * 0.9f + fillMs * 0.1f
                blitAvg = if (blitAvg == 0f) blitMs else blitAvg * 0.9f + blitMs * 0.1f
                if (i % p.fps == 0) {
                    Log.i(TAG, "SPIKE bridge t=${i / p.fps}s submitted=$submitted outBufs=${bufs.get()}")
                }
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - t0
            codec.signalEndOfInputStream()
            val tailEnd = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < tailEnd) {
                if (!drainThread.isAlive) break
                if (bufs.get() >= submitted && submitted > 0) {
                    Thread.sleep(300)
                    if (bufs.get() >= submitted) break
                }
                Thread.sleep(100)
            }
            draining.set(false)
            try { drainThread.join(5_000) } catch (_: InterruptedException) {}
            BridgeReport(
                codecName = name,
                framesSubmitted = submitted,
                outputBuffers = bufs.get(),
                outputBytes = bytes.get(),
                outputProfile = outProfile.get(),
                outputFormat = outDump.get(),
                elapsedMs = elapsed,
                submitFps = if (elapsed > 0) submitted * 1000f / elapsed else 0f,
                fillMsAvg = fillAvg,
                blitMsAvg = blitAvg,
                error = when {
                    bufs.get() == 0 -> "no output"
                    outProfile.get() != null &&
                        outProfile.get() != android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ->
                        "output ${LogVideoProbe.profileLabel(outProfile.get()!!)} != Main10"
                    else -> null
                }
            )
        } catch (e: Exception) {
            BridgeReport(error = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { ahb?.close() } catch (_: Exception) {}
            try {
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            } catch (_: Exception) {}
            try {
                if (program != 0) GLES20.glDeleteProgram(program)
            } catch (_: Exception) {}
            try { egl?.release() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

    private fun dumpOut(fmt: MediaFormat): String {
        fun gi(k: String) = runCatching { fmt.getInteger(k).toString() }.getOrNull() ?: "?"
        return "profile=${runCatching { LogVideoProbe.profileLabel(fmt.getInteger(MediaFormat.KEY_PROFILE)) }.getOrNull() ?: "?"} " +
            "${gi(MediaFormat.KEY_WIDTH)}x${gi(MediaFormat.KEY_HEIGHT)}"
    }

    private const val TAG = "AhbBridge"
}
