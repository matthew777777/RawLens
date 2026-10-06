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
                val original = HdrPlusSettings(enabled, strength, hq, keepSourceBurst = enabled)
                assertEquals(original, HdrPlusSettings.fromPreferences(original.toPreferences()))
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
        assertEquals("HDR+\nHQ ×8", on.quickText(8, 8, true, false))
        assertEquals("HDR+\nFAST ×8", on.copy(highQuality = false).quickText(8, 8, true, false))
    }

    @Test fun highQualityDefaultsOn() {
        assertEquals(true, HdrPlusSettings().highQuality)
        assertEquals(
            true,
            HdrPlusSettings.fromPreferences(emptyMap<String, Any>()).highQuality
        )
    }
}
