package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [Ica] (ICA.py `init_ica` / `align_lvl_ica` contracts).
 *
 * Goldens cross-checked with the venv reference python (`-B`, read-only:
 * real `init_ica` + `align_lvl_ica` on an 8x8 `arange(64)/64` ramp,
 * tile 8, `n_iter = 3`, `clip = True`, `search_radius = 2`):
 * ```
 * gx[4,4] = 0.03125            gy[4,4] = 0.25
 * gx[0,0] = 0.015625           gy[0,0] = 0.125
 * gx[7,7] = -0.96875           gy[7,7] = -0.859375
 * hessian[0,0] = [[5.259765625, 0.19140625], [0.19140625, 8.458984375]]
 * alignLvl(ref, moving=roll(ref,+1x)) -> flow (-0.11727940, 0.02013045)
 * alignLvl(ref, ref)              -> flow (0, 0)
 * alignLvl(const, const, flow(1.5, 0)) -> flow unchanged (zero residual)
 * ```
 */
class IcaTest {

    private fun ramp8(): Array<FloatArray> =
        Array(8) { y -> FloatArray(8) { x -> ((y * 8 + x) / 64f) } }

    @Test
    fun `init gradients match reference`() {
        val init = Ica.init(ramp8(), 8)
        assertEquals(0.03125f, init.gradX[4][4], 1e-6f)
        assertEquals(0.25f, init.gradY[4][4], 1e-6f)
        assertEquals(0.015625f, init.gradX[0][0], 1e-6f)
        assertEquals(0.125f, init.gradY[0][0], 1e-6f)
        assertEquals(-0.96875f, init.gradX[7][7], 1e-6f)
        assertEquals(-0.859375f, init.gradY[7][7], 1e-6f)
    }

    @Test
    fun `init hessian matches reference`() {
        val init = Ica.init(ramp8(), 8)
        assertEquals(5.259765625f, init.hessian[0][0][0][0], 1e-5f)
        assertEquals(0.19140625f, init.hessian[0][0][0][1], 1e-6f)
        assertEquals(0.19140625f, init.hessian[0][0][1][0], 1e-6f)
        assertEquals(8.458984375f, init.hessian[0][0][1][1], 1e-5f)
    }

    @Test
    fun `identical frames keep zero flow`() {
        val ref = ramp8()
        val init = Ica.init(ref, 8)
        val flow = Array(1) { Array(1) { floatArrayOf(0f, 0f) } }
        Ica.alignLvl(ref, init.gradX, init.gradY, init.hessian, ref, flow, 8, 3, true, 2)
        assertEquals(0f, flow[0][0][0], 1e-6f)
        assertEquals(0f, flow[0][0][1], 1e-6f)
    }

    @Test
    fun `shifted pair matches reference refinement`() {
        val ref = ramp8()
        val moving = Array(8) { y -> FloatArray(8) { x -> ref[y][(x + 7) % 8] } }
        val init = Ica.init(ref, 8)
        val flow = Array(1) { Array(1) { floatArrayOf(0f, 0f) } }
        Ica.alignLvl(ref, init.gradX, init.gradY, init.hessian, moving, flow, 8, 3, true, 2)
        // Envelope 1e-3: bilinear fractions run in double precision here vs
        // float32 in `cpu_ica`, so the Gauss-Newton steps differ at ~1e-7
        // per op; the argmin-free update path is otherwise identical.
        assertEquals(-0.11727940f, flow[0][0][0], 1e-3f)
        assertEquals(0.02013045f, flow[0][0][1], 1e-3f)
    }

    @Test
    fun `zero residual leaves flow untouched`() {
        val const = Array(8) { FloatArray(8) { 0.25f } }
        val init = Ica.init(const, 8)
        val flow = Array(1) { Array(1) { floatArrayOf(1.5f, 0f) } }
        Ica.alignLvl(const, init.gradX, init.gradY, init.hessian, const, flow, 8, 3, true, 2)
        assertEquals(1.5f, flow[0][0][0], 1e-6f)
        assertEquals(0f, flow[0][0][1], 1e-6f)
    }

    @Test
    fun `singular hessian skips the tile`() {
        val ref = ramp8()
        val init = Ica.init(ref, 8)
        val zeroHessian = Array(1) { Array(1) { arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f)) } }
        val flow = Array(1) { Array(1) { floatArrayOf(1.5f, -2f) } }
        Ica.alignLvl(ref, init.gradX, init.gradY, zeroHessian, ref, flow, 8, 3, true, 2)
        assertEquals(1.5f, flow[0][0][0], 1e-6f)
        assertEquals(-2f, flow[0][0][1], 1e-6f)
    }
}
