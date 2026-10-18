package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.parity.NpyReader
import com.rawlens.srpipeline.parity.parityFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [EndToEnd] pipeline wiring (`super_resolution.main` order).
 *
 * The full numpy golden (`endtoend_in_burst[3,256,256]` ->
 * `endtoend_out_image[512,512,3]`) is gated by the parity harness against
 * the `endtoend` envelope; these tests pin the wiring instead: the golden
 * pair is loaded read-only to assert the targeted contract shapes, the
 * real stages run on a tiny burst with an injected zero-flow alignment as
 * well as with the default coarse-to-fine loop, and the loop config is
 * shown to follow the Python-pattern defaults.
 *
 * Fixtures are only read, never written.
 */
class EndToEndTest {

    private fun tinyConfig() = PipelineConfig(
        scale = 1f,
        bayer = true,
        greyMethod = "FFT",
        factors = listOf(1, 2),
        tileSizes = listOf(8, 8),
    )

    private fun frame(seed: Int): Array<FloatArray> =
        Array(32) { y -> FloatArray(32) { x -> (((x + 3 * y + seed) % 11) / 11f * 0.8f + 0.1f) } }

    private fun zeroAlign(): (Array<FloatArray>) -> Array<Array<FloatArray>> = {
        Array(4) { Array(4) { floatArrayOf(0f, 0f) } }
    }

    @Test
    fun `golden burst and image shapes pin the targeted contract`() {
        val burst = NpyReader.load(parityFile("fixtures/endtoend_in_burst.npy"))
        val image = NpyReader.load(parityFile("fixtures/endtoend_out_image.npy"))
        assertEquals(listOf(3, 256, 256), burst.shape.toList())
        assertEquals(listOf(512, 512, 3), image.shape.toList())
    }

    @Test
    fun `run wires real stages on a tiny burst`() {
        val out = EndToEnd.run(frame(0), listOf({ frame(1) }, { frame(2) }), tinyConfig(), zeroAlign())
        assertEquals(32, out.size)
        assertEquals(32, out[0].size)
        assertEquals(3, out[0][0].size)
        for (row in out) {
            for (px in row) {
                for (ch in px) assertTrue("output must be finite, got $ch", ch.isFinite())
            }
        }
        // Deterministic: same burst gives the same image.
        val again = EndToEnd.run(frame(0), listOf({ frame(1) }, { frame(2) }), tinyConfig(), zeroAlign())
        for (y in out.indices) {
            for (x in out[y].indices) {
                for (ch in 0..2) assertEquals(out[y][x][ch], again[y][x][ch], 0.0f)
            }
        }
    }

    @Test
    fun `run works reference-only`() {
        val out = EndToEnd.run(frame(0), emptyList(), tinyConfig(), zeroAlign())
        assertEquals(32, out.size)
        assertEquals(3, out[0][0].size)
    }

    @Test
    fun `frameKernels emits grey-granularity covariances`() {
        // 32px bayer -> 16x16 decimating grey -> covs[16,16,2,2] like
        // `estimate_kernels` (kernels_out_covs is [32,32,2,2] for 64px raw).
        val covs = EndToEnd.frameKernels(frame(0), tinyConfig())
        assertEquals(16, covs.size)
        assertEquals(16, covs[0].size)
        assertEquals(2, covs[0][0].size)
    }

    @Test
    fun `default alignment runs the coarse-to-fine loop`() {
        val out = EndToEnd.run(frame(0), listOf({ frame(1) }), tinyConfig())
        assertEquals(32, out.size)
        assertEquals(32, out[0].size)
        assertEquals(3, out[0][0].size)
        for (row in out) {
            for (px in row) {
                for (ch in px) assertTrue("output must be finite, got $ch", ch.isFinite())
            }
        }
    }

    @Test
    fun `loop config follows the python-pattern defaults`() {
        val loop = EndToEnd.loopConfig(tinyConfig())
        assertEquals(listOf(1, 2), loop.factors)
        assertEquals(listOf(8, 8), loop.tileSizes)
        assertEquals(listOf(1, 4), loop.searchRadii)
        assertEquals(3, loop.nIter)
    }
}
