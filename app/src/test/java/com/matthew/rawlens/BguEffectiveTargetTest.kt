// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the BGU GPU guide-resolution floor (log-driven, 2026-10-13). */
class BguEffectiveTargetTest {

    private fun eff(
        user: Int, surface: Int, gpu: Boolean = true,
        record: Boolean = false, saver: Boolean = false
    ) = BguViewfinder.bguEffectiveTarget(user, surface, gpu, record, saver)

    @Test
    fun lowUserTargetLiftsToSurfaceOnGpu() {
        // The blurry-reporter shape: 480 bucket, 1080x2400-class screen.
        assertEquals(1080, eff(480, 2400))
        assertEquals(960, eff(640, 960))
    }

    @Test
    fun liftCapsAtProvenEnvelope() {
        assertEquals(1080, eff(480, 3200))
    }

    @Test
    fun userTargetAboveSurfaceWins() {
        assertEquals(1080, eff(1080, 800))
        assertEquals(1440, eff(1440, 2400))
    }

    @Test
    fun cpuPathKeepsUserTarget() {
        assertEquals(480, eff(480, 2400, gpu = false))
    }

    @Test
    fun cpuPathCapsAtNeonEdge() {
        // Field log 2026-10-29: a >1080 guide on the CPU path is fatal.
        assertEquals(VfCpuNeon.MAX_EDGE, eff(1440, 2400, gpu = false))
        assertEquals(VfCpuNeon.MAX_EDGE, eff(2160, 2400, gpu = false))
    }

    @Test
    fun recordAndPowerSaveCapsStayAuthoritative() {
        assertEquals(480, eff(480, 2400, record = true))
        assertEquals(480, eff(480, 2400, saver = true))
    }

    @Test
    fun boostNeedsWarmupAndHeadroom() {
        // Blurry-log shape: fast GPU, plenty of offers observed.
        assertEquals(true, BguViewfinder.guideBoostNext(false, 1.0f, 300))
        // Mid-range shape (vkMs 6-11): never engages.
        assertEquals(false, BguViewfinder.guideBoostNext(false, 6.5f, 300))
        // Warmup gate: even a free GPU waits ~3 s of evidence.
        assertEquals(false, BguViewfinder.guideBoostNext(false, 1.0f, 30))
        // No measurement yet (CPU path / first frames): keep current.
        assertEquals(false, BguViewfinder.guideBoostNext(false, 0f, 300))
    }

    @Test
    fun boostHysteresisBandHolds() {
        // Engaged at 1 ms; step-2 quadruples dispatch to ~4 ms: stays on.
        assertEquals(true, BguViewfinder.guideBoostNext(true, 4.0f, 300))
        // Genuinely saturated even boosted: releases.
        assertEquals(false, BguViewfinder.guideBoostNext(true, 9.0f, 300))
        // The 2.5..7 band does not engage from off.
        assertEquals(false, BguViewfinder.guideBoostNext(false, 5.0f, 300))
    }

    @Test
    fun unknownSurfaceKeepsUserTarget() {
        assertEquals(480, eff(480, 0))
    }
}
