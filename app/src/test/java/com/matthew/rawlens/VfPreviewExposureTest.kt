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
        width: Int, height: Int, levels: FloatArray,
        tuning: AdaptiveExposureTuning = AdaptiveExposureTuning(),
        weights: FloatArray? = null
    ): Double = VfGpuImport.estimatePreviewCorrectionEv(
        source, rowStride, pixelStride,
        left = 0, top = 0, width = width, height = height, step = 2,
        levels = levels, white = 1023f, samples = FloatArray(VfGpuImport.MAX_PREVIEW_SAMPLES),
        weights = weights, tuning = tuning
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

    @Test fun `below-knee preview ignores the shoulder bit-exactly`() {
        // Uniform mid-gray never reaches the 0.9 knee, so the shoulder is
        // identity and both tunings must agree exactly (save-path parity for
        // ordinary content).
        val source = plane(64, 64, 128, 2) { _, _ -> 237 }
        val levels = floatArrayOf(64f, 64f, 64f, 64f)
        val shouldered = estimate(source, 128, 2, 32, 32, levels)
        val pinned = estimate(
            source, 128, 2, 32, 32, levels,
            tuning = AdaptiveExposureTuning(highlightShoulder = 0f)
        )
        assertEquals(pinned, shouldered, 0.0)
    }

    @Test fun `tap focus moves the preview vote like the save`() {
        // Same disk, vote on the corner instead of the disk: the disk loses
        // its extra votes, so the lift must rise back toward uniform — the
        // preview honors the tap point through the same tuning field.
        val source = plane(64, 64, 128, 2) { x, y ->
            if ((x - 32) * (x - 32) + (y - 32) * (y - 32) < 100) 352 else 150
        }
        val levels = floatArrayOf(64f, 64f, 64f, 64f)
        val onDisk = estimate(
            source, 128, 2, 32, 32, levels,
            tuning = AdaptiveExposureTuning(centerWeight = 3f, focusX = 0.5f, focusY = 0.5f),
            weights = FloatArray(VfGpuImport.MAX_PREVIEW_SAMPLES)
        )
        val onCorner = estimate(
            source, 128, 2, 32, 32, levels,
            tuning = AdaptiveExposureTuning(centerWeight = 3f, focusX = 0f, focusY = 0f),
            weights = FloatArray(VfGpuImport.MAX_PREVIEW_SAMPLES)
        )
        assertTrue("corner=$onCorner disk=$onDisk", onCorner > onDisk)
        assertTrue("gap too small: $onCorner vs $onDisk", onCorner - onDisk > 0.05)
    }

    @Test fun `center-weighted preview follows the subject like the save`() {
        // Dim base (0.09) with a brighter center disk (0.3, ~8% of sites so
        // it lands inside the averaging band yet below the knee, meaning the
        // shoulder cannot interfere): arming the center vote must pull the
        // lift toward the disk (lower lift) — the preview placement tracks
        // the save meter.
        val source = plane(64, 64, 128, 2) { x, y ->
            if ((x - 32) * (x - 32) + (y - 32) * (y - 32) < 100) 352 else 150
        }
        val levels = floatArrayOf(64f, 64f, 64f, 64f)
        val uniform = estimate(source, 128, 2, 32, 32, levels)
        val weighted = estimate(
            source, 128, 2, 32, 32, levels,
            tuning = AdaptiveExposureTuning(centerWeight = 3f),
            weights = FloatArray(VfGpuImport.MAX_PREVIEW_SAMPLES)
        )
        assertTrue("uniform=$uniform weighted=$weighted", weighted < uniform)
        assertTrue("gap too small: $uniform vs $weighted", uniform - weighted > 0.05)
        assertTrue("out of range: $uniform / $weighted", uniform in 0.0..1.2 && weighted in 0.0..1.2)
    }

    @Test fun `above-knee preview tail meters post-shoulder like the save`() {
        // Mids on target with a 6.25% tail at sensor white: the broad guard
        // binds, so compressing the tail (1.0 -> 0.994) must loosen the
        // preview EV exactly as the save-path meter loosens — VF == save.
        val source = plane(64, 64, 128, 2) { x, _ -> if (x < 4) 1023 else 237 }
        val levels = floatArrayOf(64f, 64f, 64f, 64f)
        val shouldered = estimate(source, 128, 2, 32, 32, levels)
        val pinned = estimate(
            source, 128, 2, 32, 32, levels,
            tuning = AdaptiveExposureTuning(highlightShoulder = 0f)
        )
        assertTrue("shouldered=$shouldered pinned=$pinned", shouldered > pinned)
        assertTrue("guard runaway: $shouldered", shouldered < 0.0)
    }
}
