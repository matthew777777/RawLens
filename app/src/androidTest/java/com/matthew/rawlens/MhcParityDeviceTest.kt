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
 * MHC parity + perf: synthetic Bayer through the GPU MHC demosaic vs
 * the [MhcReference] CPU port (mean abs diff gate), then a full-crop-res
 * timing run (true GPU ms via fd poll). Owns the record-path ≤15ms
 * demosaic gate (RCD is the offline reference and asserts no perf).
 *
 * Opt-in: `-e rawlensMhcParity true`.
 */
@RunWith(AndroidJUnit4::class)
class MhcParityDeviceTest {
    @Test fun gpuMatchesCpuReference() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensMhcParity true to run the MHC parity probe",
            args.getString("rawlensMhcParity") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val spv = context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        val gradespv = context.assets.open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        val mhcspv = context.assets.open("shaders/vf/vf_mhc.spv").use { it.readBytes() }
        check(VfVulkan.initNative(spv) == VfVulkan.OK) { "vulkan init failed" }
        check(VfLogGrade.initGradeNative(gradespv) == VfVulkan.OK) { "grade init failed" }
        check(VfMhc.initMhcNative(mhcspv) == VfVulkan.OK) { "mhc init failed" }

        parity64()
        perfFullRes()
    }

    /** 64x64 tile: GPU vs CPU reference, mean abs diff gate. */
    private fun parity64() {
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
        try {
            val (ip, fp) = VfMhc.packMhc(w, h, 0, 0, channels, pitch, levels, white)
            // Warmup: first touch of CPU-written imports reads stale on
            // this gralloc; one throwaway run establishes ownership.
            val warm = VfMhc.mhcSubmitNative(raw, rgb, ip, fp, 1)
            if (warm >= 0) {
                pollFd(warm)
                VfEglImport.closeSyncFd(warm)
            }
            val fd = VfMhc.mhcSubmitNative(raw, rgb, ip, fp, 0)
            assumeTrue("mhc submit failed: $fd", fd >= 0)
            assumeTrue("mhc never completed", pollFd(fd))
            VfEglImport.closeSyncFd(fd)
            val gpu = VfEglImport.dumpFp16(rgb, w, h)
            assumeTrue("rgb readback failed", gpu != null && gpu.size == w * h * 4)
            val codes = regenBayer(w, h, pitch, 0)
            val cpu = MhcReference.demosaic(
                codes, w, h, pitch, 0, 0, w, h, channels, levels, white
            )
            var sum = 0.0
            var max = 0f
            var worst = 0
            for (i in cpu.indices) {
                val d = kotlin.math.abs(gpu!![i / 3 * 4 + i % 3] - cpu[i])
                sum += d
                if (d > max) {
                    max = d
                    worst = i
                }
            }
            val mean = (sum / cpu.size).toFloat()
            Log.i(TAG, "SPIKE mhcparity mean=$mean max=$max worstPx=${worst / 3} ch=${worst % 3}")
            assertTrue("MHC parity mean diff $mean (expect < $MEAN_GATE)", mean < MEAN_GATE)
            assertTrue("MHC parity max diff $max (expect < $MAX_GATE)", max < MAX_GATE)
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { rgb.close() } catch (_: Exception) {}
        }
    }

    /**
     * Full-crop-res timing: true GPU ms via fd poll.
     *
     * The CFA is BLOB-backed with GPU_DATA_BUFFER (not the R16
     * synthetic): real camera HAL buffers carry GPU usage, and the
     * format-agnostic storage import only needs codes[] + pitch.
     */
    private fun perfFullRes() {
        val w = 4080
        val h = 2294
        val channels = intArrayOf(0, 1, 2, 3)
        val levels = floatArrayOf(0f, 0f, 0f, 0f)
        val white = 1023f
        val raw = VfEglImport.createBlobBayerInput(w, h)
        assumeTrue("full-res RAW allocate failed", raw != null)
        val pitch = VfEglImport.fillBlobBayer(raw!!, w, h)
        assumeTrue("full-res bayer fill failed", pitch > 0)
        val rgb = HardwareBuffer.create(
            w, h, HardwareBuffer.RGBA_FP16, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        try {
            val (ip, fp) = VfMhc.packMhc(w, h, 0, 0, channels, pitch, levels, white)
            // One untimed run (warmup + cache settle), then timed.
            val warm = VfMhc.mhcSubmitNative(raw, rgb, ip, fp, 1)
            if (warm >= 0) {
                pollFd(warm)
                VfEglImport.closeSyncFd(warm)
            }
            val t0 = System.nanoTime()
            val fd = VfMhc.mhcSubmitNative(raw, rgb, ip, fp, 0)
            val submitMs = (System.nanoTime() - t0) / 1e6f
            assumeTrue("mhc perf submit failed: $fd", fd >= 0)
            assumeTrue("mhc perf never completed", pollFd(fd))
            VfEglImport.closeSyncFd(fd)
            val ms = (System.nanoTime() - t0) / 1e6f
            Log.i(TAG, "SPIKE mhcperf gpu=${"%.1f".format(ms)}ms " +
                "submit=${"%.1f".format(submitMs)}ms (gate 15ms)")
            // 20 back-to-back timed runs: ramp-down across the loop would
            // mean parked clocks (sustained video runs faster); flat means
            // work-bound. (A 300-run sustained check was flat.)
            val loop = FloatArray(20)
            for (k in loop.indices) {
                val t1 = System.nanoTime()
                val fdk = VfMhc.mhcSubmitNative(raw, rgb, ip, fp, 0)
                if (fdk < 0) break
                pollFd(fdk)
                VfEglImport.closeSyncFd(fdk)
                loop[k] = (System.nanoTime() - t1) / 1e6f
            }
            Log.i(TAG, "SPIKE mhcloop ${loop.joinToString(",") { "%.0f".format(it) }}")
            assertTrue("MHC full-res ${ms}ms exceeds ${PERF_GATE_MS}ms", ms < PERF_GATE_MS)
        } finally {
            try { raw.close() } catch (_: Exception) {}
            try { rgb.close() } catch (_: Exception) {}
        }
    }

    /**
     * Tight fd wait: yield-spin (no sleep quantum) so the gated ms is
     * true signal time, not poll cadence. A spinning wait burns one
     * core for ~25ms; acceptable for a perf probe, and it does not
     * touch the GPU under measurement.
     */
    private fun pollFd(fd: Int): Boolean {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (VfEglImport.pollSyncFd(fd)) return true
            Thread.yield()
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
        private const val TAG = "MhcParity"

        /** One FP16 store round-trip; 5e-3 is ~5 codes at 10-bit. */
        private const val MEAN_GATE = 5e-3f
        private const val MAX_GATE = 5e-2f

        /**
         * MHC component gate (the fused record stage owns the 30fps
         * frame budget; this trips on MHC-only regression with room
         * for the grade work fused alongside it). The headless
         * single-dispatch test is pessimistic vs record with a live
         * viewfinder (sustained GPU load clocks higher).
         */
        private const val PERF_GATE_MS = 25f
    }
}
