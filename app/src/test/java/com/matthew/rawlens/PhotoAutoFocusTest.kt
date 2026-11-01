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

class PhotoAutoFocusTest {
    private class Delayed(val runnable: Runnable, val delayMs: Long)

    private class FakeHost : PhotoAutoFocus.Host {
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
        val triggers = mutableListOf<Pair<Int, Long>>()
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
        override fun isAeLockSupported() = true
        override fun isAeMeteringSupported() = aeSupported
        override fun meteringRegion(viewX: Float, viewY: Float): MeteringRectangle = region
        override fun syncDevelopFocus(viewX: Float, viewY: Float) {}
        override var afRegion: MeteringRectangle? = null
        override var aeRegion: MeteringRectangle? = null
        override var selectedFocusDistanceDiopters: Float? = null
        override fun updateRepeatingRequest() { repeating++ }
        override fun sendAfTrigger(trigger: Int, runId: Long) {
            triggers += trigger to runId
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
    private lateinit var af: PhotoAutoFocus
    @Before fun setup() { host = FakeHost(); af = PhotoAutoFocus(host) }
    private val auto = CameraMetadata.CONTROL_AF_MODE_AUTO
    private val focused = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
    private val missed = CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
    private fun tap(hold: Boolean = false): Long {
        af.start(100f, 100f, hold)
        return host.triggers.last().second
    }
    private fun result(frame: Long, state: Int?, mode: Int? = auto) = af.onResult(frame, mode, state)

    @Test fun idlePrefersContinuousPictureAndTapPrefersAuto() {
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            PhotoAutoFocus.idleMode(host.modes))
        assertEquals(auto, PhotoAutoFocus.tapMode(host.modes))
        assertEquals(CameraMetadata.CONTROL_AF_MODE_OFF, PhotoAutoFocus.tapMode(intArrayOf()))
    }
    @Test fun tapSubmitsCancelThenStartWithoutDelay() {
        tap()
        assertEquals(listOf(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL,
            CameraMetadata.CONTROL_AF_TRIGGER_START), host.triggers.map { it.first })
        assertEquals(auto, af.requestedMode)
        assertTrue(af.scanning)
        assertEquals(1, host.delayed.size)
    }
    @Test fun verdictCannotCompleteBeforeStartAcknowledgement() {
        tap(); result(100, focused)
        assertTrue(af.scanning)
    }
    @Test fun oldFramesAndWrongModeCannotCompleteNewScan() {
        val run = tap(); af.onStartFrame(run, 100)
        result(99, focused); result(100, focused, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        assertTrue(af.scanning)
        result(101, focused)
        assertTrue(af.completed)
    }
    @Test fun instantLockDoesNotRequireActiveScanOrDelay() {
        val run = tap(); af.onStartFrame(run, 100); result(100, focused)
        assertEquals("FOCUS LOCKED", host.states.last())
        assertTrue(host.hasPending(PhotoAutoFocus.LOCK_HOLD_MS))
        assertFalse(host.hasPending(PhotoAutoFocus.SCAN_TIMEOUT_MS))
    }
    @Test fun passiveFocusDoesNotClaimTapLock() {
        val run = tap(); af.onStartFrame(run, 100)
        result(100, CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)
        assertTrue(af.scanning)
        assertFalse(host.locks.last().first)
    }
    @Test fun outOfOrderResultCannotReplaceNewerScanState() {
        val run = tap(); af.onStartFrame(run, 100)
        result(102, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN)
        result(101, focused)
        assertTrue(af.scanning)
        result(103, focused)
        assertTrue(af.completed)
    }
    @Test fun retapRejectsOldCommandAcknowledgementAndResults() {
        val old = tap(); af.onStartFrame(old, 100)
        val new = tap(); af.onStartFrame(old, 110); result(111, focused)
        assertTrue(af.scanning)
        af.onStartFrame(new, 120); result(119, focused)
        assertTrue(af.scanning)
        result(120, focused); assertTrue(af.completed)
    }
    @Test fun tapLockReleasesAtTenSecondsAndClearsRegion() {
        val run = tap(); af.onStartFrame(run, 100); result(100, focused)
        host.fire(PhotoAutoFocus.LOCK_HOLD_MS)
        assertFalse(af.active); assertNull(host.afRegion)
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, host.triggers.last().first)
        assertEquals(1, host.released)
    }
    @Test fun holdHasNoReleaseTimerAndResetUnlocksAe() {
        val run = tap(true); af.onStartFrame(run, 100); result(100, focused)
        assertEquals("AE/AF LOCKED ∞", host.states.last())
        assertTrue(host.delayed.isEmpty()); assertTrue(af.aeLockIndefinite)
        af.releaseToContinuous()
        assertFalse(af.active); assertFalse(af.aeLockIndefinite)
    }
    @Test fun failedHoldDoesNotShowSuccessfulFocusBadge() {
        val run = tap(true); af.onStartFrame(run, 100); result(100, missed)
        assertEquals("FOCUS NOT LOCKED", host.states.last())
        assertFalse(host.locks.last().first)
        assertTrue(af.aeLockIndefinite)
        assertTrue(host.delayed.isEmpty())
    }
    @Test fun failureReleasesAfterExistingShortHold() {
        val run = tap(); af.onStartFrame(run, 100); result(100, missed)
        host.fire(PhotoAutoFocus.FAILURE_HOLD_MS)
        assertFalse(af.active); assertNull(host.afRegion)
    }
    @Test fun missingMetadataTimesOutWithoutInventingDeviceVerdict() {
        tap(); host.fire(PhotoAutoFocus.SCAN_TIMEOUT_MS)
        assertEquals("FOCUS NOT LOCKED", host.states.last())
        host.fire(PhotoAutoFocus.FAILURE_HOLD_MS)
        assertFalse(af.active)
        tap(); assertEquals(auto, af.requestedMode)
    }
    @Test fun regionlessAutofocusStillTriggersWholeFrame() {
        host.afRegions = 0; tap()
        assertTrue(af.active); assertNull(host.afRegion); assertEquals(2, host.triggers.size)
    }
    @Test fun fixedFocusMetersWithoutSendingAfCommands() {
        host.modes = intArrayOf(CameraMetadata.CONTROL_AF_MODE_OFF)
        af.start(100f, 100f, true)
        assertTrue(host.triggers.isEmpty()); assertFalse(af.active)
        assertTrue(af.aeLockIndefinite)
    }
    @Test fun manualFocusAndStopInvalidateCommandsAndTimers() {
        val run = tap(); af.dropForManualFocus(); af.onStartFrame(run, 100); result(100, focused)
        assertFalse(af.active); assertTrue(host.delayed.isEmpty())
        tap(); af.resetForStop()
        assertFalse(af.active); assertTrue(host.delayed.isEmpty())
    }
    @Test fun tapDuringStillLeavesExistingFocusUntouched() {
        host.busy = true; af.start(100f, 100f, false)
        assertFalse(af.active); assertTrue(host.triggers.isEmpty())
    }
    @Test fun oldCommandFailureCannotFailNewTap() {
        val old = tap(); tap(); af.onCommandFailed(old)
        assertTrue(af.scanning)
        af.onCommandFailed(host.triggers.last().second)
        assertEquals("FOCUS NOT LOCKED", host.states.last())
    }
    @Test fun sessionStartupCancelsInheritedContinuousLockWithoutModeKick() {
        af.onSessionReady()
        assertEquals(listOf(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL), host.triggers.map { it.first })
        assertFalse(af.active); assertTrue(host.delayed.isEmpty())
    }
    @Test fun autoOnlyLensGetsOneStartupScan() {
        host.modes = intArrayOf(auto)
        af.onSessionReady()
        assertEquals(listOf(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL,
            CameraMetadata.CONTROL_AF_TRIGGER_START), host.triggers.map { it.first })
        assertFalse(af.active)
    }
    @Test fun timeoutCancelsSweepBeforeFailedIndefiniteHold() {
        tap(true); host.fire(PhotoAutoFocus.SCAN_TIMEOUT_MS)
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, host.triggers.last().first)
        assertTrue(af.completed); assertTrue(host.delayed.isEmpty())
        assertFalse(host.locks.last().first)
    }
    @Test fun afOnlyHoldDoesNotClaimAeLock() {
        af.start(100f, 100f, true, updateAe = false)
        val run = host.triggers.last().second
        af.onStartFrame(run, 100); result(100, focused)
        assertEquals("FOCUS LOCKED ∞", host.states.last())
        assertFalse(af.aeLockIndefinite)
    }

    @Test fun sessionStartupDoesNotTriggerUnderManualFocus() {
        host.selectedFocusDistanceDiopters = 2f
        af.onSessionReady()
        assertTrue(host.triggers.isEmpty())
    }

}
