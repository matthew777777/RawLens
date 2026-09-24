// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class RawPreviewGeometryTest {
    @Test fun `portrait rotation and mirroring share metering coordinates`() {
        assertEquals(0.25f to 0.75f, RawPreviewGeometry.sensorPoint(0.25f, 0.25f, 90, false))
        assertEquals(0.25f to 0.25f, RawPreviewGeometry.sensorPoint(0.25f, 0.25f, 90, true))
        assertEquals(0.75f to 0.25f, RawPreviewGeometry.sensorPoint(0.25f, 0.25f, 270, false))
        assertEquals(0.75f to 0.75f, RawPreviewGeometry.sensorPoint(0.25f, 0.25f, 180, false))
    }

    @Test fun `all CFA layouts map to four distinct sensor sites`() {
        for (cfa in 0..3) {
            val channels = RawPreviewGeometry.channels(cfa)
            assertEquals(4, channels.size)
            assertEquals(listOf(0, 1, 2, 3), channels.sorted())
        }
    }

    @Test fun `quad grid holds the long edge budget with an even step`() {
        // 12 MP reference sensor: 1080 -> step 4 (1020x765), 640 -> step 8.
        assertEquals(
            VfQuadGeometry(0, 0, 1020, 765, 4),
            RawPreviewGeometry.quadGeometry(4080, 3060, null, 1080)
        )
        assertEquals(
            VfQuadGeometry(0, 0, 510, 382, 8),
            RawPreviewGeometry.quadGeometry(4080, 3060, null, 640)
        )
        // Under budget the step floors at 2 (never 1: quad alignment).
        assertEquals(
            VfQuadGeometry(0, 0, 320, 240, 2),
            RawPreviewGeometry.quadGeometry(640, 480, null, 1080)
        )
    }

    @Test fun `odd crop origins round down to even`() {
        // Unit tests mock Android constructors: assign the plain fields.
        val crop = android.graphics.Rect().apply {
            left = 1; top = 1; right = 101; bottom = 101
        }
        val geo = RawPreviewGeometry.quadGeometry(4080, 3060, crop, 1080)
        assertEquals(VfQuadGeometry(0, 0, 50, 50, 2), geo)
    }

    @Test fun `cpu cap only bites above 640`() {
        val full = RawPreviewGeometry.quadGeometry(4080, 3060, null, 1080)
        val capped = RawPreviewGeometry.quadGeometry(
            4080, 3060, null, minOf(1080, VfResolution.CPU_MAX)
        )
        assertEquals(VfQuadGeometry(0, 0, 510, 382, 8), capped)
        assertEquals(
            RawPreviewGeometry.quadGeometry(4080, 3060, null, 480),
            RawPreviewGeometry.quadGeometry(4080, 3060, null, minOf(480, VfResolution.CPU_MAX))
        )
        assertEquals(1020, full.width)
    }
}
