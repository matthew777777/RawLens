// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFormatTest {
    @Test
    fun selectorCyclesInUserFacingOrder() {
        assertEquals(CaptureFormat.JPEG_DNG, CaptureFormat.JPEG.next())
        assertEquals(CaptureFormat.DNG_ONLY, CaptureFormat.JPEG_DNG.next())
        assertEquals(CaptureFormat.JPEG, CaptureFormat.DNG_ONLY.next())
    }

    @Test
    fun missingOrUnknownPreferencePreservesExistingDngBehavior() {
        assertEquals(CaptureFormat.DNG_ONLY, CaptureFormat.fromPreference(null))
        assertEquals(CaptureFormat.DNG_ONLY, CaptureFormat.fromPreference("UNKNOWN"))
        assertEquals(CaptureFormat.JPEG_DNG, CaptureFormat.fromPreference("JPEG_DNG"))
    }

    @Test
    fun outputContractsAreExplicit() {
        assertTrue(CaptureFormat.JPEG.includesJpeg)
        assertFalse(CaptureFormat.JPEG.includesDng)
        assertTrue(CaptureFormat.JPEG_DNG.includesJpeg)
        assertTrue(CaptureFormat.JPEG_DNG.includesDng)
        assertFalse(CaptureFormat.DNG_ONLY.includesJpeg)
        assertTrue(CaptureFormat.DNG_ONLY.includesDng)
    }
}
