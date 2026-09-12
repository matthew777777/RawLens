// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawHistogramThrottleTest {
    @Test
    fun neverSampledSentinelSamplesImmediately() {
        // Regression: inlining `now - last < interval` overflows for Long.MIN_VALUE to a
        // negative delta, throttling every live frame until the first forced snapshot.
        assertTrue(RawHistogramThrottle.shouldSample(1_000L, Long.MIN_VALUE, 250L))
        assertTrue(RawHistogramThrottle.shouldSample(1_000L, Long.MIN_VALUE, 250L, force = false))
    }

    @Test
    fun forcedSnapshotBypassesThrottle() {
        assertTrue(RawHistogramThrottle.shouldSample(1_100L, 1_000L, 250L, force = true))
    }

    @Test
    fun liveFramesRespectInterval() {
        assertFalse(RawHistogramThrottle.shouldSample(1_100L, 1_000L, 250L))
        assertTrue(RawHistogramThrottle.shouldSample(1_250L, 1_000L, 250L))
        assertTrue(RawHistogramThrottle.shouldSample(2_000L, 1_000L, 250L))
    }

    @Test
    fun stallDetectorFallsBackWithoutRawFrames() {
        assertTrue(RawHistogramThrottle.isStalled(1_000L, Long.MIN_VALUE, 2_000L))
        assertFalse(RawHistogramThrottle.isStalled(1_500L, 1_000L, 2_000L))
        assertFalse(RawHistogramThrottle.isStalled(3_000L, 1_000L, 2_000L))
        assertTrue(RawHistogramThrottle.isStalled(3_001L, 1_000L, 2_000L))
    }
}
