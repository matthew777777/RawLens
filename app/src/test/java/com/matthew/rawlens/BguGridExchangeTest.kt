// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class BguGridExchangeTest {
    @Test fun stalledConsumerNeverSeesOverwrittenCoefficients() {
        val exchange = BguGridExchange()
        fun publish(seq: Long) {
            val grid = exchange.beginFit(2, 2, 9)
            grid.coeffs.fill(seq.toFloat())
            exchange.publish(BguFittedGrid(seq, 32, 32, grid))
        }
        publish(1)
        val held = exchange.acquireLatest()!!
        repeat(100) { publish(it + 2L) }
        assertTrue(held.grid.coeffs.all { it == 1f })
        val newest = exchange.acquireLatest()!!
        assertEquals(101L, newest.seq)
        assertTrue(newest.grid.coeffs.all { it == 101f })
    }

    @Test fun resizingKeepsHeldGridAndPublishesMatchingGeometry() {
        val exchange = BguGridExchange()
        val old = exchange.beginFit(2, 2, 9)
        old.coeffs.fill(7f)
        exchange.publish(BguFittedGrid(1, 32, 32, old))
        assertSame(old, exchange.acquireLatest()!!.grid)
        val next = exchange.beginFit(5, 4, 9)
        exchange.publish(BguFittedGrid(2, 68, 51, next))
        assertTrue(old.coeffs.all { it == 7f })
        val current = exchange.acquireLatest()!!
        assertEquals(68, current.lowW)
        assertEquals(51, current.lowH)
        assertEquals(5, current.grid.gw)
    }

    @Test fun clearRetainsInFlightReaderLease() {
        val exchange = BguGridExchange()
        val held = exchange.beginFit(1, 1, 9)
        exchange.publish(BguFittedGrid(1, 1, 1, held))
        exchange.acquireLatest()
        exchange.clear()
        assertNotSame(held, exchange.beginFit(1, 1, 9))
        assertNull(exchange.acquireLatest())
    }

    @Test fun oldSessionFitCannotPublishAfterClear() {
        val exchange = BguGridExchange()
        val generation = exchange.generation
        val old = exchange.beginFit(1, 1, 9)
        exchange.clear()
        exchange.publish(BguFittedGrid(99, 1, 1, old), generation)
        assertNull(exchange.acquireLatest())
    }

    @Test fun steadyStateReusesAtMostThreeArrays() {
        val exchange = BguGridExchange()
        val arrays = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FloatArray, Boolean>())
        repeat(100) {
            val grid = exchange.beginFit(2, 2, 9)
            arrays.add(grid.coeffs)
            exchange.publish(BguFittedGrid(it.toLong(), 32, 32, grid))
            if (it % 3 == 0) exchange.acquireLatest()
        }
        assertTrue(arrays.size <= 3)
    }
}
