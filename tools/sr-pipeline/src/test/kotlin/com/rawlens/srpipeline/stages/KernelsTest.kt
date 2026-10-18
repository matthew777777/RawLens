package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [Kernels.kt] (kernels.py / ICA.py / linalg.py contracts).
 *
 * Goldens cross-checked with numpy (venv python `-B`):
 * ```
 * sobel of arange(25).reshape(5,5): gx[2,2] = 2.0, gy[2,2] = 10.0
 * hessian of ones/zeros 4x4, tile 2: h[0,0] = [[4,0],[0,0]]
 * eigen([[4,2],[2,3]]): l = (5.56155281, 1.43844719),
 *   e1 = (0.78820544, 0.61541221)
 * computeK(l1=4, l2=1, linear):  k = (0.61270167, 3.32379001)
 * computeK(l1=4, l2=1, hard):    k = (1.0, 1.0)
 * constant gx=1/gy=0 grads -> centre cov = [[0.25, 0],[0, 16]]
 * kerngrad of 4x4 ramp arange(16)/16 -> [0.03125, 0.125] everywhere
 * computeS 5x5 centre spike (Mt=0.8): 3x3 core s1=2, border s2=12
 * decimateMean of 4x4 ramp: out[0,0] = 0.15625
 * ```
 */
class KernelsTest {

    @Test
    fun `sobel gradients match golden`() {
        val img = Array(5) { y -> FloatArray(5) { x -> (y * 5 + x).toFloat() } }
        val (gx, gy) = sobelGradients(img)
        assertEquals(2f, gx[2][2], 1e-5f)
        assertEquals(10f, gy[2][2], 1e-5f)
    }

    @Test
    fun `hessian sums per tile`() {
        val gx = Array(4) { FloatArray(4) { 1f } }
        val gy = Array(4) { FloatArray(4) { 0f } }
        val h = computeHessian(gx, gy, 2)
        assertEquals(4f, h[0][0][0][0], 1e-6f)
        assertEquals(0f, h[0][0][0][1], 1e-6f)
        assertEquals(0f, h[0][0][1][1], 1e-6f)
    }

    @Test
    fun `eigen decomposition matches numpy`() {
        val e = eigen2x2(4f, 2f, 3f)
        assertEquals(5.56155281f, e.l1, 1e-5f)
        assertEquals(1.43844719f, e.l2, 1e-5f)
        assertEquals(0.78820544f, e.e1[0], 1e-5f)
        assertEquals(0.61541221f, e.e1[1], 1e-5f)
        // M*e1 == l1*e1 residual.
        assertEquals(e.l1 * e.e1[0], 4f * e.e1[0] + 2f * e.e1[1], 1e-5f)
        assertEquals(e.l1 * e.e1[1], 2f * e.e1[0] + 3f * e.e1[1], 1e-5f)
    }

    @Test
    fun `compute k linear and hard branches`() {
        val lin = computeK(4f, 1f, KernelParams(selection = SelectionLaw.LINEAR))
        assertEquals(0.61270167f, lin.first, 1e-5f)
        assertEquals(3.32379001f, lin.second, 1e-5f)
        val hard = computeK(4f, 1f, KernelParams(selection = SelectionLaw.HARD_THRESHOLD))
        assertEquals(1f, hard.first, 1e-6f)
        assertEquals(1f, hard.second, 1e-6f)
    }

    @Test
    fun `kernel covariances of uniform gradients`() {
        val grads = Array(3) { Array(3) { floatArrayOf(1f, 0f) } }
        val covs = estimateKernelCovariances(grads, KernelParams(selection = SelectionLaw.LINEAR))
        assertEquals(0.25f, covs[1][1][0][0], 1e-5f)
        assertEquals(0f, covs[1][1][0][1], 1e-5f)
        assertEquals(16f, covs[1][1][1][1], 1e-4f)
    }

    @Test
    fun `kern gradients of a ramp`() {
        val grey = Array(4) { y -> FloatArray(4) { x -> ((y * 4 + x) / 16f) } }
        val g = kernGradients(grey)
        assertEquals(0.03125f, g[1][1][0], 1e-6f)
        assertEquals(0.125f, g[1][1][1], 1e-6f)
    }

    @Test
    fun `ica skips singular tiles`() {
        val ref = Array(8) { FloatArray(8) { 1f } }
        val moving = Array(8) { FloatArray(8) { 0f } }
        val gx = Array(8) { FloatArray(8) }
        val hess = Array(1) { Array(1) { arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f)) } }
        val flow = Array(1) { Array(1) { floatArrayOf(0.5f, -0.25f) } }
        icaRefine(ref, gx, gx, hess, moving, flow, tileSize = 8)
        assertEquals(0.5f, flow[0][0][0], 1e-7f)
        assertEquals(-0.25f, flow[0][0][1], 1e-7f)
    }

    @Test
    fun `flow spread map marks the spike neighbourhood`() {
        val flow = Array(5) { Array(5) { floatArrayOf(0f, 0f) } }
        flow[2][2][0] = 5f
        val s = computeS(flow, 0.8f, 2f, 12f)
        assertEquals(2f, s[2][2], 1e-6f)
        assertEquals(2f, s[1][1], 1e-6f)
        assertEquals(12f, s[0][0], 1e-6f)
        assertEquals(12f, s[0][2], 1e-6f)
    }

    @Test
    fun `decimate mean and upscale flow`() {
        val img = Array(4) { y -> FloatArray(4) { x -> ((y * 4 + x) / 16f) } }
        val d = decimateMean(img)
        assertEquals(0.15625f, d[0][0], 1e-6f)
        val flow = Array(1) { Array(1) { floatArrayOf(1f, 2f) } }
        val up = upscaleFlow(flow, 1, 2f)
        assertEquals(2f, up[0][0][0], 1e-6f)
        assertEquals(4f, up[0][0][1], 1e-6f)
    }
}
