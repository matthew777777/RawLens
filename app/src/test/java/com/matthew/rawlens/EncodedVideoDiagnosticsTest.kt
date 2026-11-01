// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class EncodedVideoDiagnosticsTest {
    @Test fun samplingIsBoundedToFirstTwoFrames() {
        assertFalse(EncodedVideoDiagnostics.shouldSample(-1))
        assertTrue(EncodedVideoDiagnostics.shouldSample(0))
        assertTrue(EncodedVideoDiagnostics.shouldSample(1))
        assertFalse(EncodedVideoDiagnostics.shouldSample(2))
    }

    @Test fun gridIncludesEdgesAndNeverSplitsAQuad() {
        for ((w, h) in listOf(2 to 2, 67 to 71, 3840 to 1920)) {
            val points = EncodedVideoDiagnostics.points(w, h)
            assertEquals(32, points.size)
            assertEquals(0 to 0, points.first())
            assertEquals(((w - 2) and -2) to ((h - 2) and -2), points.last())
            assertTrue(points.all { (x, y) -> x >= 0 && y >= 0 && x + 1 < w && y + 1 < h && x % 2 == 0 && y % 2 == 0 })
        }
    }

    @Test fun rawSamplesHonorCropPaddingAndBufferPosition() {
        val bytes = ByteBuffer.allocate(4 + 8 * 24).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until 8) for (x in 0 until 10) bytes.putShort(4 + y * 24 + x * 2, (y * 100 + x).toShort())
        bytes.position(4)
        val plane = EncodedVideoDiagnostics.Plane(bytes, 24, 2)
        val samples = EncodedVideoDiagnostics.rawQuads(plane, 2, 2, 6, 4)
        assertArrayEquals(intArrayOf(202, 203, 302, 303), samples.take(4).toIntArray())
        assertArrayEquals(intArrayOf(406, 407, 506, 507), samples.takeLast(4).toIntArray())
        assertEquals(4, bytes.position())
    }

    @Test fun p010ReadsInterleavedViewsWithoutChangingTheirPositions() {
        val y = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        val uv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) y.putShort(2 + i * 2, (100 shl 6).toShort())
        uv.putShort(2, (200 shl 6).toShort()); uv.putShort(4, (300 shl 6).toShort())
        y.position(2); uv.position(2)
        val v = uv.duplicate().apply { position(4) }
        val samples = EncodedVideoDiagnostics.p010(listOf(
            EncodedVideoDiagnostics.Plane(y, 4, 2), EncodedVideoDiagnostics.Plane(uv, 8, 4),
            EncodedVideoDiagnostics.Plane(v, 8, 4)), 2, 2)
        assertTrue(samples.toList().chunked(3).all { it == listOf(100 shl 6, 200 shl 6, 300 shl 6) })
        assertEquals(2, uv.position()); assertEquals(4, v.position())
        assertTrue(EncodedVideoDiagnostics.describeP010(samples).startsWith("lowBitsNonzero=0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedPlaneFailsWithoutReadingOutsideBuffer() {
        EncodedVideoDiagnostics.Plane(ByteBuffer.allocate(3), 4, 2).word(1, 0)
    }

    @Test fun copyComparisonDetectsWordCorruption() {
        assertEquals("checked=3 mismatches=0 maxWordDelta=0",
            EncodedVideoDiagnostics.compare(intArrayOf(64, 128, 192), intArrayOf(64, 128, 192)))
        assertEquals("checked=3 mismatches=1 maxWordDelta=64",
            EncodedVideoDiagnostics.compare(intArrayOf(64, 128, 192), intArrayOf(64, 192, 192)))
    }
}
