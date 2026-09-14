// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class RawSuperResolutionSettingsTest {
    @Test
    fun disabledModePreservesSingleFrameZsl() {
        assertEquals(1, RawSuperResolutionSettings(enabled = false).activeFrameCount(1))
    }

    @Test
    fun enabledModeClampsMergeToTwoThroughThirtyFrames() {
        val settings = RawSuperResolutionSettings(enabled = true)
        assertEquals(2, settings.activeFrameCount(1))
        assertEquals(15, settings.activeFrameCount(15))
        assertEquals(30, settings.activeFrameCount(40))
    }

    @Test
    fun dngModePreferenceFallsBackToLinear() {
        assertEquals(RawSrDngMode.MOSAIC_SR, RawSrDngMode.fromPreference("mosaic_sr"))
        assertEquals(RawSrDngMode.LINEAR_RGB, RawSrDngMode.fromPreference("unknown"))
        assertEquals(RawSrDngMode.LINEAR_RGB, RawSrDngMode.fromPreference(null))
    }
}
