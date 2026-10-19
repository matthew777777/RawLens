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
    fun `downsampleInto is bit-identical to downsample`() {
        // Odd, non-square dims exercise the kernel-edge truncation
        // paths (h-k+1 / w-k+1 not divisible by the factor).
        val grey = load2D("resample_in_grey.npy", 64, 64)
        val tall = Array(61) { y -> FloatArray(45) { x -> grey[(y * 64 / 61) % 64][(x * 64 / 45) % 64] } }
        for ((img, factor) in listOf(grey to 2, tall to 2, tall to 4, grey to 1)) {
            val fresh = Resample.downsample(img, factor)
            val out = Array(fresh.size) { FloatArray(fresh[0].size) { -99f } }
            Resample.downsampleInto(img, factor, out)
            assertEquals(0.0, maxAbsDiff(flat2D(out), flat2D(fresh)), 0.0)
        }
    }

    @Test
    fun `downsampleInto folded pads match pad-then-blur`() {
        // The pad-fused reads must equal materializing circularPad
        // first (this is what lets the loop drop the 50 MB pad array).
        val grey = load2D("resample_in_grey.npy", 64, 64)
        val tall = Array(61) { y -> FloatArray(45) { x -> grey[(y * 64 / 61) % 64][(x * 64 / 45) % 64] } }
        data class Case(val img: Array<FloatArray>, val factor: Int, val padH: Int, val padW: Int)
        for ((img, factor, padH, padW) in listOf(
            Case(grey, 2, 3, 5), Case(tall, 2, 3, 3),
            Case(tall, 4, 5, 7), Case(tall, 1, 3, 3),
        )) {
            val fresh = Resample.downsample(circularPad(img, padH, padW), factor)
            val out = Array(fresh.size) { FloatArray(fresh[0].size) { Float.NaN } }
            Resample.downsampleInto(img, factor, out, padH, padW)
            assertEquals(fresh.size, out.size)
            assertEquals(fresh[0].size, out[0].size)
            assertEquals(0.0, maxAbsDiff(flat2D(out), flat2D(fresh)), 0.0)
        }
    }

    @Test
    fun `downsampleInto reuse survives hostile overwrite`() {
        val grey = load2D("resample_in_grey.npy", 64, 64)
        val fresh = Resample.downsample(grey, 2)
        val out = Array(fresh.size) { FloatArray(fresh[0].size) }
        // Poison the holder, then rebuild twice: both must be exact
        // (no stale element survives the full overwrite; the blur
        // streams with only a row transient, so there is no scratch
        // to go stale).
        for (round in 0 until 2) {
            for (row in out) row.fill(Float.NaN)
            Resample.downsampleInto(grey, 2, out)
            assertEquals(0.0, maxAbsDiff(flat2D(out), flat2D(fresh)), 0.0)
        }
    }

    @Test
    fun `pyramidInto matches fresh pyramid and survives reuse`() {
        val grey = load2D("resample_in_grey.npy", 64, 64)
        val factors = listOf(1, 2, 2)
        val fresh = Resample.buildGaussianPyramid(grey, factors, null)
        val holder = fresh.map { lvl ->
            Array(lvl.size) { FloatArray(lvl[0].size) { Float.NaN } }
        }.toMutableList()
        val other = load2D("resample_in_grey.npy", 64, 64)
            .map { row -> row.map { it * 0.5f + 3f }.toFloatArray() }.toTypedArray()
        for (img in listOf(other, grey, other)) {
            Resample.buildGaussianPyramidInto(img, 0, 0, factors, null, holder)
            val expect = Resample.buildGaussianPyramid(img, factors, null)
            for ((got, want) in holder.zip(expect)) {
                assertEquals(want.size, got.size)
                assertEquals(want[0].size, got[0].size)
                assertEquals(0.0, maxAbsDiff(flat2D(got), flat2D(want)), 0.0)
            }
        }
    }

    @Test
    fun `pyramidInto folded pads match fresh pyramid from pad`() {
        // Padded Into must equal the fresh pad-then-build path at
        // every level (the loop's production shape).
        val grey = load2D("resample_in_grey.npy", 64, 64)
        val tall = Array(61) { y -> FloatArray(45) { x -> grey[(y * 64 / 61) % 64][(x * 64 / 45) % 64] } }
        val factors = listOf(1, 2, 2)
        val padH = 3
        val padW = 3
        val shapes = Resample.pyramidShapes(tall.size + padH, tall[0].size + padW, factors)
        val holder = shapes.map { (lh, lw) ->
            Array(lh) { FloatArray(lw) { Float.NaN } }
        }.toMutableList()
        Resample.buildGaussianPyramidInto(tall, padH, padW, factors, null, holder)
        val expect = Resample.buildGaussianPyramid(circularPad(tall, padH, padW), factors, null)
        assertEquals(expect.size, holder.size)
        for ((got, want) in holder.zip(expect)) {
            assertEquals(want.size, got.size)
            assertEquals(want[0].size, got[0].size)
            assertEquals(0.0, maxAbsDiff(flat2D(got), flat2D(want)), 0.0)
        }
    }

    @Test
    fun `circularPadInto is bit-identical to circularPad`() {
        val grey = load2D("resample_in_grey.npy", 64, 64)
        for ((pb, pr) in listOf(0 to 0, 3 to 5, 17 to 1)) {
            val fresh = circularPad(grey, pb, pr)
            val out = Array(fresh.size) { FloatArray(fresh[0].size) { Float.NaN } }
            circularPadInto(grey, pb, pr, out)
            assertEquals(0.0, maxAbsDiff(flat2D(out), flat2D(fresh)), 0.0)
        }
    }

    @Test
    fun `pyramidReuse matches fresh builds across frames`() {
        // Odd-sized images with the production tileSize pad formula
        // (cf. AlignGpu.alignLevelsGpu): pads are non-degenerate here.
        val grey = load2D("resample_in_grey.npy", 64, 64)
        val tall = Array(61) { y -> FloatArray(45) { x -> grey[(y * 64 / 61) % 64][(x * 64 / 45) % 64] } }
        val other = tall.map { row -> row.map { it * 0.5f + 3f }.toFloatArray() }.toTypedArray()
        val factors = listOf(1, 2, 2)
        val tileSize = 16
        val reuse = AlignGpu.PyramidReuse()
        for (img in listOf(other, tall, other)) {
            val h = img.size
            val w = img[0].size
            val padH = if (h % tileSize == 0) 0 else tileSize - h % tileSize
            val padW = if (w % tileSize == 0) 0 else tileSize - w % tileSize
            val g2 = Resample.buildGaussianPyramid(circularPad(img, padH, padW), factors)
            val got = reuse.moving(img, padH, padW, factors)
            assertEquals(g2.size, got.size)
            for ((a, b) in got.zip(g2)) {
                assertEquals(b.size, a.size)
                assertEquals(0.0, maxAbsDiff(flat2D(a), flat2D(b)), 0.0)
            }
        }
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
