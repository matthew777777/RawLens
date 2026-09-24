// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class RawSuperResolutionSettingsTest {
    @Test fun allPreferencesSurviveReload() {
        for (enabled in listOf(false, true)) for (mode in RawSrDngMode.entries) {
            val original = RawSuperResolutionSettings(enabled, mode, 1.414f, true)
            assertEquals(original, RawSuperResolutionSettings.fromPreferences(original.toPreferences().toMutableMap()))
        }
        assertEquals(RawSuperResolutionSettings(), RawSuperResolutionSettings.fromPreferences(emptyMap<String, Any>()))
        assertEquals(1f, RawSuperResolutionSettings.fromPreferences(mapOf("raw_super_resolution_output_scale" to Float.NaN)).outputScale)
    }

    @Test fun mergeDebugFlagDefaultsOffAndSurvivesReload() {
        assertEquals(false, RawSuperResolutionSettings().saveMergeDebugFrames)
        val on = RawSuperResolutionSettings(saveMergeDebugFrames = true)
        assertEquals(on, RawSuperResolutionSettings.fromPreferences(on.toPreferences().toMutableMap()))
        assertEquals(true,
            RawSuperResolutionSettings.fromPreferences(
                mapOf("raw_super_resolution_save_merge_debug" to true)).saveMergeDebugFrames)
    }

    @Test fun queuedSnapshotSurvivesUiChangesAndPreferenceReload() {
        var ui = RawSuperResolutionSettings(true, RawSrDngMode.MOSAIC_SR, 1.414f, true)
        val shutterSnapshot = ui.copy()
        var observed: RawSuperResolutionSettings? = null
        val job = OwnedCaptureJob(CloseOnceOwner(listOf(1)) {}) { observed = shutterSnapshot }
        ui = ui.copy(enabled = false, dngMode = RawSrDngMode.LINEAR_RGB, outputScale = 1f, keepSourceBurst = false)
        assertEquals(ui, RawSuperResolutionSettings.fromPreferences(ui.toPreferences()))
        job.run()
        assertEquals(RawSuperResolutionSettings(true, RawSrDngMode.MOSAIC_SR, 1.414f, true), observed)
    }

    @Test fun legacyDispatchSavesEverySelectedSourceIndependently() {
        for (count in 1..30) {
            val frames = (0 until count).toList()
            val saved = mutableListOf<Int>()
            dispatchRawZslSelection(frames, RawSuperResolutionSettings(),
                burst = { error("Legacy frames must not become a burst job") },
                source = { index, frame -> assertEquals(index, frame); saved += frame })
            assertEquals(frames, saved)
        }
    }

    @Test fun readinessNeedsBothBufferAndCapability() {
        val on = RawSuperResolutionSettings(true)
        assertEquals("RAW SR\nWARMING", on.quickText(15, 14, true, false))
        assertEquals("RAW SR\nWARMING", on.quickText(15, 15, null, false))
        assertEquals("RAW SR\nUNAVAILABLE", on.quickText(15, 15, false, false))
        assertEquals("RAW SR\nON ×15", on.quickText(15, 15, true, false))
        assertEquals("RAW SR\nMERGING", on.quickText(15, 0, true, true))
        assertEquals("RAW SR\nOFF", on.copy(enabled = false).quickText(15, 15, true, true))
    }
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
