// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class SidecarTreeAccessTest {
    @Test fun `rawLens tree receives the stem directly`() {
        assertEquals(
            listOf("IMG_123"),
            SidecarTreeAccess.relativeSegments("DCIM/RawLens", "IMG_123")
        )
    }

    @Test fun `dcim tree nests under RawLens`() {
        assertEquals(
            listOf("RawLens", "IMG_123"),
            SidecarTreeAccess.relativeSegments("DCIM", "IMG_123")
        )
    }

    @Test fun `other folders receive the stem`() {
        assertEquals(
            listOf("IMG_123"),
            SidecarTreeAccess.relativeSegments("Download/RawLens", "IMG_123")
        )
    }
}
