// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramAeSolverTest {
    private val limits = ExposureBalanceLimits(
        isoMin = 100,
        isoMax = 6400,
        shutterMinNanos = 1_000_000L,
        shutterMaxNanos = 66_000_000L,
        shutterStartNanos = 33_000_000L
    )

    @Test
    fun balanceSliderMapsToMultiplier() {
        assertEquals(0.25f, ProgramAeProfile.balanceToMultiplier(0f), 0.01f)
        assertEquals(1.0f, ProgramAeProfile.balanceToMultiplier(0.5f), 0.01f)
        assertEquals(4.0f, ProgramAeProfile.balanceToMultiplier(1f), 0.01f)
    }

    @Test
    fun multiplierRoundTripsThroughSlider() {
        assertEquals(0.5f, ProgramAeProfile.multiplierToBalance(1f), 0.02f)
        assertEquals(0f, ProgramAeProfile.multiplierToBalance(0.25f), 0.02f)
        assertEquals(1f, ProgramAeProfile.multiplierToBalance(4f), 0.02f)
    }

    @Test
    fun shutterPriorityIsFasterThanIsoPriority() {
        val isoPriority = AutoExposureBalance.applyProgram(400, 20_000_000L, 0f, limits)
        val shutterPriority = AutoExposureBalance.applyProgram(400, 20_000_000L, 1f, limits)
        assertTrue(
            "shutter priority must be faster: iso-prio=${isoPriority.shutterNanos} " +
                "shutter-prio=${shutterPriority.shutterNanos}",
            shutterPriority.shutterNanos < isoPriority.shutterNanos
        )
        assertTrue(shutterPriority.iso >= isoPriority.iso)
    }

    @Test
    fun isoLockFixesGainWhileShutterTracksScene() {
        val dark = AutoExposureBalance.applyProgram(
            100, 40_000_000L, 0.5f, limits, ProgramLockMode.ISO_LOCK, lockedIso = 400
        )
        val bright = AutoExposureBalance.applyProgram(
            100, 10_000_000L, 0.5f, limits, ProgramLockMode.ISO_LOCK, lockedIso = 400
        )
        assertEquals(400, dark.iso)
        assertEquals(400, bright.iso)
        assertTrue(dark.shutterNanos > bright.shutterNanos)
    }

    @Test
    fun shutterLockFixesShutterWhileGainTracksScene() {
        val dark = AutoExposureBalance.applyProgram(
            100, 40_000_000L, 0.5f, limits,
            ProgramLockMode.SHUTTER_LOCK, lockedShutterNanos = 10_000_000L
        )
        val bright = AutoExposureBalance.applyProgram(
            100, 10_000_000L, 0.5f, limits,
            ProgramLockMode.SHUTTER_LOCK, lockedShutterNanos = 10_000_000L
        )
        assertEquals(10_000_000L, dark.shutterNanos)
        assertEquals(10_000_000L, bright.shutterNanos)
        assertTrue(dark.iso > bright.iso)
    }

    @Test
    fun energyEntryPointPreservesExposure() {
        val energy = 100.0 * 20_000_000L
        val result = AutoExposureBalance.applyProgramEnergy(energy, 0.5f, limits)
        assertEquals(energy, result.iso.toDouble() * result.shutterNanos, energy * 0.05)
    }

    @Test
    fun isoMinBoundIsRespected() {
        val tight = limits.copy(isoMin = 800)
        val result = AutoExposureBalance.applyProgram(100, 5_000_000L, 0f, tight)
        assertTrue(result.iso >= 800)
    }

    @Test
    fun unreachableIsoLockReportsShutterLimited() {
        // Locked ISO 100 with huge energy demand: shutter clamps at the cap.
        val result = AutoExposureBalance.applyProgram(
            6400, 66_000_000L, 0.5f, limits, ProgramLockMode.ISO_LOCK, lockedIso = 100
        )
        assertEquals(100, result.iso)
        assertEquals(66_000_000L, result.shutterNanos)
        assertTrue(result.shutterLimited)
    }

    @Test
    fun unreachableShutterLockReportsIsoLimited() {
        // Locked fast shutter with huge energy demand: gain clamps at the cap.
        val result = AutoExposureBalance.applyProgram(
            6400, 66_000_000L, 0.5f, limits,
            ProgramLockMode.SHUTTER_LOCK, lockedShutterNanos = 1_000_000L
        )
        assertEquals(1_000_000L, result.shutterNanos)
        assertEquals(6400, result.iso)
        assertTrue(result.isoLimited)
    }

    @Test
    fun profileJsonRoundTrips() {
        val profile = ProgramAeProfile(
            balance = 0.7f, isoMin = 100, isoMax = 1600,
            shutterMinNanos = 2_000_000L, shutterMaxNanos = 33_000_000L,
            useAutoSafeShutter = false, lockMode = ProgramLockMode.SHUTTER_LOCK,
            lockedIso = 0, lockedShutterNanos = 10_000_000L, evBias = 0.7f,
            metering = ProgramMetering.MEDIAN
        )
        assertEquals(profile, ProgramAeProfile.fromJson(profile.toJson()))
    }

    @Test
    fun legacyMigrationSeedsPerLensProfile() {
        val migrated = ProgramAeProfile.fromLegacy(1f, 1600, 33_000_000L, true)
        assertEquals(0.5f, migrated.balance, 0.02f)
        assertEquals(1600, migrated.isoMax)
        assertEquals(33_000_000L, migrated.shutterMaxNanos)
    }
}
