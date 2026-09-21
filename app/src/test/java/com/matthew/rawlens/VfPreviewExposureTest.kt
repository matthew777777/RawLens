// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VfPreviewExposureTest {
    private fun plane(width: Int, height: Int, rowStride: Int, pixelStride: Int, codeAt: (x: Int, y: Int) -> Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(rowStride * height).order(ByteOrder.nativeOrder())
        for (y in 0 until height) for (x in 0 until width) {
            buffer.putShort(y * rowStride + x * pixelStride, codeAt(x, y).toShort())
        }
        return buffer
    }

    private fun estimate(
        source: ByteBuffer, rowStride: Int, pixelStride: Int,
        width: Int, height: Int, levels: FloatArray
    ): Double = VfGpuImport.estimatePreviewCorrectionEv(
        source, rowStride, pixelStride,
        left = 0, top = 0, width = width, height = height, step = 2,
        levels = levels, white = 1023f, samples = FloatArray(VfGpuImport.MAX_PREVIEW_SAMPLES)
    )

    @Test fun `mid-gray plane needs no correction`() {
        val source = plane(64, 64, 128, 2) { _, _ -> 237 }
        val ev = estimate(source, 128, 2, 32, 32, floatArrayOf(64f, 64f, 64f, 64f))
        assertEquals(0.0, ev, 0.02)
    }

    @Test fun `dark and bright planes clamp to the save-path bounds`() {
        val dark = plane(64, 64, 128, 2) { _, _ -> 100 }
        assertEquals(1.5, estimate(dark, 128, 2, 32, 32, floatArrayOf(64f, 64f, 64f, 64f)), 1e-9)
        val bright = plane(64, 64, 128, 2) { _, _ -> 900 }
        assertEquals(-1.5, estimate(bright, 128, 2, 32, 32, floatArrayOf(64f, 64f, 64f, 64f)), 1e-9)
    }

    @Test fun `each quad site uses its own black level`() {
        // Site codes chosen so the four normalized levels differ; any black
        // misindexing moves the EV away from the hand-computed -0.503.
        val codes = intArrayOf(237, 337, 437, 301)
        val source = plane(8, 8, 16, 2) { x, y -> codes[(y % 2) * 2 + x % 2] }
        val ev = estimate(source, 16, 2, 4, 4, floatArrayOf(0f, 100f, 200f, 64f))
        assertEquals(-0.503, ev, 0.02)
    }

    @Test fun `padded strides sample the same sites`() {
        val codes = intArrayOf(237, 337, 437, 301)
        val source = plane(8, 8, 32, 4) { x, y -> codes[(y % 2) * 2 + x % 2] }
        val ev = estimate(source, 32, 4, 4, 4, floatArrayOf(0f, 100f, 200f, 64f))
        assertEquals(-0.503, ev, 0.02)
    }

    @Test fun `short plane yields NaN instead of over-reading`() {
        val source = plane(8, 4, 16, 2) { _, _ -> 237 }
        val ev = estimate(source, 16, 2, 4, 4, floatArrayOf(64f, 64f, 64f, 64f))
        assertTrue(ev.isNaN())
    }

    @Test fun `fewer than 64 samples yield zero correction`() {
        val source = plane(4, 4, 8, 2) { _, _ -> 100 }
        val ev = estimate(source, 8, 2, 2, 2, floatArrayOf(64f, 64f, 64f, 64f))
        assertEquals(0.0, ev, 0.0)
    }
}
