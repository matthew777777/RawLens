// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class JxlSaverTest {
    @Test fun orientationDegreesMirrorJpegSaver() {
        assertEquals(0, JxlSaver.exifOrientationDegrees(1))
        assertEquals(90, JxlSaver.exifOrientationDegrees(6))
        assertEquals(180, JxlSaver.exifOrientationDegrees(3))
        assertEquals(270, JxlSaver.exifOrientationDegrees(8))
        // Unknown/mirrored codes never fabricate a rotation.
        assertEquals(0, JxlSaver.exifOrientationDegrees(0))
        assertEquals(0, JxlSaver.exifOrientationDegrees(2))
    }
}
