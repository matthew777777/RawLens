package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.parity.NpyReader
import com.rawlens.srpipeline.parity.maxAbsDiff
import com.rawlens.srpipeline.parity.parityFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Hessian] against the read-only numpy goldens dumped by
 * `parity/gen_fixtures.py` (`stage_hessian`: `init_ica(grey[64,64], T16)`).
 *
 * Fixtures are only read, never written.
 */
class HessianTest {

    private fun load2D(name: String, h: Int, w: Int): Array<FloatArray> {
        val npy = NpyReader.load(parityFile("fixtures/$name"))
        assertEquals(h * w, npy.data.size)
        return Array(h) { y -> FloatArray(w) { x -> npy.data[y * w + x] } }
    }

    @Test
    fun `initIca matches numpy golden grads and hessians`() {
        val grey = load2D("hessian_in_grey.npy", 64, 64)
        val result = Hessian.initIca(grey, 16)

        val gxGold = NpyReader.load(parityFile("fixtures/hessian_out_gradx.npy"))
        val gyGold = NpyReader.load(parityFile("fixtures/hessian_out_grady.npy"))
        val hGold = NpyReader.load(parityFile("fixtures/hessian_out_hessian.npy"))

        val gxFlat = FloatArray(64 * 64) { result.gradX[it / 64][it % 64] }
        val gyFlat = FloatArray(64 * 64) { result.gradY[it / 64][it % 64] }
        assertEquals(0.0, maxAbsDiff(gxFlat, gxGold.data), 1e-6)
        assertEquals(0.0, maxAbsDiff(gyFlat, gyGold.data), 1e-6)

        assertEquals(4, result.hessian.size)
        assertEquals(4, result.hessian[0].size)
        // C-order [ty,tx,a,b]: i = ((ty * 4 + tx) * 2 + a) * 2 + b.
        val hFlat = FloatArray(4 * 4 * 2 * 2) { i ->
            val b = i % 2
            val a = (i / 2) % 2
            val t = i / 4
            val tx = t % 4
            val ty = t / 4
            result.hessian[ty][tx][a][b]
        }
        assertEquals(0.0, maxAbsDiff(hFlat, hGold.data), 1e-4)
        for (ty in 0 until 4) {
            for (tx in 0 until 4) {
                assertTrue(
                    "hessian must be symmetric at [$ty][$tx]",
                    result.hessian[ty][tx][0][1] == result.hessian[ty][tx][1][0],
                )
            }
        }
    }

    @Test
    fun `initIca output shapes follow tile grid`() {
        val grey = Array(32) { y -> FloatArray(32) { x -> ((x + y) % 7) / 7f } }
        val result = Hessian.initIca(grey, 8)
        assertEquals(32, result.gradX.size)
        assertEquals(32, result.gradX[0].size)
        assertEquals(4, result.hessian.size)
        assertEquals(4, result.hessian[0].size)
    }
}
