// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class StatusTextTest {
    @Test fun stripsFullFileNames() {
        assertEquals(
            "SAVED",
            StatusText.compact("SAVED IMG_20250910_123456_789.dng + IMG_20250910_123456_789.jpg")
        )
        val hdr = StatusText.compact("HDR MERGED • IMG_20250910_123456_789.jpg • HDR DNG IMG_x.dng")
        assertFalse(hdr.contains("IMG_"))
        assertFalse(hdr.contains(".dng"))
        assertTrue(hdr.length <= StatusText.MAX_LEN)
    }

    @Test fun shortMessagesPassThrough() {
        listOf("READY", "SAVED DNG", "ZSL ×8", "HDR ×3 SAVED", "TIMER • 5S", "CALIB SAVED").forEach {
            assertEquals(it, StatusText.compact(it))
        }
    }

    @Test fun longMessagesAreCapped() {
        val capped = StatusText.compact("DENOISE SETTINGS APPLY AFTER SAVES FINISH")
        assertTrue(capped.length <= StatusText.MAX_LEN)
        assertFalse(capped.contains("FINISH"))
    }

    @Test fun saveOutcomeUsesTypesNotNames() {
        assertEquals("SAVED DNG+JPG", StatusText.saveOutcome(dngSaved = true, jpegSaved = true, failed = false))
        assertEquals("SAVED DNG", StatusText.saveOutcome(dngSaved = true, jpegSaved = false, failed = false))
        assertEquals("SAVED JPG", StatusText.saveOutcome(dngSaved = false, jpegSaved = true, failed = false))
        assertEquals("SAVE ERROR", StatusText.saveOutcome(dngSaved = false, jpegSaved = false, failed = true))
        assertEquals("PARTIAL DNG", StatusText.saveOutcome(dngSaved = true, jpegSaved = false, failed = true))
    }
}
