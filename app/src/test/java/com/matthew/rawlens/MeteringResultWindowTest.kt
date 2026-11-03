// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class MeteringResultWindowTest {
    @Test fun delayedWorkerKeepsOriginalExposureInsteadOfLatest() {
        val window = MeteringResultWindow<Pair<Int, Long>>()
        window.put(100L, 100 to 10_000_000L)
        val sampledFrame = window[100L]
        window.put(200L, 800 to 30_000_000L)
        assertEquals(100 to 10_000_000L, sampledFrame)
        assertEquals(sampledFrame, window[100L])
        assertNull(window[150L])
    }
    @Test fun historyIsBoundedAndResetDropsOldSession() {
        val window = MeteringResultWindow<Int>(2)
        window.put(1, 10); window.put(2, 20); window.put(3, 30)
        assertNull(window[1]); assertEquals(20, window[2])
        window.clear()
        assertNull(window[2]); assertNull(window[3])
    }
}
