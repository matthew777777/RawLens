// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class RawSrGpuSchedulingTest {
    @Test fun slicesCoverEveryPixelOnceIncludingPartialWorkgroups() {
        for ((w, h) in listOf(7 to 5, 514 to 386, 4080 to 3060)) {
            val visits = ByteArray(w * h)
            for (s in RawSrGpuScheduling.slices(w, h, 8, 8, 131072)) {
                assertTrue(s.groupsX * s.groupsY * 64 <= 131072)
                for (y in s.y until minOf(h, s.y + s.groupsY * 8))
                    for (x in s.x until minOf(w, s.x + s.groupsX * 8)) visits[y * w + x]++
            }
            assertTrue(visits.all { it == 1.toByte() })
        }
    }

    @Test fun expensiveAlignmentIsBoundedEvenWhenOneRowExceedsBudget() {
        val slices = RawSrGpuScheduling.slices(255, 192, 1, 1, 64).toList()
        assertTrue(slices.all { it.groupsX * it.groupsY <= 64 })
        assertEquals(255 * 192, slices.sumOf { it.groupsX * it.groupsY })
    }

    @Test fun rendererStallDoesNotRestartHealthyRawStream() {
        assertFalse(RawSrGpuScheduling.shouldRecoverCamera(5000, 4900))
        assertTrue(RawSrGpuScheduling.shouldRecoverCamera(5000, 2999))
        assertTrue(RawSrGpuScheduling.shouldRecoverCamera(5000, 0))
    }
}
