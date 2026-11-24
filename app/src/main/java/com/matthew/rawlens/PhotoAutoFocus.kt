// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraMetadata.*
import android.hardware.camera2.params.MeteringRectangle
import android.os.Handler
import android.os.SystemClock

/** Camera-thread photo AF. See docs/refactor/photo-autofocus.md for the Camera2 contract. */
internal class PhotoAutoFocus(private val host: Host) {
    interface Host {
        val handler: Handler
        fun isOnCameraThread(): Boolean
        fun canSendOneShot(): Boolean
        fun captureBusy(): Boolean
        fun maxAfRegions(): Int
        fun afAvailableModes(): IntArray
        fun isAeLockSupported(): Boolean
        fun isAeMeteringSupported(): Boolean
        fun meteringRegion(viewX: Float, viewY: Float): MeteringRectangle?
        fun syncDevelopFocus(viewX: Float, viewY: Float)
        var afRegion: MeteringRectangle?
        var aeRegion: MeteringRectangle?
        var selectedFocusDistanceDiopters: Float?
        fun updateRepeatingRequest()
        fun sendAfTrigger(trigger: Int, runId: Long)
        fun cameraAccessReason(failure: CameraAccessException): String
        fun publishControls()
        fun publishPeakingActiveIfChanged()
        fun onState(state: String)
        fun onFocusLock(locked: Boolean, indefinite: Boolean, deadlineMs: Long)
        fun onMeteringReleased()
    }

    data class Command(val runId: Long, val trigger: Int)
    var active = false
        private set
    var completed = false
        private set
    var aeLockIndefinite = false
        private set
    val scanning get() = active && !completed
    private var focusLocked = false
    val holdingIndefinitely get() = active && indefinite && (scanning || focusLocked)
    var requestedMode = CONTROL_AF_MODE_OFF
        private set
    private var runId = 0L
    private var startFrame: Long? = null
    private var lastFrame = -1L
    private var indefinite = false
    private var timeout: Runnable? = null
    private var release: Runnable? = null
    private var lastState: Int? = null
    /** True while a tap lock is held as a manual distance (HAL ignored the CONT_PICTURE START). */
    private var manualHold = false
    val usesManualFocusFallback get() = manualHold
    private var focusAssistanceRequested = false
    val showsFocusAssistance get() = active && focusAssistanceRequested
    /** AF-C point mode: a tap moves the continuous-AF region instead of running a one-shot lock. */
    var continuousPoint = false
    /** True while a tapped region is steering continuous AF (cleared by reset / session / lock). */
    var continuousPointActive = false
        private set

    fun resetForSession() {
        resetForStop()
        continuousPointActive = false
        // Tap regions are in the old session's coordinates; idle AF starts full-frame.
        host.afRegion = null
    }

    /** Clear inherited HAL focus state once the new session delivers preview metadata. */
    fun onSessionReady() {
        if (!host.canSendOneShot() || host.selectedFocusDistanceDiopters != null) return
        val mode = idleMode(host.afAvailableModes())
        if (tapMode(host.afAvailableModes()) == CONTROL_AF_MODE_OFF) return
        host.sendAfTrigger(CONTROL_AF_TRIGGER_CANCEL, runId)
        // An AUTO-only lens has no passive scan; start one without pretending it has AF-C.
        if (mode == CONTROL_AF_MODE_AUTO || mode == CONTROL_AF_MODE_MACRO)
            host.sendAfTrigger(CONTROL_AF_TRIGGER_START, runId)
    }

    fun start(viewX: Float, viewY: Float, indefinite: Boolean, updateAe: Boolean = true) {
        if (!host.isOnCameraThread()) {
            host.handler.post { start(viewX, viewY, indefinite, updateAe) }
            return
        }
        if (!host.canSendOneShot() || host.captureBusy()) return
        val region = host.meteringRegion(viewX, viewY) ?: return
        if (!indefinite && continuousPoint && startContinuousPoint(region, viewX, viewY, updateAe)) return
        startScan(region, indefinite, updateAe, viewX, viewY)
    }

    fun focusSelectedPoint(centerX: Float, centerY: Float) {
        if (!host.canSendOneShot() || host.captureBusy()) return
        startScan(host.afRegion ?: host.meteringRegion(centerX, centerY), true, false, centerX, centerY,
            showFocusAssistance = false)
    }

    private fun startScan(region: MeteringRectangle?, indefinite: Boolean, updateAe: Boolean,
                          viewX: Float, viewY: Float, showFocusAssistance: Boolean = true) {
        continuousPointActive = false
        cancelTimers()
        ++runId
        startFrame = null
        lastFrame = -1L
        lastState = null
        this.indefinite = indefinite
        focusAssistanceRequested = showFocusAssistance
        aeLockIndefinite = indefinite && updateAe && host.isAeLockSupported()
        dropManualHold()
        host.selectedFocusDistanceDiopters = null
        host.afRegion = if (host.maxAfRegions() > 0) region else null
        if (updateAe && region != null && host.isAeMeteringSupported()) {
            host.aeRegion = region
            host.syncDevelopFocus(viewX, viewY)
        }
        requestedMode = tapMode(host.afAvailableModes())
        active = requestedMode != CONTROL_AF_MODE_OFF
        completed = false
        focusLocked = false
        host.onFocusLock(false, false, 0L)
        host.publishControls()
        try {
            host.updateRepeatingRequest()
            if (!active) {
                host.onState(if (aeLockIndefinite) "AE LOCKED ∞" else "AUTOFOCUS NOT SUPPORTED")
                return
            }
            // Both one-shots carry the new mode and region. Camera2 applies settings before
            // the trigger; submitting them in order needs no guessed settling delay.
            host.sendAfTrigger(CONTROL_AF_TRIGGER_CANCEL, runId)
            host.sendAfTrigger(CONTROL_AF_TRIGGER_START, runId)
            host.onState("FOCUSING")
            val currentRun = runId
            timeout = Runnable {
                timeout = null
                if (currentRun == runId && scanning && !retryContinuousLock()) finish(false)
            }.also { host.handler.postDelayed(it, SCAN_TIMEOUT_MS) }
            Log.i(TAG, "Photo AF run=$runId mode=$requestedMode START queued")
        } catch (failure: CameraAccessException) {
            requestFailed(failure)
        } catch (failure: IllegalStateException) {
            requestFailed(failure)
        } catch (failure: IllegalArgumentException) {
            requestFailed(failure)
        } finally {
            host.publishPeakingActiveIfChanged()
        }
    }

    /**
     * AF-C with a focus point: stay in the idle continuous mode, move CONTROL_AF_REGIONS to the
     * tap and keep tracking there (no lock, no timer). One CANCEL restarts the passive scan so a
     * HAL sitting in PASSIVE_FOCUSED on the old subject re-evaluates the new region at once.
     * False when the lens has no continuous mode or no AF regions (caller falls back to AF-S).
     */
    private fun startContinuousPoint(region: MeteringRectangle, viewX: Float, viewY: Float,
                                     updateAe: Boolean): Boolean {
        val idle = idleMode(host.afAvailableModes())
        if (host.maxAfRegions() <= 0 ||
            idle != CONTROL_AF_MODE_CONTINUOUS_PICTURE && idle != CONTROL_AF_MODE_CONTINUOUS_VIDEO) return false
        // Leave any AF-S lock / ∞ hold first; its CANCEL runs in the mode that owns it.
        if (active || aeLockIndefinite) {
            cancelTimers()
            if (active) try { host.sendAfTrigger(CONTROL_AF_TRIGGER_CANCEL, runId) } catch (_: Exception) {}
            clearState()
            dropManualHold()
        }
        host.selectedFocusDistanceDiopters = null // a tap overrides manual focus, as in AF-S
        host.afRegion = region
        if (updateAe && host.isAeMeteringSupported()) {
            host.aeRegion = region
            host.syncDevelopFocus(viewX, viewY)
        }
        continuousPointActive = true
        host.onFocusLock(false, false, 0L)
        host.publishControls()
        try {
            host.updateRepeatingRequest()
            host.sendAfTrigger(CONTROL_AF_TRIGGER_CANCEL, runId)
            host.onState("AF-C POINT")
            Log.i(TAG, "Photo AF-C point region=$region")
        } catch (failure: CameraAccessException) {
            requestFailed(failure)
        } catch (failure: IllegalStateException) {
            requestFailed(failure)
        } catch (failure: IllegalArgumentException) {
            requestFailed(failure)
        } finally {
            host.publishPeakingActiveIfChanged()
        }
        return true
    }

    /** Drops the AF-C point (reset button, mode toggle); continuous AF goes back to full frame. */
    fun clearContinuousPoint() { continuousPointActive = false }

    /** Some HALs abandon AUTO immediately after START but support continuous-picture locks.
     * Retry once with that advertised mode; only a real LOCKED result can succeed. */
    private fun retryContinuousLock(): Boolean {
        if (requestedMode != CONTROL_AF_MODE_AUTO && requestedMode != CONTROL_AF_MODE_MACRO) return false
        if (startFrame == null || lastFrame < 0L || lastState == null) return false
        if (CONTROL_AF_MODE_CONTINUOUS_PICTURE !in host.afAvailableModes()) return false
        ++runId
        startFrame = null
        lastFrame = -1L
        lastState = null
        requestedMode = CONTROL_AF_MODE_CONTINUOUS_PICTURE
        try {
            host.updateRepeatingRequest()
            host.sendAfTrigger(CONTROL_AF_TRIGGER_CANCEL, runId)
            host.sendAfTrigger(CONTROL_AF_TRIGGER_START, runId)
            val currentRun = runId
            timeout = Runnable {
                timeout = null
                if (currentRun == runId && scanning) finish(false)
            }.also { host.handler.postDelayed(it, SCAN_TIMEOUT_MS) }
            Log.i(TAG, "Photo AF run=$runId retry CONTINUOUS_PICTURE lock")
        } catch (failure: CameraAccessException) {
            requestFailed(failure)
        } catch (failure: IllegalStateException) {
            requestFailed(failure)
        } catch (failure: IllegalArgumentException) {
            requestFailed(failure)
        }
        return true
    }

    /** A command from a replaced tap/session cannot arm the current run. */
    fun onStartFrame(commandRun: Long, frame: Long) {
        if (commandRun != runId || !scanning || startFrame != null) return
        startFrame = frame
        Log.i(TAG, "Photo AF run=$runId START frame=$frame")
    }

    fun onCommandFailed(commandRun: Long) {
        if (commandRun == runId && scanning) finish(false)
    }

    fun onResult(frame: Long, mode: Int?, state: Int?, focusDiopters: Float? = null) {
        val first = startFrame ?: return
        if (!scanning || frame < first || frame <= lastFrame || mode != requestedMode) return
        lastFrame = frame
        // Xiaomi 14 Ultra: AUTO goes ACTIVE_SCAN -> INACTIVE one frame after START (scan
        // abandoned). Retry the continuous-picture lock now instead of after SCAN_TIMEOUT_MS.
        if (state == CONTROL_AF_STATE_INACTIVE && lastState == CONTROL_AF_STATE_ACTIVE_SCAN) {
            val stale = timeout
            if (retryContinuousLock()) {
                stale?.let(host.handler::removeCallbacks)
                return
            }
        }
        val previous = lastState
        if (state != lastState) {
            lastState = state
            Log.i(TAG, "Photo AF run=$runId frame=$frame ${Camera2Names.afStateName(state)}")
        }
        // Direct INACTIVE -> LOCKED is legal: a scan can complete between reported frames.
        // PASSIVE_FOCUSED is tracking, never evidence of a held tap lock.
        when (state) {
            CONTROL_AF_STATE_FOCUSED_LOCKED -> finish(true)
            CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> finish(false)
            // Xiaomi 14 Ultra: CONT_PICTURE START can be ignored (stays passive). Its own
            // PASSIVE_FOCUSED on the tap region is a real focus; hold that distance manually.
            // Only after a scan seen since START: a stale PASSIVE_FOCUSED is the old subject.
            CONTROL_AF_STATE_PASSIVE_FOCUSED ->
                if (previous == CONTROL_AF_STATE_PASSIVE_SCAN && requestedMode == CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
                    requestedMode == CONTROL_AF_MODE_CONTINUOUS_VIDEO) holdManually(focusDiopters)
        }
    }

    private fun holdManually(focusDiopters: Float?) {
        val d = focusDiopters?.takeIf { it.isFinite() && it >= 0f } ?: return
        Log.i(TAG, "Photo AF run=$runId START ignored; holding PASSIVE_FOCUSED ${d}D manually")
        manualHold = true
        host.selectedFocusDistanceDiopters = d
        host.updateRepeatingRequest()
        finish(true)
    }

    /** Drops a fallback manual hold so idle/next tap AF runs again. */
    private fun dropManualHold() {
        if (!manualHold) return
        manualHold = false
        host.selectedFocusDistanceDiopters = null
    }

    private fun finish(sharp: Boolean) {
        completed = true
        focusLocked = sharp
        timeout?.let(host.handler::removeCallbacks)
        timeout = null
        if (!sharp && host.canSendOneShot()) {
            try {
                // A timeout must stop the sweep too, especially for an indefinite hold.
                host.sendAfTrigger(CONTROL_AF_TRIGGER_CANCEL, runId)
            } catch (failure: CameraAccessException) {
                Log.w(TAG, "Photo AF failed-scan cancel: ${host.cameraAccessReason(failure)}")
            } catch (failure: IllegalStateException) {
                Log.w(TAG, "Photo AF failed-scan cancel: ${failure.message}")
            }
        }
        val deadline = if (indefinite) 0L else
            SystemClock.elapsedRealtime() + if (sharp) LOCK_HOLD_MS else FAILURE_HOLD_MS
        host.onFocusLock(sharp, indefinite && sharp, deadline)
        host.onState(if (sharp) {
            if (indefinite) {
                if (aeLockIndefinite) "AE/AF LOCKED ∞" else "FOCUS LOCKED ∞"
            } else "FOCUS LOCKED"
        } else "FOCUS NOT LOCKED")
        if (!indefinite) {
            val currentRun = runId
            release = Runnable {
                release = null
                if (currentRun == runId) releaseToContinuous()
            }.also { host.handler.postDelayed(it, if (sharp) LOCK_HOLD_MS else FAILURE_HOLD_MS) }
        }
    }

    fun releaseToContinuous() {
        if (!active && !aeLockIndefinite) return
        cancelTimers()
        // Cancel in the mode that owns the lock before restoring the idle mode.
        try {
            aeLockIndefinite = false
            if (active && host.canSendOneShot()) host.sendAfTrigger(CONTROL_AF_TRIGGER_CANCEL, runId)
        } catch (failure: CameraAccessException) {
            Log.w(TAG, "Photo AF release: ${host.cameraAccessReason(failure)}")
        } catch (failure: IllegalStateException) {
            Log.w(TAG, "Photo AF release: ${failure.message}")
        } finally {
            clearState()
            dropManualHold()
            host.afRegion = null
            continuousPointActive = false
            host.onFocusLock(false, false, 0L)
            host.updateRepeatingRequest()
            host.onMeteringReleased()
            host.onState("CONTINUOUS AF")
            host.publishPeakingActiveIfChanged()
        }
    }

    fun clearAeLock() { aeLockIndefinite = false }

    fun dropForManualFocus() {
        manualHold = false // the user's own distance replaces ours
        cancelTimers()
        clearState()
        host.onFocusLock(false, false, 0L)
    }

    fun resetForStop() {
        cancelTimers()
        clearState()
        dropManualHold()
    }

    private fun clearState() {
        ++runId
        active = false
        completed = false
        focusAssistanceRequested = false
        focusLocked = false
        aeLockIndefinite = false
        startFrame = null
        lastFrame = -1L
        indefinite = false
    }

    fun cancelTimers() {
        timeout?.let(host.handler::removeCallbacks)
        release?.let(host.handler::removeCallbacks)
        timeout = null
        release = null
    }

    private fun requestFailed(failure: Exception) {
        continuousPointActive = false
        Log.w(TAG, "Photo AF request failed: ${failure.message}")
        releaseToContinuous()
        host.onState("FOCUS ERROR")
    }

    companion object {
        private const val TAG = "RawLensCamera"
        const val LOCK_HOLD_MS = 10_000L
        const val FAILURE_HOLD_MS = 1_500L
        const val SCAN_TIMEOUT_MS = 3_500L

        fun idleMode(modes: IntArray): Int = listOf(CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CONTROL_AF_MODE_CONTINUOUS_VIDEO, CONTROL_AF_MODE_AUTO, CONTROL_AF_MODE_MACRO,
            CONTROL_AF_MODE_EDOF).firstOrNull { it in modes } ?: CONTROL_AF_MODE_OFF

        fun tapMode(modes: IntArray): Int = listOf(CONTROL_AF_MODE_AUTO,
            CONTROL_AF_MODE_MACRO, CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CONTROL_AF_MODE_CONTINUOUS_VIDEO).firstOrNull { it in modes } ?: CONTROL_AF_MODE_OFF
    }
}
