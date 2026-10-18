package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Robustness.kt] (robustness.py + utils.divide contracts).
 *
 * Goldens cross-checked with numpy (venv python `-B`, replicas of
 * `cpu_compute_guide_image`, `cpu_compute_local_stats`,
 * `cpu_robustness_threshold`, `cpu_compute_local_min`):
 * ```
 * raw = arange(16).reshape(4,4)/16
 * guide[:,0,0] = (0.0, 0.39528471, 0.55901700)
 * local stats of constant 0.5 guide -> mean 0.5, var 0.0
 * d=[[0,1],[0.5,2]], sigma=1, S=2, t=0.12
 *   -> R = [[1.0, 0.61575890],[1.0, 0.15067056]]
 * localMin([[0.9,0.2,0.8],[0.7,0.1,0.6],[0.5,0.4,0.3]]) -> all 0.1
 * ```
 */
class RobustnessTest {

    @Test
    fun `guide image matches algorithm 7`() {
        val raw = Array(4) { y -> FloatArray(4) { x -> ((y * 4 + x) / 16f) } }
        val g = computeGuideImage(raw)
        assertEquals(0f, g[0][0][0], 1e-6f)
        assertEquals(0.39528471f, g[1][0][0], 1e-6f)
        assertEquals(0.55901700f, g[2][0][0], 1e-6f)
    }

    @Test
    fun `local stats of a constant guide`() {
        val guide = Array(1) { Array(4) { FloatArray(4) { 0.5f } } }
        val (means, vars) = computeLocalStats(guide)
        assertEquals(0.5f, means[0][2][2], 1e-6f)
        assertEquals(0f, vars[0][2][2], 1e-6f)
    }

    @Test
    fun `robustness threshold matches golden`() {
        val dSq = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(0.5f, 2f))
        val sigmaSq = arrayOf(floatArrayOf(1f, 1f), floatArrayOf(1f, 1f))
        val s = arrayOf(floatArrayOf(2f, 2f), floatArrayOf(2f, 2f))
        val r = robustnessThreshold(dSq, sigmaSq, s, 0.12f, 2)
        assertEquals(1f, r[0][0], 1e-5f)
        assertEquals(0.61575890f, r[0][1], 1e-5f)
        assertEquals(1f, r[1][0], 1e-5f)
        assertEquals(0.15067056f, r[1][1], 1e-5f)
    }

    @Test
    fun `threshold maps NaN to zero like numba clamp`() {
        // OOB-warped pixels carry infinite means; their d_sq shrink
        // (inf/inf) is NaN. Numba lowers float min/max to minnum/maxnum,
        // so clamp(NaN, 0, 1) = 0 (probed directly on the njit clamp);
        // Kotlin coerceIn would propagate NaN and poison the local-min
        // window. Regression test for the 1024-crop rim (1488 pixels).
        val dSq = arrayOf(floatArrayOf(Float.NaN, 1f))
        val sigmaSq = arrayOf(floatArrayOf(1f, 1f))
        val s = arrayOf(floatArrayOf(2f, 2f))
        val r = robustnessThreshold(dSq, sigmaSq, s, 0.12f, 2)
        assertEquals(0f, r[0][0], 0f)
        assertEquals(0.61575890f, r[0][1], 1e-5f)
    }

    @Test
    fun `local min over 5x5 window`() {
        val r = arrayOf(
            floatArrayOf(0.9f, 0.2f, 0.8f),
            floatArrayOf(0.7f, 0.1f, 0.6f),
            floatArrayOf(0.5f, 0.4f, 0.3f),
        )
        val m = localMin(r)
        for (row in m) for (v in row) assertEquals(0.1f, v, 1e-6f)
    }

    @Test
    fun `warp out-of-bounds yields infinity`() {
        val src = Array(1) { Array(2) { FloatArray(2) { 1f } } }
        // Guide is 2x2 with tileSize 2 (half = 1), so flow needs 2x2 tiles.
        val flow = Array(2) { Array(2) { floatArrayOf(100f, 100f) } }
        val w = warpStats(src, flow, 2)
        for (row in w[0]) for (v in row) assertTrue(v.isInfinite())
    }

    @Test
    fun `divide follows ieee semantics`() {
        val num = Array(1) { Array(1) { floatArrayOf(1f, 2f, 3f) } }
        val den = Array(1) { Array(1) { floatArrayOf(2f, 4f, 0f) } }
        divide(num, den)
        assertEquals(0.5f, num[0][0][0], 1e-6f)
        assertEquals(0.5f, num[0][0][1], 1e-6f)
        assertTrue(num[0][0][2].isInfinite())
    }
}
