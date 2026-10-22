// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StillChainTest {
    @Test fun submitsQueuedStillsInOrder() {
        val chain = StillChain<String, Int>()
        chain.begin(7)
        chain.enqueue(listOf("a", "b", "c"))
        assertEquals("a", chain.takeNext())
        assertEquals("b", chain.takeNext())
        assertEquals("c", chain.takeNext())
        assertNull(chain.takeNext())
        assertEquals(7, chain.callback)
    }

    @Test fun beginDropsStillsOfThePreviousCapture() {
        val chain = StillChain<String, Int>()
        chain.begin(1)
        chain.enqueue(listOf("old1", "old2"))
        chain.begin(2)
        assertNull(chain.takeNext())
        assertEquals(2, chain.callback)
    }

    @Test fun pipelinedCaptureLeavesNothingToChain() {
        // A pipelined burst begins the chain but never enqueues: every pair
        // completion's takeNext is a no-op.
        val chain = StillChain<String, Int>()
        chain.begin(3)
        assertNull(chain.takeNext())
    }

    @Test fun clearEndsTheChain() {
        val chain = StillChain<String, Int>()
        chain.begin(4)
        chain.enqueue(listOf("a", "b"))
        chain.clear()
        assertNull(chain.takeNext())
        assertNull(chain.callback)
    }
}
