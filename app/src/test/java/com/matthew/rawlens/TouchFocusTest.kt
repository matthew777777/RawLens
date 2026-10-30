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
        override var autoScanDead = false

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
    fun oldVerdictsDuringSettleCannotCancelNewTapTrigger() {
        for (oldState in listOf(
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        )) {
            setUp()
            tf.start(100f, 100f, indefinite = false)
            tf.onAfState(oldState, 2f)
            assertFalse("old verdict must not complete the new tap", tf.completed)
            assertEquals("FOCUSING", host.states.last())
            assertTrue("START remains queued", host.hasPending(60L))
            assertTrue(host.triggers.isEmpty())
            host.fire(60L)
            assertEquals(listOf(CameraMetadata.CONTROL_AF_TRIGGER_START to true), host.triggers)
            tf.onAfState(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, 2f)
            tf.onAfState(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, 3f)
            assertEquals("FOCUS LOCKED", host.states.last())
        }
    }

    @Test
    fun scanBeforeRestartCannotValidateStaleContinuousMiss() {
        host.autoScanDead = true
        tf.start(100f, 100f, indefinite = false)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, 5.95f)
        host.fire(60L)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, 5.95f)
        assertEquals("FOCUSING", host.states.last())
        assertTrue(host.hasPending(AutoFocusPolicy.AUTOFOCUS_TIMEOUT_MS))
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, 4f)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, 2f)
        assertEquals("FOCUS LOCKED", host.states.last())
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
    fun deadAutoScanRetriesTapAsContinuous() {
        // Xiaomi 14T Pro field log: ACTIVE_SCAN for 2 s with fd pinned.
        tapAndStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, 0.33f)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, 0.33f)
        assertTrue(host.autoScanDead)
        assertTrue(tf.continuousTap)
        assertFalse(tf.autoMode)
        assertFalse("no failure shown for the retried tap", host.states.contains("FOCUS NOT LOCKED"))

        host.fire(60L)
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL to true, host.triggers.last())
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, 0.33f)
        assertFalse("stale AUTO verdict ignored", host.states.contains("FOCUS NOT LOCKED"))
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, 1.96f)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, 6.89f)
        assertEquals("FOCUS LOCKED", host.states.last())
        assertEquals(2, host.triggers.size)
    }

    @Test
    fun autoScanThatMovesTheLensFailsNormally() {
        tapAndStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, 0.33f)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, 2.5f)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, 0.4f)
        assertEquals("FOCUS NOT LOCKED", host.states.last())
        assertFalse(host.autoScanDead)
    }

    @Test
    fun unknownFocusDistanceNeverConvictsAuto() {
        tapAndStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)
        assertEquals("FOCUS NOT LOCKED", host.states.last())
        assertFalse(host.autoScanDead)
    }

    @Test
    fun savedVerdictTapsRunContinuousFromTheStart() {
        host.autoScanDead = true
        tf.start(100f, 100f, indefinite = false)
        assertTrue(tf.continuousTap)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, 0.33f)
        assertFalse("idle verdict before the restart never locks", host.states.contains("FOCUS LOCKED"))
        host.fire(60L)
        assertEquals(listOf(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL to true), host.triggers)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, 1.0f)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, 2.0f)
        assertEquals("FOCUS NOT LOCKED", host.states.last())
        host.fire(AutoFocusPolicy.FAILURE_HOLD_MS)
        assertFalse(tf.continuousTap)
    }

    @Test
    fun continuousHoldLocksWithStart() {
        host.autoScanDead = true
        tapAndStart(indefinite = true)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, 1.0f)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, 2.0f)
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_START to true, host.triggers.last())
    }

    @Test
    fun sessionStartKickReplaysTouchRelease() {
        tf.kickContinuousAtSessionStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, 0.33f)
        host.fire(1500L)
        assertTrue("repeating switched to AUTO", tf.autoMode)
        assertEquals(1, host.repeating)
        assertTrue(host.triggers.isEmpty())

        host.fire(60L)
        assertEquals(listOf(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL to true), host.triggers)
        assertFalse("back to continuous", tf.autoMode)
        assertEquals(2, host.repeating)
        assertTrue(host.delayed.isEmpty())
    }

    @Test
    fun sessionKickOnlyOnContinuousLenses() {
        host.modes = intArrayOf(CameraMetadata.CONTROL_AF_MODE_AUTO)
        tf.kickContinuousAtSessionStart()
        assertFalse(host.hasPending(1500L))
    }

    @Test
    fun tapCancelsPendingSessionKick() {
        tf.kickContinuousAtSessionStart()
        tapAndStart()
        assertFalse(host.hasPending(1500L))
        assertEquals(listOf(CameraMetadata.CONTROL_AF_TRIGGER_START to true), host.triggers)
    }

    @Test
    fun tapMidKickTakesOverFromAuto() {
        tf.kickContinuousAtSessionStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, 0.33f)
        host.fire(1500L)
        tf.start(100f, 100f, indefinite = false)
        assertEquals("kick's CANCEL step dropped; only the tap's settle remains", 1, host.delayed.count { it.delayMs == 60L })
        host.fire(60L) // the tap's START settle
        assertEquals(listOf(CameraMetadata.CONTROL_AF_TRIGGER_START to true), host.triggers)
        assertTrue(tf.autoMode)
        assertTrue(host.delayed.none { it.delayMs == 60L })
    }

    @Test
    fun manualFocusAbandonsKickCancel() {
        tf.kickContinuousAtSessionStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, 0.33f)
        host.fire(1500L)
        host.selectedFocusDistanceDiopters = 2f
        host.fire(60L)
        assertTrue("no CANCEL under manual focus", host.triggers.isEmpty())
        assertFalse(tf.autoMode)
        assertEquals("restored repeating", 2, host.repeating)
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
    @Test fun startupDoesNotInterruptContinuousScanOrFocusedLens() {
        for (state in listOf(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)) {
            setUp()
            tf.kickContinuousAtSessionStart()
            tf.onAfState(state, 1.73f)
            host.fire(1500L)
            assertEquals(0, host.repeating)
            assertTrue(host.triggers.isEmpty())
            assertFalse(tf.autoMode)
        }
    }

    @Test fun startupDoesNotKickInactiveOrUnknownAutofocus() {
        for (state in listOf(null, CaptureResult.CONTROL_AF_STATE_INACTIVE)) {
            setUp()
            tf.kickContinuousAtSessionStart()
            tf.onAfState(state, 0.03f)
            host.fire(1500L)
            assertEquals(0, host.repeating)
            assertTrue(host.triggers.isEmpty())
        }
    }

    @Test fun startupDoesNotKickAfterScanEndsUnfocusedOrLensMoves() {
        tf.kickContinuousAtSessionStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, 0.33f)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, 0.33f)
        host.fire(1500L)
        assertEquals(0, host.repeating)
        setUp()
        tf.kickContinuousAtSessionStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, 0.33f)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, 1.5f)
        host.fire(1500L)
        assertEquals(0, host.repeating)
    }

    @Test fun scanDuringKickSettleSkipsCancelAndRestoresContinuous() {
        tf.kickContinuousAtSessionStart()
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, 0.33f)
        host.fire(1500L)
        assertTrue(tf.autoMode)
        tf.onAfState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, 0.33f)
        host.fire(60L)
        assertTrue(host.triggers.isEmpty())
        assertFalse(tf.autoMode)
        assertEquals(2, host.repeating)
    }

}
