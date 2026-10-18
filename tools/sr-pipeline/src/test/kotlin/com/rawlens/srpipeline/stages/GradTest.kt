package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.parity.NpyReader
import com.rawlens.srpipeline.parity.maxAbsDiff
import com.rawlens.srpipeline.parity.parityFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [Grad] against the read-only numpy goldens dumped by
 * `parity/gen_fixtures.py` (`stage_grad`: kerngrad + Sobel + sqrt/gamma of
 * grey[64,64]).
 *
 * Fixtures are only read, never written.
 */
class GradTest {

    private fun load2D(name: String, h: Int, w: Int): Array<FloatArray> {
        val npy = NpyReader.load(parityFile("fixtures/$name"))
        assertEquals(h * w, npy.data.size)
        return Array(h) { y -> FloatArray(w) { x -> npy.data[y * w + x] } }
    }

    @Test
    fun `kernelGradients matches numpy golden`() {
        val grey = load2D("grad_in_grey.npy", 64, 64)
        val gold = NpyReader.load(parityFile("fixtures/grad_out_kerngrad.npy"))
        val got = Grad.kernelGradients(grey)
        assertEquals(63, got.size)
        assertEquals(63, got[0].size)
        val flat = FloatArray(63 * 63 * 2) { i ->
            got[(i / 2) / 63][(i / 2) % 63][i % 2]
        }
        assertEquals(0.0, maxAbsDiff(flat, gold.data), 1e-5)
    }

    @Test
    fun `sobel matches numpy golden`() {
        val grey = load2D("grad_in_grey.npy", 64, 64)
        val gxGold = NpyReader.load(parityFile("fixtures/grad_out_sobel_gx.npy"))
        val gyGold = NpyReader.load(parityFile("fixtures/grad_out_sobel_gy.npy"))
        val (gx, gy) = Grad.sobel(grey)
        val gxFlat = FloatArray(64 * 64) { gx[it / 64][it % 64] }
        val gyFlat = FloatArray(64 * 64) { gy[it / 64][it % 64] }
        assertEquals(0.0, maxAbsDiff(gxFlat, gxGold.data), 1e-6)
        assertEquals(0.0, maxAbsDiff(gyFlat, gyGold.data), 1e-6)
    }

    @Test
    fun `preprocess sqrt and gamma match numpy goldens`() {
        val grey = load2D("grad_in_grey.npy", 64, 64)
        val sqrtGold = NpyReader.load(parityFile("fixtures/grad_out_sqrt.npy"))
        val gammaGold = NpyReader.load(parityFile("fixtures/grad_out_gamma.npy"))
        val sqrtGot = Grad.preprocess(grey, "sqrt")
        val gammaGot = Grad.preprocess(grey, "gamma")
        val sqrtFlat = FloatArray(64 * 64) { sqrtGot[it / 64][it % 64] }
        val gammaFlat = FloatArray(64 * 64) { gammaGot[it / 64][it % 64] }
        assertEquals(0.0, maxAbsDiff(sqrtFlat, sqrtGold.data), 1e-6)
        assertEquals(0.0, maxAbsDiff(gammaFlat, gammaGold.data), 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `preprocess rejects unknown mode`() {
        Grad.preprocess(arrayOf(floatArrayOf(0.5f)), "gat")
    }
}
