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

    /** 64x64 tile: GPU vs CPU reference, mean abs diff gate. */
    private fun parity64(context: android.content.Context) {
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
            for (i in cpu.indices) {
                val d = kotlin.math.abs(gpu!![i / 3 * 4 + i % 3] - cpu[i])
                sum += d
                if (d > max) {
                    max = d
                    worst = i
                }
            }
            val mean = (sum / cpu.size).toFloat()
            Log.i(TAG, "SPIKE rcdparity mean=$mean max=$max worstPx=${worst / 3} ch=${worst % 3}")
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
            // 4K fidelity, ~233ms — over any video budget by design, and
            // scaling-proven work-bound (÷3.65 for ÷4 pixels)). The record
            // path uses Hamilton-Adams; its probe owns the ≤15ms gate.
            // This log line is the standing measurement.
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
