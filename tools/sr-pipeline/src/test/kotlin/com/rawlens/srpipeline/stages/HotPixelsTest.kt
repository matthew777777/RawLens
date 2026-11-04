package com.rawlens.srpipeline.stages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HotPixelsTest {
    private fun flat(v: Float = 0.2f) = Array(16) { FloatArray(16) { v } }

    @Test fun hotAndDeadAreCandidates() {
        val r = flat()
        r[6][6] = 0.9f      // hot
        r[9][8] = 0.01f     // dead
        val c = HotPixels.candidates(r)
        assertArrayEquals(intArrayOf(6 * 16 + 6, 9 * 16 + 8), c)
    }

    @Test fun darkFlatAndTexturedPixelsAreNot() {
        val dark = flat(0.01f)
        dark[6][6] = 0.03f   // 3x neighbours but below the absolute threshold
        assertEquals(0, HotPixels.candidates(dark).size)
        val ramp = Array(16) { y -> FloatArray(16) { x -> 0.05f * x + 0.02f * y } }
        assertEquals(0, HotPixels.candidates(ramp).size)
    }

    @Test fun movingPointLightIsRejectedFixedDefectIsKept() {
        val ref = flat(); ref[6][6] = 0.9f; ref[10][10] = 0.9f
        val c = HotPixels.candidates(ref)
        // frame 2: the defect at (6,6) stays; the "point light" at (10,10) moved to (10,12)
        val f2 = flat(); f2[6][6] = 0.9f; f2[10][12] = 0.9f
        val kept = HotPixels.confirm(c, 16, 16, listOf({ y: Int, x: Int -> f2[y][x] }))
        assertArrayEquals(intArrayOf(6 * 16 + 6), kept)
    }

    @Test fun correctUsesSameColourMedian() {
        val r = flat(0.2f)
        r[4][4] = 0.1f; r[8][8] = 0.3f // two of the 8 neighbours of (6,6) differ
        r[6][6] = 0.9f
        HotPixels.correct(r, intArrayOf(6 * 16 + 6))
        assertEquals(0.2f, r[6][6], 0f)
        assertEquals(0.2f, r[6][7], 0f) // other colour untouched
    }

    /** Regression (night burst 194618): a small clipped lamp core is not a defect. */
    @Test fun lampCoreIsNotADefect() {
        val r = flat(0.1f)
        for (y in 6..8) for (x in 6..8) r[y][x] = 1f   // 3x3 clipped core, other colours lit too
        assertEquals(0, HotPixels.candidates(r).size)
        val single = flat(0.1f); single[7][7] = 1f      // isolated pixel still caught
        assertArrayEquals(intArrayOf(7 * 16 + 7), HotPixels.candidates(single))
    }
}
