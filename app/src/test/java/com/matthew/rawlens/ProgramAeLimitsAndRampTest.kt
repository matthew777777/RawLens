// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramAeLimitsAndRampTest {
    private val sec = 1_000_000_000L
    private val sensor = arrayOf(50, 6400)
    private fun limits(profile: ProgramAeProfile = ProgramAeProfile(), safe: Long = sec / 15) =
        ProgramAeSolver.resolveLimits(profile, 50, 6400, 10_000L, 2 * sec) { safe }!!

    @Test
    fun defaultProfileUsesSensorRangeAndOneThirtiethStart() {
        val l = limits()
        assertEquals(50, l.isoMin)
        assertEquals(6400, l.isoMax)
        assertEquals(10_000L, l.shutterMinNanos)
        assertEquals(sec / 30, l.shutterStartNanos)
    }

    @Test
    fun profileLimitsClampToSensorAndEmptyRangeIsNull() {
        val l = limits(ProgramAeProfile(isoMin = 10, isoMax = 99_999))
        assertEquals(sensor[0], l.isoMin)
        assertEquals(sensor[1], l.isoMax)
        assertNull(ProgramAeSolver.resolveLimits(
            ProgramAeProfile(isoMin = 3200, isoMax = 100), 50, 6400, 10_000L, 2 * sec) { sec })
    }

    @Test
    fun autoSafeShutterCapsTheLongEnd() {
        val l = limits(ProgramAeProfile(useAutoSafeShutter = true), safe = sec / 15)
        assertEquals(sec / 15, l.shutterMaxNanos)
    }

    @Test
    fun lockedValuesPreferStoredThenSensor() {
        val l = limits()
        val stored = ProgramAeSolver.resolveLockedValues(
            ProgramAeProfile(lockMode = ProgramLockMode.ISO_LOCK, lockedIso = 400), l, 100, sec / 60, 800, sec / 50)
        assertEquals(400, stored.first)
        val seeded = ProgramAeSolver.resolveLockedValues(
            ProgramAeProfile(lockMode = ProgramLockMode.ISO_LOCK), l, 100, sec / 60, 800, sec / 50)
        assertEquals("unset lock seeds from the sensor, not the live pair", 800, seeded.first)
        val unlocked = ProgramAeSolver.resolveLockedValues(ProgramAeProfile(), l, 100, sec / 60, 800, sec / 50)
        assertEquals(100 to sec / 60, unlocked)
    }

}
