package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the coarse-to-fine loop ([initAlignment]/[alignLevels]/[upscaleLvl],
 * `alignment.align` contract).
 *
 * Goldens cross-checked with the venv reference python (`-B`, read-only:
 * real `init_alignment` + `align` + `upscale_lvl` on a 32x32
 * `arange(1024)/1024` ramp, `factors=[1,2]`, `tile_sizes=[8,8]`,
 * `search_radii=[2,2]`, `metrics=["L1","L2"]`, `n_iter=3`, `clip=True`):
 * ```
 * pyramid shapes (coarse-to-fine) = [(12,12),(32,32)]
 * tiled-FFT shapes                 = [(1,1,12,7),(4,4,12,7)]
 * pyr fine [0,0]=0.0  [0,4]=0.00390625
 * pyr coarse [0,0]=0.12890625  [5,5]=0.4511719
 * hessian fine [0,0] = [[0.13864899,0.06160736],[0.06160736,0.22840500]]
 * align(ref, ref)              -> flow all (0,0)
 * align(ref, roll(ref,+2x))    -> flow (2,0) on cols 0-2, border col3
 *   x=[1.6505e-04, 0.0, -1.6e-10, -0.00629787]
 *   y=[0.05489196, 0.05468750, 0.05468750, -0.00280141]
 * upscale_lvl(1x2 [[0,0],[10,0]], [2,4], l=0) -> x=[0,5,15,20]
 * upscale_lvl(1x1 [[3,-1]], [4,4], l=0)       -> 2x2 block [6,-2], rest 0
 * ```
 * The shifted-pair golden pins the level order end to end: the coarse L2
 * level finds (1,0), the loop upscales it to (2,0), and the fine L1 level
 * keeps the residual at 0. Any other visit order changes these numbers.
 */
class AlignLoopTest {

    private val config = AlignLoopConfig(
        factors = listOf(1, 2),
        tileSizes = listOf(8, 8),
        searchRadii = listOf(2, 2),
        metrics = listOf(BlockMetric.L1, BlockMetric.L2),
    )

    private fun ramp32(): Array<FloatArray> =
        Array(32) { y -> FloatArray(32) { x -> ((y * 32 + x) / 1024f) } }

    @Test
    fun `default metrics are l1 fine then l2 coarse`() {
        val cfg = AlignLoopConfig(
            factors = listOf(1, 2),
            tileSizes = listOf(8, 8),
            searchRadii = listOf(2, 2),
        )
        assertEquals(listOf(BlockMetric.L1, BlockMetric.L2), cfg.metrics)
    }

    @Test
    fun `init alignment builds coarse-to-fine pyramids`() {
        val init = initAlignment(ramp32(), config)
        assertEquals(2, init.refPyramid.size)
        assertEquals(12, init.refPyramid[0].size)
        assertEquals(12, init.refPyramid[0][0].size)
        assertEquals(32, init.refPyramid[1].size)
        assertEquals(1, init.tiledSpectra[0].size)
        assertEquals(1, init.tiledSpectra[0][0].size)
        assertEquals(4, init.tiledSpectra[1].size)
        assertEquals(4, init.tiledSpectra[1][0].size)
        // Tiled window is tileSize + 2*radius = 12 (cf. rfft2 last dim 7).
        assertEquals(12, init.tiledSpectra[0][0][0].height)
        assertEquals(12, init.tiledSpectra[1][0][0].width)
        assertEquals(1, init.hessians[0].size)
        assertEquals(4, init.hessians[1].size)
    }

    @Test
    fun `init alignment pyramid values match reference`() {
        val init = initAlignment(ramp32(), config)
        assertEquals(0f, init.refPyramid[1][0][0], 1e-7f)
        assertEquals(0.00390625f, init.refPyramid[1][0][4], 1e-7f)
        assertEquals(0.12890625f, init.refPyramid[0][0][0], 1e-5f)
        assertEquals(0.4511719f, init.refPyramid[0][5][5], 1e-5f)
    }

    @Test
    fun `init alignment hessian matches reference`() {
        val init = initAlignment(ramp32(), config)
        val h = init.hessians[1][0][0]
        assertEquals(0.13864899f, h[0][0], 1e-5f)
        assertEquals(0.06160736f, h[0][1], 1e-6f)
        assertEquals(0.06160736f, h[1][0], 1e-6f)
        assertEquals(0.22840500f, h[1][1], 1e-5f)
    }

    @Test
    fun `identical frames keep zero flow`() {
        val ref = ramp32()
        val init = initAlignment(ref, config)
        val flow = alignLevels(init, ref, config)
        assertEquals(4, flow.size)
        for (ty in flow.indices) {
            for (tx in flow[ty].indices) {
                assertEquals(0f, flow[ty][tx][0], 1e-6f)
                assertEquals(0f, flow[ty][tx][1], 1e-6f)
            }
        }
    }

    @Test
    fun `shifted pair matches reference loop output`() {
        val ref = ramp32()
        val moving = Array(32) { y -> FloatArray(32) { x -> ref[y][(x + 30) % 32] } }
        val init = initAlignment(ref, config)
        val flow = alignLevels(init, moving, config)
        val goldX = arrayOf(
            floatArrayOf(2f, 2f, 2f, 1.6505e-04f),
            floatArrayOf(2f, 2f, 2f, 0f),
            floatArrayOf(2f, 2f, 2f, -1.6e-10f),
            floatArrayOf(2f, 2f, 2f, -0.00629787f),
        )
        val goldY = arrayOf(
            floatArrayOf(0f, 0f, 0f, 0.05489196f),
            floatArrayOf(0f, 0f, 0f, 0.05468750f),
            floatArrayOf(0f, 0f, 0f, 0.05468750f),
            floatArrayOf(0f, 0f, 0f, -0.00280141f),
        )
        for (ty in 0 until 4) {
            for (tx in 0 until 4) {
                // Interior tiles are integer-exact; the wrap column carries
                // float32 ICA drift, hence the wider envelope.
                val tol = if (tx < 3) 1e-6f else 1e-4f
                assertEquals(goldX[ty][tx], flow[ty][tx][0], tol)
                assertEquals(goldY[ty][tx], flow[ty][tx][1], tol)
            }
        }
    }

    @Test
    fun `upscale lvl bilinear matches reference`() {
        val flow = Array(1) { Array(2) { tx -> floatArrayOf(tx * 10f, 0f) } }
        val got = upscaleLvl(flow, intArrayOf(2, 4), 0, config)
        assertEquals(2, got.size)
        assertEquals(4, got[0].size)
        val gold = floatArrayOf(0f, 5f, 15f, 20f)
        for (tx in 0 until 4) {
            assertEquals(gold[tx], got[0][tx][0], 1e-5f)
            assertEquals(0f, got[0][tx][1], 1e-7f)
        }
    }

    @Test
    fun `upscale lvl zero pads shortfall`() {
        val flow = Array(1) { Array(1) { floatArrayOf(3f, -1f) } }
        val got = upscaleLvl(flow, intArrayOf(4, 4), 0, config)
        for (ty in 0 until 4) {
            for (tx in 0 until 4) {
                val inside = ty < 2 && tx < 2
                assertEquals(if (inside) 6f else 0f, got[ty][tx][0], 1e-7f)
                assertEquals(if (inside) -2f else 0f, got[ty][tx][1], 1e-7f)
            }
        }
    }

    @Test
    fun `circular pad wraps borders`() {
        val img = Array(3) { y -> FloatArray(3) { x -> (y * 3 + x).toFloat() } }
        val got = circularPad(img, 1, 1)
        assertEquals(4, got.size)
        assertEquals(4, got[0].size)
        assertEquals(img[0][0], got[3][0], 0f)
        assertEquals(img[0][0], got[0][3], 0f)
        assertEquals(img[0][0], got[3][3], 0f)
        assertEquals(img[2][2], got[2][2], 0f)
    }
}
