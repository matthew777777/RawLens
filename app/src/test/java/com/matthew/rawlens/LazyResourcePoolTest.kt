// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LazyResourcePoolTest {
    @Test fun `idle pool allocates nothing and returned resources are reused`() {
        var allocations = 0
        val pool = LazyResourcePool(5) { allocations++; Any() }
        assertEquals(0, allocations)
        val first = pool.poll(0, TimeUnit.MILLISECONDS)!!
        assertTrue(pool.offer(first))
        repeat(100) {
            val next = pool.poll(0, TimeUnit.MILLISECONDS)!!
            assertSame(first, next)
            assertTrue(pool.offer(next))
        }
        assertEquals(1, allocations)
    }
    @Test fun `demand grows to original capacity and then waits for returns`() {
        var allocations = 0
        val pool = LazyResourcePool(5) { allocations++; Any() }
        val held = List(5) { pool.poll(0, TimeUnit.MILLISECONDS)!! }
        assertNull(pool.poll(1, TimeUnit.MILLISECONDS))
        assertEquals(5, allocations)
        pool.offer(held[0])
        assertSame(held[0], pool.poll(1, TimeUnit.MILLISECONDS))
    }
    @Test fun `failed allocation releases reservation`() {
        var fail = true
        val pool = LazyResourcePool(1) { if (fail) error("allocation failed") else Any() }
        assertThrows(IllegalStateException::class.java) { pool.poll(0, TimeUnit.MILLISECONDS) }
        fail = false
        assertNotNull(pool.poll(0, TimeUnit.MILLISECONDS))
    }
    @Test fun `concurrent borrowers cannot exceed capacity`() {
        val created = java.util.concurrent.atomic.AtomicInteger()
        val pool = LazyResourcePool(3) { created.incrementAndGet(); Any() }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures = List(8) { executor.submit {
                repeat(100) {
                    val item = pool.poll(1, TimeUnit.SECONDS) ?: error("pool stalled")
                    Thread.yield()
                    assertTrue(pool.offer(item))
                }
            } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(created.get() <= 3)
        } finally { executor.shutdownNow() }
    }
}
