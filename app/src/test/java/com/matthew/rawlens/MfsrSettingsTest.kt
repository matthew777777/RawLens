// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun lkrFollowsTheFullBurstRange() {
        val settings = MfsrSettings(enabled = true, engine = MfsrEngine.LKR)
        for (count in 2..59) assertEquals(count, settings.activeFrameCount(count))
        assertEquals(59, settings.activeFrameCount(80))
        assertEquals("LK-R\n×40", settings.quickText(MfsrEngine.LKR, 40, 40, true, false))
        for (z in listOf(LkrZoom.X1_5, LkrZoom.X2, LkrZoom.FULL_2X))
            assertEquals(24, settings.copy(lkrZoom = z).activeFrameCount(40))
    }

    @Test fun quickTextUsesConfiguredCountAndWaitsForFullSelection() {
        val on = MfsrSettings(enabled = true)
        val m = MfsrEngine.MFSR
        assertEquals("MFSR\nOFF", MfsrSettings().quickText(m, 12, 12, true, false))
        assertEquals("MFSR\nMERGING", on.quickText(m, 12, 12, true, true))
        assertEquals("MFSR\nUNAVAILABLE", on.quickText(m, 12, 12, false, false))
        assertEquals("MFSR\nWARMING", on.quickText(m, 12, 8, true, false))
        assertEquals("MFSR\n×12", on.quickText(m, 12, 12, true, false))
        assertEquals("MFSR\nWARMING", on.quickText(m, 30, 15, true, false))
        assertEquals("MFSR\n×16", on.quickText(m, 30, 16, true, false))
    }

    @Test fun lkrMiddleReferenceDefaultsOnAndRoundTrips() {
        assertTrue(MfsrSettings().lkrMiddleReference)
        assertTrue(MfsrSettings.fromPreferences(emptyMap<String, Any>()).lkrMiddleReference)
        val off = MfsrSettings(lkrMiddleReference = false)
        assertFalse(MfsrSettings.fromPreferences(off.toPreferences()).lkrMiddleReference)
    }

    @Test fun lkrDefaultsToNoPrior() {
        assertFalse(MfsrSettings().lkrDctPrior)
        assertFalse(MfsrSettings.fromPreferences(emptyMap<String, Any>()).lkrDctPrior)
        // Settings saved for the removed legacy switches are ignored.
        assertFalse(MfsrSettings.fromPreferences(mapOf("lkr_use_prior" to true, "lkr_paper_parity_v2" to true)).lkrDctPrior)
        val on = MfsrSettings(lkrDctPrior = true)
        assertTrue(MfsrSettings.fromPreferences(on.toPreferences()).lkrDctPrior)
    }

    @Test fun lkrZoomCropsTheCentreAndKeepsSensorOutput() {
        assertEquals(null, LkrZoom.X1.crop(4080, 3060))
        // 2x: centre half, solved at x2 -> exactly 4080x3060
        assertArrayEquals(intArrayOf(1020, 764, 2040, 1530), LkrZoom.X2.crop(4080, 3060))
        // 1.5x: centre 2720x2040, solved at x2 (5440x4080) then resampled to the sensor size
        assertArrayEquals(intArrayOf(680, 510, 2720, 2040), LkrZoom.X1_5.crop(4080, 3060))
        for (z in LkrZoom.entries) z.crop(4080, 3060)?.forEach { assertEquals(0, it % 2) }   // Bayer phase
        assertEquals(LkrZoom.X1_5, LkrZoom.X1.next())
        assertEquals(LkrZoom.FULL_2X, LkrZoom.X2.next())
        assertEquals(LkrZoom.X1, LkrZoom.FULL_2X.next())
        assertEquals(null, LkrZoom.FULL_2X.crop(4080, 3060))
        val z = MfsrSettings(lkrZoom = LkrZoom.X2)
        assertEquals(LkrZoom.X2, MfsrSettings.fromPreferences(z.toPreferences()).lkrZoom)
    }

    @Test fun eachEngineHasItsOwnTile() {
        val lkr = MfsrSettings(enabled = true, engine = MfsrEngine.LKR)
        assertEquals("LK-R\n×12", lkr.quickText(MfsrEngine.LKR, 12, 12, true, false))
        assertEquals("MFSR\nOFF", lkr.quickText(MfsrEngine.MFSR, 12, 12, true, false))
        assertTrue(lkr.isOn(MfsrEngine.LKR))
        assertFalse(lkr.isOn(MfsrEngine.MFSR))
        assertFalse(lkr.copy(enabled = false).isOn(MfsrEngine.LKR))
        assertEquals("LK-R\nOFF", MfsrSettings().quickText(MfsrEngine.LKR, 12, 12, true, false))
    }

    @Test fun kernelAndS3SurviveReload() {
        for (kernel in MfsrKernel.entries) for (s3 in listOf(false, true)) {
            val original = MfsrSettings(kernel = kernel, s3 = s3)
            assertEquals(original, MfsrSettings.fromPreferences(original.toPreferences()))
        }
        // Corrupt values heal to the defaults, like the frame count.
        assertEquals(
            MfsrKernel.KERNELNET,
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
        assertEquals(MfsrKernel.KERNELNET, fresh.kernel)
        assertEquals(true, fresh.s3)
    }

    @org.junit.Test fun learnedNoiseDefaultsOnAndRoundTrips() {
        org.junit.Assert.assertEquals(true, MfsrSettings.fromPreferences(emptyMap<String, Any>()).learnedNoise)
        val off = MfsrSettings(learnedNoise = false)
        org.junit.Assert.assertEquals(off, MfsrSettings.fromPreferences(off.toPreferences()))
    }
    @Test fun freshPhotoBurstDoesNotWaitForZslRing() {
        val on = MfsrSettings(enabled = true)
        assertEquals("MFSR\n×8", on.quickText(MfsrEngine.MFSR, 8, 0, true, false, zsl = false))
    }

}
