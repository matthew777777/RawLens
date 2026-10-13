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
        else -> CaptureRequest.CONTROL_AF_MODE_OFF
    }

    fun supportsContinuousPicture(availableModes: IntArray): Boolean =
        availableModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

    /** Explicit AUTO trigger available for the tap scan/lock cycle. */
    fun supportsTouchTrigger(availableModes: IntArray): Boolean =
        availableModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)

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
     * Tap gate shared by the overlay (bounce only, [scanInFlight] false) and
     * the controller (bounce plus mid-scan same-spot suppression). Deliberate
     * retargets — taps past [retargetFraction] of [minViewDimPx] — always pass,
     * so one tap still switches place even while a scan runs.
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
        if (dt < bounceMs) return false
        if (!scanInFlight) return true
        val dx = x - lastTapX
        val dy = y - lastTapY
        return dx * dx + dy * dy >=
            (retargetFraction * minViewDimPx) * (retargetFraction * minViewDimPx)
    }
}
