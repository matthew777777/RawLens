package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [Align.kt] (block_matching.py contract).
 *
 * Goldens below were cross-checked with numpy
 * (`Handheld-Multi-Frame-Super-Resolution/.venv/bin/python -B`, pure-numpy
 * replica of `cpu_l1_local_search` scan order and of the clamped-window
 * SSD equivalent of `align_lvl_block_matching_L2`):
 * ```
 * ref = arange(64).reshape(8,8)/64; moving = ref shifted by (dx=1, dy=0)
 * l1 identical (R=2)              -> shift (0,0), err 0.0
 * l1 zeros    (R=2)              -> first-minimum (-2,-2), err 0.0
 * l2 clamped SSD (R=2)          -> shift (sx=1, sy=0), err 0.001953125
 * ```
 */
class AlignTest {

    private fun ramp8(): Array<FloatArray> =
        Array(8) { y -> FloatArray(8) { x -> ((y * 8 + x) / 64f) } }

    private fun flow1() = Array(1) { Array(1) { floatArrayOf(0f, 0f) } }

    @Test
    fun `l1 identical images keep zero flow`() {
        val ref = ramp8()
        val flow = flow1()
        l1LocalSearch(ref, ref, 2, flow, 8)
        assertEquals(0f, flow[0][0][0], 1e-6f)
        assertEquals(0f, flow[0][0][1], 1e-6f)
    }

    @Test
    fun `l1 all-zero images take the first minimum`() {
        val img = Array(8) { FloatArray(8) }
        val flow = flow1()
        l1LocalSearch(img, img, 2, flow, 8)
        // Every shift ties at err 0; strict `<` keeps the first in scan order.
        assertEquals(-2f, flow[0][0][0], 1e-6f)
        assertEquals(-2f, flow[0][0][1], 1e-6f)
    }

    @Test
    fun `l2 recovers a one-pixel x shift`() {
        val ref = ramp8()
        val moving = Array(8) { y -> FloatArray(8) { x -> if (x > 0) ref[y][x - 1] else 0f } }
        val flow = flow1()
        l2BlockMatch(ref, moving, flow, 8, 2)
        assertEquals(1f, flow[0][0][0], 1e-6f)
        assertEquals(0f, flow[0][0][1], 1e-6f)
    }

    @Test
    fun `round half away from zero`() {
        assertEquals(3, roundHalfAway(2.5f))
        assertEquals(-3, roundHalfAway(-2.5f))
        assertEquals(2, roundHalfAway(2.4f))
        assertEquals(-2, roundHalfAway(-2.4f))
    }

    @Test
    fun `extract flow patches clamps borders`() {
        val frame = Array(4) { y -> FloatArray(4) { x -> (y * 4 + x).toFloat() } }
        val flow = Array(1) { Array(1) { floatArrayOf(0f, 0f) } }
        val patches = extractFlowPatches(frame, flow, 2, 1)
        assertEquals(1, patches.size)
        assertEquals(4, patches[0][0].size)
        // oy=-1 clamps to row 0; oy=+2 lands on row 2.
        assertEquals(frame[0][0], patches[0][0][0][0], 1e-6f)
        assertEquals(frame[2][2], patches[0][0][3][3], 1e-6f)
    }

    @Test
    fun `align level dispatches l1 and l2`() {
        val ref = ramp8()
        val flowL1 = flow1()
        alignLvl(ref, ref, flowL1, 0, listOf(8), listOf(2), listOf(BlockMetric.L1))
        assertEquals(0f, flowL1[0][0][0], 1e-6f)
        val moving = Array(8) { y -> FloatArray(8) { x -> if (x > 0) ref[y][x - 1] else 0f } }
        val flowL2 = flow1()
        alignLvl(ref, moving, flowL2, 0, listOf(8), listOf(2), listOf(BlockMetric.L2))
        assertEquals(1f, flowL2[0][0][0], 1e-6f)
    }
}
