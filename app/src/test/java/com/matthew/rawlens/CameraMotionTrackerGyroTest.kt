// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class CameraMotionTrackerGyroTest {
    @Test fun `xyz window covers exposure plus skew and preserves axes`() {
        val tracker = CameraMotionTracker(Mockito.mock(Context::class.java))
        tracker.addSampleForTest(900L, 0.1f, 0f, 0f)
        tracker.addSampleForTest(1_000L, 0.1f, 0.2f, 0.3f)
        tracker.addSampleForTest(1_050L, 0.4f, 0.5f, 0.6f)
        tracker.addSampleForTest(2_000L, 9f, 9f, 9f)

        val window = tracker.gyroWindowForFrame(
            timestampNanos = 1_000L,
            exposureNanos = 100L,
            rollingShutterSkewNanos = 50L,
            realtimeTimestamps = true
        )

        assertEquals(
            listOf(
                GyroSample(1_000L, 0.1f, 0.2f, 0.3f),
                GyroSample(1_050L, 0.4f, 0.5f, 0.6f)
            ),
            window
        )
    }

    @Test fun `window is empty without realtime timestamps`() {
        val tracker = CameraMotionTracker(Mockito.mock(Context::class.java))
        tracker.addSampleForTest(1_000L, 0.1f, 0.2f, 0.3f)

        assertTrue(
            tracker.gyroWindowForFrame(1_000L, 100L, 50L, realtimeTimestamps = false).isEmpty()
        )
    }

    @Test fun `window is an immutable snapshot`() {
        val tracker = CameraMotionTracker(Mockito.mock(Context::class.java))
        tracker.addSampleForTest(1_000L, 0.1f, 0.2f, 0.3f)

        val first = tracker.gyroWindowForFrame(1_000L, 100L, 0L, realtimeTimestamps = true)
        tracker.addSampleForTest(1_050L, 0.4f, 0.5f, 0.6f)
        val second = tracker.gyroWindowForFrame(1_000L, 100L, 0L, realtimeTimestamps = true)

        assertEquals(1, first.size)
        assertEquals(2, second.size)
    }

    @Test fun `scalar motion scoring is unchanged without samples`() {
        val tracker = CameraMotionTracker(Mockito.mock(Context::class.java))

        assertEquals(0f, tracker.currentMotion())
        assertEquals(0f, tracker.motionForFrame(1_000L, 100L, 50L, realtimeTimestamps = true))
    }
}
