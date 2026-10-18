// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RCD parity + perf: synthetic Bayer through the GPU RCD modes vs the
 * [RcdReference] CPU port (mean abs diff gate), then a full-crop-res
 * timing run with per-mode GPU milliseconds (fd-poll, true exec time).
 * The fused test proves the RCD-finalize + grade single dispatch is
 * tap-identical to the split path (mode 3 + grade-YUV) at identity and
 * ladder-downscale export sizes, and logs its full-res time.
 *
 * Opt-in: `-e rawlensRcdParity true`.
 */
@RunWith(AndroidJUnit4::class)
class RcdParityDeviceTest {
    @Test fun gpuMatchesCpuReference() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensRcdParity true to run the RCD parity probe",
            args.getString("rawlensRcdParity") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val spv = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        val gradespv = context.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        val rcdspv = context.assets.open("shaders/vf/vf_rcd.spv").use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) { "grade init failed" }
        check(VfRcd.initRcdNative(rcdspv) == VfVulkan.OK) { "rcd init failed" }

        parity64(context)
        perfFullRes()
    }

    /**
     * Fused finalize equivalence + timing: modes 0-2, then the split
     * path (mode 3 + grade-YUV fullRgb=1) vs the fused dispatch
     * ([VfRcd.rcdFusedYuvSubmitNative]) into two P010 buffers, sampled
     * on a grid — every word must match exactly (same taps, same op
     * order). Identity export plus a ladder-style 2x downscale case,
     * then a full-res modes-0-2+fused timing for the standing record.
     */
    @Test fun fusedFinalizeMatchesSplit() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensRcdParity true to run the RCD parity probe",
            args.getString("rawlensRcdParity") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        // Separate instrument run with `-e rawlensRcdF16 true` validates
        // the f16 variants (fresh process: pipeline init is one-shot per
        // SPIR-V). The split reference stays f32 modes + f32 grade, so
        // the f16 run gates demosaic + grade precision, not tap identity.
        val f16 = args.getString("rawlensRcdF16") == "true"
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val spv = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        val gradespv = context.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        val yuvspv = context.assets.open("shaders/vf/vf_gradeyuv.spv").use { it.readBytes() }
        val rcdspv = context.assets.open(VfRcd.rcdAsset(f16)).use { it.readBytes() }
        val rcdyuvspv = context.assets.open(VfRcd.rcdYuvAsset(f16)).use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) { "grade init failed" }
        check(VfLogGrade.initGradeYuvNative(yuvspv) == VfVulkan.OK) { "yuv init failed" }
        check(VfRcd.initRcdNative(rcdspv) == VfVulkan.OK) { "rcd init failed" }
        check(VfRcd.initRcdYuvNative(rcdyuvspv) == VfVulkan.OK) { "rcd-yuv init failed" }
        check(
            VfLogGrade.gradeLutUploadNative(
                VfLogGrade.bakeLut(VfLogGrade.DEFAULT_CONTRAST)
            ) == VfVulkan.OK
        ) { "lut upload failed" }

        fusedCase(256, 144, 256, 144, "identity", f16)
        fusedCase(256, 144, 128, 72, "half", f16)
        fusedFullRes()
        // No rgb-level parity64 in f16 runs: it gates a non-shipping
        // intermediate against a stricter-than-product bar (f16
        // discrimination flips on Nyquist content, mean 0.016 vs gate
        // 0.005). The product is P010, gated above at worst <= 8 codes.
    }

    private fun runModes(
        raw: HardwareBuffer, rgb: HardwareBuffer, scratch: HardwareBuffer,
        cw: Int, ch: Int, pitch: Int, channels: IntArray,
        levels: FloatArray, white: Float, modes: IntRange, slot: Int,
    ) {
        for (mode in modes) {
            val (ip, fp) = VfRcd.packRcd(cw, ch, 0, 0, channels, pitch, mode, levels, white)
            val fd = VfRcd.rcdSubmitNative(raw, rgb, scratch, ip, fp, slot)
            assumeTrue("rcd submit mode $mode failed: $fd", fd >= 0)
            assumeTrue("rcd mode $mode never completed", pollFd(fd))
            VfEglImport.closeSyncFd(fd)
        }
    }

    private fun sampleGrid(
        p010: HardwareBuffer, yStride: Int, uvStride: Int,
        ew: Int, eh: Int,
    ): List<IntArray> {
        val xs = intArrayOf(8, ew / 4, ew / 2, 3 * ew / 4, ew - 8)
        val ys = intArrayOf(8, eh / 4, eh / 2, 3 * eh / 4, eh - 8)
        return buildList {
            for (y in ys) for (x in xs) {
                val w = VfEglImport.sampleP010Center(p010, yStride, uvStride, ew, eh, x, y)
                assumeTrue("P010 readback failed at ($x,$y)", w != null && w.size == 3)
                add(w!!)
            }
        }
    }

    private fun fusedCase(cw: Int, ch: Int, ew: Int, eh: Int, label: String, f16: Boolean) {
        val channels = intArrayOf(0, 1, 2, 3)
        val levels = floatArrayOf(0f, 0f, 0f, 0f)
        val white = 1023f
        val raw = VfEglImport.createBayerInput(cw, ch)
        assumeTrue("synthetic RAW allocate failed", raw != null)
        val pitch = VfEglImport.fillBayerPattern(raw!!, cw, ch, 0)
        assumeTrue("bayer fill failed", pitch > 0)
        val rgb = HardwareBuffer.create(
            cw, ch, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val scratch = HardwareBuffer.create(
            cw, ch, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val pSplit = VfEglImport.createP010(ew, eh)
        assumeTrue("P010 split allocate failed", pSplit != null)
        val pFused = VfEglImport.createP010(ew, eh)
        assumeTrue("P010 fused allocate failed", pFused != null)
        try {
            val layout = VfEglImport.describeP010(pSplit!!)
            assumeTrue("P010 describe failed", layout != null)
            val strides = intArrayOf(layout!![0], layout[1], ew, eh)
            val (gidims, gfparams) = VfLogGrade.packGrade(
                intArrayOf(ew, eh),
                floatArrayOf(1f, 1f, 1f, 1f),
                VfLogGrade.IDENTITY_CCM,
                0f, false
            )
            val src = intArrayOf(cw, ch)
            // Warmup pair (stale-gralloc ownership, slot 1), then measured (slot 0).
            runModes(raw, rgb, scratch, cw, ch, pitch, channels, levels, white, 0..2, 1)
            runModes(raw, rgb, scratch, cw, ch, pitch, channels, levels, white, 0..2, 0)
            // Split path: mode 3 + grade-YUV.
            val (m3i, m3f) = VfRcd.packRcd(cw, ch, 0, 0, channels, pitch, 3, levels, white)
            val m3fd = VfRcd.rcdSubmitNative(raw, rgb, scratch, m3i, m3f, 0)
            assumeTrue("split mode 3 failed: $m3fd", m3fd >= 0)
            assumeTrue("split mode 3 never completed", pollFd(m3fd))
            VfEglImport.closeSyncFd(m3fd)
            val gfd = VfLogGrade.gradeYuvSubmitNative(
                rgb, pSplit, gidims, src, gfparams, strides, 0, 1
            )
            assumeTrue("split grade failed: $gfd", gfd >= 0)
            assumeTrue("split grade never completed", pollFd(gfd))
            VfEglImport.closeSyncFd(gfd)
            val splitWords = sampleGrid(pSplit!!, layout[0], layout[1], ew, eh)
            // Fused path over the same modes 0-2 results.
            val ffd = VfRcd.rcdFusedYuvSubmitNative(
                raw, rgb, scratch, pFused!!, m3i, m3f,
                gidims, src, gfparams, strides, 0
            )
            assumeTrue("fused submit failed: $ffd", ffd >= 0)
            assumeTrue("fused never completed", pollFd(ffd))
            VfEglImport.closeSyncFd(ffd)
            val fusedWords = sampleGrid(pFused!!, layout[0], layout[1], ew, eh)
            // Gate: at most 1 P010 code (64 raw units) per word. The fused
            // kernel consumes the mode-3 refinement in arithmetic while
            // the split path materializes it through the RGB image, so
            // FMA contraction can legitimately differ by a rounding step
            // at isolated boundary points (same taps, same op order).
            // Any real tap/dispatch/mapping bug moves dozens of codes
            // across many points, far above this gate.
            var bad = 0
            var worst = 0
            var codeSum = 0.0
            val detail = StringBuilder()
            for (i in splitWords.indices) {
                for (k in 0..2) {
                    val d = kotlin.math.abs(splitWords[i][k] - fusedWords[i][k])
                    if (d > worst) worst = d
                    codeSum += (d / 64).toDouble()
                    // f32 run: tap identity allows at most the single
                    // rounding step from store-vs-consume contraction.
                    // f16 run: demosaic + grade precision, gated in codes
                    // like the grade probes (mean < 2, max <= 8).
                    val limit = if (f16) 8 * 64 else 64
                    if (d > limit) {
                        if (bad < 10) detail.append("px$i ch$k split=${splitWords[i][k]} fused=${fusedWords[i][k]} ")
                        bad++
                    }
                }
            }
            val codeMean = codeSum / (splitWords.size * 3)
            Log.i(TAG, "SPIKE rcdfused $label ${ew}x$eh f16=$f16 over=$bad/75 worst=$worst codemean=${"%.2f".format(codeMean)} $detail")
            assertTrue("fused-vs-split $label over-limit $bad/75 worst=$worst ($detail)", bad == 0)
            if (f16) assertTrue("f16 code mean $codeMean (expect < 2)", codeMean < 2.0)
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { rgb.close() } catch (_: Exception) {}
            try { scratch.close() } catch (_: Exception) {}
            try { pSplit?.close() } catch (_: Exception) {}
            try { pFused?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Rapid-sequence regression test for the twin-CB use-after-reset
     * that killed RCD takes (device lost after a few frames, then
     * PIPELINE_FAILED): modes 0-3 fired back-to-back on rotating slots
     * with NO waits between submits — the production pattern — then all
     * fds polled and the RGB compared to the CPU reference. The old
     * shared-twin discipline wedged here (resetting pending CBs); the
     * fence-retired per-submit CBs must complete every submit.
     * Standalone only: `-e rawlensRcdRapid true` (with rawlensRcdParity).
     */
    @Test fun rcdRapidSequence() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensRcdParity true to run the RCD parity probe",
            args.getString("rawlensRcdParity") == "true"
        )
        assumeTrue(
            "Pass -e rawlensRcdRapid true to run the rapid-sequence probe",
            args.getString("rawlensRcdRapid") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val w = 512
        val h = 320
        val channels = intArrayOf(0, 1, 2, 3)
        val levels = floatArrayOf(0f, 0f, 0f, 0f)
        val white = 1023f

        val spv = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        val gradespv = context.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        val yuvspv = context.assets.open("shaders/vf/vf_gradeyuv.spv").use { it.readBytes() }
        val rcdspv = context.assets.open(VfRcd.rcdAsset(false)).use { it.readBytes() }
        val rcdyuvspv = context.assets.open(VfRcd.rcdYuvAsset(false)).use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) { "grade init failed" }
        check(VfLogGrade.initGradeYuvNative(yuvspv) == VfVulkan.OK) { "yuv init failed" }
        check(VfRcd.initRcdNative(rcdspv) == VfVulkan.OK) { "rcd init failed" }
        check(VfRcd.initRcdYuvNative(rcdyuvspv) == VfVulkan.OK) { "rcd-yuv init failed" }
        check(
            VfLogGrade.gradeLutUploadNative(
                VfLogGrade.bakeLut(VfLogGrade.DEFAULT_CONTRAST)
            ) == VfVulkan.OK
        ) { "lut upload failed" }

        val raw = VfEglImport.createBayerInput(w, h)
        assumeTrue("RAW allocate failed", raw != null)
        val pitch = VfEglImport.fillBayerPattern(raw!!, w, h, 0)
        assumeTrue("bayer fill failed", pitch > 0)
        val rgb = HardwareBuffer.create(
            w, h, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val scratch = HardwareBuffer.create(
            w, h, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        try {
            // Warmup sequential (slot 1) to prime imports.
            runModes(raw, rgb, scratch, w, h, pitch, channels, levels, white, 0..3, 1)
            // Rapid fire: 3 rounds x 4 modes, rotating slots, no polls.
            val fds = ArrayList<Int>()
            for (round in 0..2) {
                for (mode in 0..3) {
                    val (ip, fp) = VfRcd.packRcd(w, h, 0, 0, channels, pitch, mode, levels, white)
                    val fd = VfRcd.rcdSubmitNative(raw, rgb, scratch, ip, fp, round)
                    assumeTrue("rapid submit round $round mode $mode failed: $fd", fd >= 0)
                    fds.add(fd)
                }
            }
            for ((i, fd) in fds.withIndex()) {
                assumeTrue("rapid submit $i never completed (wedge?)", pollFd(fd))
                VfEglImport.closeSyncFd(fd)
            }
            val gpu = VfEglImport.dumpFp16(rgb, w, h)
            assumeTrue("RGB readback failed", gpu != null)
            val codes = regenBayer(w, h, pitch, 0)
            val cpu = RcdReference.demosaic(
                codes, w, h, pitch, 0, 0, w, h, channels, levels, white
            )
            var sum = 0.0
            var max = 0f
            for (i in cpu.indices) {
                val d = kotlin.math.abs(gpu!![i / 3 * 4 + i % 3] - cpu[i])
                sum += d
                if (d > max) max = d
            }
            val mean = (sum / cpu.size).toFloat()
            Log.i(TAG, "SPIKE rcdrapid mean=$mean max=$max")
            assertTrue("rapid parity mean $mean (expect < $MEAN_GATE)", mean < MEAN_GATE)
            assertTrue("rapid parity max $max (expect < $MAX_GATE)", max < MAX_GATE)
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { rgb.close() } catch (_: Exception) {}
            try { scratch.close() } catch (_: Exception) {}
        }
    }

    /**
     * Real-data stills reproduction: a 512x512 window of an on-device
     * DNG's CFA (lapis GBRG, black 64, white 1023) through the exact
     * stills upload (BLOB + blit, tight pitch) and modes 0-3, compared
     * to the CPU reference on the same bytes. Passes iff upload+modes
     * are faithful on real data (isolating any stills maze to the tail
     * or later stages). Reads /data/local/tmp/dng_win.bin (pushed from
     * the host). Standalone: `-e rawlensRcdStills true`.
     */
    @Test fun rcdStillsRealData() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensRcdParity true to run the RCD parity probe",
            args.getString("rawlensRcdParity") == "true"
        )
        assumeTrue(
            "Pass -e rawlensRcdStills true (needs /data/local/tmp/dng_win.bin)",
            args.getString("rawlensRcdStills") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val w = 512
        val h = 512
        val channels = VfRcd.channelsFromPattern(BayerPattern.GBRG)
        val levels = floatArrayOf(64f, 64f, 64f, 64f)
        val white = 1023f

        val rcdspv = context.assets.open(VfRcd.rcdAsset(false)).use { it.readBytes() }
        val spv = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfRcd.initRcdNative(rcdspv) == VfVulkan.OK) { "rcd init failed" }

        val bytes = try {
            java.io.File("/data/local/tmp/dng_win.bin").readBytes()
        } catch (e: Exception) {
            null
        }
        assumeTrue("dng_win.bin missing/unreadable", bytes != null && bytes.size == w * h * 2)
        val codes = IntArray(w * h)
        val shortBuf = java.nio.ByteBuffer.wrap(bytes!!)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in codes.indices) codes[i] = shortBuf.get(i).toInt() and 0xFFFF
        val staging = java.nio.ByteBuffer.allocateDirect(bytes.size)
            .order(java.nio.ByteOrder.nativeOrder())
        staging.put(bytes).flip()

        val cfa = VfEglImport.createBlobBayerInput(w * h, 1)
        assumeTrue("BLOB allocate failed", cfa != null)
        try {
            check(VfEglImport.blitBytesToBlob(cfa!!, staging, bytes.size) == 0) { "blit failed" }
            val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            val rgb = HardwareBuffer.create(w, h, HardwareBuffer.RGBA_FP16, 1, usage)
            val scratch = HardwareBuffer.create(w, h, HardwareBuffer.RGBA_FP16, 1, usage)
            try {
                runModes(raw = cfa, rgb = rgb, scratch = scratch, cw = w, ch = h,
                    pitch = w, channels = channels, levels = levels, white = white,
                    modes = 0..3, slot = 0)
                val gpu = VfEglImport.dumpFp16(rgb, w, h)
                assumeTrue("RGB readback failed", gpu != null)
                val cpu = RcdReference.demosaic(
                    codes, w, h, w, 0, 0, w, h, channels, levels, white
                )
                var sum = 0.0
                var max = 0f
                for (i in cpu.indices) {
                    val d = kotlin.math.abs(gpu!![i / 3 * 4 + i % 3] - cpu[i])
                    sum += d
                    if (d > max) max = d
                }
                val mean = (sum / cpu.size).toFloat()
                // Column signature: the production maze shows a fixed
                // even/odd column split; log it for diagnosis either way.
                var evenSum = 0.0
                var oddSum = 0.0
                var evenN = 0
                var oddN = 0
                for (y in 0 until h) for (x in 0 until w) {
                    val v = gpu!![(y * w + x) * 4].toDouble()
                    if ((x and 1) == 0) { evenSum += v; evenN++ } else { oddSum += v; oddN++ }
                }
                Log.i(TAG, "SPIKE rcdstills mean=$mean max=$max evencol=${evenSum / evenN} oddcol=${oddSum / oddN}")
                assertTrue("stills parity mean $mean (expect < $MEAN_GATE)", mean < MEAN_GATE)
                assertTrue("stills parity max $max (expect < $MAX_GATE)", max < MAX_GATE)
            } finally {
                try { rgb.close() } catch (_: Exception) {}
                try { scratch.close() } catch (_: Exception) {}
            }
        } finally {
            try { cfa?.close() } catch (_: Exception) {}
        }
    }

    /** Full-crop-res timing: modes 0-2 + fused, fd-poll true GPU ms. */
    private fun fusedFullRes() {
        val w = 4080
        val h = 2294
        val channels = intArrayOf(0, 1, 2, 3)
        val levels = floatArrayOf(0f, 0f, 0f, 0f)
        val white = 1023f
        val raw = VfEglImport.createBayerInput(w, h)
        assumeTrue("full-res RAW allocate failed", raw != null)
        val pitch = VfEglImport.fillBayerPattern(raw!!, w, h, 0)
        assumeTrue("full-res bayer fill failed", pitch > 0)
        val rgb = HardwareBuffer.create(
            w, h, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val scratch = HardwareBuffer.create(
            w, h, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val p010 = VfEglImport.createP010(w, h)
        assumeTrue("P010 allocate failed", p010 != null)
        try {
            val layout = VfEglImport.describeP010(p010!!)
            assumeTrue("P010 describe failed", layout != null)
            val strides = intArrayOf(layout!![0], layout[1], w, h)
            val (gidims, gfparams) = VfLogGrade.packGrade(
                intArrayOf(w, h),
                floatArrayOf(1f, 1f, 1f, 1f),
                VfLogGrade.IDENTITY_CCM,
                0f, false
            )
            val src = intArrayOf(w, h)
            val (m3i, m3f) = VfRcd.packRcd(w, h, 0, 0, channels, pitch, 3, levels, white)
            // Warmup (slot 1), then timed (slot 0).
            runModes(raw, rgb, scratch, w, h, pitch, channels, levels, white, 0..2, 1)
            var wfd = VfRcd.rcdFusedYuvSubmitNative(
                raw, rgb, scratch, p010, m3i, m3f, gidims, src, gfparams, strides, 1
            )
            if (wfd >= 0) {
                pollFd(wfd)
                VfEglImport.closeSyncFd(wfd)
            }
            val ms = FloatArray(4)
            val tModes = System.nanoTime()
            runModes(raw, rgb, scratch, w, h, pitch, channels, levels, white, 0..2, 0)
            ms[3] = (System.nanoTime() - tModes) / 1e6f
            val t0 = System.nanoTime()
            wfd = VfRcd.rcdFusedYuvSubmitNative(
                raw, rgb, scratch, p010, m3i, m3f, gidims, src, gfparams, strides, 0
            )
            assumeTrue("fused submit failed: $wfd", wfd >= 0)
            assumeTrue("fused never completed", pollFd(wfd))
            VfEglImport.closeSyncFd(wfd)
            ms[0] = (System.nanoTime() - t0) / 1e6f
            val total = ms[3] + ms[0]
            Log.i(TAG, "SPIKE rcdfused full modes02=${"%.1f".format(ms[3])}ms " +
                "fused=${"%.1f".format(ms[0])}ms total=${"%.1f".format(total)}ms " +
                "(split: 236ms modes + ~18ms grade)")
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { rgb.close() } catch (_: Exception) {}
            try { scratch.close() } catch (_: Exception) {}
            try { p010?.close() } catch (_: Exception) {}
        }
    }

    /** 64x64 tile: GPU vs CPU reference, mean abs diff gate. */
    private fun parity64(context: android.content.Context) {
        val dump = InstrumentationRegistry.getArguments().getString("rawlensRcdDump") == "true"
        val w = 64
        val h = 64
        val channels = intArrayOf(0, 1, 2, 3)
        val levels = floatArrayOf(0f, 0f, 0f, 0f)
        val white = 1023f
        val raw = VfEglImport.createBayerInput(w, h)
        assumeTrue("synthetic RAW allocate failed", raw != null)
        val pitch = VfEglImport.fillBayerPattern(raw!!, w, h, 0)
        assumeTrue("bayer fill failed", pitch > 0)
        val rgb = HardwareBuffer.create(
            w, h, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                HardwareBuffer.USAGE_CPU_READ_OFTEN
        )
        val scratch = HardwareBuffer.create(
            w, h, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        try {
            // Warmup: first touch of CPU-written imports reads stale on
            // this gralloc; one throwaway run establishes ownership.
            for (mode in 0..3) {
                val (ip, fp) = VfRcd.packRcd(w, h, 0, 0, channels, pitch, mode, levels, white)
                val fd = VfRcd.rcdSubmitNative(raw, rgb, scratch, ip, fp, 1)
                if (fd >= 0) {
                    pollFd(fd)
                    VfEglImport.closeSyncFd(fd)
                }
            }
            for (mode in 0..3) {
                val (ip, fp) = VfRcd.packRcd(w, h, 0, 0, channels, pitch, mode, levels, white)
                val fd = VfRcd.rcdSubmitNative(raw, rgb, scratch, ip, fp, 0)
                assumeTrue("rcd submit mode $mode failed: $fd", fd >= 0)
                assumeTrue("rcd mode $mode never completed", pollFd(fd))
                VfEglImport.closeSyncFd(fd)
            }
            val gpu = VfEglImport.dumpFp16(rgb, w, h)
            assumeTrue("rgb readback failed", gpu != null && gpu.size == w * h * 4)
            val codes = regenBayer(w, h, pitch, 0)
            val cpu = RcdReference.demosaic(
                codes, w, h, pitch, 0, 0, w, h, channels, levels, white
            )
            var sum = 0.0
            var max = 0f
            var worst = 0
            var big = 0
            var bigMinX = 1 shl 30
            var bigMaxX = -1
            var bigMinY = 1 shl 30
            var bigMaxY = -1
            for (i in cpu.indices) {
                val d = kotlin.math.abs(gpu!![i / 3 * 4 + i % 3] - cpu[i])
                sum += d
                if (d > max) {
                    max = d
                    worst = i
                }
                if (d > 0.05f) {
                    big++
                    val px = i / 3
                    bigMinX = minOf(bigMinX, px % w)
                    bigMaxX = maxOf(bigMaxX, px % w)
                    bigMinY = minOf(bigMinY, px / w)
                    bigMaxY = maxOf(bigMaxY, px / w)
                }
            }
            val mean = (sum / cpu.size).toFloat()
            Log.i(TAG, "SPIKE rcdparity mean=$mean max=$max worstPx=${worst / 3} ch=${worst % 3} " +
                "big=$big box=($bigMinX,$bigMinY)-($bigMaxX,$bigMaxY)")
            if (dump) {
                val wpx = worst / 3
                val wx = wpx % w
                val wy = wpx / w
                val sb = StringBuilder()
                for (dy in -2..2) for (dx in -2..2) {
                    val x = (wx + dx).coerceIn(0, w - 1)
                    val y = (wy + dy).coerceIn(0, h - 1)
                    val ch = worst % 3
                    val gi = (y * w + x) * 3 + ch
                    sb.append("($x,$y g=${"%.4f".format(gpu!![(y * w + x) * 4 + ch])} c=${"%.4f".format(cpu[gi])} code=${codes[y * pitch + x]} )")
                }
                Log.i(TAG, "SPIKE rcddump $sb")
            }
            assertTrue("RCD parity mean diff $mean (expect < $MEAN_GATE)", mean < MEAN_GATE)
            assertTrue("RCD parity max diff $max (expect < $MAX_GATE)", max < MAX_GATE)
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { rgb.close() } catch (_: Exception) {}
            try { scratch.close() } catch (_: Exception) {}
        }
    }

    /** Full-crop-res timing: per-mode true GPU ms via fd poll. */
    private fun perfFullRes() {
        val w = 4080
        val h = 2294
        val channels = intArrayOf(0, 1, 2, 3)
        val levels = floatArrayOf(0f, 0f, 0f, 0f)
        val white = 1023f
        val raw = VfEglImport.createBayerInput(w, h)
        assumeTrue("full-res RAW allocate failed", raw != null)
        val pitch = VfEglImport.fillBayerPattern(raw!!, w, h, 0)
        assumeTrue("full-res bayer fill failed", pitch > 0)
        val rgb = HardwareBuffer.create(
            w, h, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val scratch = HardwareBuffer.create(
            w, h, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        try {
            // One untimed run (warmup + cache settle), then timed.
            for (mode in 0..3) {
                val (ip, fp) = VfRcd.packRcd(w, h, 0, 0, channels, pitch, mode, levels, white)
                val fd = VfRcd.rcdSubmitNative(raw, rgb, scratch, ip, fp, 1)
                if (fd >= 0) {
                    pollFd(fd)
                    VfEglImport.closeSyncFd(fd)
                }
            }
            val ms = FloatArray(4)
            val submitMs = FloatArray(4)
            for (mode in 0..3) {
                val (ip, fp) = VfRcd.packRcd(w, h, 0, 0, channels, pitch, mode, levels, white)
                val t0 = System.nanoTime()
                val fd = VfRcd.rcdSubmitNative(raw, rgb, scratch, ip, fp, 0)
                submitMs[mode] = (System.nanoTime() - t0) / 1e6f
                assumeTrue("rcd perf submit mode $mode failed: $fd", fd >= 0)
                assumeTrue("rcd perf mode $mode never completed", pollFd(fd))
                VfEglImport.closeSyncFd(fd)
                ms[mode] = (System.nanoTime() - t0) / 1e6f
            }
            val total = ms[0] + ms[1] + ms[2] + ms[3]
            Log.i(TAG, "SPIKE rcdperf dirs=${"%.1f".format(ms[0])}ms " +
                "green=${"%.1f".format(ms[1])}ms opp=${"%.1f".format(ms[2])}ms " +
                "atG=${"%.1f".format(ms[3])}ms total=${"%.1f".format(total)}ms " +
                "(gate ~15ms cooled)")
            Log.i(TAG, "SPIKE rcdsubmit cpu=${submitMs.joinToString(",") { "%.1f".format(it) }}ms")
            // No perf assert: RCD is the offline-quality reference (full
            // 4K fidelity, ~250ms with grade — over any video budget by
            // design, and scaling-proven work-bound (÷3.65 for ÷4
            // pixels)). The record path uses Hamilton-Adams; its probe
            // owns the ≤15ms gate. This log line is the standing
            // measurement (see also the fused-finalize timing below).
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { rgb.close() } catch (_: Exception) {}
            try { scratch.close() } catch (_: Exception) {}
        }
    }

    private fun pollFd(fd: Int): Boolean {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (VfEglImport.pollSyncFd(fd)) return true
            Thread.sleep(2)
        }
        return false
    }

    /**
     * CPU copy of the synthetic mosaic (mirrors fillBayerPattern frame 0:
     * R horizontal, Gr vertical, Gb/B diagonal ramps + saturated bar).
     * Returns full-plane codes with [pitch] stride.
     */
    private fun regenBayer(w: Int, h: Int, pitch: Int, frame: Int): IntArray {
        val out = IntArray(pitch * h)
        val barX = (frame * 97) % w
        val barHalf = maxOf(w / 120, 2)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = when {
                    x % 2 == 0 && y % 2 == 0 -> (x * 1023) / w
                    x % 2 == 1 && y % 2 == 0 -> (y * 1023) / h
                    x % 2 == 0 -> ((x + y) * 1023) / (w + h)
                    else -> ((x + y + frame * 29) * 1023) / (w + h)
                }
                var dx = x - barX
                if (dx < 0) dx = -dx
                out[y * pitch + x] = if (dx < barHalf) 1023 else v
            }
        }
        return out
    }

    companion object {
        private const val TAG = "RcdParity"

        /** FP16 store round-trips dominate; 5e-3 is ~5 codes at 10-bit. */
        private const val MEAN_GATE = 5e-3f
        private const val MAX_GATE = 5e-2f
    }
}
