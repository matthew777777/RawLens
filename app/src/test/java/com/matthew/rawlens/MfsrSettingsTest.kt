// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MfsrSettingsTest {
    @Test fun preferencesSurviveReload() {
        for (enabled in listOf(false, true)) for (frames in listOf(2, 8, 15)) {
            val original = MfsrSettings(enabled, frames, keepSourceBurst = enabled)
            assertEquals(original, MfsrSettings.fromPreferences(original.toPreferences()))
        }
        assertEquals(MfsrSettings(), MfsrSettings.fromPreferences(emptyMap<String, Any>()))
    }

    @Test fun corruptFrameCountCoercesToBounds() {
        // House pattern (cf. HdrPlusSettingsTest): out-of-range numbers
        // coerce, missing keys fall back to the default.
        assertEquals(
            MfsrSettings.MAX_MERGE_FRAMES,
            MfsrSettings.fromPreferences(mapOf("mfsr_frames" to 99)).frameCount
        )
        assertEquals(
            MfsrSettings.MIN_MERGE_FRAMES,
            MfsrSettings.fromPreferences(mapOf("mfsr_frames" to -1)).frameCount
        )
        assertEquals(
            MfsrSettings.DEFAULT_FRAMES,
            MfsrSettings.fromPreferences(mapOf("mfsr_frames" to "junk")).frameCount
        )
    }

    @Test fun frameValidationRejectsOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { MfsrSettings(frameCount = 1) }
        assertThrows(IllegalArgumentException::class.java) { MfsrSettings(frameCount = 16) }
    }

    @Test fun activeFrameCountClampsToMergeBounds() {
        assertEquals(8, MfsrSettings(enabled = true).activeFrameCount())
        assertEquals(2, MfsrSettings(enabled = true, frameCount = 2).activeFrameCount())
        assertEquals(15, MfsrSettings(enabled = true, frameCount = 15).activeFrameCount())
    }

    @Test fun quickTextStates() {
        val off = MfsrSettings()
        assertEquals("MFSR\nOFF", off.quickText(8, true, false))
        val on = MfsrSettings(enabled = true)
        assertEquals("MFSR\nMERGING", on.quickText(8, true, true))
        assertEquals("MFSR\nUNAVAILABLE", on.quickText(8, false, false))
        assertEquals("MFSR\nWARMING", on.quickText(3, true, false))
        assertEquals("MFSR\n×8", on.quickText(8, true, false))
        assertEquals(
            "MFSR\n×4",
            on.copy(frameCount = 4).quickText(8, true, false)
        )
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

    @Test fun defaultsOffEightFrames() {
        val fresh = MfsrSettings.fromPreferences(emptyMap<String, Any>())
        assertEquals(MfsrSettings(), fresh)
        assertEquals(false, fresh.enabled)
        assertEquals(8, fresh.frameCount)
        assertEquals(false, fresh.keepSourceBurst)
        assertEquals(MfsrKernel.WRONSKI, fresh.kernel)
        assertEquals(true, fresh.s3)
    }
}
