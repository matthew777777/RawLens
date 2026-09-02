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
