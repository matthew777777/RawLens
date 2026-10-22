// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Open Camera CameraController2 precapture states @ 0dd4cbe. */
class FlashPrecaptureTest {
    private val converged = CaptureResult.CONTROL_AE_STATE_CONVERGED
    private val flashRequired = CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
    private val precapture = CaptureResult.CONTROL_AE_STATE_PRECAPTURE

    @Test fun preTriggerConvergedDoesNotFire() {
        // The old waiter fired here: a result from before the trigger.
        val wait = FlashPrecapture(0)
        assertFalse(wait.onAeState(converged, 10))
        assertFalse(wait.onAeState(flashRequired, 20))
        assertEquals(FlashPrecapture.Phase.WAIT_START, wait.phase)
    }

    @Test fun firesAfterAeEntersAndLeavesPrecapture() {
        val wait = FlashPrecapture(0)
        assertFalse(wait.onAeState(precapture, 50))
        assertFalse(wait.onAeState(precapture, 150))
        assertTrue(wait.onAeState(converged, 400))
        assertFalse(wait.timedOut)
    }

    @Test fun missingAeStateCountsAsBothTransitions() {
        val wait = FlashPrecapture(0)
        assertFalse(wait.onAeState(null, 10))
        assertTrue(wait.onAeState(null, 20))
    }

    @Test fun startTimeoutThenDoneTimeout() {
        val wait = FlashPrecapture(0)
        assertFalse(wait.onTick(FlashPrecapture.START_TIMEOUT_MS))
        assertFalse(wait.onTick(FlashPrecapture.START_TIMEOUT_MS + 1))   // -> WAIT_DONE
        assertEquals(FlashPrecapture.Phase.WAIT_DONE, wait.phase)
        assertFalse(wait.onAeState(precapture, 3000))
        assertTrue(wait.onTick(FlashPrecapture.START_TIMEOUT_MS + 1 + FlashPrecapture.DONE_TIMEOUT_MS + 1))
        assertTrue(wait.timedOut)
    }
}
