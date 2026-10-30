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
        // Bottom-aligned: the 92px spare is absorbed by growing the bottom
        // panel, and the picture tucks under it by the corner radius.
        assertEquals(92, box.spare)
        assertEquals(276, box.topMargin)
        assertEquals(2400 - 700 + 16, box.topMargin + box.height)
    }

    @Test fun shortScreenKeepsFullWidthInsteadOfSideGutters() {
        val box = PhotoViewfinderBox.compute(
            rootW = 800, rootH = 1000, topBarH = 100, panelH = 700, sealOverlap = 16
        )!!
        assertEquals(1067, box.height)
        assertEquals(800, box.width)
        assertTrue(kotlin.math.abs(box.width * 4 - box.height * 3) <= 2)
        assertEquals(0, box.topMargin)
        // No spare: the panel stays put and the picture extends behind chrome.
        assertEquals(0, box.spare)
    }

    @Test fun spareSpaceIsResolvedBeforePlacingPreview() {
        // Reproduce the screenshot's tall aspect ratio at several UI densities.
        for (naturalPanel in listOf(470, 560, 700)) {
            val initial = PhotoViewfinderBox.compute(931, 2048, 128, naturalPanel, 16)!!
            val panel = naturalPanel + initial.spare
            val settled = PhotoViewfinderBox.compute(931, 2048, 128, panel, 16)!!
            assertEquals(0, settled.spare)
            assertEquals(112, settled.topMargin)
            assertEquals(2048 - panel + 16, settled.topMargin + settled.height)
        }
    }

    @Test fun resizingReleasesPreviouslyAllocatedSpareSpace() {
        val naturalPanel = 560
        val tall = PhotoViewfinderBox.compute(930, 2400, 128, naturalPanel, 16)!!
        val short = PhotoViewfinderBox.compute(930, 2000, 128, naturalPanel, 16)!!
        assertTrue(tall.spare > short.spare)
        val settled = PhotoViewfinderBox.compute(
            930, 2000, 128, naturalPanel + short.spare, 16
        )!!
        assertEquals(112, settled.topMargin)
        assertEquals(0, settled.spare)
        assertEquals(930, settled.width)
    }

    @Test fun slightlyShortViewportStillReachesBothScreenEdges() {
        // Screenshot: the former height fit produced roughly 11px side gutters.
        val box = PhotoViewfinderBox.compute(946, 2048, 182, 710, 36)!!
        assertEquals(946, box.width)
        assertEquals(1262, box.height)
        assertEquals(112, box.topMargin)
        assertEquals(2048 - 710 + 36, box.topMargin + box.height)
        assertEquals(0, box.spare)
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
        assertEquals(292, box.spare)
        assertEquals(0, box.width * 4 - box.height * 3)
    }
}
