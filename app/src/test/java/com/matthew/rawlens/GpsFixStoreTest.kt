package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class GpsFixStoreTest {
    private val now = 1_000_000_000_000L
    private val second = 1_000_000_000L
    private fun gps(latitude: Double = 1.0, accuracy: Float? = 5f, wallTime: Long = 1L) =
        GpsLocation(latitude, 2.0, timeMillis = wallTime, accuracyMeters = accuracy)

    @Test fun everyFixExpiresIncludingLiveAndSeededFixes() {
        val store = GpsFixStore()
        val token = store.reset(true)
        val fix = gps()
        store.offer(token, fix, now, now, "gps")
        assertEquals(fix, store.snapshot(now + GpsFixStore.MAX_AGE_NANOS))
        assertNull(store.snapshot(now + GpsFixStore.MAX_AGE_NANOS + 1))
        store.offer(token, fix, now, now + GpsFixStore.MAX_AGE_NANOS + 1, "gps")
        assertNull(store.snapshot(now + GpsFixStore.MAX_AGE_NANOS + 1))
    }

    @Test fun wallClockChangesDoNotAffectFreshness() {
        for (wallTime in listOf(0L, Long.MAX_VALUE)) {
            val store = GpsFixStore()
            val token = store.reset(true)
            val fix = gps(wallTime = wallTime)
            store.offer(token, fix, now, now, "gps")
            assertEquals(fix, store.snapshot(now + second))
            assertNull(store.snapshot(now + 121 * second))
        }
    }

    @Test fun rejectsFutureUnknownAndInvalidAccuracyFixes() {
        val store = GpsFixStore()
        val token = store.reset(true)
        for (measured in listOf(-1L, 0L, now + 1)) store.offer(token, gps(), measured, now, "gps")
        for (accuracy in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            store.offer(token, gps(accuracy = accuracy), now, now, "gps")
        }
        assertNull(store.snapshot(now))
    }

    @Test fun resetRejectsLateCallbacksAndClearsFineFixBeforeCoarseRestart() {
        val store = GpsFixStore()
        val fineToken = store.reset(true)
        store.offer(fineToken, gps(), now, now, "gps")
        store.reset(false)
        assertNull(store.snapshot(now))
        val coarseToken = store.reset(true)
        store.offer(fineToken, gps(), now, now, "gps")
        assertNull(store.snapshot(now))
        val coarse = gps(accuracy = 2000f)
        store.offer(coarseToken, coarse, now, now, "network")
        assertEquals(coarse, store.snapshot(now))
    }

    @Test fun nearbyImpreciseNetworkFixDoesNotDisplaceAccurateGps() {
        val store = GpsFixStore()
        val token = store.reset(true)
        val accurate = gps()
        store.offer(token, accurate, now, now, "gps")
        store.offer(token, gps(2.0, 2000f), now + second, now + second, "network")
        assertEquals(accurate, store.snapshot(now + second))
        val moved = gps(3.0, 100f)
        store.offer(token, moved, now + 31 * second, now + 31 * second, "network")
        assertEquals(moved, store.snapshot(now + 31 * second))
    }

    @Test fun betterOlderFixHasBoundedRecencyAndKnownAccuracyWins() {
        val store = GpsFixStore()
        val token = store.reset(true)
        val recent = gps(accuracy = null)
        store.offer(token, recent, now, now, "network")
        store.offer(token, gps(2.0, 1f), now - 31 * second, now, "gps")
        assertEquals(recent, store.snapshot(now))
        val better = gps(3.0, 5f)
        store.offer(token, better, now - second, now, "gps")
        assertEquals(better, store.snapshot(now))
    }
}
