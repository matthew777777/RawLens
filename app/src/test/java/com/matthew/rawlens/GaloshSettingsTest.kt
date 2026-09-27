// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class GaloshSettingsTest {
    @Test fun defaultsAreOffWithNeutralSliders() {
        val settings = GaloshSettings()
        assertFalse(settings.enabled)
        assertTrue(settings.saveOriginalDng)
        assertEquals(1f, settings.strength, 0f)
        assertEquals(1f, settings.luma, 0f)
        assertEquals(1f, settings.chroma, 0f)
        assertFalse(settings.fastUpsample)
        assertFalse(settings.fastMode)
    }
    @Test fun sliderRangesAreEnforced() {
        GaloshSettings(strength = 0f, luma = 0f, chroma = 0f)
        GaloshSettings(strength = 1f, luma = 2f, chroma = 2f)
        for (bad in listOf(-0.001f, 1.001f, Float.NaN)) {
            try {
                GaloshSettings(strength = bad)
                throw AssertionError("strength=$bad accepted")
            } catch (expected: IllegalArgumentException) { }
        }
        for (bad in listOf(-0.001f, 2.001f, Float.NaN)) {
            try {
                GaloshSettings(luma = bad)
                throw AssertionError("luma=$bad accepted")
            } catch (expected: IllegalArgumentException) { }
            try {
                GaloshSettings(chroma = bad)
                throw AssertionError("chroma=$bad accepted")
            } catch (expected: IllegalArgumentException) { }
        }
    }
}
