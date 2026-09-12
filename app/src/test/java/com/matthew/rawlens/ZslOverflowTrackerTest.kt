// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZslOverflowTrackerTest {
    @Test
    fun burstOfOverflowsWithinStallWindowDoesNotFallBack() {
        // Regression: at 30 fps six dropped frames are only 200 ms. A brief stall
        // burst must drain-and-continue, never retire the stream.
        var now = 1_000L
        val tracker = ZslOverflowTracker(clockMs = { now })
        tracker.onPaired()
        repeat(60) {
            now += 33
            assertFalse("overflow at +${now - 1_000}ms", tracker.onOverflow())
        }
    }

    @Test
    fun stallPastLimitFallsBack() {
        var now = 0L
        val tracker = ZslOverflowTracker(clockMs = { now }, maxStallMs = 2_000L)
        tracker.onPaired()
        now += 1_999
        assertFalse(tracker.onOverflow())
        now += 2
        assertTrue(tracker.onOverflow())
    }

    @Test
    fun pairingResetsStallClock() {
        var now = 0L
        val tracker = ZslOverflowTracker(clockMs = { now }, maxStallMs = 1_000L)
        tracker.onPaired()
        now += 900
        tracker.onPaired()
        now += 900
        assertFalse(tracker.onOverflow())
    }

    @Test
    fun freshStreamResetsStallClock() {
        var now = 0L
        val tracker = ZslOverflowTracker(clockMs = { now }, maxStallMs = 100L)
        tracker.onPaired()
        now += 90
        tracker.reset()
        now += 90
        assertFalse(tracker.onOverflow())
    }
}
