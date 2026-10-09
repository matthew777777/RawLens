// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DngLevelRepairTest {
    private fun packedFrame(
        width: Int, height: Int, rowStride: Int, fill: (x: Int, y: Int) -> Int
    ): ByteBuffer {
        val buffer = ByteBuffer.allocate(height * rowStride).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer.putShort(y * rowStride + x * 2, fill(x, y).toShort())
            }
        }
        return buffer
    }

    @Test fun `sample finds per-phase minima and the peak on a padded layout`() {
        val width = 8
        val height = 8
        val rowStride = 20 // 16 packed + 4 pad
        val buffer = packedFrame(width, height, rowStride) { x, y ->
            when {
                x == 0 && y == 0 -> 10 // phase 0 (TL)
                x == 1 && y == 0 -> 20 // phase 1 (TR)
                x == 0 && y == 1 -> 30 // phase 2 (BL)
                x == 1 && y == 1 -> 40 // phase 3 (BR)
                x == 7 && y == 7 -> 900
                else -> 500
            }
        }
        val sample = DngLevelRepair.sample(buffer, rowStride, 2, width, height)!!
        assertEquals(listOf(10, 20, 30, 40), sample.phaseMin.toList())
        assertEquals(900, sample.max)
    }

    @Test fun `sample covers every Bayer phase on a strided grid`() {
        // 256x192 strides 4x4 from the origin: a naive stride would never
        // visit odd columns/rows and two phases would stay unsampled.
        val width = 256
        val height = 192
        val rowStride = 528 // 512 packed + 16 pad
        val buffer = packedFrame(width, height, rowStride) { x, y ->
            when {
                x == 5 && y == 5 -> 5 // odd/odd: phase 3 minimum
                x == 4 && y == 4 -> 9000 // even/even: phase 0 peak
                else -> 777
            }
        }
        val sample = DngLevelRepair.sample(buffer, rowStride, 2, width, height)!!
        assertEquals(5, sample.phaseMin[3])
        assertEquals(9000, sample.max)
        assertTrue(sample.phaseMin.all { it in 0..9000 })
    }

    @Test fun `sample returns null on hostile layouts instead of throwing`() {
        val tiny = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
        assertNull(DngLevelRepair.sample(tiny, 2, 0, 2, 1)) // no pixel stride
        assertNull(DngLevelRepair.sample(tiny, -1, 2, 2, 1)) // negative row stride
        assertNull(DngLevelRepair.sample(tiny, 2, 2, 0, 1)) // empty frame
        assertNull(DngLevelRepair.sample(ByteBuffer.allocate(0), 2, 2, 2, 1))
        // Stride claims past the store: rows 1+ unreadable, phases 2/3 missing.
        assertNull(DngLevelRepair.sample(tiny, 100000, 2, 64, 64))
    }

    @Test fun `ceiling white rounds the peak up to standard rungs`() {
        assertEquals(1023f, DngLevelRepair.ceilingWhite(0))
        assertEquals(1023f, DngLevelRepair.ceilingWhite(1023))
        assertEquals(4095f, DngLevelRepair.ceilingWhite(1024))
        assertEquals(4095f, DngLevelRepair.ceilingWhite(4095))
        assertEquals(16383f, DngLevelRepair.ceilingWhite(4096))
        // Non-standard truths (Vivo DCG 8712) land on the next rung up, unclipped.
        assertEquals(16383f, DngLevelRepair.ceilingWhite(8712))
        assertEquals(16383f, DngLevelRepair.ceilingWhite(16383))
        assertEquals(65535f, DngLevelRepair.ceilingWhite(16384))
        assertEquals(65535f, DngLevelRepair.ceilingWhite(65535))
    }

    @Test fun `repair output is always sane`() {
        val (black, white) = DngLevelRepair.repair(SampledLevels(intArrayOf(64, 66, 63, 65), 900))
        assertEquals(listOf(64f, 66f, 63f, 65f), black.toList())
        assertEquals(1023f, white)
        assertTrue(VfLevels.isSane(black, white))
        // Uniform frame at exactly a ceiling rung still clears by one code.
        val (flatBlack, flatWhite) = DngLevelRepair.repair(
            SampledLevels(intArrayOf(1023, 1023, 1023, 1023), 1023))
        assertEquals(1024f, flatWhite)
        assertTrue(VfLevels.isSane(flatBlack, flatWhite))
        // Defensive clamps never escape sanity either.
        val (wildBlack, wildWhite) = DngLevelRepair.repair(
            SampledLevels(intArrayOf(-5, 70000, 10, 10), 70000))
        assertTrue(VfLevels.isSane(wildBlack, wildWhite))
        // Q6 conversion of repair output never throws.
        VfLevels.toFixedQ6(black, white)
        VfLevels.toFixedQ6(flatBlack, flatWhite)
        VfLevels.toFixedQ6(wildBlack, wildWhite)
    }

    @Test fun `needsRepair fires on missing or insane pairs only`() {
        val sane = floatArrayOf(64f, 64f, 64f, 64f)
        assertFalse(DngLevelRepair.needsRepair(sane, 1023f))
        assertFalse(DngLevelRepair.needsRepair(floatArrayOf(1024f, 1024f, 1024f, 1024f), 8712f))
        assertTrue(DngLevelRepair.needsRepair(null, 1023f))
        assertTrue(DngLevelRepair.needsRepair(sane, null))
        assertTrue(DngLevelRepair.needsRepair(FloatArray(4), 0f)) // Vivo white=0
        assertTrue(DngLevelRepair.needsRepair(floatArrayOf(1024f, 1024f, 1024f, 1024f), 1023f))
        assertTrue(DngLevelRepair.needsRepair(floatArrayOf(64f, 64f, 64f), 1023f))
        assertTrue(DngLevelRepair.needsRepair(floatArrayOf(64f, Float.NaN, 64f, 64f), 1023f))
    }
}
