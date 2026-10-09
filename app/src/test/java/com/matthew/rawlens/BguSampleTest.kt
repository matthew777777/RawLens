// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BguSampleTest {
    private fun plane8(): ByteBuffer {
        // 8x8 u16 codes: code(x, y) = (x + y * 8) * 16 (0..1008).
        val buf = ByteBuffer.allocate(8 * 8 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until 8) for (x in 0 until 8) {
            buf.putShort((y * 8 + x) * 2, ((x + y * 8) * 16).toShort())
        }
        return buf
    }

    @Test fun samplesQuadsPlanar() {
        val out = ByteArray(2 * 2 * 4)
        val ok = BguSample.sampleLowFrame(
            plane8(), 16, 2, 0, 0, 4, 2, 2,
            intArrayOf(0, 1, 2, 3), FloatArray(4), FloatArray(4) { 1f / 1023 },
            out
        )
        assertTrue(ok)
        // Texel (0,0) <- quad (0,0): codes 0, 16, 128, 144.
        assertEquals(0.toByte(), out[0])
        assertEquals(4.toByte(), out[4])
        assertEquals(32.toByte(), out[8])
        assertEquals(36.toByte(), out[12])
        // Texel (1,0) <- quad (4,0): codes 64, 80, 192, 208.
        assertEquals(16.toByte(), out[1])
        assertEquals(20.toByte(), out[5])
        assertEquals(48.toByte(), out[9])
        assertEquals(52.toByte(), out[13])
        // Texel (0,1) <- quad (0,4): codes 512, 528, 640, 656.
        assertEquals(128.toByte(), out[2])
        assertEquals(132.toByte(), out[6])
        assertEquals(160.toByte(), out[10])
        assertEquals(164.toByte(), out[14])
    }

    @Test fun samplesStridedPlane() {
        // Same codes at pixelStride 4 (rowStride 32).
        val buf = ByteBuffer.allocate(8 * 32).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until 8) for (x in 0 until 8) {
            buf.putShort(y * 32 + x * 4, ((x + y * 8) * 16).toShort())
        }
        val out = ByteArray(2 * 2 * 4)
        val ok = BguSample.sampleLowFrame(
            buf, 32, 4, 0, 0, 4, 2, 2,
            intArrayOf(0, 1, 2, 3), FloatArray(4), FloatArray(4) { 1f / 1023 },
            out
        )
        assertTrue(ok)
        assertEquals(0.toByte(), out[0])
        assertEquals(4.toByte(), out[4])
        assertEquals(32.toByte(), out[8])
        assertEquals(36.toByte(), out[12])
    }

    @Test fun clampsAndBlackLevel() {
        val out = ByteArray(1 * 1 * 4)
        val ok = BguSample.sampleLowFrame(
            plane8(), 16, 2, 6, 6, 4, 1, 1,
            intArrayOf(0, 1, 2, 3), FloatArray(4) { 64f }, FloatArray(4) { 1f / (1023 - 64) },
            out
        )
        assertTrue(ok)
        // Quad (6,6): codes 864, 880, 992, 1008 -> (code-64)/959.
        assertEquals(213.toByte(), out[0]) // (800/959*255+.5) = 213.2 -> 213
        assertEquals(217.toByte(), out[1]) // (816/959*255+.5) = 217.5 -> 217 (banker's? no: toInt truncates 217.45 -> 217)
        assertEquals(247.toByte(), out[2])
        assertEquals(251.toByte(), out[3])
    }

    @Test fun rejectsOutOfBounds() {
        val out = ByteArray(2 * 2 * 4)
        assertFalse(
            BguSample.sampleLowFrame(
                plane8(), 16, 2, 0, 0, 64, 2, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), FloatArray(4) { 1f / 1023 },
                out
            )
        )
        assertFalse(
            BguSample.sampleLowFrame(
                plane8(), 0, 2, 0, 0, 4, 2, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), FloatArray(4) { 1f / 1023 },
                out
            )
        )
    }
}
