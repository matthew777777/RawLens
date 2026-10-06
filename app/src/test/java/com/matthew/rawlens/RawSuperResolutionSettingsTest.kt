// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun kernelPresetResolveUsesScannedFlatForDecoupled() {
        // C2 semantics: the RAWR detail end with the scene's own scanned
        // flat width (SNR 18 scans to kDetail 0.29 x kDenoise 4.0).
        val scan = RawSrTuning.forSnr(18.0)
        assertNull(RawSrKernelPreset.REFERENCE.resolve(scan))
        val decoupled = RawSrKernelPreset.DECOUPLED_SHARP.resolve(scan)!!
        assertEquals(scan.kDetail * scan.kDenoise, decoupled.flatSigma!!, 0.0)
        assertEquals(1.16, decoupled.flatSigma!!, 1e-9)
        assertEquals(0.08, decoupled.kDetail, 0.0)
        assertEquals(0.25, decoupled.dTh, 0.0)
        assertEquals(0.30, decoupled.dTr, 0.0)
        assertEquals(1.0, decoupled.kStretch, 0.0)
        assertEquals(8.0, decoupled.kShrink, 0.0)
        assertEquals(0.4, decoupled.detailFloor!!, 0.0)
        assertEquals(18.0, decoupled.snr, 0.0)
    }

    @Test fun sharpestReferenceDefaultsOnAndSurvivesReload() {
        assertEquals(true, RawSuperResolutionSettings().sharpestReference)
        assertEquals(RawSrKernelPreset.REFERENCE, RawSuperResolutionSettings().kernelPreset)
        val custom = RawSuperResolutionSettings(
            sharpestReference = false, kernelPreset = RawSrKernelPreset.DECOUPLED_SHARP)
        assertEquals(custom, RawSuperResolutionSettings.fromPreferences(custom.toPreferences().toMutableMap()))
        assertEquals(true,
            RawSuperResolutionSettings.fromPreferences(emptyMap<String, Any>()).sharpestReference)
        assertEquals(RawSrKernelPreset.DECOUPLED_SHARP, RawSrKernelPreset.fromPreference("decoupled_sharp"))
        assertEquals(RawSrKernelPreset.REFERENCE, RawSrKernelPreset.fromPreference("unknown"))
        assertEquals(RawSrKernelPreset.REFERENCE, RawSrKernelPreset.fromPreference(null))
    }

    @Test fun mosaicScaleDefaultsSrAndSurvivesReload() {
        assertEquals(RawSrMosaicScale.SR, RawSuperResolutionSettings().mosaicScale)
        val native = RawSuperResolutionSettings(mosaicScale = RawSrMosaicScale.NATIVE)
        assertEquals(native, RawSuperResolutionSettings.fromPreferences(native.toPreferences().toMutableMap()))
        assertEquals(RawSrMosaicScale.NATIVE, RawSrMosaicScale.fromPreference("native"))
        assertEquals(RawSrMosaicScale.SR, RawSrMosaicScale.fromPreference("sr"))
        assertEquals(RawSrMosaicScale.SR, RawSrMosaicScale.fromPreference("unknown"))
        assertEquals(RawSrMosaicScale.SR, RawSrMosaicScale.fromPreference(null))
        assertEquals(1.0, RawSrMosaicScale.NATIVE.factor, 0.0)
        assertEquals(MosaicSrReconstructor.LINEAR_SCALE, RawSrMosaicScale.SR.factor, 0.0)
    }

    @Test fun linearScaleDefaultsX1AndSurvivesReload() {
        assertEquals(RawSrLinearScale.X1, RawSuperResolutionSettings().linearScale)
        val sr = RawSuperResolutionSettings(linearScale = RawSrLinearScale.SR)
        assertEquals(sr, RawSuperResolutionSettings.fromPreferences(sr.toPreferences().toMutableMap()))
        assertEquals(RawSrLinearScale.X1, RawSrLinearScale.fromPreference("1x"))
        assertEquals(RawSrLinearScale.SR, RawSrLinearScale.fromPreference("sr"))
        assertEquals(RawSrLinearScale.X1, RawSrLinearScale.fromPreference("unknown"))
        assertEquals(RawSrLinearScale.X1, RawSrLinearScale.fromPreference(null))
        assertEquals(1.0, RawSrLinearScale.X1.factor, 0.0)
        assertEquals(MosaicSrReconstructor.LINEAR_SCALE, RawSrLinearScale.SR.factor, 0.0)
        // Unity pin: both paths resolve the same √2 factor.
        assertEquals(RawSrMosaicScale.SR.factor, RawSrLinearScale.SR.factor, 0.0)
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
