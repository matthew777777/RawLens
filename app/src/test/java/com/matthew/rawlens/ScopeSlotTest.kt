// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotation regression: the scope reposition used to be skipped entirely when
 * the orientation event landed before layout (width/height still 0), so a
 * quarter turn parked the histogram behind the capture panel until the next
 * rotation. Seating now always runs, falling back to the fixed layout slot.
 */
class ScopeSlotTest {
    @Test fun `portrait needs no rotation inset`() {
        assertEquals(248, ScopeSlot.bottomMarginPx(false, 124, 64, 248))
    }

    @Test fun `quarter turn lifts by half the width-height difference`() {
        // 124x64 slot: the rotated scope's visual height equals its width,
        // so it needs (124 - 64) / 2 = 30px of extra lift to clear the panel.
        assertEquals(278, ScopeSlot.bottomMarginPx(true, 124, 64, 248))
    }

    @Test fun `quarter turn with square scope needs no inset`() {
        assertEquals(248, ScopeSlot.bottomMarginPx(true, 64, 64, 248))
    }

    @Test fun `recompute is idempotent and never accumulates`() {
        // Same inputs always give the same margin: repeated rotations pass
        // the base through again instead of adding to the previous margin.
        val first = ScopeSlot.bottomMarginPx(true, 124, 64, 248)
        val second = ScopeSlot.bottomMarginPx(true, 124, 64, 248)
        assertEquals(first, second)
        assertEquals(278, second)
    }

    @Test fun `measured size wins over layout size`() {
        assertEquals(124, ScopeSlot.sizePx(measuredPx = 124, layoutPx = 124))
        assertEquals(200, ScopeSlot.sizePx(measuredPx = 200, layoutPx = 124))
    }

    @Test fun `unmeasured view falls back to layout slot`() {
        assertEquals(124, ScopeSlot.sizePx(measuredPx = 0, layoutPx = 124))
    }

    @Test fun `match-parent layout size never leaks through`() {
        assertEquals(0, ScopeSlot.sizePx(measuredPx = 0, layoutPx = -1))
        assertEquals(0, ScopeSlot.sizePx(measuredPx = 0, layoutPx = 0))
    }

    @Test fun `seat always clears the panel`() {
        // Regression: the old fixed 150dp video override sat inside the
        // 232dp-minimum panel, hiding the scope behind it (portrait) or
        // peeking out "cut" (landscape). The seat base is the measured panel
        // plus the gap, so the margin can never sink into the panel span.
        val portrait = ScopeSlot.bottomMarginPx(false, 124, 64, 232 + 16)
        val landscape = ScopeSlot.bottomMarginPx(true, 124, 64, 232 + 16)
        assertTrue(portrait >= 232 + 16)
        assertTrue(landscape >= 232 + 16)
    }

    @Test fun `stacked video seat clears ev row when rotated`() {
        // DirectLog: scope floats above the 68dp EV slider row (+12 gap);
        // the rotation inset keeps the rotated visual bottom clear of it.
        val margin = ScopeSlot.bottomMarginPx(true, 124, 64, 232 + 16 + 68 + 12)
        assertEquals(232 + 16 + 68 + 12 + 30, margin)
        // 640px screen: rotated visual bottom = layout bottom edge pulled up
        // by the margin, extending half the scope width below its center.
        val visualBottom = 640 - margin + (124 - 64) / 2
        val sliderTop = 640 - 232 - 16 - 68
        assertTrue("$visualBottom <= $sliderTop", visualBottom <= sliderTop)
    }
}
