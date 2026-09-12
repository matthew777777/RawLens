// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawZslFpsRangeTest {
    @Test
    fun prefersExactFixedThirtyFpsRange() {
        // Regression: the RAW stream must request a fixed 30 fps range (MotionCam
        // RAW-viewfinder port) instead of a variable range that lets AE sag.
        val ranges = listOf(15..30, 30..30, 5..30)
        assertEquals(30..30, RawCameraController.selectRawZslFpsRange(ranges, 30))
    }

    @Test
    fun prefersNarrowestRangeContainingTarget() {
        val ranges = listOf(10..30, 24..30, 15..60)
        assertEquals(24..30, RawCameraController.selectRawZslFpsRange(ranges, 30))
    }

    @Test
    fun fallsBackToFastestAdvertisedRange() {
        val ranges = listOf(10..20, 5..15)
        assertEquals(10..20, RawCameraController.selectRawZslFpsRange(ranges, 30))
    }

    @Test
    fun emptyAdvertisedRangesSelectNothing() {
        assertNull(RawCameraController.selectRawZslFpsRange(emptyList(), 30))
    }
}
