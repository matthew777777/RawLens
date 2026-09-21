// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class RawZslBufferTest {
    @Test fun `fifty alternating partial and complete saves release every image once`() {
        val buffer = RawZslBuffer(8)
        val images = mutableListOf<Image>()
        repeat(50) { cycle ->
            val count = if (cycle % 2 == 0) 3 else 8
            repeat(count) { index ->
                val item = frame((cycle * 100 + index).toLong())
                images += item.image
                buffer.addTestFrame(item, (cycle * 100 + index).toLong())
            }
            val selected = buffer.takeForCapture((cycle * 100 + 10).toLong(), false, 8, true)
            val job = OwnedCaptureJob(CloseOnceOwner(selected) { it.image.close() }) {
                if (cycle % 7 == 0) error("save failed")
            }
            if (cycle % 11 == 0) job.cancelBeforeRun() else runCatching { job.run() }
            buffer.clear()
            assertEquals(0, buffer.size)
        }
        images.forEach { Mockito.verify(it).close() }
    }

    @Test fun `hybrid off retries preserve images until complete selection is available`() {
        val first = frame(1000L)
        val second = frame(2000L)
        val buffer = RawZslBuffer(2)
        buffer.addTestFrame(first, 1000L)
        repeat(20) { assertTrue(buffer.takeForCapture(3000L, false, 2, false).isEmpty()) }
        assertEquals(1, buffer.size)
        Mockito.verify(first.image, Mockito.never()).close()
        buffer.addTestFrame(second, 2000L)
        val selected = buffer.takeForCapture(3000L, false, 2, false)
        assertEquals(2, selected.size)
        CloseOnceOwner(selected) { it.image.close() }.use { }
        buffer.clear()
        Mockito.verify(first.image).close()
        Mockito.verify(second.image).close()
    }

    @Test fun `hybrid on transfers partial selection to save owner even when save fails`() {
        val only = frame(1000L)
        val buffer = RawZslBuffer(3)
        buffer.addTestFrame(only, 1000L)
        val selected = buffer.takeForCapture(3000L, false, 3, true)
        assertEquals(1, selected.size)
        assertEquals(0, buffer.size)
        val owner = CloseOnceOwner(selected) { it.image.close() }
        val job = OwnedCaptureJob(owner) { error("simulated save failure") }
        runCatching { job.run() }
        owner.close()
        buffer.clear()
        Mockito.verify(only.image).close()
        assertTrue(buffer.takeForCapture(4000L, false, 3, true).isEmpty())
    }

    @Test fun `selection transfers requested frames and closes unselected frames`() {
        val first = frame(1_000L)
        val second = frame(2_000L)
        val third = frame(3_000L)
        val buffer = RawZslBuffer(3)
        buffer.addTestFrame(first, 1_000L, 0.1f)
        buffer.addTestFrame(second, 2_000L, 0.0f)
        buffer.addTestFrame(third, 3_000L, 0.2f)

        val selected = buffer.takeBest(4_000L, realtimeTimestamps = false, count = 2)

        assertEquals(listOf(2_000L, 3_000L), selected.map { it.timestampNanos })
        Mockito.verify(first.image).close()
        Mockito.verify(second.image, Mockito.never()).close()
        Mockito.verify(third.image, Mockito.never()).close()
        assertEquals(0, buffer.size)
    }

    @Test fun `insufficient selection keeps ownership in ring`() {
        val only = frame(1_000L)
        val buffer = RawZslBuffer(2)
        buffer.addTestFrame(only, 1_000L)

        assertTrue(buffer.takeBest(2_000L, false, 2).isEmpty())
        assertEquals(1, buffer.size)
        Mockito.verify(only.image, Mockito.never()).close()
        buffer.clear()
        Mockito.verify(only.image).close()
    }

    @Test fun `capacity eviction and duplicate replacement close exactly once`() {
        val original = frame(1_000L)
        val replacement = frame(1_000L)
        val newest = frame(2_000L)
        val overflow = frame(3_000L)
        val buffer = RawZslBuffer(2)

        buffer.addTestFrame(original, 1_000L)
        buffer.addTestFrame(replacement, 1_000L)
        buffer.addTestFrame(newest, 2_000L)
        buffer.addTestFrame(overflow, 3_000L)
        buffer.clear()
        buffer.clear()

        Mockito.verify(original.image).close()
        Mockito.verify(replacement.image).close()
        Mockito.verify(newest.image).close()
        Mockito.verify(overflow.image).close()
    }

    @Test fun `takeUpTo returns partial selection instead of refusing`() {
        val first = frame(1_000L)
        val second = frame(2_000L)
        val buffer = RawZslBuffer(8)
        buffer.addTestFrame(first, 1_000L)
        buffer.addTestFrame(second, 2_000L)

        val selected = buffer.takeUpTo(3_000L, realtimeTimestamps = false, maxCount = 8)

        assertEquals(listOf(1_000L, 2_000L), selected.map { it.timestampNanos })
        Mockito.verify(first.image, Mockito.never()).close()
        Mockito.verify(second.image, Mockito.never()).close()
        assertEquals(0, buffer.size)
    }

    @Test fun `takeUpTo caps at maxCount and closes the rest`() {
        val frames = (1L..5L).map { frame(it * 1_000L) }
        val buffer = RawZslBuffer(8)
        frames.forEachIndexed { index, f -> buffer.addTestFrame(f, (index + 1) * 1_000L) }

        val selected = buffer.takeUpTo(9_000L, realtimeTimestamps = false, maxCount = 3)

        assertEquals(3, selected.size)
        assertEquals(0, buffer.size)
    }

    @Test fun `takeUpTo on empty ring returns empty`() {
        val buffer = RawZslBuffer(8)
        assertTrue(buffer.takeUpTo(9_000L, realtimeTimestamps = false, maxCount = 8).isEmpty())
        assertTrue(buffer.takeUpTo(9_000L, realtimeTimestamps = false, maxCount = 0).isEmpty())
    }

    @Test fun `approximate frames lose to exact frames with equal content`() {
        val approx = frame(1_000L)
        val exact = frame(2_000L)
        val buffer = RawZslBuffer(8)
        buffer.addSnapshot(approx.image, approx.result, 1_000L, 0L, 0L, 0f,
            metadataApproximate = true)
        buffer.addTestFrame(exact, 2_000L)

        val selected = buffer.takeBest(3_000L, realtimeTimestamps = false, count = 1)

        assertEquals(listOf(2_000L), selected.map { it.timestampNanos })
        assertEquals(false, selected.single().metadataApproximate)
        assertEquals(0, buffer.size)
    }

    @Test fun `takeUpTo carries approximate flags through`() {
        val approx = frame(1_000L)
        val exact = frame(2_000L)
        val buffer = RawZslBuffer(8)
        buffer.addSnapshot(approx.image, approx.result, 1_000L, 0L, 0L, 0f,
            metadataApproximate = true)
        buffer.addTestFrame(exact, 2_000L)

        val selected = buffer.takeUpTo(3_000L, realtimeTimestamps = false, maxCount = 8)

        assertEquals(2, selected.size)
        assertEquals(true, selected.first { it.timestampNanos == 1_000L }.metadataApproximate)
        assertEquals(false, selected.first { it.timestampNanos == 2_000L }.metadataApproximate)
        Mockito.verify(approx.image, Mockito.never()).close()
        Mockito.verify(exact.image, Mockito.never()).close()
    }

    @Test fun `approximate penalty stays below genuine defect penalties`() {
        // Sanity on the -1.5 design point: an approximate converged frame must
        // still beat a hunting-AE exact frame (-2.0), so stalls degrade
        // gracefully instead of promoting motion-blurred alternatives.
        val approxGood = frame(1_000L)
        Mockito.`when`(approxGood.result.get(CaptureResult.CONTROL_AE_STATE))
            .thenReturn(CaptureResult.CONTROL_AE_STATE_CONVERGED)
        val exactBad = frame(2_000L)
        Mockito.`when`(exactBad.result.get(CaptureResult.CONTROL_AE_STATE))
            .thenReturn(CaptureResult.CONTROL_AE_STATE_SEARCHING)
        val buffer = RawZslBuffer(8)
        buffer.addSnapshot(approxGood.image, approxGood.result, 1_000L, 0L, 0L, 0f,
            metadataApproximate = true)
        buffer.addTestFrame(exactBad, 2_000L)

        val selected = buffer.takeBest(3_000L, realtimeTimestamps = false, count = 1)

        assertEquals(listOf(1_000L), selected.map { it.timestampNanos })
    }

    private fun frame(timestamp: Long): TestFrame {
        val image = Mockito.mock(Image::class.java)
        val result = Mockito.mock(TotalCaptureResult::class.java)
        return TestFrame(image, result)
    }

    private fun RawZslBuffer.addTestFrame(frame: TestFrame, timestamp: Long, motion: Float = 0f) {
        addSnapshot(frame.image, frame.result, timestamp, 0L, 0L, motion)
    }

    private data class TestFrame(val image: Image, val result: TotalCaptureResult)
}
