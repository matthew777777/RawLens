// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class CaptureQueueCapacityTest {
    @Test fun `six individual JPEG inputs fit before saving completes then seventh waits`() {
        var pending = 0
        repeat(6) {
            assertTrue(CaptureQueueCapacity.accepts(pending, 1, 6, 20, 10))
            pending++
        }
        assertFalse(CaptureQueueCapacity.accepts(pending, 1, 6, 20, 10))
        pending-- // completion or save failure releases one slot
        assertTrue(CaptureQueueCapacity.accepts(pending, 1, 6, 20, 10))
    }
    @Test fun `multi-frame selection requires capacity for the entire selection`() {
        assertTrue(CaptureQueueCapacity.accepts(4, 2, 6, 20, 10))
        assertFalse(CaptureQueueCapacity.accepts(5, 2, 6, 20, 10))
        assertTrue(CaptureQueueCapacity.accepts(0, 6, 6, 20, 10))
    }
    @Test fun `preview reserve bounds a larger DNG queue and ring refill`() {
        assertFalse(CaptureQueueCapacity.accepts(10, 1, 30, 20, 10))
        assertTrue(CaptureQueueCapacity.ringFits(6, 2, 20, 10))
        assertFalse(CaptureQueueCapacity.ringFits(6, 8, 20, 10))
        assertFalse(CaptureQueueCapacity.accepts(0, 1, 6, 0, 10))
        assertFalse(CaptureQueueCapacity.accepts(0, 0, 6, 20, 10))
    }
}
