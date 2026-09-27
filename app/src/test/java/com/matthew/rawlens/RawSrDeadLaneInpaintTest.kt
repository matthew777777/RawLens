// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

/** Dead-lane inpaint: heals unsupported lanes, never touches live ones. */
class RawSrDeadLaneInpaintTest {
    private fun planes(w: Int, h: Int, value: Float = 1f, den: Float = 1f) =
        Pair(FloatArray(w * h * 3) { value }, FloatArray(w * h * 3) { den })

    @Test fun healthyMergeIsUntouched() {
        val (rgb, den) = planes(9, 7, 0.5f, 2f)
        val before = rgb.copyOf()
        assertEquals(0, RawSrDeadLaneInpaint.inpaint(rgb, den, 9, 7))
        assertArrayEquals(before, rgb, 0f)
    }

    @Test fun isolatedDeadLaneTakesRingMean() {
        // Lane values equal x so the ring-1 mean is hand-computable.
        val w = 9
        val h = 7
        val rgb = FloatArray(w * h * 3) { o -> (o / 3 % w).toFloat() }
        val den = FloatArray(w * h * 3) { 1f }
        // Kill R at (4,3): ring-1 R neighbours are x=3 (3 taps), x=4 (2 taps), x=5 (3 taps).
        den[(3 * w + 4) * 3] = 0f
        rgb[(3 * w + 4) * 3] = 0f
        assertEquals(1, RawSrDeadLaneInpaint.inpaint(rgb, den, w, h))
        assertEquals((3f * 3 + 4f * 2 + 5f * 3) / 8f, rgb[(3 * w + 4) * 3], 0f)
        // Other lanes at the same pixel stay live and untouched.
        assertEquals(4f, rgb[(3 * w + 4) * 3 + 1], 0f)
        assertEquals(4f, rgb[(3 * w + 4) * 3 + 2], 0f)
    }

    @Test fun deadRingFallsThroughToOuterRing() {
        val w = 9
        val h = 9
        val (rgb, den) = planes(w, h, 2f, 1f)
        // Kill the G lane on the full 3x3 block around (4,4); the centre must
        // heal from ring 2 (all 2.0) rather than the dead ring 1.
        for (oy in -1..1) for (ox in -1..1) {
            val o = ((4 + oy) * w + 4 + ox) * 3 + 1
            den[o] = 0f
            rgb[o] = 0f
        }
        assertEquals(9, RawSrDeadLaneInpaint.inpaint(rgb, den, w, h))
        // Centre healed from ring 2; ring-1 cells healed from ring 1 (mixed
        // live neighbours at value 2, since fills read the pre-inpaint plane
        // and dead taps contribute nothing).
        assertEquals(2f, rgb[(4 * w + 4) * 3 + 1], 0f)
        // Unaffected far lane still live.
        assertEquals(2f, rgb[1], 0f)
    }

    @Test fun hopelessCellKeepsZero() {
        val (rgb, den) = planes(5, 5, 1f, 0f)
        rgb.fill(0f)
        // Every lane dead everywhere: nothing to heal from.
        assertEquals(0, RawSrDeadLaneInpaint.inpaint(rgb, den, 5, 5))
        assertTrue(rgb.all { it == 0f })
    }

    @Test fun epsBoundaryMatchesDivide() {
        val (rgb, den) = planes(4, 4, 3f, 1f)
        // Exactly EPS is dead (divide uses den > EPS); just above is live.
        val eps = RawSrBayerMerge.EPS.toFloat()
        den[0] = eps
        rgb[0] = 0f
        den[4 * 3] = Math.nextUp(eps)
        assertEquals(1, RawSrDeadLaneInpaint.inpaint(rgb, den, 4, 4))
        assertTrue(rgb[0] > 0f)
        assertEquals(3f, rgb[4 * 3], 0f)
    }
}
