package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.parity.NpyReader
import com.rawlens.srpipeline.parity.maxAbsDiff
import com.rawlens.srpipeline.parity.parityFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [Resample] against the read-only numpy goldens dumped by
 * `parity/gen_fixtures.py` (`stage_resample`: gaussian down2 + pyramid
 * `[1,2,2]` + bilinear flow upscale of grey[64,64]).
 *
 * Fixtures are only read, never written.
 */
class ResampleTest {

    private fun load2D(name: String, h: Int, w: Int): Array<FloatArray> {
        val npy = NpyReader.load(parityFile("fixtures/$name"))
        assertEquals(h * w, npy.data.size)
        return Array(h) { y -> FloatArray(w) { x -> npy.data[y * w + x] } }
    }

    private fun flat2D(a: Array<FloatArray>): FloatArray {
        val h = a.size
        val w = a[0].size
        return FloatArray(h * w) { a[it / w][it % w] }
    }

    @Test
    fun `gaussian kernel is normalised with factor-2 radius`() {
        // sigma = factor * 0.5 = 1, radius = int(4 * factor * 0.5 + 0.5) = 4.
        val kernel = Resample.gaussianKernel1d(1.0, 4)
        assertEquals(9, kernel.size)
        assertEquals(1.0f, kernel.sum(), 1e-6f)
        assertArrayEquals(kernel, kernel.reversed().toFloatArray(), 1e-7f)
    }

    @Test
    fun `downsample factor 2 matches numpy golden`() {
        val grey = load2D("resample_in_grey.npy", 64, 64)
        val gold = NpyReader.load(parityFile("fixtures/resample_out_down2.npy"))
        val got = Resample.downsample(grey, 2)
        assertEquals(28, got.size)
        assertEquals(28, got[0].size)
        assertEquals(0.0, maxAbsDiff(flat2D(got), gold.data), 1e-5)
    }

    @Test
    fun `downsample factor 1 is a passthrough copy`() {
        val grey = load2D("resample_in_grey.npy", 64, 64)
        val got = Resample.downsample(grey, 1)
        assertEquals(0.0, maxAbsDiff(flat2D(got), flat2D(grey)), 0.0)
        val original = grey[0][0]
        got[0][0] = original + 1f
        assertEquals(original, grey[0][0], 0.0f)
    }

    @Test
    fun `pyramid matches numpy goldens coarse to fine`() {
        val grey = load2D("resample_in_grey.npy", 64, 64)
        val pyr = Resample.buildGaussianPyramid(grey, listOf(1, 2, 2))
        assertEquals(3, pyr.size)
        val goldNames = listOf("resample_out_pyr0.npy", "resample_out_pyr1.npy", "resample_out_pyr2.npy")
        for ((lvl, entry) in pyr.withIndex()) {
            val gold = NpyReader.load(parityFile("fixtures/${goldNames[lvl]}"))
            assertEquals(gold.data.size, flat2D(entry).size)
            assertEquals(0.0, maxAbsDiff(flat2D(entry), gold.data), 1e-5)
        }
    }

    @Test
    fun `upscaleLvl of zero flow matches numpy golden`() {
        val flow = Array(1) { Array(1) { floatArrayOf(0f, 0f) } }
        val gold = NpyReader.load(parityFile("fixtures/resample_out_upscaled_flow.npy"))
        val got = Resample.upscaleLvl(flow, intArrayOf(2, 2), 16, 16, 2)
        assertEquals(2, got.size)
        assertEquals(2, got[0].size)
        val flat = FloatArray(2 * 2 * 2) { i -> got[(i / 2) / 2][(i / 2) % 2][i % 2] }
        assertEquals(0.0, maxAbsDiff(flat, gold.data), 0.0)
    }

    @Test
    fun `upscaleLvl bilinear pins align-corners-false mapping`() {
        // 1x2 flow, repeat 2, no magnitude scaling: src = (dst + .5)/2 - .5
        // gives [0, 2.5, 7.5, 10] with clamped border taps.
        // repeat = up / (newTile / prevTile) = 2; src = (dst + .5)/2 - .5
        // gives [0, 2.5, 7.5, 10] with clamped border taps, then *= 2.
        val flow = Array(1) { Array(2) { tx -> floatArrayOf(tx * 10f, 0f) } }
        val got = Resample.upscaleLvl(flow, intArrayOf(1, 4), 8, 8, 2)
        assertArrayEquals(floatArrayOf(0f, 5f, 15f, 20f), FloatArray(4) { got[0][it][0] }, 1e-5f)
    }
}
