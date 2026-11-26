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

    private fun sample(max: Int, min: Int = 1000) = SampledLevels(IntArray(4) { min }, max)

    @Test fun `deeper storage scales 10-bit levels to a 14-bit DCG payload`() {
        val (black, white) = DngLevelRepair.deeperStorage(floatArrayOf(64f, 64f, 64f, 64f), 1023f, sample(9000))!!
        assertEquals(16383f, white)
        black.forEach { assertEquals(1024f, it) }
    }

    @Test fun `deeper storage scales 10-bit levels to a 12-bit payload`() {
        val (black, white) = DngLevelRepair.deeperStorage(floatArrayOf(64f, 65f, 63f, 64f), 1023f, sample(3000, 250))!!
        assertEquals(4095f, white)
        assertEquals(listOf(256f, 260f, 252f, 256f), black.toList())
    }

    @Test fun `deeper storage scales 12-bit levels to a 14-bit payload`() {
        val (black, white) = DngLevelRepair.deeperStorage(floatArrayOf(256f, 256f, 256f, 256f), 4095f, sample(12000))!!
        assertEquals(16383f, white)
        black.forEach { assertEquals(1024f, it) }
    }

    @Test fun `deeper storage keeps levels when the data fits or the pair is unusable`() {
        assertNull(DngLevelRepair.deeperStorage(floatArrayOf(64f, 64f, 64f, 64f), 1023f, sample(1023, 60)))
        assertNull(DngLevelRepair.deeperStorage(floatArrayOf(1024f, 1024f, 1024f, 1024f), 16383f, sample(16000)))
        assertNull(DngLevelRepair.deeperStorage(floatArrayOf(1024f, 1024f, 1024f, 1024f), 8712f, sample(8712)))
        assertNull(DngLevelRepair.deeperStorage(floatArrayOf(64f, 64f, 64f), 1023f, sample(9000)))
        assertNull(DngLevelRepair.deeperStorage(floatArrayOf(2000f, 64f, 64f, 64f), 1023f, sample(9000)))
    }

    @Test fun `non-standard levels scale by a power of two for a deeper payload`() {
        val (black, white) = DngLevelRepair.deeperStorage(floatArrayOf(60f, 60f, 62f, 60f), 1000f, sample(15000, 900))!!
        assertEquals(16000f, white)
        assertEquals(listOf(960f, 960f, 992f, 960f), black.toList())
    }

    @Test fun `slight overshoot raises only the white`() {
        val (black, white) = DngLevelRepair.deeperStorage(floatArrayOf(64f, 64f, 64f, 64f), 1000f, sample(1023, 60))!!
        assertEquals(1023f, white)
        black.forEach { assertEquals(64f, it) }
        // vivo-style 8712 with a peak past it: no standard ceiling within 1.5x -> the sampled peak.
        val (black2, white2) = DngLevelRepair.deeperStorage(floatArrayOf(1024f, 1024f, 1024f, 1024f), 8712f, sample(9000))!!
        assertEquals(9000f, white2)
        black2.forEach { assertEquals(1024f, it) }
    }

    @Test fun `16-bit payload on 14-bit levels`() {
        val (black, white) = DngLevelRepair.deeperStorage(floatArrayOf(1024f, 1024f, 1024f, 1024f), 16383f, sample(60000, 4000))!!
        assertEquals(65535f, white)
        black.forEach { assertEquals(4096f, it) }
    }

    @Test fun `a dead pixel does not veto the deeper levels`() {
        val levels = DngLevelRepair.deeperStorage(floatArrayOf(64f, 64f, 64f, 64f), 1023f,
            SampledLevels(intArrayOf(0, 1010, 1012, 1009), 15000))
        assertEquals(16383f, levels!!.second)
    }
}
