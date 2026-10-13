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
        assertFalse(
            AutoFocusPolicy.shouldAcceptTap(
                nowMs = 1_349L, lastTapMs = 1_000L, lastTapX = 100f, lastTapY = 100f,
                x = 900f, y = 900f, minViewDimPx = 1_000f, scanInFlight = false
            )
        )
        assertTrue(
            AutoFocusPolicy.shouldAcceptTap(
                nowMs = 1_350L, lastTapMs = 1_000L, lastTapX = 100f, lastTapY = 100f,
                x = 900f, y = 900f, minViewDimPx = 1_000f, scanInFlight = false
            )
        )
    }

    @Test fun midScanSameSpotSuppressedButRetargetPasses() {
        // 12% of 1000px = 120px: 50px is hammering, 200px is a deliberate move.
        assertFalse(
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

    @Test fun failureHoldShorterThanLockHold() {
        assertTrue(AutoFocusPolicy.FAILURE_HOLD_MS > 0L)
        assertTrue(AutoFocusPolicy.FAILURE_HOLD_MS < AutoFocusPolicy.LOCK_HOLD_MS)
    }
}
