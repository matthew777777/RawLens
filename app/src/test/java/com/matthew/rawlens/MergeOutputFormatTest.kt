// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeOutputFormatTest {
    @Test fun newOfflineJobsDefaultToDevelopedPhotos() {
        assertEquals(CaptureFormat.JPEG, MergeScreenSettings.outputFormat(null))
        assertEquals(CaptureFormat.JPEG, MergeScreenSettings.outputFormat("invalid"))
    }

    @Test fun explicitOutputChoicesSurviveReloading() {
        for (format in CaptureFormat.entries) {
            assertEquals(format, MergeScreenSettings.outputFormat(format.name))
        }
    }
}
