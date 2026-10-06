// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class WhiteLevelBitDepthTest {
    // The DNG chip and its dynamic-white updates share one mapping, so both
    // report the same depth for the same level.
    @Test
    fun mapsKnownWhiteLevels() {
        assertEquals(10, RawCameraController.whiteLevelBitDepth(1023))
        assertEquals(12, RawCameraController.whiteLevelBitDepth(4095))
        assertEquals(14, RawCameraController.whiteLevelBitDepth(16383))
        assertEquals(16, RawCameraController.whiteLevelBitDepth(65535))
    }

    @Test
    fun missingKeyFallsBackToTenBit() {
        assertEquals(10, RawCameraController.whiteLevelBitDepth(null))
    }
}
