package com.rawlens.srpipeline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PyramidTest {

    @Test
    fun `gaussian kernel is normalized and symmetric`() {
        val kernel = Pyramid.gaussianKernel(2)
        assertEquals(9, kernel.size)
        assertEquals(1f, kernel.sum(), 1e-6f)
        for (i in kernel.indices) {
            assertEquals(kernel[i], kernel[kernel.size - 1 - i], 1e-7f)
        }
    }

    @Test
    fun `downsample factor one is passthrough`() {
        val input = FloatArray(6 * 8) { it.toFloat() }
        val grid = Pyramid.downsample(input, 6, 8, 1)
        assertEquals(6, grid.height)
        assertEquals(8, grid.width)
        for (i in input.indices) {
            assertEquals(input[i], grid.data[i], 0f)
        }
    }

    @Test
    fun `downsample halves constant frame`() {
        val input = FloatArray(8 * 8) { 2f }
        val grid = Pyramid.downsample(input, 8, 8, 2)
        assertEquals(4, grid.height)
        assertEquals(4, grid.width)
        for (v in grid.data) {
            assertEquals(2f, v, 1e-5f)
        }
    }

    @Test
    fun `downsample uses floored dims`() {
        val input = FloatArray(9 * 7) { 1f }
        val grid = Pyramid.downsample(input, 9, 7, 2)
        assertEquals(4, grid.height)
        assertEquals(3, grid.width)
    }

    @Test
    fun `upsample of constant frame is constant`() {
        val input = FloatArray(4 * 4) { 3f }
        val grid = Pyramid.upsample(input, 4, 4, 8, 8)
        assertEquals(8, grid.height)
        assertEquals(8, grid.width)
        for (v in grid.data) {
            assertEquals(3f, v, 1e-5f)
        }
    }

    @Test
    fun `upsample matches exact bilinear values`() {
        // 2x2 [[0, 1], [2, 3]] -> 4x4: corners preserved, diagonal is linear.
        val input = floatArrayOf(0f, 1f, 2f, 3f)
        val grid = Pyramid.upsample(input, 2, 2, 4, 4)
        assertEquals(0f, grid.data[0 * 4 + 0], 1e-6f)
        assertEquals(1f, grid.data[0 * 4 + 3], 1e-6f)
        assertEquals(2f, grid.data[3 * 4 + 0], 1e-6f)
        assertEquals(3f, grid.data[3 * 4 + 3], 1e-6f)
        assertEquals(1f, grid.data[1 * 4 + 1], 1e-5f)
        assertEquals(2f, grid.data[2 * 4 + 2], 1e-5f)
    }

    @Test
    fun `downsample then upsample stays close on smooth ramp`() {
        val h = 16
        val w = 16
        val input = FloatArray(h * w) { i -> ((i / w) + (i % w)).toFloat() / (h + w) }
        val down = Pyramid.downsample(input, h, w, 2)
        val back = Pyramid.upsample(down.data, down.height, down.width, h, w)
        // Decimation samples even indices while bilinear upsampling maps the
        // coarse grid back uniformly, so the roundtrip carries a geometric
        // resampling offset of up to ~1 coarse sample on a slope; the bound
        // below covers that plus the Gaussian softening near the borders.
        var interiorMax = 0f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val err = kotlin.math.abs(input[y * w + x] - back.data[y * w + x])
                interiorMax = maxOf(interiorMax, err)
            }
        }
        assertTrue("smooth interior roundtrip error $interiorMax too large", interiorMax < 0.1f)
    }

    @Test
    fun `buildPyramid follows factors`() {
        val img = Image.zeros(8, 8, 1)
        val levels = Pyramid.buildPyramid(img, intArrayOf(1, 2, 4))
        assertEquals(3, levels.size)
        assertEquals(8 to 8, levels[0].height to levels[0].width)
        assertEquals(4 to 4, levels[1].height to levels[1].width)
        assertEquals(2 to 2, levels[2].height to levels[2].width)
    }
}
