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
}
