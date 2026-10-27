// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class BguQuadPoolTest {
    @Test fun stalledFitKeepsItsFrameAcrossArbitraryOffers() {
        val pool = BguQuadPool(4)
        val held = pool.acquire(16)!!
        held.fill(7)
        repeat(100) {
            val next = pool.acquire(16)!!
            assertNotSame(held, next)
            next.fill(it.toByte())
            pool.release(next)
        }
        assertTrue(held.all { it == 7.toByte() })
        pool.release(held)
    }

    @Test fun capacityDropsAndReleaseAllowsReuse() {
        val pool = BguQuadPool(2)
        val a = pool.acquire(4)!!
        val b = pool.acquire(4)!!
        assertNull(pool.acquire(4))
        pool.release(a)
        assertSame(a, pool.acquire(4))
        pool.release(b)
        assertEquals(8, pool.acquire(8)!!.size)
    }
}
