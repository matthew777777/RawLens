// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class MfsrSettingsTest {
    @Test fun preferencesSurviveReload() {
        for (enabled in listOf(false, true)) {
            val original = MfsrSettings(enabled = enabled, keepSourceBurst = enabled)
            assertEquals(original, MfsrSettings.fromPreferences(original.toPreferences()))
        }
    }

    @Test fun legacyFramePreferenceCannotOverrideZslSelection() {
        for (legacy in listOf(2, 8, 15, 99, "junk")) {
            val settings = MfsrSettings.fromPreferences(mapOf("mfsr_frames" to legacy))
            assertEquals(MfsrSettings(), settings)
            assertEquals(12, settings.activeFrameCount(12))
            assertEquals(false, settings.toPreferences().containsKey("mfsr_frames"))
        }
    }

    @Test fun followsZslWithMfsrOnlyCeiling() {
        val settings = MfsrSettings(enabled = true)
        for (count in 2..16) assertEquals(count, settings.activeFrameCount(count))
        assertEquals(2, settings.activeFrameCount(1))
        assertEquals(16, settings.activeFrameCount(30))
    }

    @Test fun quickTextUsesConfiguredCountAndWaitsForFullSelection() {
        val on = MfsrSettings(enabled = true)
        assertEquals("MFSR\nOFF", MfsrSettings().quickText(12, 12, true, false))
        assertEquals("MFSR\nMERGING", on.quickText(12, 12, true, true))
        assertEquals("MFSR\nUNAVAILABLE", on.quickText(12, 12, false, false))
        assertEquals("MFSR\nWARMING", on.quickText(12, 8, true, false))
        assertEquals("MFSR\n×12", on.quickText(12, 12, true, false))
        assertEquals("MFSR\nWARMING", on.quickText(30, 15, true, false))
        assertEquals("MFSR\n×16", on.quickText(30, 16, true, false))
    }

    @Test fun kernelAndS3SurviveReload() {
        for (kernel in MfsrKernel.entries) for (s3 in listOf(false, true)) {
            val original = MfsrSettings(kernel = kernel, s3 = s3)
            assertEquals(original, MfsrSettings.fromPreferences(original.toPreferences()))
        }
        // Corrupt values heal to the defaults, like the frame count.
        assertEquals(
            MfsrKernel.WRONSKI,
            MfsrSettings.fromPreferences(mapOf("mfsr_kernel" to "junk")).kernel
        )
        assertEquals(
            true,
            MfsrSettings.fromPreferences(mapOf("mfsr_s3" to "junk")).s3
        )
    }

    @Test fun defaultsOff() {
        val fresh = MfsrSettings.fromPreferences(emptyMap<String, Any>())
        assertEquals(MfsrSettings(), fresh)
        assertEquals(false, fresh.enabled)
        assertEquals(false, fresh.keepSourceBurst)
        assertEquals(MfsrKernel.WRONSKI, fresh.kernel)
        assertEquals(true, fresh.s3)
    }
}
