// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens
import org.junit.Assert.*
import org.junit.Test

class SensorCadenceTest {
    @Test fun `measures delivered 24 30 and 60 fps independently of target`() {
        for (rate in listOf(24, 30, 60)) {
            val cadence = SensorCadence()
            var measured: Double? = null
            for (i in 0..rate * 2) cadence.add(1_000_000_000L + i * 1_000_000_000L / rate)?.let { measured = it }
            assertEquals(rate.toDouble(), measured!!, 0.01)
        }
    }
    @Test fun `duplicates missing and reordered results do not inflate FPS`() {
        val cadence = SensorCadence()
        assertNull(cadence.add(null))
        assertNull(cadence.add(1_000_000_000L))
        assertNull(cadence.add(1_000_000_000L))
        assertNull(cadence.add(999_999_999L))
        var measured: Double? = null
        for (i in 1..60) cadence.add(1_000_000_000L + i * 1_000_000_000L / 30)?.let { measured = it }
        assertEquals(30.0, measured!!, 0.01)
    }
}
