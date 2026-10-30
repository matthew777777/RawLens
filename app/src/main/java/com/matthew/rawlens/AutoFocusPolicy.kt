// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult

/**
 * Pure autofocus policy: idle-mode selection and tap-to-focus gating.
 *
 * Idle is always continuous-picture when the HAL offers it; AUTO appears only
 * while a tap scan/lock is in flight. Failed scans release back to continuous
 * quickly ([FAILURE_HOLD_MS]) instead of pinning a blurry AUTO lock, and taps
 * that look accidental (bounce, same-spot hammering mid-scan) are ignored.
 * No Android calls here — only framework int constants — so host tests cover it.
 */
object AutoFocusPolicy {
    /** Timed tap-lock hold before auto-return to continuous AF. */
    const val LOCK_HOLD_MS = 10_000L
    /** Failed-scan hold: long enough to read FOCUS NOT LOCKED, then recover. */
    const val FAILURE_HOLD_MS = 1_500L
    /** Taps faster than this after the previous tap are bounce; ignore them. */
    const val TAP_BOUNCE_MS = 350L
    /** Mid-scan retaps closer than this fraction of the viewfinder move nothing. */
    const val SCAN_RETARGET_FRACTION = 0.12f
    /** Grace before INACTIVE-after-START counts as a failed lock. */
    const val INACTIVE_GRACE_MS = 800L
    /**
     * Floor before a lock verdict counts without a scan: on retap the HAL
     * still reports the previous lock for ~200 ms before the new scan
     * starts, and latching it "locks" the wrong distance. Locks persist
     * across frames, so the real verdict lands just past the floor.
     */
    const val STALE_VERDICT_FLOOR_MS = 300L
    /**
     * Full scan budget before declaring failure. Field logs show genuine
     * locks at ~2.0 s on a slow HAL in a near-black scene (2 peaking
     * passes), so the budget must clear slow-but-real scans with margin;
     * the failure hold still releases quickly once it fires.
     */
    const val AUTOFOCUS_TIMEOUT_MS = 3500L
    /** LENS_FOCUS_DISTANCE change (diopters) that proves the lens moved. */
    const val LENS_MOVED_EPSILON_D = 0.02f
    /**
     * Continuous-tap verdict floor: after the CANCEL restart the HAL keeps
     * reporting the old passive verdict until the new scan begins (~350 ms
     * in the Xiaomi 14T Pro field log), so a verdict without a scan only
     * counts past this floor.
     */
    const val CONTINUOUS_TAP_VERDICT_FLOOR_MS = 1_000L

    /**
     * Evidence from a continuous-AF session. Missing AF metadata is inconclusive;
     * any scan/verdict or lens motion prevents a no-AF verdict. Only combine
     * this with missing HAL AWB, so a normal quiet AF session is not convicted.
     */
    class SessionEvidence {
        private var sawInactive = false
        private var sawActivity = false
        private var firstDistance: Float? = null

        fun observe(state: Int?, distance: Float?) {
            if (state == CaptureResult.CONTROL_AF_STATE_INACTIVE) sawInactive = true
            else if (state != null) sawActivity = true
            if (distance != null && distance.isFinite() && distance >= 0f) {
                val first = firstDistance
                if (first == null) firstDistance = distance
                else if (lensMoved(first, distance)) sawActivity = true
            }
        }

        val inactiveThroughout: Boolean get() = sawInactive && !sawActivity && firstDistance != null
        val working: Boolean get() = sawActivity
    }

    /** Idle AF mode: continuous-picture first, AUTO only where nothing scans. */
    fun defaultAfMode(availableModes: IntArray): Int = when {
        availableModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        availableModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
            CaptureRequest.CONTROL_AF_MODE_AUTO
        availableModes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) ->
            CaptureRequest.CONTROL_AF_MODE_MACRO
        availableModes.contains(CaptureRequest.CONTROL_AF_MODE_EDOF) ->
            CaptureRequest.CONTROL_AF_MODE_EDOF
        availableModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        else -> CaptureRequest.CONTROL_AF_MODE_OFF
    }

    /** Explicit AUTO trigger available for the tap scan/lock cycle. */
    fun supportsTouchTrigger(availableModes: IntArray): Boolean =
        availableModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)

    /**
     * AUTO-only lenses (the idle pick itself is AUTO): idle never scans
     * without a trigger, so the app must stand in for continuous AF with an
     * occasional full-frame scan. Continuous modes scan on their own.
     */
    fun needsIdleScanNudge(availableModes: IntArray): Boolean =
        defaultAfMode(availableModes) == CaptureRequest.CONTROL_AF_MODE_AUTO

    /**
     * Continuous idle modes eligible for a guarded startup recovery. Xiaomi
     * 14T Pro (MediaTek) field logs: CONT_PICTURE sat PASSIVE_UNFOCUSED at a
     * fixed distance until CANCEL arrived. TouchFocus must also observe that
     * stalled state; capability alone never justifies a mode reset, which
     * can interrupt working AF (Xiaomi 14 Ultra log 2026-10-29 19:34:35).
     */
    fun needsSessionKick(availableModes: IntArray): Boolean =
        when (defaultAfMode(availableModes)) {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> true
            else -> false
        }

    /** True when two LENS_FOCUS_DISTANCE reports differ by more than [epsilon]. */
    fun lensMoved(fromDiopters: Float, toDiopters: Float, epsilon: Float = LENS_MOVED_EPSILON_D): Boolean =
        kotlin.math.abs(toDiopters - fromDiopters) > epsilon

    /**
     * True when an AUTO tap scan ran (START sent, scan state seen) and the
     * lens never moved: the HAL reports ACTIVE_SCAN but its AUTO sweep is
     * dead. Xiaomi 14T Pro field log: every AUTO scan held fd=0.33D for
     * ~2 s, then NOT_FOCUSED_LOCKED, while continuous AF on the same
     * session swept 0.33 -> 6.89 D. Unknown distance (no fd reports)
     * never convicts.
     */
    fun autoScanLeftLensStill(
        startSent: Boolean,
        scanSeen: Boolean,
        scanStartDiopters: Float?,
        lensMoved: Boolean
    ): Boolean =
        startSent && scanSeen && scanStartDiopters != null && !lensMoved

    /** Lock states worth a badge, including passive HALs that never emit AUTO locks. */
    fun isLockSuccess(afState: Int?): Boolean =
        afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED

    /** Scan states proving START reached the HAL (see [inactiveMeansFailure]). */
    fun isScanState(afState: Int?): Boolean =
        afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN

    /**
     * True when INACTIVE after START means the lock failed: START was sent,
     * no scan state was ever observed, and the grace has expired. A lone
     * INACTIVE early in the window (or after a real scan started) is HAL
     * chatter, not failure.
     */
    fun inactiveMeansFailure(
        startSent: Boolean,
        scanSeen: Boolean,
        elapsedSinceStartMs: Long,
        graceMs: Long = INACTIVE_GRACE_MS
    ): Boolean =
        startSent && !scanSeen && elapsedSinceStartMs > graceMs

    /**
     * True when a lock verdict (sharp or missed) may complete the scan: any
     * verdict before START is genuine idle state, a scan proves the new cycle
     * runs, otherwise the verdict must arrive past [floorMs] so a stale
     * previous lock cannot complete the new scan.
     */
    fun verdictReady(
        startSent: Boolean,
        scanSeen: Boolean,
        elapsedSinceStartMs: Long,
        floorMs: Long = STALE_VERDICT_FLOOR_MS
    ): Boolean =
        !startSent || scanSeen || elapsedSinceStartMs >= floorMs

    /**
     * Tap gate: hardware double-fires landing within [bounceMs] at the same
     * spot are accidents and dropped. Anything past the bounce window is a
     * deliberate user action and always lands — including a same-spot retap
     * mid-scan, which restarts the hunt (the handler CANCELs first, and the
     * verdict floor absorbs the stale latch). Swallowing retaps while a
     * low-contrast scan hunts is what forced tap-twice: the user, seeing no
     * dots, taps again and the tap is eaten. Far taps are deliberate
     * retargets and always land immediately — a frustrated user re-aiming
     * during a slow 2 s scan must never feel ignored
     * (field log: 5 swallowed retaps). [scanInFlight] is retained for
     * call-site readability; it no longer suppresses intentional retaps.
     */
    fun shouldAcceptTap(
        nowMs: Long,
        lastTapMs: Long,
        lastTapX: Float,
        lastTapY: Float,
        x: Float,
        y: Float,
        minViewDimPx: Float,
        scanInFlight: Boolean,
        bounceMs: Long = TAP_BOUNCE_MS,
        retargetFraction: Float = SCAN_RETARGET_FRACTION
    ): Boolean {
        if (lastTapMs <= 0L) return true
        val dt = nowMs - lastTapMs
        if (dt < 0L) return true
        val dx = x - lastTapX
        val dy = y - lastTapY
        val threshold = retargetFraction * minViewDimPx
        if (dx * dx + dy * dy >= threshold * threshold) return true
        if (dt < bounceMs) return false
        return true
    }
}
