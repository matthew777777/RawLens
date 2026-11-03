// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoManualPolicyTest {
    @Test fun manualCadenceUsesRequestedRateAndStreamLimits() {
        assertEquals(33_333_333L, VideoManualPolicy.frameDuration(30, 10_000_000, 0, 1_000_000_000))
        assertEquals(16_666_666L, VideoManualPolicy.frameDuration(60, 10_000_000, 0, null))
        assertEquals(50_000_000L, VideoManualPolicy.frameDuration(30, 10_000_000, 50_000_000, 1_000_000_000))
        assertEquals(40_000_000L, VideoManualPolicy.frameDuration(30, 40_000_000, 0, 1_000_000_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleManualCadenceIsRejected() {
        VideoManualPolicy.frameDuration(30, 10_000_000, 50_000_000, 40_000_000)
    }

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
    fun calibratedWhiteIsNeutralAfterGainsAndTransform() {
        // An ideal XYZ sensor makes the expected neutral independent of a phone vendor.
        val identity = doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        for (k in listOf(2000,3200,5500,6500,10000)) {
            val setting = CameraWhiteBalance.calculate(identity,null,null,null,null,null,k)!!
            val white = RawWhiteBalanceFallback.Locus.planckXyz(k.toDouble())
            val g = setting.gains
            val rgb = Matrix3(setting.transform) * Vec3(white.x*g[0],white.y*g[1],white.z*g[3])
            assertEquals(rgb.x,rgb.y,0.0001)
            assertEquals(rgb.y,rgb.z,0.0001)
            assertTrue(g.all { it.isFinite() && it >= 1f })
        }
    }

    @Test
    fun singularCalibrationCannotSupplyKelvin() {
        assertEquals(null,CameraWhiteBalance.calculate(DoubleArray(9),null,null,null,null,null,5500))
    }

    @Test
    fun calibratedTemperatureChangesBlueGain() {
        val identity = doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        val warm = CameraWhiteBalance.calculate(identity,null,null,null,null,null,2000)!!
        val cold = CameraWhiteBalance.calculate(identity,null,null,null,null,null,10000)!!
        assertTrue(warm.gains[3] > cold.gains[3])
    }
}
