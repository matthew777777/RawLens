// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Render-starvation verdict pins (fake timestamps, no GL needed). */
class BguWatchdogTest {
    private val limit = 2500L
    private val now = 1_000_000L

    @Test fun backgroundGapIsHealthy() {
        // Both timestamps stale across a background gap: silence is no
        // camera, not starvation (the resume black-VF bug).
        assertFalse(renderStarveVerdict(now, 90_000L, 100_000L, 100_000L, limit))
    }

    @Test fun sessionRestartBeforeFirstLightIsHealthy() {
        // Fresh invalidate (zeros) with no offers yet: must not fail.
        assertFalse(renderStarveVerdict(now, 0L, 0L, 0L, limit))
    }

    @Test fun coldFirstFrameGetsGrace() {
        // First offer 40 ms ago, first present still in flight: healthy
        // (an immediate verdict here killed every fresh launch).
        assertFalse(renderStarveVerdict(now, now - 40L, now - 40L, 0L, limit))
    }

    @Test fun flowingOffersWithoutPresentIsStarved() {
        // Offers for 3 s with zero presents: genuinely stuck.
        assertTrue(renderStarveVerdict(now, now - 3000L, now - 100L, 0L, limit))
    }

    @Test fun stalledPresentsAreStarved() {
        assertTrue(renderStarveVerdict(now, now - 10_000L, now - 100L, now - 3000L, limit))
    }

    @Test fun healthyStreamIsHealthy() {
        assertFalse(renderStarveVerdict(now, now - 10_000L, now - 100L, now - 100L, limit))
    }
}
