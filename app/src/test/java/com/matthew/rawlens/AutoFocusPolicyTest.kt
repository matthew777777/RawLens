// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import org.junit.Assert.*
import org.junit.Test

class AutoFocusPolicyTest {
    @Test fun idlePrefersContinuousPicture() {
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            AutoFocusPolicy.defaultAfMode(
                intArrayOf(
                    CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
            )
        )
    }

    @Test fun idleFallsBackInOrder() {
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_AUTO,
            AutoFocusPolicy.defaultAfMode(
                intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF, CaptureRequest.CONTROL_AF_MODE_AUTO)
            )
        )
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_MACRO,
            AutoFocusPolicy.defaultAfMode(
                intArrayOf(CaptureRequest.CONTROL_AF_MODE_MACRO, CaptureRequest.CONTROL_AF_MODE_OFF)
            )
        )
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_EDOF,
            AutoFocusPolicy.defaultAfMode(intArrayOf(CaptureRequest.CONTROL_AF_MODE_EDOF))
        )
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_OFF,
            AutoFocusPolicy.defaultAfMode(intArrayOf())
        )
    }

    @Test fun touchTriggerNeedsAuto() {
        assertTrue(AutoFocusPolicy.supportsTouchTrigger(intArrayOf(CaptureRequest.CONTROL_AF_MODE_AUTO)))
        assertFalse(
            AutoFocusPolicy.supportsTouchTrigger(
                intArrayOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            )
        )
    }

    @Test fun lockSuccessCoversPassiveHal() {
        assertTrue(AutoFocusPolicy.isLockSuccess(CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED))
        assertTrue(AutoFocusPolicy.isLockSuccess(CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED))
        assertFalse(AutoFocusPolicy.isLockSuccess(CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED))
        assertFalse(AutoFocusPolicy.isLockSuccess(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN))
        assertFalse(AutoFocusPolicy.isLockSuccess(null))
    }

    @Test fun scanStatesProveStartLanded() {
        assertTrue(AutoFocusPolicy.isScanState(CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN))
        assertTrue(AutoFocusPolicy.isScanState(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN))
        assertFalse(AutoFocusPolicy.isScanState(CaptureResult.CONTROL_AF_STATE_INACTIVE))
        assertFalse(AutoFocusPolicy.isScanState(null))
    }

    @Test fun inactiveFailsOnlyPastGraceWithoutScan() {
        assertFalse(AutoFocusPolicy.inactiveMeansFailure(false, false, 5_000L, 800L))
        assertFalse(AutoFocusPolicy.inactiveMeansFailure(true, true, 5_000L, 800L))
        assertFalse(AutoFocusPolicy.inactiveMeansFailure(true, false, 800L, 800L))
        assertTrue(AutoFocusPolicy.inactiveMeansFailure(true, false, 801L, 800L))
    }

    @Test fun firstTapAlwaysAccepted() {
        assertTrue(
            AutoFocusPolicy.shouldAcceptTap(
                nowMs = 1_000L, lastTapMs = 0L, lastTapX = 0f, lastTapY = 0f,
                x = 10f, y = 10f, minViewDimPx = 1_000f, scanInFlight = true
            )
        )
    }

    @Test fun bounceWindowRejectsFastRetap() {
        // Near the previous tap (30px of a 120px threshold): spacing applies.
        assertFalse(
            AutoFocusPolicy.shouldAcceptTap(
                nowMs = 1_349L, lastTapMs = 1_000L, lastTapX = 100f, lastTapY = 100f,
                x = 120f, y = 110f, minViewDimPx = 1_000f, scanInFlight = false
            )
        )
        assertTrue(
            AutoFocusPolicy.shouldAcceptTap(
                nowMs = 1_350L, lastTapMs = 1_000L, lastTapX = 100f, lastTapY = 100f,
                x = 120f, y = 110f, minViewDimPx = 1_000f, scanInFlight = false
            )
        )
    }

    @Test fun farRetargetPassesInsideBounceWindow() {
        // Re-aiming across the frame is deliberate even 100 ms later, idle
        // or mid-scan: never swallowed as bounce.
        assertTrue(
            AutoFocusPolicy.shouldAcceptTap(
                nowMs = 1_100L, lastTapMs = 1_000L, lastTapX = 100f, lastTapY = 100f,
                x = 900f, y = 900f, minViewDimPx = 1_000f, scanInFlight = false
            )
        )
        assertTrue(
            AutoFocusPolicy.shouldAcceptTap(
                nowMs = 1_100L, lastTapMs = 1_000L, lastTapX = 100f, lastTapY = 100f,
                x = 900f, y = 900f, minViewDimPx = 1_000f, scanInFlight = true
            )
        )
    }

    @Test fun midScanSameSpotRetapRestartsScan() {
        // 12% of 1000px = 120px: past the bounce window a same-spot retap is
        // deliberate (tap-twice fix) and restarts the hunt; the handler
        // CANCELs first and the verdict floor absorbs the stale latch.
        assertTrue(
            AutoFocusPolicy.shouldAcceptTap(
                nowMs = 2_000L, lastTapMs = 1_000L, lastTapX = 100f, lastTapY = 100f,
                x = 130f, y = 140f, minViewDimPx = 1_000f, scanInFlight = true
            )
        )
        assertTrue(
            AutoFocusPolicy.shouldAcceptTap(
                nowMs = 2_000L, lastTapMs = 1_000L, lastTapX = 100f, lastTapY = 100f,
                x = 300f, y = 100f, minViewDimPx = 1_000f, scanInFlight = true
            )
        )
    }

    @Test fun idleNearTapPassesAfterBounce() {
        assertTrue(
            AutoFocusPolicy.shouldAcceptTap(
                nowMs = 2_000L, lastTapMs = 1_000L, lastTapX = 100f, lastTapY = 100f,
                x = 105f, y = 100f, minViewDimPx = 1_000f, scanInFlight = false
            )
        )
    }

    @Test fun verdictFloorRejectsStaleLatch() {
        // Retap: previous FOCUSED still reported ~200 ms after the new START.
        assertFalse(AutoFocusPolicy.verdictReady(true, false, 203L, 300L))
        assertFalse(AutoFocusPolicy.verdictReady(true, false, 299L, 300L))
        // Past the floor the persisting lock completes the scan.
        assertTrue(AutoFocusPolicy.verdictReady(true, false, 300L, 300L))
        // A scan proves the new cycle runs: verdicts count immediately.
        assertTrue(AutoFocusPolicy.verdictReady(true, true, 0L, 300L))
        // Verdicts before START are genuine idle state, never stale.
        assertTrue(AutoFocusPolicy.verdictReady(false, false, 0L, 300L))
    }

    @Test fun idleFallsBackToContinuousVideoBeforeOff() {
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            AutoFocusPolicy.defaultAfMode(
                intArrayOf(
                    CaptureRequest.CONTROL_AF_MODE_OFF,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            )
        )
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_AUTO,
            AutoFocusPolicy.defaultAfMode(
                intArrayOf(
                    CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            )
        )
    }

    @Test fun idleScanNudgeOnlyForAutoParkedLenses() {
        assertTrue(
            AutoFocusPolicy.needsIdleScanNudge(intArrayOf(CaptureRequest.CONTROL_AF_MODE_AUTO))
        )
        assertTrue(
            AutoFocusPolicy.needsIdleScanNudge(
                intArrayOf(
                    CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            )
        )
        assertFalse(
            AutoFocusPolicy.needsIdleScanNudge(
                intArrayOf(
                    CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            )
        )
        assertFalse(
            AutoFocusPolicy.needsIdleScanNudge(
                intArrayOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            )
        )
        assertFalse(
            AutoFocusPolicy.needsIdleScanNudge(intArrayOf(CaptureRequest.CONTROL_AF_MODE_MACRO))
        )
        assertFalse(AutoFocusPolicy.needsIdleScanNudge(intArrayOf()))
    }

    @Test fun scanBudgetClearsSlowGenuineLocks() {
        // Field log: genuine locks landed at +2023/+2031 ms on a slow HAL.
        assertTrue(AutoFocusPolicy.AUTOFOCUS_TIMEOUT_MS > 2_500L)
        assertTrue(AutoFocusPolicy.AUTOFOCUS_TIMEOUT_MS <= 5_000L)
    }

    @Test fun failureHoldShorterThanLockHold() {
        assertTrue(AutoFocusPolicy.FAILURE_HOLD_MS > 0L)
        assertTrue(AutoFocusPolicy.FAILURE_HOLD_MS < AutoFocusPolicy.LOCK_HOLD_MS)
    }

    @Test
    fun autoScanLeftLensStillNeedsScanAndKnownStillDistance() {
        assertTrue(AutoFocusPolicy.autoScanLeftLensStill(true, true, 0.33f, lensMoved = false))
        assertFalse(AutoFocusPolicy.autoScanLeftLensStill(true, true, 0.33f, lensMoved = true))
        assertFalse(AutoFocusPolicy.autoScanLeftLensStill(true, false, 0.33f, lensMoved = false))
        assertFalse(AutoFocusPolicy.autoScanLeftLensStill(true, true, null, lensMoved = false))
        assertFalse(AutoFocusPolicy.lensMoved(0.33f, 0.34f))
        assertTrue(AutoFocusPolicy.lensMoved(0.33f, 0.48f))
    }

    @Test
    fun sessionKickOnlyForContinuousIdle() {
        assertTrue(AutoFocusPolicy.needsSessionKick(intArrayOf(CaptureRequest.CONTROL_AF_MODE_AUTO, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)))
        assertFalse(AutoFocusPolicy.needsSessionKick(intArrayOf(CaptureRequest.CONTROL_AF_MODE_AUTO)))
        assertFalse(AutoFocusPolicy.needsSessionKick(intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF)))
    }
    @Test fun inactiveMotionlessSessionIsEvidenceOfMissingAf() {
        val evidence = AutoFocusPolicy.SessionEvidence()
        repeat(100) { evidence.observe(CaptureResult.CONTROL_AF_STATE_INACTIVE, 0.03f) }
        assertTrue(evidence.inactiveThroughout)
        assertFalse(evidence.working)
    }

    @Test fun missingMetadataCannotConvictAf() {
        val evidence = AutoFocusPolicy.SessionEvidence()
        repeat(100) { evidence.observe(null, null) }
        assertFalse(evidence.inactiveThroughout)
        evidence.observe(CaptureResult.CONTROL_AF_STATE_INACTIVE, null)
        assertFalse(evidence.inactiveThroughout)
    }

    @Test fun scanOrLensMotionPreventsInactiveVerdict() {
        val scanned = AutoFocusPolicy.SessionEvidence()
        scanned.observe(CaptureResult.CONTROL_AF_STATE_INACTIVE, 0.03f)
        scanned.observe(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, 0.03f)
        assertFalse(scanned.inactiveThroughout)
        assertTrue(scanned.working)
        val moved = AutoFocusPolicy.SessionEvidence()
        moved.observe(CaptureResult.CONTROL_AF_STATE_INACTIVE, 0.03f)
        moved.observe(CaptureResult.CONTROL_AF_STATE_INACTIVE, 0.93f)
        assertFalse(moved.inactiveThroughout)
        assertTrue(moved.working)
    }

}
