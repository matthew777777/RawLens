// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic 50 MP CPU-path tests (host, no camera, no native lib).
 *
 * High-resolution phones (50 MP = 8192x6144, 100 MiB packed planes) stress the
 * CPU fallback with wide strides and large quad steps. These tests prove the
 * contract validation, quad geometry and preview-EV estimate stay correct at
 * that scale, and log the estimate cost that motivated the 30 fps governor.
 */
class VfCpuSynthetic50MpTest {
    companion object {
        const val SENSOR_W = 8192
        const val SENSOR_H = 6144
        const val ROW_STRIDE = SENSOR_W * 2
        const val WHITE = 1023f
        val BLACK = floatArrayOf(64f, 64f, 64f, 64f)
        val CHANNELS = intArrayOf(0, 1, 2, 3)
    }

    @Test fun `50mp full-edge geometry stays even and inside budget`() {
        val geo = RawPreviewGeometry.quadGeometry(SENSOR_W, SENSOR_H, null, VfResolution.MAX)
        assertEquals(8, geo.step)
        assertEquals(1024, geo.width)
        assertEquals(768, geo.height)
        assertEquals(0, geo.left)
        assertEquals(0, geo.top)
    }

    @Test fun `50mp cpu-cap geometry uses step 14`() {
        val geo = RawPreviewGeometry.quadGeometry(SENSOR_W, SENSOR_H, null, VfResolution.CPU_MAX)
        assertEquals(14, geo.step)
        assertEquals(584, geo.width)
        assertEquals(438, geo.height)
    }

    @Test fun `50mp contract validation passes and fails only on missing native lib`() {
        // 100 MiB direct plane, unfilled: validation runs before any pixel read.
        val source = ByteBuffer.allocateDirect(ROW_STRIDE * SENSOR_H).order(ByteOrder.nativeOrder())
        val geo = RawPreviewGeometry.quadGeometry(SENSOR_W, SENSOR_H, null, VfResolution.CPU_MAX)
        val dest = ByteBuffer.allocateDirect(geo.width * geo.height * 4).order(ByteOrder.nativeOrder())
        try {
            VfCpuNeon.copy(
                source, ROW_STRIDE, 2, geo.left, geo.top,
                geo.width, geo.height, geo.step, CHANNELS, BLACK, WHITE, dest
            )
            // On a host with the .so present this would succeed; either way the
            // 50 MP-scale require() math (Long spans, no Int overflow) passed.
        } catch (e: IllegalStateException) {
            // Expected on host: validation passed, native library is absent.
            assertTrue(e.message!!.contains("unavailable"))
        }
    }

    @Test fun `50mp short plane is rejected by the contract`() {
        // Ends one row above the last visited quad row: must fail validation,
        // never reach JNI.
        val geo = RawPreviewGeometry.quadGeometry(SENSOR_W, SENSOR_H, null, VfResolution.CPU_MAX)
        val shortRows = geo.top + (geo.height - 1) * geo.step + 1
        val source = ByteBuffer.allocateDirect(ROW_STRIDE * shortRows).order(ByteOrder.nativeOrder())
        val dest = ByteBuffer.allocateDirect(geo.width * geo.height * 4).order(ByteOrder.nativeOrder())
        try {
            VfCpuNeon.copy(
                source, ROW_STRIDE, 2, geo.left, geo.top,
                geo.width, geo.height, geo.step, CHANNELS, BLACK, WHITE, dest
            )
            throw AssertionError("expected IllegalArgumentException for short 50 MP plane")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test fun `50mp preview-ev matches small-buffer oracle on uniform codes`() {
        // Uniform dark plane: any stride/gather bug at 50 MP span reads wrong
        // codes and moves EV away from the save-path +1.5 clamp pinned by
        // VfPreviewExposureTest on small buffers.
        val source = uniformPlane(code = 100)
        val geo = RawPreviewGeometry.quadGeometry(SENSOR_W, SENSOR_H, null, VfResolution.MAX)
        val started = System.nanoTime()
        val ev = VfGpuImport.estimatePreviewCorrectionEv(
            source, ROW_STRIDE, 2, geo.left, geo.top,
            geo.width, geo.height, geo.step,
            BLACK, WHITE, FloatArray(VfGpuImport.MAX_PREVIEW_SAMPLES)
        )
        val costMs = (System.nanoTime() - started) / 1_000_000.0
        println("50mp preview-ev: %.2f ms over %dx%d step %d".format(costMs, geo.width, geo.height, geo.step))
        assertEquals(1.5, ev, 1e-9)
        // Generous non-flaky bound: the 32x32 coarse grid keeps this in the
        // low milliseconds; seconds would mean the stride cap regressed.
        assertTrue("preview-ev took %.1f ms at 50 MP".format(costMs), costMs < 2000.0)
    }

    @Test fun `50mp preview-ev at cpu geometry is equally bounded`() {
        val source = uniformPlane(code = 900)
        val geo = RawPreviewGeometry.quadGeometry(SENSOR_W, SENSOR_H, null, VfResolution.CPU_MAX)
        val started = System.nanoTime()
        val ev = VfGpuImport.estimatePreviewCorrectionEv(
            source, ROW_STRIDE, 2, geo.left, geo.top,
            geo.width, geo.height, geo.step,
            BLACK, WHITE, FloatArray(VfGpuImport.MAX_PREVIEW_SAMPLES)
        )
        val costMs = (System.nanoTime() - started) / 1_000_000.0
        println("50mp cpu-geo preview-ev: %.2f ms over %dx%d step %d".format(costMs, geo.width, geo.height, geo.step))
        assertEquals(-1.5, ev, 1e-9)
        assertTrue("preview-ev took %.1f ms at 50 MP cpu-geo".format(costMs), costMs < 2000.0)
    }

    /** 100 MiB packed plane of one repeated code, filled in 1M-short chunks. */
    private fun uniformPlane(code: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(ROW_STRIDE * SENSOR_H).order(ByteOrder.nativeOrder())
        val shorts = buffer.asShortBuffer()
        val chunk = ShortArray(1 shl 20) { code.toShort() }
        var remaining = SENSOR_W * SENSOR_H
        while (remaining > 0) {
            val n = minOf(remaining, chunk.size)
            shorts.put(chunk, 0, n)
            remaining -= n
        }
        return buffer
    }
}
