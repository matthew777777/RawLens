// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoResultLagTest {
    private val f60 = 1_000_000_000L / 60

    @Test fun startsAtLegacyTwoFrameHold() {
        assertEquals(3, VideoResultLag(f60).holdFrames(6)) // 2 frames lag + 1
    }

    @Test fun measuredLagGrowsHoldWithinCap() {
        val lag = VideoResultLag(f60)
        lag.onImage(1, 0); lag.onResult(1, 70_000_000) // 70 ms result lag
        assertEquals(70_000_000, lag.lagNs)
        assertEquals(6, lag.holdFrames(6)) // ceil(70/16.7)=5, +1
        assertEquals(4, lag.holdFrames(4))
        assertEquals(70_000_000 + f60, lag.waitNs())
    }

    @Test fun fastResultsDecayTowardShortHold() {
        val lag = VideoResultLag(f60)
        lag.onImage(1, 0); lag.onResult(1, 70_000_000)
        for (t in 2L..400L) { lag.onImage(t, 0); lag.onResult(t, 1_000_000) }
        assertEquals(2, lag.holdFrames(6))
    }

    @Test fun resultBeforeImageIsIgnoredAndEarlyResultIsZeroLag() {
        val lag = VideoResultLag(f60)
        lag.onResult(5, 10) // no image yet: no sample
        assertEquals(0, lag.samples)
        lag.onImage(6, 100); lag.onResult(6, 50)
        assertEquals(1, lag.samples)
    }
}
