package com.matthew.rawlens

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PhotoDevelopmentExecutorTest {
    @Test fun recreatedControllerWaitsForOldSaveAndKeepsItsOwnThread() {
        val old = PhotoDevelopmentExecutor(4)
        val recreated = PhotoDevelopmentExecutor(4)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val nextEntered = CountDownLatch(1)
        try {
            val first = old.submit<Thread> {
                entered.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                Thread.currentThread()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            old.shutdown() // Activity destruction lets accepted work drain.
            val next = recreated.submit<Thread> {
                nextEntered.countDown()
                Thread.currentThread()
            }
            assertFalse(nextEntered.await(100, TimeUnit.MILLISECONDS))
            finish.countDown()
            val oldThread = first.get(5, TimeUnit.SECONDS)
            val newThread = next.get(5, TimeUnit.SECONDS)
            assertNotSame(oldThread, newThread)
            val failed = recreated.submit { throw IllegalStateException("failed save") }
            try { failed.get(5, TimeUnit.SECONDS); fail("Expected failed save") }
            catch (_: java.util.concurrent.ExecutionException) { }
            assertSame(newThread, recreated.submit<Thread> { Thread.currentThread() }
                .get(5, TimeUnit.SECONDS))
            // A failure also releases the process-wide lock for other controllers.
            val third = PhotoDevelopmentExecutor(1)
            try { third.submit { }.get(5, TimeUnit.SECONDS) }
            finally { third.shutdownNow() }
        } finally {
            finish.countDown()
            old.shutdownNow()
            recreated.shutdownNow()
        }
    }
}
