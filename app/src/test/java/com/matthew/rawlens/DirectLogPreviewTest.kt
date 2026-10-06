// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Direct-Log take monitor's JVM-testable contracts: preview
 * sizing, aspect-fit viewport, and the YUV<->RGB round trip the
 * `vf_p010preview` kernel implements (limit-range BT.709 inverse of the
 * record stages' `rgbToYuv` + `pack10`).
 */
class DirectLogPreviewTest {
    @Test
    fun previewDimsQuarterEvenFloor() {
        assertArrayEquals(intArrayOf(960, 540), VfRecordPreview.previewDims(3840, 2160))
        assertArrayEquals(intArrayOf(480, 270), VfRecordPreview.previewDims(1920, 1080))
        // Odd quarter floors to even (chroma grid is half-res).
        assertArrayEquals(intArrayOf(24, 24), VfRecordPreview.previewDims(100, 100))
    }

    @Test
    fun previewDimsRejectsNonPositive() {
        try {
            VfRecordPreview.previewDims(0, 2160)
            assertTrue("width=0 must throw", false)
        } catch (_: IllegalArgumentException) {
        }
        try {
            VfRecordPreview.previewDims(3840, -1)
            assertTrue("height=-1 must throw", false)
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun previewBoxAcceptsExactEvenDownsample() {
        assertEquals(4, VfRecordPreview.previewBox(3840, 2160, 960, 540))
        assertEquals(4, VfRecordPreview.previewBox(1920, 1080, 480, 270))
    }

    @Test
    fun previewBoxRejectsSkewedBoxes() {
        // Axis mismatch.
        try {
            VfRecordPreview.previewBox(3840, 2160, 960, 270)
            assertTrue("axis-skewed box must throw", false)
        } catch (_: IllegalArgumentException) {
        }
        // Non-divisible.
        try {
            VfRecordPreview.previewBox(100, 100, 30, 30)
            assertTrue("non-divisible box must throw", false)
        } catch (_: IllegalArgumentException) {
        }
        // Odd box (chroma grid skew).
        try {
            VfRecordPreview.previewBox(3840, 2160, 1280, 720)
            assertTrue("odd box must throw", false)
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun fitViewportExactFitFillsSurface() {
        // 9:16 surface, 16:9 image rotated to portrait: exact.
        assertArrayEquals(
            intArrayOf(0, 0, 1080, 1920),
            DirectLogPreviewView.fitViewport(1080, 1920, 960, 540, 90)
        )
        // Landscape, no rotation: exact.
        assertArrayEquals(
            intArrayOf(0, 0, 1920, 1080),
            DirectLogPreviewView.fitViewport(1920, 1080, 960, 540, 0)
        )
    }

    @Test
    fun fitViewportPillarboxesNarrowImage() {
        // 3:4 surface, 9:16 displayed image: 810x1440 centered.
        assertArrayEquals(
            intArrayOf(135, 0, 810, 1440),
            DirectLogPreviewView.fitViewport(1080, 1440, 960, 540, 90)
        )
        // 270 mirrors 90.
        assertArrayEquals(
            intArrayOf(135, 0, 810, 1440),
            DirectLogPreviewView.fitViewport(1080, 1440, 960, 540, 270)
        )
    }

    @Test
    fun fitViewportLetterboxesWideImage() {
        // Portrait surface, landscape image unrotated: fit width.
        assertArrayEquals(
            intArrayOf(0, 656, 1080, 607),
            DirectLogPreviewView.fitViewport(1080, 1920, 960, 540, 0)
        )
    }

    @Test
    fun fitViewportRejectsNonPositive() {
        try {
            DirectLogPreviewView.fitViewport(0, 1920, 960, 540, 90)
            assertTrue("surface width=0 must throw", false)
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun yuvRoundTripAnchorsAreExact() {
        // Achromatic anchors round-trip bit-near-exactly (no clamping,
        // no quantization in either direction at these codes).
        for (v in floatArrayOf(0f, 0.18f, 0.5f, 1f)) {
            val rgb = floatArrayOf(v, v, v)
            val yuv = VfRecordPreview.rgbToYuv709Limited(rgb)
            val back = VfRecordPreview.yuvToRgb709(yuv[0], yuv[1], yuv[2])
            assertArrayEquals("gray=$v", rgb, back, 1e-5f)
        }
    }

    @Test
    fun yuvRoundTripRecoversPrimariesThrough10Bit() {
        // Chromatic vectors through simulated 10-bit quantization: must
        // recover within quantization noise (~0.002). A wrong matrix
        // (601 vs 709) or range errs by 0.05+, far outside this bound.
        val vectors = arrayOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f),
            floatArrayOf(1f, 1f, 0f),
            floatArrayOf(0f, 1f, 1f),
            floatArrayOf(1f, 0f, 1f),
            floatArrayOf(0.8f, 0.5f, 0.4f),
            floatArrayOf(0.2f, 0.4f, 0.6f)
        )
        for (rgb in vectors) {
            val yuv = VfRecordPreview.rgbToYuv709Limited(rgb)
            val q = FloatArray(3) { kotlin.math.round(yuv[it]).toFloat() }
            val back = VfRecordPreview.yuvToRgb709(q[0], q[1], q[2])
            assertArrayEquals("rgb=${rgb.toList()}", rgb, back, 0.005f)
        }
    }
}
