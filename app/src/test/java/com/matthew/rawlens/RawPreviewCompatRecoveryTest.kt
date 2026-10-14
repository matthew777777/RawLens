// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.HardwareBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewCompatRecoveryTest {
    // Regression: on Samsung the repeating RAW stream delivers zero buffers
    // under the PREVIEW + STILL_CAPTURE plan with a forced 30 fps range, and
    // request-level recovery retries forever without effect. A stillborn
    // stream must escalate once to a compat session rebuild instead.
    @Test
    fun stillbornStreamEscalatesAfterOneRequestRecovery() {
        assertTrue(RawCameraController.shouldEscalateRawPreviewToCompatSession(
            failures = 1, deliveredImages = 0, compatMode = false
        ))
    }

    @Test
    fun firstStarvationStaysRequestLevel() {
        // One request-level retry is cheap prudence against slow HAL startup
        // (long exposures legitimately delay the first buffer for seconds).
        assertFalse(RawCameraController.shouldEscalateRawPreviewToCompatSession(
            failures = 0, deliveredImages = 0, compatMode = false
        ))
    }

    @Test
    fun flowingStreamNeverEscalates() {
        // Buffers arriving means the session is fine: starvation is a
        // viewfinder/rendering problem, not a session problem.
        assertFalse(RawCameraController.shouldEscalateRawPreviewToCompatSession(
            failures = 4, deliveredImages = 12, compatMode = false
        ))
    }

    @Test
    fun escalationFiresAtMostOnce() {
        assertFalse(RawCameraController.shouldEscalateRawPreviewToCompatSession(
            failures = 3, deliveredImages = 0, compatMode = true
        ))
    }

    // Regression: on vivo X300 Ultra the compat session configures fine and
    // then delivers zero RAW buffers (silent combo rejection). Request
    // toggles cannot revive it; the session ladder must advance to degraded
    // combos instead of parking on a black viewfinder.
    @Test
    fun stillbornInCompatAdvancesLadder() {
        assertTrue(RawCameraController.shouldAdvanceStillbornSessionLadder(
            failures = 1, deliveredImages = 0, compatMode = true,
            altRouteAvailable = false, comboIndex = 0, comboCount = 3
        ))
    }

    @Test
    fun stillbornFirstStarvationStaysRequestLevel() {
        // Same cheap prudence as the compat escalation: one request-level
        // retry before paying for a session rebuild.
        assertFalse(RawCameraController.shouldAdvanceStillbornSessionLadder(
            failures = 0, deliveredImages = 0, compatMode = true,
            altRouteAvailable = false, comboIndex = 0, comboCount = 3
        ))
    }

    @Test
    fun stillbornFlowingStreamNeverAdvances() {
        // Buffers arriving means the session is fine: starvation is a
        // viewfinder/rendering problem, not a session problem.
        assertFalse(RawCameraController.shouldAdvanceStillbornSessionLadder(
            failures = 4, deliveredImages = 12, compatMode = true,
            altRouteAvailable = false, comboIndex = 0, comboCount = 3
        ))
    }

    @Test
    fun stillbornPreCompatEscalationOwnsRecovery() {
        // Before compat mode the compat rebuild owns stillborn recovery;
        // the ladder only runs once that rebuild also delivers nothing.
        assertFalse(RawCameraController.shouldAdvanceStillbornSessionLadder(
            failures = 3, deliveredImages = 0, compatMode = false,
            altRouteAvailable = false, comboIndex = 0, comboCount = 3
        ))
    }

    @Test
    fun stillbornExhaustedLadderStaysTerminal() {
        assertFalse(RawCameraController.shouldAdvanceStillbornSessionLadder(
            failures = 3, deliveredImages = 0, compatMode = true,
            altRouteAvailable = false, comboIndex = 2, comboCount = 3
        ))
    }

    @Test
    fun stillbornAltRouteAvailableAdvancesPastLastCombo() {
        // The direct-physical route is a ladder step of its own: with no
        // combos left but an untried route, recovery still advances.
        assertTrue(RawCameraController.shouldAdvanceStillbornSessionLadder(
            failures = 2, deliveredImages = 0, compatMode = true,
            altRouteAvailable = true, comboIndex = 2, comboCount = 3
        ))
    }

    // Regression: on MT6878 the RAW reader trial with GPU-sampled usage
    // configures fine and then delivers zero buffers. A stillborn trial
    // must disarm once (rebuild without GPU usage) before the compat
    // escalation spends the plan axis on a usage problem.
    @Test
    fun stillbornGpuTrialDisarmsAfterOneRequestRecovery() {
        assertTrue(RawCameraController.shouldDisarmVfGpuReaderTrial(
            failures = 1, deliveredImages = 0, trialArmed = true
        ))
    }

    @Test
    fun gpuTrialFirstStarvationStaysRequestLevel() {
        // Same cheap prudence as the other stillborn rungs: one
        // request-level retry before paying for a session rebuild.
        assertFalse(RawCameraController.shouldDisarmVfGpuReaderTrial(
            failures = 0, deliveredImages = 0, trialArmed = true
        ))
    }

    @Test
    fun gpuTrialFlowingStreamNeverDisarms() {
        // Buffers arriving means the trial allocation works on this HAL:
        // starvation is a viewfinder/rendering problem, not a usage problem.
        assertFalse(RawCameraController.shouldDisarmVfGpuReaderTrial(
            failures = 4, deliveredImages = 12, trialArmed = true
        ))
    }

    @Test
    fun gpuTrialDisarmFiresAtMostOnce() {
        // Once disarmed the rung is spent: a stillborn disarmed session is
        // the compat escalation's (then the ladder's), never a re-disarm.
        assertFalse(RawCameraController.shouldDisarmVfGpuReaderTrial(
            failures = 3, deliveredImages = 0, trialArmed = false
        ))
    }

    @Test
    fun gpuReaderProbeMatchesTrialUsagePlusCameraWrite() {
        // The pre-flight must probe exactly what the trial reader requests
        // (plus the HAL's producer bit): a looser superset risks false
        // refusals that wrongly disarm a working trial.
        assertEquals(
            HardwareBuffer.USAGE_CPU_READ_OFTEN or
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                RawCameraController.GRALLOC_USAGE_HW_CAMERA_WRITE,
            RawCameraController.gpuReaderProbeUsage()
        )
    }
}
