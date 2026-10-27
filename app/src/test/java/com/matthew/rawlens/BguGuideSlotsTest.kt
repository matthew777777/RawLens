// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class BguGuideSlotsTest {
    @Test fun producerCannotLapStalledConsumer() {
        val slots = BguGuideSlots()
        assertTrue(slots.tryWrite(0))
        slots.publish(0,1)
        assertFalse(slots.tryRead(0,1))
        slots.finishWrite(0)
        assertTrue(slots.tryRead(0,1))
        repeat(100) { assertFalse(slots.tryWrite(0)) }
        slots.finishRead(0)
        assertTrue(slots.tryWrite(0))
        slots.finishWrite(0)
        assertFalse(slots.tryRead(0,1))
    }

    @Test fun clearInvalidatesJobsWithoutRevokingLeases() {
        val slots = BguGuideSlots()
        slots.tryWrite(0)
        slots.publish(0,1)
        slots.finishWrite(0)
        assertTrue(slots.tryRead(0,1))
        slots.clear()
        assertFalse(slots.tryWrite(0))
        slots.finishRead(0)
        assertFalse(slots.tryRead(0,1))
        assertTrue(slots.tryWrite(0))
    }
}
