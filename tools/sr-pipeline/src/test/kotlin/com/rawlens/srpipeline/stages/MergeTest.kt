package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [Merge.kt] (merge.py Algorithm 4 contract).
 *
 * Goldens cross-checked with numpy (venv python `-B`, replica of
 * `cpu_accumulate` in grey mode, scale 1, identity flow/covariances,
 * `r = 1`, `comp = arange(4).reshape(2,2)/4`):
 * ```
 * num[0,0] = 0.73080760, den[0,0] = 2.58094072
 * num[1,1] = 1.20489800, den[1,1] = 2.58094072
 * ```
 */
class MergeTest {

    private fun identityCovs(h: Int, w: Int): Array<Array<Array<FloatArray>>> =
        Array(h) { Array(w) { arrayOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)) } }

    @Test
    fun `accumulate matches numpy golden`() {
        val comp = Array(2) { y -> FloatArray(2) { x -> ((y * 2 + x) / 4f) } }
        val flow = Array(1) { Array(1) { floatArrayOf(0f, 0f) } }
        val covs = identityCovs(2, 2)
        val r = Array(2) { FloatArray(2) { 1f } }
        val num = Array(2) { Array(2) { FloatArray(1) } }
        val den = Array(2) { Array(2) { FloatArray(1) } }
        accumulate(comp, flow, covs, r, false, 1f, 8, num, den)
        assertEquals(0.73080760f, num[0][0][0], 1e-5f)
        assertEquals(2.58094072f, den[0][0][0], 1e-5f)
        assertEquals(1.20489800f, num[1][1][0], 1e-5f)
        assertEquals(2.58094072f, den[1][1][0], 1e-5f)
    }

    @Test
    fun `merge forwards to accumulate`() {
        val comp = Array(2) { y -> FloatArray(2) { x -> ((y * 2 + x) / 4f) } }
        val flow = Array(1) { Array(1) { floatArrayOf(0f, 0f) } }
        val covs = identityCovs(2, 2)
        val r = Array(2) { FloatArray(2) { 1f } }
        val numA = Array(2) { Array(2) { FloatArray(1) } }
        val denA = Array(2) { Array(2) { FloatArray(1) } }
        val numB = Array(2) { Array(2) { FloatArray(1) } }
        val denB = Array(2) { Array(2) { FloatArray(1) } }
        accumulate(comp, flow, covs, r, false, 1f, 8, numA, denA)
        merge(comp, flow, covs, r, numB, denB, false, 1f, 8)
        assertEquals(numA[1][0][0], numB[1][0][0], 1e-7f)
        assertEquals(denA[1][0][0], denB[1][0][0], 1e-7f)
    }
}
