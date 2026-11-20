package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeQueueLimitTest {
    private val gb = 1L shl 30
    private val px12 = 4080L * 3060 // 12.5 MP

    @Test fun eightGbPhoneTwelveMpEightFrames() {
        // working set 12.5M * 92 * 1.1 = 1.26 GB; copy 0.19 GiB each
        assertEquals(8, MergeMemoryBudget.mergeQueueLimit(5L * gb / 2, 1, px12, 8))
        assertEquals(2, MergeMemoryBudget.mergeQueueLimit(3L * gb / 2, 1, px12, 8))
    }

    @Test fun lowHeadroomStillAdmitsWhatIsInFlightOnly() {
        assertEquals(1, MergeMemoryBudget.mergeQueueLimit(gb, 1, px12, 8))
        assertEquals(1, MergeMemoryBudget.mergeQueueLimit(0, 0, px12, 8))
    }

    @Test fun unknownHeadroomKeepsOldLimit() {
        assertEquals(3, MergeMemoryBudget.mergeQueueLimit(null, 1, px12, 8))
    }
}
