// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineBurstOrientationTest {
    @Test fun oldPortraitBurstsUseSensorMount() {
        assertEquals(6, OfflineBurstMetadata.resolveOrientation(0, 0, 90))
        assertEquals(8, OfflineBurstMetadata.resolveOrientation(0, 0, 270))
    }

    @Test fun savedCaptureOrientationWinsOverSensorMount() {
        for (orientation in listOf(1, 3, 6, 8)) {
            assertEquals(orientation, OfflineBurstMetadata.resolveOrientation(0, orientation, 90))
        }
        assertEquals(8, OfflineBurstMetadata.resolveOrientation(8, 6, 90))
    }

    @Test fun missingExifUsesRecordedPhoneRotation() {
        assertEquals(1, OfflineBurstMetadata.resolveOrientation(0, 0, 90, 270))
        assertEquals(3, OfflineBurstMetadata.resolveOrientation(0, 0, 90, 90))
        assertEquals(8, OfflineBurstMetadata.resolveOrientation(0, 0, 90, 180))
        assertEquals(1, OfflineBurstMetadata.resolveOrientation(0, 0, 90, 90, true))
    }

    @Test fun invalidExifFallsBackToSensorMount() {
        assertEquals(6, OfflineBurstMetadata.resolveOrientation(-1, 9, 90))
    }
}
