// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HdrPlusSettingsTest {
    @Test fun preferencesSurviveReload() {
        for (enabled in listOf(false, true)) for (hq in listOf(false, true)) {
            for (strength in listOf(1f, 13f, 22f)) {
                for (autoS in listOf(false, true)) for (autoQ in listOf(false, true)) {
                    val original = HdrPlusSettings(
                        enabled, strength, hq, keepSourceBurst = enabled,
                        autoStrength = autoS, autoQuality = autoQ
                    )
                    assertEquals(original, HdrPlusSettings.fromPreferences(original.toPreferences()))
                }
            }
        }
        assertEquals(HdrPlusSettings(), HdrPlusSettings.fromPreferences(emptyMap<String, Any>()))
    }

    @Test fun corruptStrengthFallsBackToDefault() {
        assertEquals(
            HdrPlusSettings.DEFAULT_STRENGTH,
            HdrPlusSettings.fromPreferences(mapOf("hdr_plus_strength" to Float.NaN)).strength
        )
        assertEquals(
            HdrPlusSettings.MAX_STRENGTH,
            HdrPlusSettings.fromPreferences(mapOf("hdr_plus_strength" to 99f)).strength
        )
        assertEquals(
            HdrPlusSettings.MIN_STRENGTH,
            HdrPlusSettings.fromPreferences(mapOf("hdr_plus_strength" to -1f)).strength
        )
    }

    @Test fun strengthValidationRejectsOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { HdrPlusSettings(strength = 0f) }
        assertThrows(IllegalArgumentException::class.java) { HdrPlusSettings(strength = 23f) }
        assertThrows(IllegalArgumentException::class.java) {
            HdrPlusSettings(strength = Float.NaN)
        }
    }

    @Test fun activeFrameCountClampsToMergeBounds() {
        val on = HdrPlusSettings(enabled = true)
        assertEquals(2, on.activeFrameCount(1))
        assertEquals(8, on.activeFrameCount(8))
        assertEquals(30, on.activeFrameCount(99))
        assertEquals(1, HdrPlusSettings().activeFrameCount(1))
    }

    @Test fun quickTextStates() {
        val off = HdrPlusSettings()
        assertEquals("HDR+\nOFF", off.quickText(8, 8, true, false))
        val on = HdrPlusSettings(enabled = true)
        assertEquals("HDR+\nMERGING", on.quickText(8, 8, true, true))
        assertEquals("HDR+\nUNAVAILABLE", on.quickText(8, 8, false, false))
        assertEquals("HDR+\nWARMING", on.quickText(8, 3, true, false))
        assertEquals("HDR+\nAUTO ×8", on.quickText(8, 8, true, false))
        assertEquals(
            "HDR+\nHQ ×8",
            on.copy(autoQuality = false).quickText(8, 8, true, false)
        )
        assertEquals(
            "HDR+\nFAST ×8",
            on.copy(autoQuality = false, highQuality = false).quickText(8, 8, true, false)
        )
    }

    @Test fun autoDefaultsOn() {
        assertEquals(true, HdrPlusSettings().autoStrength)
        assertEquals(true, HdrPlusSettings().autoQuality)
        val reloaded = HdrPlusSettings.fromPreferences(emptyMap<String, Any>())
        assertEquals(true, reloaded.autoStrength)
        assertEquals(true, reloaded.autoQuality)
    }

    @Test fun highQualityDefaultsOn() {
        assertEquals(true, HdrPlusSettings().highQuality)
        assertEquals(
            true,
            HdrPlusSettings.fromPreferences(emptyMap<String, Any>()).highQuality
        )
    }

    @Test fun strengthSliderMapping() {
        assertEquals(1f, HdrPlusSettings.progressToStrength(0), 0f)
        assertEquals(22f, HdrPlusSettings.progressToStrength(210), 0f)
        assertEquals(8f, HdrPlusSettings.progressToStrength(70), 0f)
        assertEquals(13f, HdrPlusSettings.progressToStrength(120), 0f)
        assertEquals(1f, HdrPlusSettings.progressToStrength(-5), 0f)
        assertEquals(22f, HdrPlusSettings.progressToStrength(999), 0f)
        assertEquals(0, HdrPlusSettings.strengthToProgress(1f))
        assertEquals(210, HdrPlusSettings.strengthToProgress(22f))
        assertEquals(70, HdrPlusSettings.strengthToProgress(8f))
        assertEquals(120, HdrPlusSettings.strengthToProgress(13f))
        assertEquals(0, HdrPlusSettings.strengthToProgress(-1f))
        assertEquals(210, HdrPlusSettings.strengthToProgress(99f))
        assertEquals(120, HdrPlusSettings.strengthToProgress(Float.NaN))
    }

    @Test fun strengthSliderRoundTripsEveryStep() {
        for (progress in 0..HdrPlusSettings.STRENGTH_SLIDER_STEPS) {
            assertEquals(
                progress,
                HdrPlusSettings.strengthToProgress(HdrPlusSettings.progressToStrength(progress))
            )
        }
    }

    @Test fun strengthSliderText() {
        assertEquals("HDR+ strength: 8.0", HdrPlusSettings.strengthSliderText(8f))
        assertEquals("HDR+ strength: 13.0", HdrPlusSettings.strengthSliderText(13f))
    }
}
