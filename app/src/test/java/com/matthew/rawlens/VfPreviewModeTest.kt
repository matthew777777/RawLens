// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WYSIWYG contract: JPEG/JPEG_DNG render the cheap scene-referred AgX preview,
 * DNG_ONLY renders raw-clean. resolve() returns true for the raw path.
 */
class VfPreviewModeTest {
    @Test
    fun followTracksOutputContract() {
        assertFalse(VfPreviewMode.FOLLOW.resolve(CaptureFormat.JPEG))
        assertFalse(VfPreviewMode.FOLLOW.resolve(CaptureFormat.JPEG_DNG))
        assertTrue(VfPreviewMode.FOLLOW.resolve(CaptureFormat.DNG_ONLY))
    }

    @Test
    fun explicitModesOverrideFormat() {
        for (format in CaptureFormat.entries) {
            assertTrue(VfPreviewMode.RAW.resolve(format))
            assertFalse(VfPreviewMode.JPEG.resolve(format))
        }
    }

    @Test
    fun labelsMatchRenderPath() {
        assertEquals("JPG VF", VfPreviewMode.FOLLOW.label(CaptureFormat.JPEG))
        assertEquals("JPG VF", VfPreviewMode.FOLLOW.label(CaptureFormat.JPEG_DNG))
        assertEquals("RAW VF", VfPreviewMode.FOLLOW.label(CaptureFormat.DNG_ONLY))
    }

    @Test
    fun missingPreferenceKeepsFollow() {
        assertEquals(VfPreviewMode.FOLLOW, VfPreviewMode.fromPreference(null))
        assertEquals(VfPreviewMode.FOLLOW, VfPreviewMode.fromPreference("UNKNOWN"))
        assertEquals(VfPreviewMode.JPEG, VfPreviewMode.fromPreference("JPEG"))
    }
}
