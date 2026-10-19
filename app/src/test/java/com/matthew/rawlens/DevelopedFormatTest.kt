// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class DevelopedFormatTest {
    @Test
    fun togglesBetweenContainers() {
        assertEquals(DevelopedFormat.JXL, DevelopedFormat.JPEG.next())
        assertEquals(DevelopedFormat.JPEG, DevelopedFormat.JXL.next())
    }

    @Test
    fun unknownPreferenceStaysJpeg() {
        assertEquals(DevelopedFormat.JPEG, DevelopedFormat.fromPreference(null))
        assertEquals(DevelopedFormat.JPEG, DevelopedFormat.fromPreference("UNKNOWN"))
        assertEquals(DevelopedFormat.JXL, DevelopedFormat.fromPreference("JXL"))
    }

    @Test
    fun badgePairsWithDng() {
        assertEquals("JXL", DevelopedFormat.JXL.badgeLabel(false))
        assertEquals("JXL + DNG", DevelopedFormat.JXL.badgeLabel(true))
        assertEquals("JPEG + DNG", DevelopedFormat.JPEG.badgeLabel(true))
        assertEquals("jxl", DevelopedFormat.JXL.extension)
    }

    @Test
    fun statusOutcomeUsesDevelopedToken() {
        assertEquals(
            "SAVED JXL",
            StatusText.saveOutcome(dngSaved = false, jpegSaved = true, failed = false, developed = "JXL")
        )
        assertEquals(
            "SAVED DNG+JXL",
            StatusText.saveOutcome(dngSaved = true, jpegSaved = true, failed = false, developed = "JXL")
        )
        assertEquals(
            "PARTIAL JXL",
            StatusText.saveOutcome(dngSaved = false, jpegSaved = true, failed = true, developed = "JXL")
        )
        // Legacy default unchanged.
        assertEquals(
            "SAVED JPG",
            StatusText.saveOutcome(dngSaved = false, jpegSaved = true, failed = false)
        )
    }
}
