// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Handler
import android.os.SystemClock
import android.util.Log

/**
 * Tap / press-and-hold focus and metering: the Open Camera continuous-picture
 * autofocus port (CameraController2.setFocusAndMeteringArea / autoFocus,
 * https://sourceforge.net/p/opencamera/code/ci/master/tree/app/src/main/java/net/sourceforge/opencamera/cameracontroller/CameraController2.java).
 *
 * A tap switches the repeating request to AF_MODE_AUTO with the tap region,
 * then sends one AF_TRIGGER_START; the AF state in each result drives the
 * lock / failure verdict (thresholds in [AutoFocusPolicy]). The lock is held
 * until an explicit release: timer, retap, double-tap/button reset, manual
 * focus, lens switch, or stop. Trigger semantics follow
 * https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AF_TRIGGER.
 *
 * Extracted verbatim from RawCameraController. All methods run on the camera
 * thread ([Host.handler]); the public entry [start] re-posts itself if not.
 */
internal class TouchFocus(private val host: Host) {

    /** Camera-side operations the state machine needs; implemented by the controller. */
    interface Host {
        val handler: Handler
        fun isOnCameraThread(): Boolean
        /** Device, session and one-shot target surface are all available. */
        fun canSendOneShot(): Boolean
        /** A still capture or capture sequence is executing. */
        fun captureBusy(): Boolean
        fun maxAfRegions(): Int
        fun afAvailableModes(): IntArray
        fun isAeMeteringSupported(): Boolean
        fun meteringRegion(viewX: Float, viewY: Float): MeteringRectangle?
        fun syncDevelopFocus(viewX: Float, viewY: Float)
        var afRegion: MeteringRectangle?
        var aeRegion: MeteringRectangle?
        var selectedFocusDistanceDiopters: Float?
        fun viewfinderMinSide(): Float
        /** Repeating-request rebuild that keeps the RAW ZSL ring. */
        fun updateRepeatingRequest()
        /**
         * One-shot capture of the current controls plus [trigger]
         * (CONTROL_AF_TRIGGER_*); [aeOnForDynamic] forces AE_MODE_ON while
         * PROGRAM custom AE is enabled. Caller checks [canSendOneShot] first.
         */
        @Throws(CameraAccessException::class)
        fun sendAfTrigger(trigger: Int, aeOnForDynamic: Boolean)
        fun cameraAccessReason(failure: CameraAccessException): String
        fun publishControls()
        fun publishPeakingActiveIfChanged()
        fun onState(state: String)
        fun onFocusLock(locked: Boolean, indefinite: Boolean, deadlineMs: Long)
        fun onMeteringReleased()
    }

    /** AF_MODE_AUTO + tap region held in the repeating request (scan or lock). */
    var active = false
        private set
    /** The scan reached a verdict (locked or failed); false while scanning. */
    var completed = false
        private set
    /** Long-press also freezes AE (CONTROL_AE_LOCK) until the same reset. */
    var aeLockIndefinite = false
        private set

    val scanning: Boolean get() = active && !completed

    private var timeout: Runnable? = null
    private var continuousReset: Runnable? = null
    private var pendingStart: Runnable? = null
    private var pendingIdleNudge: Runnable? = null
    private var startElapsedMs = 0L
    private var startSent = false
    /** True once a scan state proves START reached the HAL (INACTIVE filter). */
    private var scanSeen = false
    /** Last AF state logged while a tap scan/lock is in flight (transition-only trace). */
    private var lastLoggedState: Int? = null
    private var lastLoggedStateValid = false
    /** Last accepted tap: accidental-tap gate (time + same-spot mid-scan). */
    private var lastTapElapsedMs = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    /** True once FOCUSED_LOCKED is held (badge-worthy); false while scanning or on failure. */
    private var lockedSharp = false
    /** Set on tap-scan failure: the release drops the AF region (full-frame). */
    private var dropAfRegionOnRelease = false
    /** Long-press locks ignore the auto-release timer until double-tap. */
    private var lockIndefinite = false
    /** Elapsed-realtime expiry of a timed lock; 0 when indefinite or unlocked. */
    private var lockDeadlineMs = 0L

    fun start(viewX: Float, viewY: Float, indefinite: Boolean, updateAe: Boolean = true) {
        if (!host.isOnCameraThread()) {
            host.handler.post { start(viewX, viewY, indefinite, updateAe) }
            return
        }
        if (!host.canSendOneShot()) return
        if (host.captureBusy()) {
            // A tap landing mid-still would be ignored or fail: drop it instead
            // of installing regions the scan can never use.
            Log.i(LOG_TAG, "Touch focus ignored (capture in progress)")
            return
        }
        val tapNow = SystemClock.elapsedRealtime()
        if (!AutoFocusPolicy.shouldAcceptTap(
                tapNow, lastTapElapsedMs, lastTapX, lastTapY,
                viewX, viewY, host.viewfinderMinSide(),
                active && !completed
            )
        ) {
            Log.i(LOG_TAG, "Touch focus ignored (accidental tap)")
            return
        }
        lastTapElapsedMs = tapNow
        lastTapX = viewX
        lastTapY = viewY
        Log.i(
            LOG_TAG,
            "Touch focus tap @(${viewX.toInt()},${viewY.toInt()})" +
                (if (indefinite) " hold" else "") + (if (!updateAe) " af-only" else "")
        )
        val hasAfRegions = host.maxAfRegions() > 0
        if (!hasAfRegions) {
            // Matches Open Camera CameraController2.setFocusAndMeteringArea():
            // AE metering may still have been installed even when the camera
            // has no AF regions, and setFocusAndMeteringArea() reports no focus
            // area. A hold still freezes AE indefinitely here (AF simply has
            // nothing to lock).
            if (host.isAeMeteringSupported()) {
                host.aeRegion = host.meteringRegion(viewX, viewY)
                host.syncDevelopFocus(viewX, viewY)
                aeLockIndefinite = indefinite && updateAe && host.aeRegion != null
                if (host.aeRegion != null) host.updateRepeatingRequest()
                if (aeLockIndefinite) host.onState("AE LOCKED ∞") else host.onState("METERING EXPOSURE")
            } else {
                host.onState("FOCUS AREA NOT SUPPORTED")
            }
            return
        }

        val wasTouchActive = active
        dropAfRegionOnRelease = false
        removeContinuousReset()
        removeTimeout()
        removePendingStart()
        removeIdleNudge()
        // A CANCEL is only needed to interrupt a previous touch scan/lock.
        // First taps skip it entirely: one less capture + one less repeating
        // rebuild on the critical path.
        if (wasTouchActive) sendCancel()
        host.selectedFocusDistanceDiopters = null
        val region = host.meteringRegion(viewX, viewY) ?: run {
            host.publishPeakingActiveIfChanged()
            return
        }
        host.afRegion = region
        if (updateAe && host.isAeMeteringSupported()) {
            host.aeRegion = region
            host.syncDevelopFocus(viewX, viewY)
        }
        active = true
        completed = false
        startSent = false
        scanSeen = false
        lastLoggedStateValid = false
        lockedSharp = false
        lockIndefinite = indefinite
        aeLockIndefinite = indefinite && updateAe
        lockDeadlineMs = 0L
        host.onFocusLock(false, false, 0L)
        host.publishControls()

        if (!AutoFocusPolicy.supportsTouchTrigger(host.afAvailableModes())) {
            // Open Camera still applies the focus/metering rectangles in modes where an explicit
            // AUTO trigger is unavailable; continuous AF then keeps running with the new region.
            active = false
            host.updateRepeatingRequest()
            host.onState("FOCUS AREA SET")
            host.publishPeakingActiveIfChanged()
            return
        }

        try {
            host.updateRepeatingRequest()
            // Let the AUTO-mode repeating commit before START is queued so the
            // HAL cannot observe START while still in CONT_PICTURE.
            host.onState("FOCUSING")
            scheduleTimeout()
            pendingStart = Runnable {
                pendingStart = null
                if (active && !completed) sendStart()
            }.also { host.handler.postDelayed(it, START_SETTLE_MS) }
            host.publishPeakingActiveIfChanged()
        } catch (_: CameraAccessException) {
            abortOnError()
        }
    }

    /** Feed every preview/one-shot result's CONTROL_AF_STATE here. */
    fun onAfState(state: Int?) {
        // Transition-only trace: a scan logs a few lines, never 30 fps.
        if (active && (!lastLoggedStateValid || state != lastLoggedState)) {
            lastLoggedStateValid = true
            lastLoggedState = state
            val sinceStart = if (startSent)
                " +${SystemClock.elapsedRealtime() - startElapsedMs}ms" else ""
            Log.i(LOG_TAG, "Touch AF -> ${Camera2Names.afStateName(state)}$sinceStart")
        }
        // Late lock after a declared failure (HAL slower than the grace):
        // upgrade to the sharp lock instead of showing FOCUSED_LOCKED with a
        // FOCUS NOT LOCKED status.
        if (active && completed && !lockedSharp && AutoFocusPolicy.isLockSuccess(state)) {
            upgradeLateLock()
            return
        }
        if (!active || completed) return
        if (AutoFocusPolicy.isScanState(state)) scanSeen = true
        when (state) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                if (!AutoFocusPolicy.verdictReady(
                        startSent, scanSeen, SystemClock.elapsedRealtime() - startElapsedMs
                    )
                ) {
                    // Stale latch: the previous lock is still reported before
                    // the new scan starts. Stay in FOCUSING; the persisting
                    // lock completes on a later frame past the floor.
                    return
                }
                completed = true
                removeTimeout()
                removePendingStart()
                if (AutoFocusPolicy.isLockSuccess(state)) {
                    lockedSharp = true
                    publishSharpLock()
                    Log.i(LOG_TAG, "Touch focus locked" + if (lockIndefinite) " ∞" else "")
                } else {
                    completeUnlocked("FOCUS NOT LOCKED", "not-focused")
                    return
                }
                // Re-assert the repeating request once so the HAL keeps
                // IDLE/AUTO instead of drifting; circles stay until cleared.
                host.updateRepeatingRequest()
            }
            CaptureResult.CONTROL_AF_STATE_INACTIVE -> {
                // Some HALs return to INACTIVE after START without ever reporting
                // a locked state. Treat that as a failed lock once START is known
                // to have been sent, instead of hanging until the full timeout.
                if (AutoFocusPolicy.inactiveMeansFailure(
                        startSent, scanSeen, SystemClock.elapsedRealtime() - startElapsedMs
                    )
                ) {
                    completeUnlocked("FOCUS NOT LOCKED", "inactive")
                }
            }
        }
    }

    /**
     * Explicit release back to continuous AF (timer, reset, metering-mode
     * change). Also releases a hold-only AE lock on AF-region-less lenses.
     */
    fun releaseToContinuous() {
        if (!active && !aeLockIndefinite) return
        removeContinuousReset()
        removeTimeout()
        removePendingStart()
        startSent = false
        scanSeen = false
        lastLoggedStateValid = false
        // Back to continuous: the next tap always refocuses.
        lastTapElapsedMs = 0L
        // Clear before the CANCEL + repeating rebuild below so both carry
        // AE_LOCK=false and the HAL actually releases a held exposure lock.
        aeLockIndefinite = false
        if (dropAfRegionOnRelease) {
            // The tap point just failed to lock (corner/sky): resume
            // full-frame continuous instead of biasing it there forever.
            dropAfRegionOnRelease = false
            host.afRegion = null
            Log.i(LOG_TAG, "Failed tap region dropped; full-frame continuous")
        }
        if (host.canSendOneShot()) {
            try {
                host.sendAfTrigger(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, aeOnForDynamic = true)
            } catch (failure: CameraAccessException) {
                Log.w(LOG_TAG, "Open Camera AF cancel failed: ${host.cameraAccessReason(failure)}")
            }
        }
        active = false
        completed = false
        lockedSharp = false
        lockIndefinite = false
        lockDeadlineMs = 0L
        host.onFocusLock(false, false, 0L)
        host.updateRepeatingRequest()
        // UI-only notification: clear the AF/AE target circles when the explicit
        // touch-focus release completes. This does not alter Camera2 AF behavior.
        host.onMeteringReleased()
        host.onState("CONTINUOUS AF")
        Log.i(LOG_TAG, "AF back to continuous")
        host.publishPeakingActiveIfChanged()
        nudgeIdleIfNeeded()
    }

    /** Manual-reset path: the next tap always refocuses. */
    fun rearmTapGate() {
        lastTapElapsedMs = 0L
    }

    /** Reset with no touch scan active: drops a hold-only AE lock flag. */
    fun clearAeLock() {
        aeLockIndefinite = false
    }

    /**
     * Manual focus takes over: drop the held touch lock silently (no CANCEL
     * needed, AF_MODE_OFF wins in the controller's applyCameraControls).
     */
    fun dropForManualFocus() {
        if (!active) return
        removeContinuousReset()
        removeTimeout()
        removePendingStart()
        startSent = false
        scanSeen = false
        lockedSharp = false
        lockIndefinite = false
        aeLockIndefinite = false
        lockDeadlineMs = 0L
        host.onFocusLock(false, false, 0L)
        active = false
        completed = false
    }

    /** Camera stop: forget all touch state (timers via [cancelTimers]). */
    fun resetForStop() {
        active = false
        completed = false
        dropAfRegionOnRelease = false
        startSent = false
        scanSeen = false
        lastLoggedStateValid = false
        lastTapElapsedMs = 0L
        lockedSharp = false
        lockIndefinite = false
        aeLockIndefinite = false
        lockDeadlineMs = 0L
    }

    fun cancelTimers() {
        removeTimeout()
        removeContinuousReset()
        removePendingStart()
        removeIdleNudge()
    }

    /**
     * AUTO-only lenses (no continuous mode advertising): idle parks the focus
     * at the last position forever, so one full-frame scan stands in for
     * continuous AF at session start and after every touch release. The result
     * is ignored (the touch handler is gated on touch state); the lens moves
     * and holds. Never fires on continuous-capable lenses.
     */
    fun nudgeIdleIfNeeded() {
        if (!AutoFocusPolicy.needsIdleScanNudge(host.afAvailableModes())) return
        removeIdleNudge()
        pendingIdleNudge = Runnable {
            pendingIdleNudge = null
            if (active || host.selectedFocusDistanceDiopters != null) return@Runnable
            if (host.captureBusy()) return@Runnable
            sendIdleStart()
        }.also { host.handler.postDelayed(it, IDLE_NUDGE_DELAY_MS) }
    }

    private fun sendStart() {
        if (!host.canSendOneShot()) return
        if (host.captureBusy()) {
            // START mid-still is ignored or fails: wait out the capture. The
            // touch timeout bounds this; it aborts START once completed.
            Log.i(LOG_TAG, "AF START deferred (capture in progress)")
            removePendingStart()
            pendingStart = Runnable {
                pendingStart = null
                if (active && !completed) sendStart()
            }.also { host.handler.postDelayed(it, START_RETRY_MS) }
            return
        }
        try {
            startSent = true
            startElapsedMs = SystemClock.elapsedRealtime()
            host.sendAfTrigger(CameraMetadata.CONTROL_AF_TRIGGER_START, aeOnForDynamic = true)
            Log.i(LOG_TAG, "AF START sent")
        } catch (failure: CameraAccessException) {
            Log.w(LOG_TAG, "AF START failed: ${host.cameraAccessReason(failure)}")
            abortOnError()
        }
    }

    /** Single CANCEL capture with no repeating rebuild; caller re-asserts state once. */
    private fun sendCancel() {
        if (!host.canSendOneShot()) return
        try {
            host.sendAfTrigger(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, aeOnForDynamic = false)
        } catch (_: CameraAccessException) {
        }
    }

    private fun sendIdleStart() {
        if (!host.canSendOneShot()) return
        try {
            host.sendAfTrigger(CameraMetadata.CONTROL_AF_TRIGGER_START, aeOnForDynamic = true)
            Log.i(LOG_TAG, "Idle AF scan (AUTO-only lens)")
        } catch (failure: CameraAccessException) {
            Log.w(LOG_TAG, "Idle AF scan failed: ${host.cameraAccessReason(failure)}")
        }
    }

    /** Clears every touch-focus/lock flag after a failed AF request and restores idle AF. */
    private fun abortOnError() {
        active = false
        startSent = false
        scanSeen = false
        lockedSharp = false
        lockIndefinite = false
        aeLockIndefinite = false
        lockDeadlineMs = 0L
        host.onFocusLock(false, false, 0L)
        host.updateRepeatingRequest()
        host.onState("FOCUS ERROR")
        host.publishPeakingActiveIfChanged()
    }

    /**
     * FOCUSED_LOCKED that arrived after the failure verdict (slow HAL beaten by
     * the INACTIVE grace or the scan timeout). Promotes the hold to a real
     * lock with its full timer instead of leaving a contradictory status.
     */
    private fun upgradeLateLock() {
        lockedSharp = true
        // The region did lock after all: keep it for the continuous tail.
        dropAfRegionOnRelease = false
        removeContinuousReset()
        publishSharpLock()
        Log.i(LOG_TAG, "Touch focus late lock (upgraded)")
        host.updateRepeatingRequest()
    }

    /**
     * Sharp lock reached: an indefinite hold has no deadline; a tap lock
     * arms the release back to continuous after [AutoFocusPolicy.LOCK_HOLD_MS].
     */
    private fun publishSharpLock() {
        if (lockIndefinite) {
            lockDeadlineMs = 0L
            host.onState(if (aeLockIndefinite) "AE/AF LOCKED ∞" else "FOCUS LOCKED ∞")
        } else {
            lockDeadlineMs = SystemClock.elapsedRealtime() + AutoFocusPolicy.LOCK_HOLD_MS
            scheduleContinuousReset()
            host.onState("FOCUS LOCKED")
        }
        host.onFocusLock(true, lockIndefinite, lockDeadlineMs)
    }

    /**
     * Failed scan: brief hold so FOCUS NOT LOCKED reads, then fast release to
     * continuous. A blurry miss must never pin AUTO for the full lock hold.
     */
    private fun completeUnlocked(status: String, reason: String) {
        completed = true
        startSent = false
        lockedSharp = false
        removeTimeout()
        removePendingStart()
        host.onState(status)
        Log.i(LOG_TAG, "Touch focus failed ($reason)")
        // A failed indefinite hold keeps the held AE lock (no badge, no timer):
        // the user asked to freeze exposure, AF just missed. Release stays manual
        // (double-tap / reset / retap / manual / lens / stop).
        if (lockIndefinite) {
            // The hold is still in force (AE frozen, AF parked): show the ∞
            // badge so the user sees the lock took even when AF missed.
            host.onFocusLock(true, true, 0L)
            host.updateRepeatingRequest()
            return
        }
        lockDeadlineMs = SystemClock.elapsedRealtime() + AutoFocusPolicy.FAILURE_HOLD_MS
        // The point failed to lock: don't let continuous inherit the bias.
        dropAfRegionOnRelease = true
        scheduleContinuousReset(AutoFocusPolicy.FAILURE_HOLD_MS)
        host.updateRepeatingRequest()
    }

    private fun scheduleTimeout() {
        removeTimeout()
        timeout = Runnable {
            timeout = null
            if (active && !completed) completeUnlocked("FOCUS NOT LOCKED", "timeout")
        }.also { host.handler.postDelayed(it, AutoFocusPolicy.AUTOFOCUS_TIMEOUT_MS) }
    }

    private fun scheduleContinuousReset(delayMs: Long = AutoFocusPolicy.LOCK_HOLD_MS) {
        removeContinuousReset()
        continuousReset = Runnable {
            continuousReset = null
            releaseToContinuous()
        }.also { host.handler.postDelayed(it, delayMs) }
    }

    private fun removeContinuousReset() {
        continuousReset?.let(host.handler::removeCallbacks)
        continuousReset = null
    }

    private fun removeTimeout() {
        timeout?.let(host.handler::removeCallbacks)
        timeout = null
    }

    private fun removePendingStart() {
        pendingStart?.let(host.handler::removeCallbacks)
        pendingStart = null
    }

    private fun removeIdleNudge() {
        pendingIdleNudge?.let(host.handler::removeCallbacks)
        pendingIdleNudge = null
    }

    private companion object {
        const val LOG_TAG = "RawLensCamera"
        /** Settle before the idle-AF nudge scan on AUTO-only lenses. */
        const val IDLE_NUDGE_DELAY_MS = 500L
        /** Settle between the AUTO repeating commit and the one-shot AF START. */
        const val START_SETTLE_MS = 60L
        /** Retry delay when START is deferred because a still is executing. */
        const val START_RETRY_MS = 400L
    }
}
