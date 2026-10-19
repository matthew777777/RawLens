// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class PhotoViewfinderBoxTest {
    @Test fun tallPhoneSeatsFullWidthThreeByFour() {
        // 1080x2400 screen, 200px top island, 700px panel, 16px seals.
        val box = PhotoViewfinderBox.compute(
            rootW = 1080, rootH = 2400, topBarH = 200, panelH = 700, sealOverlap = 16
        )!!
        assertEquals(1080, box.width)
        assertEquals(1440, box.height)
        // Exact 3:4 with no side crop: cover == fit on this surface.
        assertEquals(0, box.width * 4 - box.height * 3)
        // Bottom-aligned: spare 92px sits below the top island, and the
        // picture tucks under the bottom panel by the corner radius.
        assertEquals(276, box.topMargin)
        assertEquals(2400 - 700 + 16, box.topMargin + box.height)
    }

    @Test fun shortScreenFallsBackToHeightConstrained() {
        val box = PhotoViewfinderBox.compute(
            rootW = 800, rootH = 1000, topBarH = 100, panelH = 700, sealOverlap = 16
        )!!
        assertEquals(232, box.height)
        assertEquals(174, box.width)
        assertEquals(0, box.width * 4 - box.height * 3)
        assertEquals(84, box.topMargin)
    }

    @Test fun missingMeasurementsReturnNull() {
        assertNull(PhotoViewfinderBox.compute(0, 2400, 200, 700, 16))
        assertNull(PhotoViewfinderBox.compute(1080, 0, 200, 700, 16))
        // No room between the islands yet: caller keeps full-bleed.
        assertNull(PhotoViewfinderBox.compute(1080, 800, 200, 700, 16))
    }

    @Test fun topMarginNeverNegative() {
        val box = PhotoViewfinderBox.compute(
            rootW = 1080, rootH = 2400, topBarH = 0, panelH = 700, sealOverlap = 16
        )!!
        // Spare room absorbs the missing island: still bottom-aligned.
        assertEquals(276, box.topMargin)
        assertEquals(0, box.width * 4 - box.height * 3)
    }
}
