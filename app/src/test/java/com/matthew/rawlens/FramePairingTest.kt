// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Handler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class FramePairingTest {
    private val delayed = mutableListOf<Pair<Runnable, Long>>()
    private val handler: Handler = mock(Handler::class.java).also { h ->
        doAnswer { inv -> delayed += inv.getArgument<Runnable>(0) to inv.getArgument<Long>(1); true }
            .`when`(h).postDelayed(any(Runnable::class.java), anyLong())
        doAnswer { inv -> val r = inv.getArgument<Runnable>(0); delayed.removeAll { it.first === r }; null }
            .`when`(h).removeCallbacks(any(Runnable::class.java))
    }

    private class Expired(val ts: Long, val image: Image?, val result: TotalCaptureResult?,
                          val zsl: PendingZslResult?, val forward: Boolean, val shot: Int)

    private inner class Host : FramePairing.Host {
        override var running = true
        val forward = mutableListOf<Pair<Long, Image>>()
        val zsl = mutableListOf<Pair<Long, Image>>()
        val expired = mutableListOf<Expired>()
        var ringFrames = 0
        override fun onForwardPair(timestamp: Long, image: Image, result: TotalCaptureResult) {
            forward += timestamp to image
        }
        override fun onZslPair(timestamp: Long, image: Image, pending: PendingZslResult) {
            zsl += timestamp to image
        }
        override fun onPairExpired(timestamp: Long, image: Image?, result: TotalCaptureResult?,
                                   zslPending: PendingZslResult?, forward: Boolean, shot: Int) {
            expired += Expired(timestamp, image, result, zslPending, forward, shot)
        }
        override fun evictOldestRingFrame(): Boolean = if (ringFrames > 0) { ringFrames--; true } else false
    }

    private val host = Host()
    private val pairing = FramePairing(handler, host)
    private fun image(): Image = mock(Image::class.java)
    private fun result(): TotalCaptureResult = mock(TotalCaptureResult::class.java)
    private fun zsl() = PendingZslResult(result(), 0f, 1L)
    private fun schedule(ts: Long, forward: Boolean, current: Boolean = true) =
        pairing.scheduleTimeout(ts, forward, shot = 7, isCurrent = { current },
            forwardTimeoutMs = 5_000L, zslTimeoutMs = 150L)

    @Test
    fun imageFirstThenForwardResultPairs() {
        val img = image()
        pairing.putImage(10L, img)
        schedule(10L, forward = false)
        pairing.putForwardResult(10L, result())
        pairing.pairAvailable(10L)
        assertEquals(listOf(10L to img), host.forward)
        assertTrue("pairing cancels the timeout", delayed.isEmpty())
        assertEquals(0, pairing.pendingImageCount)
    }

    @Test
    fun zslResultPairsWithItsImage() {
        val img = image()
        pairing.putZslResult(20L, zsl())
        pairing.putImage(20L, img)
        pairing.pairAvailable(20L)
        assertSame(img, host.zsl.single().second)
        assertFalse(pairing.hasZslResult(20L))
    }

    @Test
    fun forwardResultWinsOverZslForTheSameTimestamp() {
        pairing.putImage(30L, image())
        pairing.putZslResult(30L, zsl())
        pairing.putForwardResult(30L, result())
        pairing.pairAvailable(30L)
        assertEquals(1, host.forward.size)
        assertTrue(host.zsl.isEmpty())
    }

    @Test
    fun halfPairWaitsAndNothingPairsAfterStop() {
        pairing.putForwardResult(40L, result())
        pairing.pairAvailable(40L)
        assertTrue(host.forward.isEmpty())
        host.running = false
        pairing.putImage(40L, image())
        pairing.pairAvailable(40L)
        assertTrue(host.forward.isEmpty())
        assertEquals(1, pairing.pendingImageCount)
    }

    @Test
    fun timeoutHandsEveryWaitingHalfToTheHost() {
        val img = image()
        pairing.putImage(50L, img)
        pairing.putZslResult(50L, zsl())
        schedule(50L, forward = false)
        assertEquals(150L, delayed.single().second)
        delayed.single().first.run()
        val e = host.expired.single()
        assertSame(img, e.image)
        assertFalse(e.forward)
        assertEquals(7, e.shot)
        assertEquals(0, pairing.pendingImageCount)
        assertFalse(pairing.hasZslResult(50L))
    }

    @Test
    fun staleTimeoutIsIgnored() {
        pairing.putImage(60L, image())
        schedule(60L, forward = true, current = false)
        assertEquals(5_000L, delayed.single().second)
        delayed.single().first.run()
        assertTrue(host.expired.isEmpty())
        assertTrue(pairing.hasImage(60L))
    }

    @Test
    fun reschedulingReplacesTheWindow() {
        schedule(70L, forward = false)
        schedule(70L, forward = true)
        assertEquals(listOf(5_000L), delayed.map { it.second })
    }

    @Test
    fun repeatingRawTimestampIsRememberedOnce() {
        pairing.dropPreviewRaw(80L)
        assertTrue(pairing.consumePreviewRaw(80L))
        assertFalse(pairing.consumePreviewRaw(80L))
    }

    @Test
    fun repeatingRawWithImageHeldDropsIt() {
        pairing.putImage(90L, image())
        pairing.dropPreviewRaw(90L)
        assertFalse(pairing.hasImage(90L))
        assertFalse("image path does not remember the timestamp", pairing.consumePreviewRaw(90L))
    }

    @Test
    fun pressureReliefPrefersUnmatchedThenRingThenOldestPairing() {
        pairing.putImage(100L, image())
        pairing.putForwardResult(100L, result())
        pairing.putImage(110L, image())
        host.ringFrames = 1
        assertTrue(pairing.relievePressure())
        assertFalse("unmatched image goes first", pairing.hasImage(110L))
        assertTrue(pairing.relievePressure())
        assertEquals("then a ring frame", 0, host.ringFrames)
        assertTrue(pairing.hasImage(100L))
        assertTrue(pairing.relievePressure())
        assertFalse("last resort: oldest pairing", pairing.hasImage(100L))
        assertFalse(pairing.relievePressure())
    }

    @Test
    fun closeUnmatchedKeepsImagesWithAResult() {
        pairing.putImage(120L, image())
        pairing.putZslResult(120L, zsl())
        pairing.putImage(130L, image())
        pairing.closeUnmatched()
        assertTrue(pairing.hasImage(120L))
        assertFalse(pairing.hasImage(130L))
    }

    @Test
    fun clearZslLeavesForwardPairings() {
        pairing.putImage(140L, image())
        pairing.putZslResult(140L, zsl())
        pairing.putImage(150L, image())
        pairing.putForwardResult(150L, result())
        pairing.clearZsl()
        assertFalse(pairing.hasImage(140L))
        assertTrue(pairing.hasImage(150L))
    }

    @Test
    fun clearAllForgetsEverything() {
        pairing.putImage(160L, image())
        schedule(160L, forward = true)
        pairing.dropPreviewRaw(170L)
        pairing.clearAll()
        assertEquals(0, pairing.pendingImageCount)
        assertTrue(delayed.isEmpty())
        assertFalse(pairing.consumePreviewRaw(170L))
    }

    @Test
    fun teardownKeepsRepeatingTimestampsLikeBefore() {
        pairing.dropPreviewRaw(180L)
        pairing.putImage(190L, image())
        pairing.clearForTeardown(ignoreReleaseFailures = true)
        assertEquals(0, pairing.pendingImageCount)
        assertTrue(pairing.consumePreviewRaw(180L))
    }

    @Test
    fun displacedDuplicateImageIsReleased() {
        val first = image()
        pairing.putImage(200L, first)
        pairing.putImage(200L, image())
        verify(first).close()
        assertEquals(1, pairing.pendingImageCount)
    }

    @Test
    fun lostBufferDropsTheWaitingResultOfThatFrameOnly() {
        val lost = result().also { org.mockito.Mockito.`when`(it.frameNumber).thenReturn(41L) }
        val kept = result().also { org.mockito.Mockito.`when`(it.frameNumber).thenReturn(42L) }
        pairing.putForwardResult(210L, lost)
        schedule(210L, forward = true)
        pairing.putForwardResult(220L, kept)
        assertTrue(pairing.dropForwardResultForFrame(41L))
        assertTrue("its timeout is cancelled", delayed.isEmpty())
        assertFalse("already dropped", pairing.dropForwardResultForFrame(41L))
        assertFalse("unknown frame", pairing.dropForwardResultForFrame(99L))
        pairing.putImage(220L, image())
        pairing.pairAvailable(220L)
        assertEquals(1, host.forward.size)
    }

    @Test
    fun missingHalfNeverPairs() {
        pairing.pairAvailable(999L)
        assertTrue(host.forward.isEmpty() && host.zsl.isEmpty())
        assertNull(host.expired.firstOrNull())
    }
}
