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

    @Test fun `csv export maps window to camera frame`() {
        val tracker = CameraMotionTracker(Mockito.mock(Context::class.java))
        tracker.addSampleForTest(900L, 9f, 9f, 9f)
        tracker.addSampleForTest(1_000L, 0.1f, 0.2f, 0.3f)
        tracker.addSampleForTest(1_050L, 0.4f, 0.5f, 0.6f)
        tracker.addSampleForTest(2_000L, 9f, 9f, 9f)

        // Portrait back camera, SENSOR_ORIENTATION=90: (x,y,z) -> (y,x,-z).
        assertEquals(
            "timestamp_ns,x_rad_s,y_rad_s,z_rad_s\n" +
                "1000,0.2,0.1,-0.3\n" +
                "1050,0.5,0.4,-0.6\n",
            tracker.gyroCsvForFrame(
                timestampNanos = 1_000L,
                exposureNanos = 100L,
                rollingShutterSkewNanos = 50L,
                realtimeTimestamps = true,
                sensorOrientationDeg = 90,
                frontFacing = false
            )
        )
    }

    @Test fun `csv export is null when there is nothing to write`() {
        val tracker = CameraMotionTracker(Mockito.mock(Context::class.java))
        // No samples at all.
        assertEquals(
            null,
            tracker.gyroCsvForFrame(1_000L, 100L, 50L, true, 90, false)
        )
        // Samples exist but timestamps are not realtime: no live gyro domain,
        // so writing a file would fabricate synchronization.
        tracker.addSampleForTest(1_000L, 0.1f, 0.2f, 0.3f)
        assertEquals(
            null,
            tracker.gyroCsvForFrame(1_000L, 100L, 50L, false, 90, false)
        )
        // Samples exist but outside the window.
        assertEquals(
            null,
            tracker.gyroCsvForFrame(5_000L, 100L, 50L, true, 90, false)
        )
    }

    @Test fun `csv export fails fast on non-quarter-turn mount`() {
        val tracker = CameraMotionTracker(Mockito.mock(Context::class.java))
        tracker.addSampleForTest(1_000L, 0.1f, 0.2f, 0.3f)
        try {
            tracker.gyroCsvForFrame(1_000L, 100L, 50L, true, 45, false)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Callers treat this as skip-the-file, never as capture failure.
        }
    }

    @Test fun `diagnostics report listener state and ring depth`() {
        val tracker = CameraMotionTracker(Mockito.mock(Context::class.java))
        assertEquals(false, tracker.isRunning())
        assertEquals(0, tracker.bufferedSampleCount())
        tracker.addSampleForTest(1_000L, 0.1f, 0.2f, 0.3f)
        tracker.addSampleForTest(1_050L, 0.4f, 0.5f, 0.6f)
        assertEquals(2, tracker.bufferedSampleCount())
    }
}
