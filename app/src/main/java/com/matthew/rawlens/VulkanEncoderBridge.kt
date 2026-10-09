// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.GLES20
import android.util.Log
import android.view.Surface

/**
 * Phase-A probe: synthetic RGGB RAW16 -> Vulkan superpixel compute ->
 * export AHB -> EGL blit -> MediaCodec Main10 4K30 input Surface.
 *
 * Same pipeline shape as the future camera path (HAL RAW import is already
 * proven byte-exact in the viewfinder); only the sensor is synthetic, filled
 * on CPU once per frame. The export side is 100% real: Vulkan compute into
 * ping-ponged export AHBs, EGL-fence handoff (no `glFinish` stalls),
 * textured blit onto the encoder surface with paced 30fps submit.
 */
object VulkanEncoderBridge {
    data class Params(
        val width: Int = LogVideoProbe.WIDTH,
        val height: Int = LogVideoProbe.HEIGHT,
        val fps: Int = LogVideoProbe.FPS,
        val bitrate: Int = LogVideoProbe.BITRATE,
        val frames: Int = 90,
        /** Synthetic sensor size; superpixel export is half-res per axis. */
        val rawWidth: Int = 1920,
        val rawHeight: Int = 1080,
        val codecName: String? = null,
        /** vf_superpixel.spv bytes (Vulkan 1.1, viewfinder device init). */
        val spv: ByteArray? = null,
        /** vf_superpixel_f16.spv bytes (recorder compute path). */
        val f16spv: ByteArray? = null,
        /** vf_loggrade.spv bytes; required when [grade] is true. */
        val gradeSpv: ByteArray? = null,
        /** Phase B: WB/CCM/log grade on GPU; the blit becomes a dumb copy. */
        val grade: Boolean = true,
        /** Pipeline-cache file (null keeps the cache in-memory only). */
        val cachePath: String? = null,
        val gains: FloatArray = VfLogGrade.PROBE_GAINS,
        val ccm: FloatArray = VfLogGrade.IDENTITY_CCM,
        val exposureEv: Float = 0f,
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
        /** Synthetic-sensor CPU cost (absent in the real camera path). */
        val fillMsAvg: Float = 0f,
        val computeMsAvg: Float = 0f,
        val gradeMsAvg: Float = 0f,
        val blitMsAvg: Float = 0f,
        val fenceWaitMsAvg: Float = 0f,
        val fenceTimeouts: Int = 0,
        val fellBackToFinish: Boolean = false,
        /** Frame-0 (bypass copy) vs final-frame (graded) mean luma, or null. */
        val bypassAvgLuma: Float? = null,
        val gradedAvgLuma: Float? = null,
        val readbackSkipped: Boolean = false,
        val error: String? = null,
    )

    fun params(spv: ByteArray? = null): Params = Params(spv = spv)

    private class Slot(
        var raw: HardwareBuffer? = null,
        var export: HardwareBuffer? = null,
        var graded: HardwareBuffer? = null,
        var image: Long = 0L,
        var fence: Long = 0L,
    )

    fun probe(p: Params = Params()): BridgeReport {
        if (!VfEglImport.available) return BridgeReport(error = "rawLensVfEgl native library unavailable")
        if (!VfVulkan.available) return BridgeReport(error = "Vulkan bridge unavailable")
        if (p.spv == null) return BridgeReport(error = "superpixel SPIR-V required")
        val expW = p.rawWidth / 2
        val expH = p.rawHeight / 2
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var egl: EglPusher? = null
        var program = 0
        var texture = 0
        val slots = arrayOf(Slot(), Slot())
        return try {
            val name = p.codecName ?: LogVideoProbe.preferredHwEncoder()
                ?: return BridgeReport(error = "no HW HEVC 4K30 Surface encoder")
            val target = LogVideoProbe.Target(
                width = p.width, height = p.height, fps = p.fps, bitrate = p.bitrate
            )
            p.cachePath?.let(VfVulkan::setPipelineCachePathNative)
            val initRc = VfVulkan.initNative(p.spv)
            if (initRc != VfVulkan.OK) {
                return BridgeReport(codecName = name, error = "vulkan init: ${VfVulkan.describe(initRc)}")
            }
            if (p.f16spv == null) {
                return BridgeReport(codecName = name, error = "f16 SPIR-V required")
            }
            val f16Rc = VfVulkan.initF16Native(p.f16spv)
            if (f16Rc != VfVulkan.OK) {
                return BridgeReport(codecName = name, error = "f16 init: ${VfVulkan.describe(f16Rc)}")
            }
            if (p.grade) {
                if (p.gradeSpv == null) {
                    return BridgeReport(codecName = name, error = "grade SPIR-V required")
                }
                val gradeRc = VfLogGrade.initGradeNative(p.gradeSpv)
                if (gradeRc != VfVulkan.OK) {
                    return BridgeReport(codecName = name, error = "grade init: ${VfVulkan.describe(gradeRc)}")
                }
            }
            codec = MediaCodec.createByCodecName(name)
            codec.configure(
                LogVideoProbe.toMediaFormat(target), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            surface = codec.createInputSurface()
            egl = EglPusher(surface)
            val push = egl ?: return BridgeReport(codecName = name, error = "egl init failed")
            codec.start()
            // Ping-pong sensor + export buffers (the efficiency core of Phase A:
            // frame N+1 fills/computes while frame N drains through the encoder).
            // The graded export additionally carries CPU_READ for sparse
            // validation sampling (HAL-style readable GPU buffer).
            var readbackSkipped = false
            for (s in slots) {
                s.raw = VfEglImport.createBayerInput(p.rawWidth, p.rawHeight)
                    ?: return BridgeReport(codecName = name, error = "synthetic RAW allocate failed")
                // FP16 exports (same contract as the recorder): the twins
                // import them privately; no shared-slot rebinding here.
                s.export = HardwareBuffer.create(
                    expW, expH, HardwareBuffer.RGBA_FP16, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                        HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                )
                s.graded = try {
                    HardwareBuffer.create(
                        expW, expH, HardwareBuffer.RGBA_FP16, 1,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                            HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                            HardwareBuffer.USAGE_CPU_READ_OFTEN
                    )
                } catch (_: Exception) {
                    readbackSkipped = true
                    HardwareBuffer.create(
                        expW, expH, HardwareBuffer.RGBA_FP16, 1,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                            HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                    )
                }
            }
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
            var computeAvg = 0f
            var gradeAvg = 0f
            var gradeMs = 0f
            var blitAvg = 0f
            var fenceAvg = 0f
            var fenceTimeouts = 0
            var fellBack = false
            var bypassLuma: Float? = null
            var gradedLuma: Float? = null
            var pitch = 0
            val t0 = android.os.SystemClock.elapsedRealtime()
            // Warmup: the first Vulkan read of CPU-written memory returns
            // stale (frame-0 black) without a prior ownership cycle; one
            // untimed submit pair per slot establishes it. The camera path
            // never sees this (HAL-owned buffers arrive correctly owned).
            // Wait-free twins throughout (same calls as the recorder); no
            // blocking fallback exists for FP16 (format mismatch), so twin
            // failure aborts the probe loudly.
            for ((slotIdx, ws) in slots.withIndex()) {
                val stride = VfEglImport.fillBayerPattern(ws.raw!!, p.rawWidth, p.rawHeight, 1000 + slotIdx)
                if (stride <= 0) {
                    return BridgeReport(codecName = name, error = "warmup fill rc=$stride")
                }
                if (pitch == 0) pitch = stride
                val (wparams, wfparams) = VfVulkan.packParams(
                    intArrayOf(0, 1, 2, 3), 0, 0, expW, expH, 2, pitch,
                    floatArrayOf(64f, 64f, 64f, 64f), 1023f
                )
                if (VfVulkan.computeSubmitNative(ws.raw!!, ws.export!!, wparams, wfparams, slotIdx, null, null) != VfVulkan.OK) {
                    return BridgeReport(codecName = name, error = "warmup compute submit failed")
                }
                val (wgparams, wgfparams) = VfLogGrade.packGrade(
                    intArrayOf(expW, expH), p.gains, p.ccm, p.exposureEv, true
                )
                val wfd = VfLogGrade.gradeSubmitNative(ws.export!!, ws.graded!!, wgparams, wgfparams, slotIdx)
                if (wfd < 0) {
                    return BridgeReport(codecName = name, error = "warmup grade submit rc=$wfd")
                }
                if (VfEglImport.adoptNativeFence(wfd) != 0) {
                    return BridgeReport(codecName = name, error = "warmup fence adopt failed")
                }
            }
            val startNs = System.nanoTime()
            val stepNs = 1_000_000_000L / p.fps
            var submitted = 0
            repeat(p.frames) { i ->
                val s = slots[i % 2]
                val raw = s.raw!!
                val exp = s.export!!
                // 1. Synthetic sensor fill (CPU; absent in the camera path).
                val f0 = android.os.SystemClock.elapsedRealtimeNanos()
                val strideOrErr = VfEglImport.fillBayerPattern(raw, p.rawWidth, p.rawHeight, i)
                if (strideOrErr <= 0) {
                    return BridgeReport(codecName = name, error = "bayer fill rc=$strideOrErr")
                }
                if (pitch == 0) pitch = strideOrErr
                val f1 = android.os.SystemClock.elapsedRealtimeNanos()
                // 2. Wait for this slot's previous frame (fence, not glFinish).
                val w0 = android.os.SystemClock.elapsedRealtimeNanos()
                if (s.fence != 0L) {
                    if (!VfEglImport.waitFence(s.fence, 50_000_000L)) fenceTimeouts++
                    VfEglImport.destroyFence(s.fence)
                    s.fence = 0L
                }
                if (s.image != 0L) {
                    VfEglImport.destroyEGLImage(s.image)
                    s.image = 0L
                }
                val w1 = android.os.SystemClock.elapsedRealtimeNanos()
                // 3. Vulkan superpixel into the export buffer (wait-free twin,
                // same call as the recorder; imports are recorder-private).
                val (iparams, fparams) = VfVulkan.packParams(
                    intArrayOf(0, 1, 2, 3), 0, 0, expW, expH, 2, pitch,
                    floatArrayOf(64f, 64f, 64f, 64f), 1023f
                )
                val c0 = android.os.SystemClock.elapsedRealtimeNanos()
                if (VfVulkan.computeSubmitNative(raw, exp, iparams, fparams, i % 2, null, null) != VfVulkan.OK) {
                    return BridgeReport(
                        codecName = name,
                        error = "vulkan compute submit failed at frame $i"
                    )
                }
                val c1 = android.os.SystemClock.elapsedRealtimeNanos()
                // 4. Phase-B grade on GPU (WB/CCM/log); the blit below stays
                // a dumb copy.
                val blitSrc: HardwareBuffer
                if (p.grade) {
                    val grd = s.graded!!
                    // Frame 0 and every 45th frame run bypass (bit-exact copy)
                    // as the readback baseline proving the curve does the work.
                    val bypass = i % 45 == 0
                    val (gparams, gfparams) = VfLogGrade.packGrade(
                        intArrayOf(expW, expH), p.gains, p.ccm, p.exposureEv, bypass
                    )
                    val g0 = android.os.SystemClock.elapsedRealtimeNanos()
                    val gfd = VfLogGrade.gradeSubmitNative(exp, grd, gparams, gfparams, i % 2)
                    if (gfd < 0) {
                        return BridgeReport(
                            codecName = name,
                            error = "vulkan grade submit rc=$gfd at frame $i"
                        )
                    }
                    if (VfEglImport.adoptNativeFence(gfd) != 0) {
                        return BridgeReport(
                            codecName = name,
                            error = "fence adopt failed at frame $i"
                        )
                    }
                    val g1 = android.os.SystemClock.elapsedRealtimeNanos()
                    gradeMs = (g1 - g0) / 1e6f
                    if (!readbackSkipped && (bypass || i <= 2 || i == p.frames - 1)) {
                        // Wait-free submits leave the GPU behind the CPU: a
                        // glFinish here (probe validation only, never the
                        // product path) settles both dispatches before the
                        // CPU readback, or samples race and read stale data.
                        GLES20.glFinish()
                        VfEglImport.sampleRgba(exp, expW, expH)?.let { sample ->
                            val luma = 0.2126f * sample[0] + 0.7152f * sample[1] + 0.0722f * sample[2]
                            Log.i(TAG, "SPIKE vgrade superpixel avg luma=${"%.3f".format(luma)}")
                        }
                        VfEglImport.sampleRgba(grd, expW, expH)?.let { sample ->
                            val luma = 0.2126f * sample[0] + 0.7152f * sample[1] + 0.0722f * sample[2]
                            if (bypass) bypassLuma = luma else gradedLuma = luma
                            Log.i(TAG, "SPIKE vgrade ${if (bypass) "bypass" else "graded"} " +
                                "avg=(${"%.3f".format(sample[0])}," +
                                "${"%.3f".format(sample[1])},${"%.3f".format(sample[2])}) " +
                                "luma=${"%.3f".format(luma)}")
                        } ?: run { readbackSkipped = true }
                    }
                    blitSrc = grd
                } else {
                    blitSrc = exp
                }
                // 5. EGL import + blit to the encoder surface.
                val image = VfEglImport.createEGLImage(blitSrc)
                if (image == 0L) {
                    return BridgeReport(codecName = name, error = "createEGLImage failed at frame $i")
                }
                try {
                    val bindErr = VfEglImport.bindEGLImageToTexture2D(image, texture)
                    if (bindErr != GLES20.GL_NO_ERROR) {
                        return BridgeReport(codecName = name, error = "egl-bind=0x${bindErr.toString(16)} at $i")
                    }
                    GLES20.glViewport(0, 0, p.width, p.height)
                    GLES20.glUseProgram(program)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
                    GlBlit.drawQuad(program, quad)
                    GLES20.glFlush()
                } finally {
                    // Deferred destroy: the image stays alive until this slot
                    // is reused (fence-waited above), never glFinish-stalled.
                    s.image = image
                }
                push.setPresentationTime(LogVideoProbe.framePtsNs(i, p.fps))
                push.swap()
                s.fence = VfEglImport.createFence()
                if (s.fence == 0L && !fellBack) {
                    fellBack = true
                    Log.w(TAG, "sync fences unsupported; ordering via queue depth only")
                }
                val f2 = android.os.SystemClock.elapsedRealtimeNanos()
                submitted++
                fillAvg = ema(fillAvg, (f1 - f0) / 1e6f)
                fenceAvg = ema(fenceAvg, (w1 - w0) / 1e6f)
                computeAvg = ema(computeAvg, (c1 - w1) / 1e6f)
                if (p.grade) gradeAvg = ema(gradeAvg, gradeMs)
                blitAvg = ema(blitAvg, (f2 - c1) / 1e6f - (if (p.grade) gradeMs else 0f))
                if (i % p.fps == 0) {
                    Log.i(TAG, "SPIKE vbridge t=${i / p.fps}s submitted=$submitted outBufs=${bufs.get()}")
                }
                // Pace to wall-clock fps so big cores can idle between frames.
                val deadlineNs = startNs + (i + 1L) * stepNs
                val nowNs = System.nanoTime()
                if (deadlineNs > nowNs) {
                    val sleepMs = (deadlineNs - nowNs) / 1_000_000L
                    if (sleepMs > 0) try { Thread.sleep(sleepMs) } catch (_: InterruptedException) {}
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
                computeMsAvg = computeAvg,
                gradeMsAvg = gradeAvg,
                blitMsAvg = blitAvg,
                fenceWaitMsAvg = fenceAvg,
                fenceTimeouts = fenceTimeouts,
                fellBackToFinish = fellBack,
                bypassAvgLuma = bypassLuma,
                gradedAvgLuma = gradedLuma,
                readbackSkipped = readbackSkipped,
                error = when {
                    bufs.get() == 0 -> "no output"
                    outProfile.get() != null && outProfile.get() != MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ->
                        "output ${LogVideoProbe.profileLabel(outProfile.get()!!)} != Main10"
                    else -> null
                }
            )
        } catch (e: Exception) {
            BridgeReport(error = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { VfVulkan.resetNative() } catch (_: Exception) {}
            for (s in slots) {
                try { s.image.takeIf { it != 0L }?.let { VfEglImport.destroyEGLImage(it) } } catch (_: Exception) {}
                try { VfEglImport.destroyFence(s.fence) } catch (_: Exception) {}
                try { s.raw?.close() } catch (_: Exception) {}
                try { s.export?.close() } catch (_: Exception) {}
                try { s.graded?.close() } catch (_: Exception) {}
            }
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

    private fun ema(avg: Float, sample: Float): Float =
        if (avg == 0f) sample else avg * 0.9f + sample * 0.1f

    private fun dumpOut(fmt: MediaFormat): String {
        fun gi(k: String) = runCatching { fmt.getInteger(k).toString() }.getOrNull() ?: "?"
        return "profile=${runCatching { LogVideoProbe.profileLabel(fmt.getInteger(MediaFormat.KEY_PROFILE)) }.getOrNull() ?: "?"} " +
            "${gi(MediaFormat.KEY_WIDTH)}x${gi(MediaFormat.KEY_HEIGHT)}"
    }

    private const val TAG = "VulkanBridge"
}
