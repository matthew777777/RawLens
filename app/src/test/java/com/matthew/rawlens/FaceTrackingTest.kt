// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceTrackingTest {
    private class FakeHost : FaceTracking.Host {
        var now = 1_000L
        var pushes = 0
        val published = ArrayList<List<FaceOverlayBox>>()
        override fun nowMs() = now
        override fun pushRegions() { pushes++ }
        override fun viewSize() = 1000 to 750
        override fun sensorOrientation() = 0
        override fun frontFacing() = false
        override fun publish(boxes: List<FaceOverlayBox>) { published += boxes }
    }

    private val active = SensorActiveArray(0, 0, 4000, 3000)
    private fun face(l: Int, t: Int = 1000, size: Int = 400) = HalFace(l, t, l + size, t + size, 90)

    private fun tracker(host: FakeHost) = FaceTracking(host, initialEnabled = true).also { it.resetForSession(5) }

    @Test fun firstFaceSetsRegionAndPushesOnce() {
        val host = FakeHost(); val t = tracker(host)
        t.onPreviewFaces(listOf(face(1000)), active, active)
        assertEquals(FaceRegion(1000, 1000, 1400, 1400), t.region)
        assertEquals(1, host.pushes)
        assertTrue(host.published.last().single().tracked)
    }

    @Test fun jitterWithinToleranceDoesNotPush() {
        val host = FakeHost(); val t = tracker(host)
        t.onPreviewFaces(listOf(face(1000)), active, active)
        host.now += 1_000
        t.onPreviewFaces(listOf(face(1000 + 80)), active, active)  // tolerance = 2% of 4000 = 80
        assertEquals(1, host.pushes)
        host.now += 1_000
        t.onPreviewFaces(listOf(face(1000 + 81)), active, active)
        assertEquals(2, host.pushes)
    }

    @Test fun movesInsideThePushIntervalAreThrottled() {
        val host = FakeHost(); val t = tracker(host)
        t.onPreviewFaces(listOf(face(1000)), active, active)
        host.now += FaceTracking.REGION_PUSH_MIN_INTERVAL_MS - 1
        t.onPreviewFaces(listOf(face(1500)), active, active)
        assertEquals(1, host.pushes)
        assertEquals(FaceRegion(1000, 1000, 1400, 1400), t.region)
    }

    @Test fun regionDropsOnlyAfterMissTolerance() {
        val host = FakeHost(); val t = tracker(host)
        t.onPreviewFaces(listOf(face(1000)), active, active)
        repeat(FaceTracking.MISS_TOLERANCE - 1) { t.onPreviewFaces(emptyList(), active, active) }
        assertEquals(1, host.pushes)
        t.onPreviewFaces(emptyList(), active, active)
        assertNull(t.region)
        assertEquals(2, host.pushes)
    }

    @Test fun faceOutsideCropClearsRegion() {
        val host = FakeHost(); val t = tracker(host)
        t.onPreviewFaces(listOf(face(1000)), active, active)
        t.onPreviewFaces(listOf(face(1000)), active, SensorActiveArray(2000, 0, 4000, 3000))
        assertNull(t.region)
        assertEquals(2, host.pushes)
    }

    @Test fun intersectMatchesAndroidRect() {
        assertEquals(FaceRegion(2000, 1000, 2400, 1400),
            FaceTracking.intersect(HalFace(1800, 1000, 2400, 1400, 1), SensorActiveArray(2000, 0, 4000, 3000)))
        // Touching edges do not overlap (Rect.intersect uses strict <).
        assertNull(FaceTracking.intersect(HalFace(1600, 1000, 2000, 1400, 1), SensorActiveArray(2000, 0, 4000, 3000)))
    }

    @Test fun rejectionLatchesForTheSession() {
        val host = FakeHost(); val t = tracker(host)
        assertEquals(true, t.requestedDetectFull())
        t.markRejected()
        assertNull(t.requestedDetectFull())
        assertFalse(t.isSupported())
        t.onPreviewFaces(listOf(face(1000)), active, active)
        assertNull(t.region)
        t.resetForSession(5)
        assertTrue(t.isSupported())
    }

    @Test fun disabledRequestsDetectOffAndIgnoresFaces() {
        val host = FakeHost(); val t = tracker(host)
        assertTrue(t.setEnabled(false))
        assertFalse(t.setEnabled(false))
        assertEquals(false, t.requestedDetectFull())
        t.onPreviewFaces(listOf(face(1000)), active, active)
        assertNull(t.region)
        assertEquals(0, host.pushes)
    }
}
