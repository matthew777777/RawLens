// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

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
}
