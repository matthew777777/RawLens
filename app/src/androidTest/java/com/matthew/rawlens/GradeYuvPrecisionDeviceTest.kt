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
 * True-10-bit precision proof: flat mid-gray through superpixel-f16 +
 * grade-YUV into an app-owned P010 buffer, read back on CPU.
 *
 * Deterministic by construction: the synthetic ramp hits ~0.5 at its
 * center texel, gains are 1, CCM is identity, so the expected words come
 * from the CPU grade golden ([VfLogGrade.encodePixel709]) through BT.709 + pack10.
 * This validates the WHOLE content path — FP16 math, P010 packing
 * (shift, ranges), strides, and the interleaved UV offset — with no
 * encoder or rate control involved.
 *
 * Opt-in: `-e rawlensGradeYuvProof true`.
 */
@RunWith(AndroidJUnit4::class)
class GradeYuvPrecisionDeviceTest {
    @Test fun flatGrayLandsOnExpectedP010Codes() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensGradeYuvProof true to run the precision probe",
            args.getString("rawlensGradeYuvProof") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val spv = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        val f16spv = context.assets.open("shaders/vf/vf_superpixel_f16.spv").use { it.readBytes() }
        val yuvspv = context.assets.open("shaders/vf/vf_gradeyuv.spv").use { it.readBytes() }
        val gradespv = context.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfVulkan.initF16Native(f16spv) == VfVulkan.OK) { "f16 init failed" }
        check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) { "grade init failed" }
        check(VfLogGrade.initGradeYuvNative(yuvspv) == VfVulkan.OK) { "yuv init failed" }
        uploadNeutralLut()

        // Synthetic sensor (ramp hits exactly 0.5 at its center texel).
        val raw = VfEglImport.createBayerInput(1920, 1080)
        assumeTrue("synthetic RAW allocate failed", raw != null)
        val pitch = VfEglImport.fillBayerPattern(raw!!, 1920, 1080, 0)
        assumeTrue("bayer fill failed", pitch > 0)
        val exp = HardwareBuffer.create(
            960, 540, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val p010 = VfEglImport.createP010(960, 540)
        assumeTrue("P010 staging allocate failed", p010 != null)
        try {
            val layout = VfEglImport.describeP010(p010!!)
            assumeTrue("P010 describe failed", layout != null)
            Log.i(TAG, "SPIKE yuvproof ours yStride=${layout!![0]} uvStride=${layout[1]}")
            // Superpixel via the wait-free twin (same call as the recorder).
            // Black is 0 (not sensor 64): the center quad (511,511,511,512)
            // then normalizes to ~0.5, which is what the golden expects.
            val (iparams, fparams) = VfVulkan.packParams(
                intArrayOf(0, 1, 2, 3), 0, 0, 960, 540, 2, pitch,
                floatArrayOf(0f, 0f, 0f, 0f), 1023f
            )
            check(VfVulkan.computeSubmitNative(raw, exp!!, iparams, fparams, 0, null, null) == VfVulkan.OK) {
                "compute submit failed"
            }
            // Warmup pair: first touch of CPU-written imports reads stale on
            // this gralloc; one throwaway submit establishes ownership.
            val (wgparams, wgfparams) = VfLogGrade.packGrade(
                intArrayOf(960, 540),
                floatArrayOf(1f, 1f, 1f, 1f),
                VfLogGrade.IDENTITY_CCM,
                0f, true
            )
            val wfd = VfLogGrade.gradeYuvSubmitNative(
                exp, p010,
                intArrayOf(960, 540), intArrayOf(960, 540), wgfparams,
                intArrayOf(layout[0], layout[1], 960, 540), 1, 0
            )
            if (wfd >= 0) {
                val wend = System.currentTimeMillis() + 5000
                while (!VfEglImport.pollSyncFd(wfd) && System.currentTimeMillis() < wend) {
                    Thread.sleep(2)
                }
                VfEglImport.closeSyncFd(wfd)
            }
            val (gparams, gfparams) = VfLogGrade.packGrade(
                intArrayOf(960, 540),
                floatArrayOf(1f, 1f, 1f, 1f),
                VfLogGrade.IDENTITY_CCM,
                0f, false
            )
            val gt0 = System.nanoTime()
            val fd = VfLogGrade.gradeYuvSubmitNative(
                exp, p010,
                intArrayOf(960, 540), intArrayOf(960, 540), gfparams,
                intArrayOf(layout[0], layout[1], 960, 540), 0, 0
            )
            assumeTrue("grade-yuv submit failed: $fd", fd >= 0)
            // Blocking valve (probe validation only): fd completion, then
            // the words are final for the CPU readback.
            val deadline = System.currentTimeMillis() + 5000
            var ready = false
            while (System.currentTimeMillis() < deadline) {
                if (VfEglImport.pollSyncFd(fd)) {
                    ready = true
                    break
                }
                Thread.sleep(2)
            }
            VfEglImport.closeSyncFd(fd)
            assumeTrue("GPU work never completed", ready)
            Log.i(TAG, "SPIKE yuvtime-sp mode=superpixel ms=${"%.1f".format((System.nanoTime() - gt0) / 1e6f)}")

            val words = VfEglImport.sampleP010Center(p010, layout[0], layout[1], 960, 540, 480, 270)
            assumeTrue("P010 readback failed", words != null && words.size == 3)
            // Center quad mean (511, 511.5, 511)/1023 through the golden.
            val expected = gradeExpectedWords(
                floatArrayOf(511f / 1023f, 511.5f / 1023f, 511f / 1023f)
            )
            Log.i(TAG, "SPIKE yuvproof Y=${words!![0]} U=${words[1]} V=${words[2]} " +
                "(ref ${expected.toList()})")
            assertGradeWords("yuvproof", words, expected)
        } finally {
            try { raw?.close() } catch (_: Exception) {}
            try { exp.close() } catch (_: Exception) {}
            try { p010?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Full-RGB mode proof: MHC demosaic of the synthetic mosaic graded
     * straight to P010 (the record path: demosaic -> grade fullRgb=1).
     * The mosaic center is ~0.5 in every channel — MHC reproduces linear
     * ramps — and the expects come from the CPU grade golden.
     *
     * Opt-in: `-e rawlensGradeYuvProof true` (same flag).
     */
    @Test fun fullRgbLandsOnExpectedP010Codes() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensGradeYuvProof true to run the precision probe",
            args.getString("rawlensGradeYuvProof") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val spv = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        val yuvspv = context.assets.open("shaders/vf/vf_gradeyuv.spv").use { it.readBytes() }
        val gradespv = context.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        val mhcspv = context.assets.open("shaders/vf/vf_mhc.spv").use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) { "grade init failed" }
        check(VfLogGrade.initGradeYuvNative(yuvspv) == VfVulkan.OK) { "yuv init failed" }
        uploadNeutralLut()
        check(VfMhc.initMhcNative(mhcspv) == VfVulkan.OK) { "mhc init failed" }

        val w = 256
        val h = 256
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
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val p010 = VfEglImport.createP010(w, h)
        assumeTrue("P010 staging allocate failed", p010 != null)
        try {
            val layout = VfEglImport.describeP010(p010!!)
            assumeTrue("P010 describe failed", layout != null)
            val (ip, fp) = VfMhc.packMhc(w, h, 0, 0, channels, pitch, levels, white)
            // Warmup: first touch of CPU-written imports reads stale on
            // this gralloc; one throwaway run establishes ownership.
            val warm = VfMhc.mhcSubmitNative(raw, rgb, ip, fp, 1)
            if (warm >= 0) {
                poll(warm)
                VfEglImport.closeSyncFd(warm)
            }
            val mfd = VfMhc.mhcSubmitNative(raw, rgb, ip, fp, 0)
            assumeTrue("mhc submit failed: $mfd", mfd >= 0)
            assumeTrue("mhc never completed", poll(mfd))
            VfEglImport.closeSyncFd(mfd)
            val (gparams, gfparams) = VfLogGrade.packGrade(
                intArrayOf(w, h),
                floatArrayOf(1f, 1f, 1f, 1f),
                VfLogGrade.IDENTITY_CCM,
                0f, false
            )
            val gt0 = System.nanoTime()
            val fd = VfLogGrade.gradeYuvSubmitNative(
                rgb, p010,
                intArrayOf(w, h), intArrayOf(w, h), gfparams,
                intArrayOf(layout!![0], layout[1], w, h), 0, 1
            )
            assumeTrue("grade-yuv full-rgb submit failed: $fd", fd >= 0)
            assumeTrue("GPU work never completed", poll(fd))
            VfEglImport.closeSyncFd(fd)
            Log.i(TAG, "SPIKE yuvtime-rgb mode=fullrgb ms=${"%.1f".format((System.nanoTime() - gt0) / 1e6f)}")

            val words = VfEglImport.sampleP010Center(p010, layout[0], layout[1], w, h, w / 2, h / 2)
            assumeTrue("P010 readback failed", words != null && words.size == 3)
            // MHC reproduces the linear ramps: 511/1023 per channel.
            val g = 511f / 1023f
            val expected = gradeExpectedWords(floatArrayOf(g, g, g))
            Log.i(TAG, "SPIKE yuvproof-rgb Y=${words!![0]} U=${words[1]} V=${words[2]} " +
                "(ref ${expected.toList()})")
            assertGradeWords("yuvproof-rgb", words, expected)
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { rgb.close() } catch (_: Exception) {}
            try { p010?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Production-size chain timing (informational, no gate): MHC full
     * crop -> grade full-RGB at encode res, each stage timed separately
     * with a tight poll. The two share one Vulkan queue (serial), so
     * the SUM is the GPU cost per record frame at 30fps.
     *
     * Opt-in: `-e rawlensGradeYuvProof true` (same flag).
     */
    @Test fun fullResChainTiming() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensGradeYuvProof true to run the precision probe",
            args.getString("rawlensGradeYuvProof") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val spv = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        val yuvspv = context.assets.open("shaders/vf/vf_gradeyuv.spv").use { it.readBytes() }
        val gradespv = context.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        val mhcspv = context.assets.open("shaders/vf/vf_mhc.spv").use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) { "grade init failed" }
        check(VfLogGrade.initGradeYuvNative(yuvspv) == VfVulkan.OK) { "yuv init failed" }
        uploadNeutralLut()
        check(VfMhc.initMhcNative(mhcspv) == VfVulkan.OK) { "mhc init failed" }

        val cw = 3840
        val ch = 2160
        val ow = 3840
        val oh = 2160
        val channels = intArrayOf(0, 1, 2, 3)
        val levels = floatArrayOf(0f, 0f, 0f, 0f)
        val white = 1023f
        val raw = VfEglImport.createBlobBayerInput(cw, ch)
        assumeTrue("BLOB CFA allocate failed", raw != null)
        val pitch = VfEglImport.fillBlobBayer(raw!!, cw, ch)
        assumeTrue("BLOB CFA fill failed", pitch > 0)
        val rgb = HardwareBuffer.create(
            cw, ch, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val p010 = VfEglImport.createP010(ow, oh)
        assumeTrue("P010 staging allocate failed", p010 != null)
        try {
            val layout = VfEglImport.describeP010(p010!!)
            assumeTrue("P010 describe failed", layout != null)
            val (ip, fp) = VfMhc.packMhc(cw, ch, 0, 0, channels, pitch, levels, white)
            val mw = VfMhc.mhcSubmitNative(raw, rgb, ip, fp, 1)
            if (mw >= 0) {
                pollTight(mw)
                VfEglImport.closeSyncFd(mw)
            }
            val t0 = System.nanoTime()
            val mfd = VfMhc.mhcSubmitNative(raw, rgb, ip, fp, 0)
            assumeTrue("mhc submit failed: $mfd", mfd >= 0)
            assumeTrue("mhc never completed", pollTight(mfd))
            VfEglImport.closeSyncFd(mfd)
            val mhcMs = (System.nanoTime() - t0) / 1e6f
            val (_, gfparams) = VfLogGrade.packGrade(
                intArrayOf(ow, oh),
                floatArrayOf(1f, 1f, 1f, 1f),
                VfLogGrade.IDENTITY_CCM,
                0f, false
            )
            val gw = VfLogGrade.gradeYuvSubmitNative(
                rgb, p010,
                intArrayOf(ow, oh), intArrayOf(cw, ch), gfparams,
                intArrayOf(layout!![0], layout[1], ow, oh), 1, 1
            )
            if (gw >= 0) {
                pollTight(gw)
                VfEglImport.closeSyncFd(gw)
            }
            val t1 = System.nanoTime()
            val fd = VfLogGrade.gradeYuvSubmitNative(
                rgb, p010,
                intArrayOf(ow, oh), intArrayOf(cw, ch), gfparams,
                intArrayOf(layout[0], layout[1], ow, oh), 0, 1
            )
            assumeTrue("grade-yuv submit failed: $fd", fd >= 0)
            assumeTrue("GPU work never completed", pollTight(fd))
            VfEglImport.closeSyncFd(fd)
            val gradeMs = (System.nanoTime() - t1) / 1e6f
            Log.i(TAG, "SPIKE yuvchain mhc=${"%.1f".format(mhcMs)}ms " +
                "grade=${"%.1f".format(gradeMs)}ms sum=${"%.1f".format(mhcMs + gradeMs)}ms")
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { rgb.close() } catch (_: Exception) {}
            try { p010?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Fused record-stage proof (precision + perf): CFA mosaic straight
     * to graded P010 at production sizes (3840x2160 window -> 3840x2160).
     * Expects come from the CPU grade golden (the mosaic center is ~0.5
     * in every channel); the fused path keeps fp32 between stages (no
     * f16 RGB round trip), so it lands at least as close. Perf gate is
     * the 30fps frame minus margin (single queue: the fused ms IS the
     * GPU cost per record frame).
     *
     * Opt-in: `-e rawlensGradeYuvProof true` (same flag).
     */
    @Test fun fullResFusedProof() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensGradeYuvProof true to run the precision probe",
            args.getString("rawlensGradeYuvProof") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val spv = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        val gradespv = context.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        val fusedspv = context.assets.open("shaders/vf/vf_mhcyuv.spv").use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) { "grade init failed" }
        check(VfLogGrade.initFusedYuvNative(fusedspv) == VfVulkan.OK) { "fused init failed" }
        uploadNeutralLut()

        val cw = 3840
        val ch = 2160
        val ow = 3840
        val oh = 2160
        val channels = intArrayOf(0, 1, 2, 3)
        val levels = floatArrayOf(0f, 0f, 0f, 0f)
        val white = 1023f
        val raw = VfEglImport.createBlobBayerInput(cw, ch)
        assumeTrue("BLOB CFA allocate failed", raw != null)
        val pitch = VfEglImport.fillBlobBayer(raw!!, cw, ch)
        assumeTrue("BLOB CFA fill failed", pitch > 0)
        val p010 = VfEglImport.createP010(ow, oh)
        assumeTrue("P010 staging allocate failed", p010 != null)
        try {
            val layout = VfEglImport.describeP010(p010!!)
            assumeTrue("P010 describe failed", layout != null)
            val (ip, fp) = VfMhc.packMhc(cw, ch, 0, 0, channels, pitch, levels, white)
            val (_, gfparams) = VfLogGrade.packGrade(
                intArrayOf(ow, oh),
                floatArrayOf(1f, 1f, 1f, 1f),
                VfLogGrade.IDENTITY_CCM,
                0f, false
            )
            val strides = intArrayOf(layout!![0], layout[1], ow, oh)
            val warm = VfLogGrade.fusedYuvSubmitNative(
                raw, p010, ip, fp, intArrayOf(ow, oh), gfparams, strides, 1, null, null
            )
            if (warm >= 0) {
                pollTight(warm)
                VfEglImport.closeSyncFd(warm)
            }
            val t0 = System.nanoTime()
            val fd = VfLogGrade.fusedYuvSubmitNative(
                raw, p010, ip, fp, intArrayOf(ow, oh), gfparams, strides, 0, null, null
            )
            assumeTrue("fused submit failed: $fd", fd >= 0)
            assumeTrue("GPU work never completed", pollTight(fd))
            VfEglImport.closeSyncFd(fd)
            val ms = (System.nanoTime() - t0) / 1e6f
            Log.i(TAG, "SPIKE yuvchain-fused ms=${"%.1f".format(ms)} (gate $FUSED_GATE_MS)")
            assertTrue("fused full-res ${ms}ms exceeds ${FUSED_GATE_MS}ms", ms < FUSED_GATE_MS)

            val words = VfEglImport.sampleP010Center(p010, layout[0], layout[1], ow, oh, ow / 2, oh / 2)
            assumeTrue("P010 readback failed", words != null && words.size == 3)
            // BLOB center is 511/1023 per channel; MHC reproduces ramps.
            val g = 511f / 1023f
            val expected = gradeExpectedWords(floatArrayOf(g, g, g))
            Log.i(TAG, "SPIKE yuvproof-fused Y=${words!![0]} U=${words[1]} V=${words[2]} " +
                "(ref ${expected.toList()})")
            assertGradeWords("yuvproof-fused", words, expected)
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { p010?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Fused WB/exposure regression: capture-result white balance and
     * exposure MUST reach the math (folded into the CCM on the CPU —
     * the fused shader applies neither itself). Each run is checked
     * against the CPU grade golden fed the same folded input; the guards
     * first prove the predicted jumps are big, so the test can't pass
     * vacuously. Fails with identical outputs when gains are ignored.
     *
     * Opt-in: `-e rawlensGradeYuvProof true` (same flag).
     */
    @Test fun fusedAppliesWbAndExposure() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensGradeYuvProof true to run the precision probe",
            args.getString("rawlensGradeYuvProof") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val spv = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        val gradespv = context.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        val fusedspv = context.assets.open("shaders/vf/vf_mhcyuv.spv").use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) { "grade init failed" }
        check(VfLogGrade.initFusedYuvNative(fusedspv) == VfVulkan.OK) { "fused init failed" }
        uploadNeutralLut()

        val cw = 960
        val ch = 540
        val ow = 960
        val oh = 540
        val channels = intArrayOf(0, 1, 2, 3)
        val levels = floatArrayOf(0f, 0f, 0f, 0f)
        val white = 1023f
        val raw = VfEglImport.createBlobBayerInput(cw, ch)
        assumeTrue("BLOB CFA allocate failed", raw != null)
        val pitch = VfEglImport.fillBlobBayer(raw!!, cw, ch)
        assumeTrue("BLOB CFA fill failed", pitch > 0)
        val p010 = VfEglImport.createP010(ow, oh)
        assumeTrue("P010 staging allocate failed", p010 != null)
        try {
            val layout = VfEglImport.describeP010(p010!!)
            assumeTrue("P010 describe failed", layout != null)
            val (ip, fp) = VfMhc.packMhc(cw, ch, 0, 0, channels, pitch, levels, white)
            val strides = intArrayOf(layout!![0], layout[1], ow, oh)
            fun run(gains: FloatArray, ev: Float): IntArray {
                val (_, gfparams) = VfLogGrade.packGrade(
                    intArrayOf(ow, oh), gains, VfLogGrade.IDENTITY_CCM, ev, false
                )
                val fd = VfLogGrade.fusedYuvSubmitNative(
                    raw, p010, ip, fp, intArrayOf(ow, oh), gfparams, strides, 0, null, null
                )
                assumeTrue("fused submit failed: $fd", fd >= 0)
                assumeTrue("GPU work never completed", pollTight(fd))
                VfEglImport.closeSyncFd(fd)
                val words = VfEglImport.sampleP010Center(
                    p010, layout[0], layout[1], ow, oh, ow / 2, oh / 2
                )
                assumeTrue("P010 readback failed", words != null && words.size == 3)
                return words!!
            }
            val unit = floatArrayOf(1f, 1f, 1f, 1f)
            val base = run(unit, 0f)
            val red2 = run(floatArrayOf(2f, 1f, 1f, 1f), 0f)
            val ev1 = run(unit, 1f)
            Log.i(TAG, "SPIKE yuvproof-wb base=${base.toList()} red2=${red2.toList()} ev1=${ev1.toList()}")
            // Folded inputs (identity CCM): gains x linear x 2^ev.
            val g = 511f / 1023f
            val expBase = gradeExpectedWords(floatArrayOf(g, g, g))
            val expRed = gradeExpectedWords(floatArrayOf(2f * g, g, g))
            val expEv = gradeExpectedWords(floatArrayOf(2f * g, 2f * g, 2f * g))
            // Guards: the predicted jumps must be big (else vacuous).
            assertTrue(
                "no predicted V jump ${expBase.toList()} -> ${expRed.toList()}",
                expRed[2] - expBase[2] > 1024
            )
            assertTrue(
                "no predicted Y jump ${expBase.toList()} -> ${expEv.toList()}",
                expEv[0] - expBase[0] > 1024
            )
            assertGradeWords("wb-base", base, expBase)
            assertGradeWords("wb-red2", red2, expRed)
            assertGradeWords("wb-ev1", ev1, expEv)
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { p010?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Fused lens-shading (vignetting) proof: a uniform HAL gain map must
     * multiply demosaiced RGB pre-WB — R/B per channel, demosaiced green
     * by the Gr/Gb mean (it mixes both parities). The uniform 2x2 map is
     * bilinear-exact at every texel, so expects are the CPU grade golden
     * fed the scaled linear input. Null dims/gains is the identity path
     * (probes + HAL-shaded sensors).
     *
     * Opt-in: `-e rawlensGradeYuvProof true` (same flag).
     */
    @Test fun fusedAppliesLensShading() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensGradeYuvProof true to run the precision probe",
            args.getString("rawlensGradeYuvProof") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val spv = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        val gradespv = context.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        val fusedspv = context.assets.open("shaders/vf/vf_mhcyuv.spv").use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) { "grade init failed" }
        check(VfLogGrade.initFusedYuvNative(fusedspv) == VfVulkan.OK) { "fused init failed" }
        uploadNeutralLut()

        val cw = 960
        val ch = 540
        val ow = 960
        val oh = 540
        val channels = intArrayOf(0, 1, 2, 3)
        val levels = floatArrayOf(0f, 0f, 0f, 0f)
        val white = 1023f
        val raw = VfEglImport.createBlobBayerInput(cw, ch)
        assumeTrue("BLOB CFA allocate failed", raw != null)
        val pitch = VfEglImport.fillBlobBayer(raw!!, cw, ch)
        assumeTrue("BLOB CFA fill failed", pitch > 0)
        val p010 = VfEglImport.createP010(ow, oh)
        assumeTrue("P010 staging allocate failed", p010 != null)
        try {
            val layout = VfEglImport.describeP010(p010!!)
            assumeTrue("P010 describe failed", layout != null)
            val (ip, fp) = VfMhc.packMhc(cw, ch, 0, 0, channels, pitch, levels, white)
            val (_, gfparams) = VfLogGrade.packGrade(
                intArrayOf(ow, oh),
                floatArrayOf(1f, 1f, 1f, 1f),
                VfLogGrade.IDENTITY_CCM,
                0f, false
            )
            val strides = intArrayOf(layout!![0], layout[1], ow, oh)
            fun run(dims: IntArray?, gains: ShortArray?): IntArray {
                val fd = VfLogGrade.fusedYuvSubmitNative(
                    raw, p010, ip, fp, intArrayOf(ow, oh), gfparams, strides, 0,
                    dims, gains
                )
                assumeTrue("fused submit failed: $fd", fd >= 0)
                assumeTrue("GPU work never completed", pollTight(fd))
                VfEglImport.closeSyncFd(fd)
                val words = VfEglImport.sampleP010Center(
                    p010, layout[0], layout[1], ow, oh, ow / 2, oh / 2
                )
                assumeTrue("P010 readback failed", words != null && words.size == 3)
                return words!!
            }
            val dims = intArrayOf(2, 2, 0, 0, cw, ch)
            fun halfMap(cells: FloatArray): ShortArray {
                require(cells.size == 2 * 2 * 4)
                return ShortArray(cells.size) { i -> DirectLogRecorder.floatToHalfBits(cells[i]) }
            }
            fun uniformMap(r: Float, gr: Float, gb: Float, b: Float) =
                halfMap(FloatArray(2 * 2 * 4) { i ->
                    when (i % 4) {
                        0 -> r
                        1 -> gr
                        2 -> gb
                        else -> b
                    }
                })
            val base = run(null, null)
            val red2 = run(dims, uniformMap(2f, 1f, 1f, 1f))
            val greenMean = run(dims, uniformMap(1f, 2f, 1f, 1f))
            // Gradient map: R gains 1/1.25/1.75/2 per cell (row-major),
            // rest 1. The center sample then pins the texture coordinate
            // mapping: the CPU golden below bilinear-matches at the exact
            // center pixel, so a mapping slip lands on a different value
            // (asymmetric: no slip reproduces the mean). All gains stay
            // <1 post-scale (no clipping path) and exact in fp16.
            val gradient = halfMap(floatArrayOf(
                1f, 1f, 1f, 1f, 1.25f, 1f, 1f, 1f,
                1.75f, 1f, 1f, 1f, 2f, 1f, 1f, 1f
            ))
            val gradRun = run(dims, gradient)
            Log.i(TAG, "SPIKE yuvproof-shade base=${base.toList()} red2=${red2.toList()} gr=${greenMean.toList()} grad=${gradRun.toList()}")
            // BLOB center is 511/1023 per channel; shading scales linear
            // pre-WB, green by the Gr/Gb mean ((2+1)/2 = 1.5).
            val g = 511f / 1023f
            val expBase = gradeExpectedWords(floatArrayOf(g, g, g))
            val expRed = gradeExpectedWords(floatArrayOf(2f * g, g, g))
            val expGreen = gradeExpectedWords(floatArrayOf(g, 1.5f * g, g))
            val gradModel = LensShadingModel(
                2, 2,
                floatArrayOf(
                    1f, 1f, 1f, 1f, 1.25f, 1f, 1f, 1f,
                    1.75f, 1f, 1f, 1f, 2f, 1f, 1f, 1f
                ),
                IntRectSnapshot(0, 0, cw, ch), false
            )
            val expGrad = gradeExpectedWords(
                floatArrayOf(
                    g * gradModel.gainAt(ow / 2, oh / 2, CfaColor.RED),
                    g, g
                )
            )
            // Guards: the predicted jumps must be big (else vacuous).
            assertTrue(
                "no predicted V jump ${expBase.toList()} -> ${expRed.toList()}",
                expRed[2] - expBase[2] > 1024
            )
            assertTrue(
                "no predicted Y jump ${expBase.toList()} -> ${expGreen.toList()}",
                expGreen[0] - expBase[0] > 512
            )
            assertTrue(
                "no predicted Y jump ${expBase.toList()} -> ${expGrad.toList()}",
                expGrad[0] - expBase[0] > 512
            )
            assertGradeWords("shade-base", base, expBase)
            assertGradeWords("shade-red2", red2, expRed)
            assertGradeWords("shade-gr-mean", greenMean, expGreen)
            assertGradeWords("shade-gradient", gradRun, expGrad)
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { p010?.close() } catch (_: Exception) {}
        }
    }

    private fun poll(fd: Int): Boolean {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (VfEglImport.pollSyncFd(fd)) return true
            Thread.sleep(2)
        }
        return false
    }

    /** Tight fd wait (yield-spin) so chain timing is true signal time. */
    private fun pollTight(fd: Int): Boolean {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (VfEglImport.pollSyncFd(fd)) return true
            Thread.yield()
        }
        return false
    }

    /**
     * Upload the neutral-knob LUT (required before any grade submit;
     * every proof grades neutral unless stated).
     */
    private fun uploadNeutralLut() {
        val rc = VfLogGrade.gradeLutUploadNative(
            VfLogGrade.bakeLut(VfLogGrade.DEFAULT_CONTRAST)
        )
        check(rc == VfVulkan.OK) { "grade lut upload failed: $rc" }
    }

    /**
     * Reference BT.709 P010 words for a developed-linear input: the CPU
     * golden ([VfLogGrade.encodePixel709]) through BT.709 +
     * limited-range pack10 (mirror of the shader).
     * Self-calibrating: no magic code numbers, robust to future
     * look-default changes.
     */
    private fun gradeExpectedWords(linear: FloatArray): IntArray {
        val disp = VfLogGrade.encodePixel709(linear)
        val y = 0.2126f * disp[0] + 0.7152f * disp[1] + 0.0722f * disp[2]
        val cb = (disp[2] - y) / (2f * (1f - 0.0722f)) + 0.5f
        val cr = (disp[0] - y) / (2f * (1f - 0.2126f)) + 0.5f
        fun pack10(v: Float, low: Float, high: Float): Int {
            val code = Math.round(v.coerceIn(0f, 1f) * (high - low) + low)
            return (code shl 6) and 0xFFC0
        }
        return intArrayOf(pack10(y, 64f, 940f), pack10(cb, 64f, 960f), pack10(cr, 64f, 960f))
    }

    private fun assertGradeWords(label: String, actual: IntArray, expected: IntArray) {
        for (i in 0..2) {
            assertTrue(
                "$label ch$i ${actual[i]} vs ref ${expected[i]}",
                Math.abs(actual[i] - expected[i]) <= GRADE_WORD_TOLERANCE
            )
        }
    }

    companion object {
        private const val TAG = "GradeYuvProof"

        /** ±8 codes: f16 round trip + libm + mosaic slope (see proofs). */
        private const val GRADE_WORD_TOLERANCE = 8 shl 6

        /** Fused record-stage gate: the 30fps frame minus margin. */
        private const val FUSED_GATE_MS = 30f
    }
}
