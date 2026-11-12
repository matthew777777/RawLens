// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawZslFpsRangeTest {
    @Test fun centredTakeWaitsHalfABurstUpToTheLatencyBound() {
        val f30 = 33_333_333L
        assertEquals(266L, ZslPolicy.centredTakeDelayMs(16, f30, 300L))
        assertEquals(133L, ZslPolicy.centredTakeDelayMs(8, f30, 300L))
        // Slow exposure: half a burst would be 800 ms; the wait stops at the bound.
        assertEquals(300L, ZslPolicy.centredTakeDelayMs(16, 100_000_000L, 300L))
        // Nothing to centre.
        assertEquals(0L, ZslPolicy.centredTakeDelayMs(2, f30, 300L))
        assertEquals(0L, ZslPolicy.centredTakeDelayMs(16, 0L, 300L))
    }

    @Test
    fun prefersExactFixedThirtyFpsRange() {
        // Regression: the RAW stream must request a fixed 30 fps range (MotionCam
        // RAW-viewfinder port) instead of a variable range that lets AE sag.
        val ranges = listOf(15..30, 30..30, 5..30)
        assertEquals(30..30, ZslPolicy.selectRawZslFpsRange(ranges, 30))
    }

    @Test
    fun prefersNarrowestRangeContainingTarget() {
        val ranges = listOf(10..30, 24..30, 15..60)
        assertEquals(24..30, ZslPolicy.selectRawZslFpsRange(ranges, 30))
    }

    @Test
    fun fallsBackToFastestAdvertisedRange() {
        val ranges = listOf(10..20, 5..15)
        assertEquals(10..20, ZslPolicy.selectRawZslFpsRange(ranges, 30))
    }

    @Test
    fun emptyAdvertisedRangesSelectNothing() {
        assertNull(ZslPolicy.selectRawZslFpsRange(emptyList(), 30))
    }
}
