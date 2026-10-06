// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SrMergeExecutorTest {
    @Test fun `single flight config pins one thread and zero queue`() {
        val executor = createSrMergeExecutor()
        try {
            assertEquals(1, executor.maximumPoolSize)
            assertTrue(executor.queue is SynchronousQueue<*>)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `second submit while busy rejects instead of queueing burst pins`() {
        val executor = createSrMergeExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            executor.execute {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            try {
                executor.execute { fail("must not queue behind a running merge") }
                fail("expected RejectedExecutionException")
            } catch (_: RejectedExecutionException) {
                // Single-flight backpressure: the shutter fails fast with
                // SR BUSY instead of pinning a second burst against the ring.
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
