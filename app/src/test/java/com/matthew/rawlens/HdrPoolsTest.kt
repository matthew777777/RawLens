// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class HdrPoolsTest {
    @Test fun failedStripWaitsForSiblingBeforeReturningStorage() {
        val pool = Executors.newFixedThreadPool(2)
        val release = CountDownLatch(1)
        val siblingStarted = CountDownLatch(1)
        val waiterStarted = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val wrote = AtomicBoolean()
        val failure = AtomicReference<Throwable>()
        val broken = pool.submit { throw IllegalStateException("strip failed") }
        val sibling = pool.submit {
            siblingStarted.countDown()
            release.await()
            wrote.set(true)
        }
        val waiter = Thread {
            waiterStarted.countDown()
            try { HdrPools.awaitWorkers(listOf(broken, sibling)) }
            catch (t: Throwable) { failure.set(t) }
            finally { returned.countDown() }
        }
        try {
            assertTrue(siblingStarted.await(5, TimeUnit.SECONDS))
            waiter.start()
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS))
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertTrue(wrote.get())
            assertTrue(failure.get() is ExecutionException)
            assertEquals("strip failed", failure.get().cause?.message)
        } finally {
            release.countDown()
            waiter.join(5000)
            pool.shutdownNow()
        }
    }

    @Test fun interruptedWaitDrainsWorkerAndRestoresInterrupt() {
        val pool = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val restored = AtomicBoolean()
        val failure = AtomicReference<Throwable>()
        val task = pool.submit { release.await() }
        val waiter = Thread {
            Thread.currentThread().interrupt()
            try { HdrPools.awaitWorkers(listOf(task)) }
            catch (t: Throwable) { failure.set(t) }
            finally {
                restored.set(Thread.currentThread().isInterrupted)
                returned.countDown()
            }
        }
        try {
            waiter.start()
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertTrue(task.isDone)
            assertTrue(restored.get())
            assertTrue(failure.get() is InterruptedException)
        } finally {
            release.countDown()
            waiter.join(5000)
            pool.shutdownNow()
        }
    }
}
