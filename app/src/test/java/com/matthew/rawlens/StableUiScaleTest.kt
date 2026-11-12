// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class StableUiScaleTest {
    @Test fun enlargedDisplaySizeReturnsToStableDensity() =
        assertEquals(440, StableUiScale.targetDensityDpi(currentDpi = 495, stableDpi = 440, shortSidePx = 1080))

    @Test fun shrunkDisplaySizeReturnsToStableDensity() =
        assertEquals(440, StableUiScale.targetDensityDpi(currentDpi = 374, stableDpi = 440, shortSidePx = 1080))

    @Test fun stableDensityFromAnotherResolutionIsRejected() =
        // Factory 640 dpi for 1440 px, but the panel now runs at 720 px: 180 dp would be unusable.
        assertEquals(320, StableUiScale.targetDensityDpi(currentDpi = 320, stableDpi = 640, shortSidePx = 720))

    @Test fun missingValuesKeepTheCurrentDensity() {
        assertEquals(440, StableUiScale.targetDensityDpi(currentDpi = 440, stableDpi = 0, shortSidePx = 1080))
        assertEquals(440, StableUiScale.targetDensityDpi(currentDpi = 440, stableDpi = 480, shortSidePx = 0))
    }
}
