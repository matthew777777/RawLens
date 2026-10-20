// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

/**
 * JVM coverage for the touch-AF state machine behind [TouchFocus.Host].
 * Android stubs return defaults, so SystemClock reads 0: verdicts complete
 * through the scan-seen path ([AutoFocusPolicy.verdictReady]).
 */
class TouchFocusTest {
    private class Delayed(val runnable: Runnable, val delayMs: Long)

    private class FakeHost : TouchFocus.Host {
        val delayed = mutableListOf<Delayed>()
        override val handler: Handler = mock(Handler::class.java).also { h ->
            doAnswer { inv ->
                delayed += Delayed(inv.getArgument(0), inv.getArgument(1)); true
            }.`when`(h).postDelayed(any(Runnable::class.java), anyLong())
            doAnswer { inv ->
                val r = inv.getArgument<Runnable>(0); delayed.removeAll { it.runnable === r }; null
            }.`when`(h).removeCallbacks(any(Runnable::class.java))
        }
        var busy = false
        var afRegions = 1
        var aeSupported = true
        var modes = intArrayOf(
            CameraMetadata.CONTROL_AF_MODE_AUTO, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        val triggers = mutableListOf<Pair<Int, Boolean>>()
        val states = mutableListOf<String>()
        val locks = mutableListOf<Triple<Boolean, Boolean, Long>>()
        var repeating = 0
        var released = 0
        private val region = mock(MeteringRectangle::class.java)

        override fun isOnCameraThread() = true
        override fun canSendOneShot() = true
        override fun captureBusy() = busy
        override fun maxAfRegions() = afRegions
        override fun afAvailableModes() = modes
        override fun isAeMeteringSupported() = aeSupported
        override fun meteringRegion(viewX: Float, viewY: Float): MeteringRectangle = region
        override fun syncDevelopFocus(viewX: Float, viewY: Float) {}
        override var afRegion: MeteringRectangle? = null
        override var aeRegion: MeteringRectangle? = null
        override var selectedFocusDistanceDiopters: Float? = null
        override fun viewfinderMinSide() = 1000f
        override fun updateRepeatingRequest() { repeating++ }
        override fun sendAfTrigger(trigger: Int, aeOnForDynamic: Boolean) {
            triggers += trigger to aeOnForDynamic
        }
        override fun cameraAccessReason(failure: android.hardware.camera2.CameraAccessException) = "x"
        override fun publishControls() {}
        override fun publishPeakingActiveIfChanged() {}
        override fun onState(state: String) { states += state }
        override fun onFocusLock(locked: Boolean, indefinite: Boolean, deadlineMs: Long) {
            locks += Triple(locked, indefinite, deadlineMs)
        }
        override fun onMeteringReleased() { released++ }

        /** Runs and removes the first pending runnable posted with [delayMs]. */
        fun fire(delayMs: Long) {
            val d = delayed.first { it.delayMs == delayMs }
            delayed.remove(d)
            d.runnable.run()
        }
        fun hasPending(delayMs: Long) = delayed.any { it.delayMs == delayMs }
    }

    private lateinit var host: FakeHost
    private lateinit var tf: TouchFocus

    @Before
    fun setUp() {
        host = FakeHost()
        tf = TouchFocus(host)
    }

    private fun tapAndStart(indefinite: Boolean = false) {
        tf.start(100f, 100f, indefinite)
        host.fire(60L) // START settle
    }

    @Test
    fun tapLocksThenReleasesToContinuousAfterHold() {
        tapAndStart()
        assertTrue(tf.active)
        assertEquals(listOf(CameraMetadata.CONTROL_AF_TRIGGER_START to true), host.triggers)
        assertTrue(host.hasPending(AutoFocusPolicy.AUTOFOCUS_TIMEOUT_MS))

        tf.onAfState(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
        assertEquals("FOCUS LOCKED", host.states.last())
        assertEquals(Triple(true, false, AutoFocusPolicy.LOCK_HOLD_MS), host.locks.last())
        assertFalse("timeout cleared on verdict", host.hasPending(AutoFocusPolicy.AUTOFOCUS_TIMEOUT_MS))

        host.fire(AutoFocusPolicy.LOCK_HOLD_MS)
        assertFalse(tf.active)
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL to true, host.triggers.last())
        assertEquals("CONTINUOUS AF", host.states.last())
        assertEquals(1, host.released)
        assertTrue("tap region kept after a sharp lock", host.afRegion != null)
    }

    @Test
    fun failedScanReleasesFastAndDropsRegion() {
        tapAndStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)
        assertEquals("FOCUS NOT LOCKED", host.states.last())
        assertTrue(host.hasPending(AutoFocusPolicy.FAILURE_HOLD_MS))
        host.fire(AutoFocusPolicy.FAILURE_HOLD_MS)
        assertNull(host.afRegion)
        assertFalse(tf.active)
    }

    @Test
    fun lateLockAfterFailureUpgradesToSharpLock() {
        tapAndStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
        assertEquals("FOCUS LOCKED", host.states.last())
        assertFalse(host.hasPending(AutoFocusPolicy.FAILURE_HOLD_MS))
        assertTrue(host.hasPending(AutoFocusPolicy.LOCK_HOLD_MS))
        host.fire(AutoFocusPolicy.LOCK_HOLD_MS)
        assertTrue("upgraded lock keeps its region", host.afRegion != null)
    }

    @Test
    fun staleLockBeforeScanDoesNotComplete() {
        tapAndStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
        assertTrue(tf.scanning)
        assertEquals("FOCUSING", host.states.last())
    }

    @Test
    fun holdLocksIndefinitelyWithAeLock() {
        tapAndStart(indefinite = true)
        assertTrue(tf.aeLockIndefinite)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)
        assertEquals("AE/AF LOCKED ∞", host.states.last())
        assertEquals(Triple(true, true, 0L), host.locks.last())
        assertFalse(host.hasPending(AutoFocusPolicy.LOCK_HOLD_MS))
    }

    @Test
    fun timeoutWithoutVerdictFails() {
        tapAndStart()
        host.fire(AutoFocusPolicy.AUTOFOCUS_TIMEOUT_MS)
        assertEquals("FOCUS NOT LOCKED", host.states.last())
    }

    @Test
    fun retapCancelsPreviousScanWithoutDynamicAe() {
        tapAndStart()
        host.triggers.clear()
        tf.start(900f, 900f, indefinite = false)
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL to false, host.triggers.first())
    }

    @Test
    fun noAfRegionsMetersExposureOnly() {
        host.afRegions = 0
        tf.start(100f, 100f, indefinite = true)
        assertFalse(tf.active)
        assertTrue(tf.aeLockIndefinite)
        assertEquals("AE LOCKED ∞", host.states.last())
        assertTrue(host.triggers.isEmpty())
    }

    @Test
    fun noAutoModeSetsRegionWithoutTrigger() {
        host.modes = intArrayOf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        tf.start(100f, 100f, indefinite = false)
        assertFalse(tf.active)
        assertEquals("FOCUS AREA SET", host.states.last())
        assertTrue(host.triggers.isEmpty())
    }

    @Test
    fun tapDuringCaptureIsIgnored() {
        host.busy = true
        tf.start(100f, 100f, indefinite = false)
        assertFalse(tf.active)
        assertTrue(host.states.isEmpty())
    }

    @Test
    fun manualFocusDropsLockSilently() {
        tapAndStart()
        tf.dropForManualFocus()
        assertFalse(tf.active)
        assertFalse(tf.aeLockIndefinite)
        assertTrue(host.delayed.isEmpty())
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_START to true, host.triggers.single())
    }

    @Test
    fun autoOnlyLensGetsIdleNudge() {
        host.modes = intArrayOf(CameraMetadata.CONTROL_AF_MODE_AUTO)
        tf.nudgeIdleIfNeeded()
        host.fire(500L)
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_START to true, host.triggers.single())
    }

    @Test
    fun continuousLensGetsNoIdleNudge() {
        tf.nudgeIdleIfNeeded()
        assertTrue(host.delayed.isEmpty())
    }
}
