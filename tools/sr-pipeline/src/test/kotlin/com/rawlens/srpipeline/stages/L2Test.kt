package com.rawlens.srpipeline.stages

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [L2] (block_matching.py `align_lvl_block_matching_L2` contract).
 *
 * Goldens cross-checked with the venv reference python (`-B`, read-only:
 * real `align_lvl_block_matching_L2` on an 8x8 `arange(64)/64` ramp,
 * tile 8, `search_radius = 2`):
 * ```
 * moving = roll(ref, +1x) -> flow (1, 0)
 * moving = ref             -> flow (0, 0)
 * float64 replica err map: err[2,3] = -20.833984375 (map minimum,
 *   argmin index 13 -> dx = +1, dy = 0)
 * ```
 */
class L2Test {

    private fun ramp8(): Array<FloatArray> =
        Array(8) { y -> FloatArray(8) { x -> ((y * 8 + x) / 64f) } }

    @Test
    fun `fft path recovers a one-pixel x shift`() {
        val ref = ramp8()
        val moving = Array(8) { y -> FloatArray(8) { x -> ref[y][(x + 7) % 8] } }
        val flow = Array(1) { Array(1) { floatArrayOf(0f, 0f) } }
        L2.alignLvl(ref, moving, flow, 8, 2)
        assertEquals(1f, flow[0][0][0], 1e-6f)
        assertEquals(0f, flow[0][0][1], 1e-6f)
    }

    @Test
    fun `fft path keeps zero flow on identical frames`() {
        val ref = ramp8()
        val flow = Array(1) { Array(1) { floatArrayOf(0f, 0f) } }
        L2.alignLvl(ref, ref, flow, 8, 2)
        assertEquals(0f, flow[0][0][0], 1e-6f)
        assertEquals(0f, flow[0][0][1], 1e-6f)
    }

    @Test
    fun `correlation error map matches replica within float envelope`() {
        val ref = ramp8()
        val moving = Array(8) { y -> FloatArray(8) { x -> ref[y][(x + 7) % 8] } }
        val patches = extractFlowPatches(moving, Array(1) { Array(1) { floatArrayOf(0f, 0f) } }, 8, 2)
        val scores = L2.correlationErrors(ref, patches[0][0], 8, 2)
        // Envelope 1e-2 on scores: double-precision JTransforms FFT plus
        // sequential box sums vs torch float32 rfft2/conv2d change the
        // summation order; the argmin (first minimum) contract is exact.
        assertEquals(-20.833984375f, scores[2][3], 1e-2f)
        assertEquals(5, scores.size)
        assertEquals(5, scores[0].size)
    }

    @Test
    fun `all-zero images take the first minimum`() {
        val img = Array(8) { FloatArray(8) }
        val flow = Array(1) { Array(1) { floatArrayOf(0f, 0f) } }
        L2.alignLvl(img, img, flow, 8, 2)
        assertEquals(-2f, flow[0][0][0], 1e-6f)
        assertEquals(-2f, flow[0][0][1], 1e-6f)
    }

    @Test
    fun `fractional flow is preserved`() {
        val ref = ramp8()
        val flow = Array(1) { Array(1) { floatArrayOf(0.25f, -0.5f) } }
        L2.alignLvl(ref, ref, flow, 8, 2)
        // Identical frames: winning shift (0,0) is added, fraction kept.
        assertEquals(0.25f, flow[0][0][0], 1e-6f)
        assertEquals(-0.5f, flow[0][0][1], 1e-6f)
    }
}
