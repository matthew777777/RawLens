// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Precapture wait follows Open Camera CameraController2 @ 0dd4cbe: Copyright Mark Harman,
// GPL-3.0-or-later (NOTICE.md).

package com.matthew.rawlens

import android.hardware.camera2.CaptureResult

/**
 * AE precapture wait for an AE-driven flash still, as Open Camera's
 * CameraController2 (STATE_WAITING_PRECAPTURE_START / _DONE @ 0dd4cbe):
 * first wait for AE to enter PRECAPTURE (or report no state), at most
 * [START_TIMEOUT_MS]; then wait for it to leave PRECAPTURE (or report no
 * state), at most [DONE_TIMEOUT_MS]. Accepting CONVERGED / FLASH_REQUIRED
 * before the sequence started could fire on a pre-trigger result; Open
 * Camera notes FLASH_REQUIRED there gave badly exposed flash shots.
 * Camera thread only; fed with every preview result after the trigger.
 */
internal class FlashPrecapture(startMs: Long) {
    enum class Phase { WAIT_START, WAIT_DONE, DONE }

    var phase = Phase.WAIT_START
        private set
    /** True when a phase ended on its timeout (logged as a precapture timeout). */
    var timedOut = false
        private set
    private var phaseStartMs = startMs

    /** One result's CONTROL_AE_STATE (null when the HAL omits it). True when done. */
    fun onAeState(aeState: Int?, nowMs: Long): Boolean {
        when (phase) {
            Phase.WAIT_START -> if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                phase = Phase.WAIT_DONE
                phaseStartMs = nowMs
            }
            Phase.WAIT_DONE -> if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                phase = Phase.DONE
            }
            Phase.DONE -> Unit
        }
        return onTick(nowMs)
    }

    /** Timeouts (also when no results arrive). True when done. */
    fun onTick(nowMs: Long): Boolean {
        when (phase) {
            Phase.WAIT_START -> if (nowMs - phaseStartMs > START_TIMEOUT_MS) {
                timedOut = true
                phase = Phase.WAIT_DONE
                phaseStartMs = nowMs
            }
            Phase.WAIT_DONE -> if (nowMs - phaseStartMs > DONE_TIMEOUT_MS) {
                timedOut = true
                phase = Phase.DONE
            }
            Phase.DONE -> Unit
        }
        return phase == Phase.DONE
    }

    companion object {
        /** Open Camera precapture_start_timeout_c. */
        const val START_TIMEOUT_MS = 2000L
        /** Open Camera precapture_done_timeout_c. */
        const val DONE_TIMEOUT_MS = 3000L
    }
}
