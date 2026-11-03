// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class RecordingHealthTest {
    private val health = RecordingHealth()
    private fun update(ms: Long, drops: Int = 0, bytes: Long = ms * 1000, fps: Float = 30f,
        pressure: Boolean = false, thermal: Int = 0, free: Long? = null) =
        health.update(ms, drops, bytes, fps, 30, pressure, thermal, free)

    @Test fun dropWarningClearsButHistoricalCountRemains() {
        assertTrue(update(1000).contains("STARTING"))
        assertTrue(update(3500).contains("OK"))
        assertTrue(update(4000, drops = 2).contains("FRAME DROPS"))
        assertTrue(update(8500, drops = 2).contains("FRAME DROPS"))
        assertEquals("HEALTH OK · D2", update(9500, drops = 2))
    }

    @Test fun noOutputOverridesAHealthyEncoderRate() {
        update(2000, bytes = 10_000)
        assertTrue(update(5500, bytes = 10_000).contains("NO OUTPUT"))
        assertTrue(update(6000, bytes = 20_000).contains("OK"))
    }

    @Test fun storageThermalPressureAndCadenceAreSurfaced() {
        assertTrue(update(6000, free = 100, thermal = 3).contains("LOW SPACE"))
        assertTrue(update(6500, thermal = 3).contains("HOT"))
        assertTrue(update(7000, pressure = true).contains("BUSY"))
        assertTrue(update(7500, thermal = 2).contains("WARM"))
        assertTrue(update(8000, fps = 20f).contains("SLOW"))
        assertTrue(update(8500, free = null).contains("OK"))
    }

    @Test fun resetStartsANewTakeWithoutStaleDropOrWriteHistory() {
        update(6000, drops = 4)
        health.reset()
        assertEquals("HEALTH STARTING · D0", update(500, bytes = 0))
        assertTrue(update(6000, bytes = 0).contains("NO OUTPUT"))
    }

    @Test fun focusPullHitsBothEndpointsAndMovesMonotonicallyInEitherDirection() {
        for ((start, end) in listOf(0L to 8500L, 8500L to 0L)) {
            assertEquals(start, FocusPull.value(start, end, -1, 1200))
            var previous = start
            for (ms in 0L..1200L step 33) {
                val next = FocusPull.value(start, end, ms, 1200)
                assertTrue(next in minOf(start, end)..maxOf(start, end))
                assertTrue(if (end > start) next >= previous else next <= previous)
                previous = next
            }
            assertEquals(end, FocusPull.value(start, end, 1200, 1200))
            assertEquals(end, FocusPull.value(start, end, 2000, 1200))
        }
    }
}
