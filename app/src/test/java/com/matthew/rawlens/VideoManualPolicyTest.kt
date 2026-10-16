// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoManualPolicyTest {
    @Test
    fun locksDefaultOff() {
        val locks = VideoRecordLocks.load { false }
        assertFalse(locks.lockExposure)
        assertFalse(locks.lockWb)
        assertFalse(locks.lockFocus)
        assertEquals(VideoRecordLocks(), VideoRecordLocks.defaults())
    }

    @Test
    fun manualSnapshotWinsOverMetered() {
        val snap = VideoManualSnapshot(iso = 800, shutterNanos = 10_000_000L)
        assertTrue(VideoManualPolicy.wantsManualExposure(snap))
        assertEquals(800, VideoManualPolicy.resolveIso(snap.iso, 100, false))
        assertEquals(10_000_000L, VideoManualPolicy.resolveShutter(snap.shutterNanos, 33_000_000L, false))
    }

    @Test
    fun unlockedAutoYieldsNull() {
        val snap = VideoManualSnapshot()
        assertFalse(VideoManualPolicy.wantsManualExposure(snap))
        assertNull(VideoManualPolicy.resolveIso(null, 100, false))
        assertNull(VideoManualPolicy.resolveShutter(null, 10_000_000L, false))
        assertFalse(VideoManualPolicy.resolveAwbLock(null, false))
        assertFalse(VideoManualPolicy.resolveFocusLock(null, false))
    }

    @Test
    fun lockedTakeFreezesMeteredValues() {
        assertEquals(400, VideoManualPolicy.resolveIso(null, 400, true))
        assertEquals(16_000_000L, VideoManualPolicy.resolveShutter(null, 16_000_000L, true))
        assertTrue(VideoManualPolicy.resolveAwbLock(null, true))
        assertTrue(VideoManualPolicy.resolveFocusLock(null, true))
        // Manual still wins over the lock.
        assertEquals(800, VideoManualPolicy.resolveIso(800, 400, true))
        assertFalse(VideoManualPolicy.resolveAwbLock(5500, true))
        assertFalse(VideoManualPolicy.resolveFocusLock(1.5f, true))
    }

    @Test
    fun shutterClampsToFrameBudget() {
        // 30fps budget is ~33.3ms: a 1s photo shutter clamps to the frame.
        assertEquals(33_333_333L, VideoManualPolicy.clampShutter(1_000_000_000L, 100L, 1_000_000_000L, 30))
        // Inside range passes through.
        assertEquals(10_000_000L, VideoManualPolicy.clampShutter(10_000_000L, 100L, 1_000_000_000L, 30))
    }

    @Test
    fun wbGainsRampWithKelvin() {
        val warm = VideoManualPolicy.wbGainsForKelvin(2000)
        val cold = VideoManualPolicy.wbGainsForKelvin(10000)
        // Warmer (low K) boosts blue, cooler boosts red.
        assertTrue(warm[3] > cold[3])
        assertTrue(cold[0] > warm[0])
        assertEquals(1f, warm[1], 0f)
    }
}
