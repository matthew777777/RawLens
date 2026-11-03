// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class VideoResultWindowTest {
    @Test fun delayedExactResultReplacesNearbyFallback() {
        val window = VideoResultWindow<String>()
        window.offer(100, "previous")
        assertFalse(window.hasExact(110))
        assertEquals(VideoResultWindow.Match("previous", false), window.match(110, 10))
        window.offer(110, "exact")
        assertTrue(window.hasExact(110))
        assertEquals(VideoResultWindow.Match("exact", true), window.match(110, 0))
    }

    @Test fun futureResultsNeverColourAnOlderImage() {
        val window = VideoResultWindow<String>()
        window.offer(120, "future")
        assertNull(window.match(110, 50))
        window.offer(100, "past")
        assertEquals("past", window.match(110, 10)?.value)
    }

    @Test fun staleLimitIsInclusiveAndExpiresWhenCallbacksStop() {
        val window = VideoResultWindow<String>()
        window.offer(100, "past")
        assertNotNull(window.match(110, 10))
        assertNull(window.match(111, 10))
        assertNull(window.match(200, 10))
    }

    @Test fun exactOnlyRejectsEvenTheImmediatelyPreviousExposure() {
        val window = VideoResultWindow<String>()
        window.offer(100, "past")
        assertNull(window.match(101, 0))
        assertNotNull(window.match(100, 0))
    }

    @Test fun capacityAndResetDiscardOldTakeMetadata() {
        val window = VideoResultWindow<String>(2)
        window.offer(100, "one")
        window.offer(110, "two")
        window.offer(120, "three")
        assertNull(window.match(100, 0))
        assertEquals("two", window.match(110, 0)?.value)
        window.clear()
        assertNull(window.match(120, 100))
    }

    @Test fun fallbackBudgetTracksFpsAndHasAnAbsoluteCap() {
        assertEquals(33_333_333L, VideoResultWindow.maxAgeNanos(30))
        assertEquals(16_666_666L, VideoResultWindow.maxAgeNanos(60))
        assertEquals(50_000_000L, VideoResultWindow.maxAgeNanos(10))
    }
}
