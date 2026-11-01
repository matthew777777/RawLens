// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedVideoStabilizerTest {
    @Test fun `pts maps to the cfr grid index`() {
        // 30fps grid: frame i at i * 1e6 / 30 us (33333/33334 alternating).
        assertEquals(0, EncodedVideoStabilizer.frameIndexForPts(0L, 30, 90))
        assertEquals(0, EncodedVideoStabilizer.frameIndexForPts(10_000L, 30, 90))
        assertEquals(1, EncodedVideoStabilizer.frameIndexForPts(33_333L, 30, 90))
        assertEquals(1, EncodedVideoStabilizer.frameIndexForPts(33_334L, 30, 90))
        assertEquals(2, EncodedVideoStabilizer.frameIndexForPts(66_667L, 30, 90))
        assertEquals(45, EncodedVideoStabilizer.frameIndexForPts(1_500_000L, 30, 90))
    }

    @Test fun `pts index clamps to the table`() {
        assertEquals(0, EncodedVideoStabilizer.frameIndexForPts(-5_000L, 30, 90))
        assertEquals(89, EncodedVideoStabilizer.frameIndexForPts(9_999_999_999L, 30, 90))
        assertEquals(0, EncodedVideoStabilizer.frameIndexForPts(33_333L, 30, 1))
    }

    @Test fun `pts index follows 24fps grid`() {
        assertEquals(1, EncodedVideoStabilizer.frameIndexForPts(41_667L, 24, 90))
        assertEquals(24, EncodedVideoStabilizer.frameIndexForPts(1_000_000L, 24, 90))
    }

    @Test fun `ten-bit detection follows the luma pixel stride`() {
        assertTrue(EncodedVideoStabilizer.isTenBitOutput(2))
        assertFalse(EncodedVideoStabilizer.isTenBitOutput(1))
        assertFalse(EncodedVideoStabilizer.isTenBitOutput(0))
    }
}
