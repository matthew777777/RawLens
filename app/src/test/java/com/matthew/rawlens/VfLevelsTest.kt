// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VfLevelsTest {
    @Test fun `canonical quad vectors match the old float bytes`() {
        // Same vectors the on-device CPU test pins (black 0, white 510).
        val (blackQ, denQ) = VfLevels.toFixedQ6(FloatArray(4), 510f)
        val bytes = intArrayOf(100, 200, 300, 400).map { VfLevels.normalizeByte(it, blackQ[0], denQ[0]) }
        assertEquals(listOf(50, 100, 150, 200), bytes)
    }

    @Test fun `per-site levels clip like the old float path`() {
        // (10,40,65535,0) at black=(10,20,30,40), white=60.
        val (blackQ, denQ) = VfLevels.toFixedQ6(floatArrayOf(10f, 20f, 30f, 40f), 60f)
        val codes = intArrayOf(10, 40, 65535, 0)
        val bytes = codes.mapIndexed { i, code -> VfLevels.normalizeByte(code, blackQ[i], denQ[i]) }
        assertEquals(listOf(0, 128, 255, 0), bytes)
    }

    @Test fun `fractional black resolves to 64ths`() {
        val (blackQ, denQ) = VfLevels.toFixedQ6(floatArrayOf(64.5f, 64f, 64f, 64f), 1023f)
        assertEquals((64.5f * 64 + 0.5f).toInt(), blackQ[0])
        assertEquals(4128, blackQ[0])
        assertEquals(0, VfLevels.normalizeByte(64, blackQ[0], denQ[0]))
        // Half a code above black stays 0, like (0.5/958.5*255+0.5).toInt().
        assertEquals(0, VfLevels.normalizeByte(65, blackQ[0], denQ[0]))
        assertTrue(VfLevels.normalizeByte(70, blackQ[0], denQ[0]) > 0)
    }

    @Test fun `degenerate range clamps to one code`() {
        val (blackQ, denQ) = VfLevels.toFixedQ6(floatArrayOf(2000f, 2000f, 2000f, 2000f), 1023f)
        assertEquals(64, denQ[0])
        assertEquals(0, VfLevels.normalizeByte(2000, blackQ[0], denQ[0]))
        assertEquals(255, VfLevels.normalizeByte(2001, blackQ[0], denQ[0]))
    }

    @Test fun `bit-cast round-trips and never yields NaN`() {
        val (blackQ, denQ) = VfLevels.toFixedQ6(floatArrayOf(0f, 64.5f, 2000f, 65535f), 1023f)
        val bits = VfLevels.toBits(blackQ, denQ)
        assertEquals(8, bits.size)
        for (i in 0..3) assertEquals(blackQ[i], bits[i].toRawBits())
        for (i in 0..3) assertEquals(denQ[i], bits[4 + i].toRawBits())
        for (f in bits) {
            val exp = (f.toRawBits() ushr 23) and 0xff
            assertTrue("NaN/Inf pattern: ${f.toRawBits().toString(16)}", exp != 0xff)
        }
    }

    @Test fun `absurd white clamps the denominator`() {
        val (_, denQ) = VfLevels.toFixedQ6(FloatArray(4), 1e20f)
        assertEquals(VfLevels.MAX_DEN_Q, denQ[0])
        // Mid codes still normalize (near-black), nothing overflows.
        assertEquals(0, VfLevels.normalizeByte(100, 0, denQ[0]))
    }

    @Test fun `intermediates stay in signed 32 bit`() {
        // Worst case: full-scale code, zero black, minimum range.
        val num = 65535 * VfLevels.SCALE
        val denQ = VfLevels.SCALE
        val product = num.toLong() * 255 + denQ / 2
        assertTrue(product < Int.MAX_VALUE)
        assertEquals(255, VfLevels.normalizeByte(65535, 0, denQ))
    }

    @Test fun `random codes stay within one LSB of the float reference`() {
        // The integer formula deliberately re-rounds: document the bound
        // (old float: (code-black)*inv*255+0.5 truncated).
        val rnd = java.util.Random(11)
        val blacks = floatArrayOf(10f, 64.5f, 30f, 40f)
        val white = 1023f
        val (blackQ, denQ) = VfLevels.toFixedQ6(blacks, white)
        val inv = FloatArray(4) { i -> 1f / (white - blacks[i]).coerceAtLeast(1f) }
        repeat(20000) {
            val ch = rnd.nextInt(4)
            val code = rnd.nextInt(65536)
            val want = VfLevels.normalizeByte(code, blackQ[ch], denQ[ch])
            val n = ((code - blacks[ch]) * inv[ch]).coerceIn(0f, 1f)
            val got = (n * 255f + 0.5f).toInt()
            val d = kotlin.math.abs(want - got)
            assertTrue("code=$code ch=$ch int=$want float=$got", d <= 1)
        }
    }

    @Test fun `sanity accepts reported levels and rejects Vivo-style garbage`() {
        assertTrue(VfLevels.isSane(floatArrayOf(64f, 64f, 64f, 64f), 1023f))
        assertTrue(VfLevels.isSane(floatArrayOf(1024f, 1024f, 1024f, 1024f), 8712f))
        // Vivo X300 Ultra dynamic-levels modes report white=0 placeholders.
        assertFalse(VfLevels.isSane(floatArrayOf(0f, 0f, 0f, 0f), 0f))
        assertFalse(VfLevels.isSane(floatArrayOf(64f, 64f, 64f, 64f), 0f))
        // Mode-mismatched pairs clamp every code to 0 (no throw to catch).
        assertFalse(VfLevels.isSane(floatArrayOf(2000f, 2000f, 2000f, 2000f), 1023f))
        assertFalse(VfLevels.isSane(floatArrayOf(1024f, 1024f, 1024f, 1024f), 1024f))
        assertFalse(VfLevels.isSane(floatArrayOf(64f, 64f, 64f, 64f), Float.NaN))
        assertFalse(VfLevels.isSane(floatArrayOf(64f, Float.NaN, 64f, 64f), 1023f))
        assertFalse(VfLevels.isSane(floatArrayOf(64f, 64f, 64f), 1023f))
    }

    @Test fun `data-driven fallback spans the sample and is always sane`() {
        val (black, white) = VfLevels.fallbackFromSample(64, 900)
        assertEquals(listOf(64f, 64f, 64f, 64f), black.toList())
        assertEquals(900f, white)
        assertTrue(VfLevels.isSane(black, white))
        // Uniform frame (lens cap): one code of range, no throw downstream.
        val (flatBlack, flatWhite) = VfLevels.fallbackFromSample(512, 512)
        assertEquals(513f, flatWhite)
        assertTrue(VfLevels.isSane(flatBlack, flatWhite))
        // Hostile inputs clamp into range instead of escaping it.
        val (clampedBlack, clampedWhite) = VfLevels.fallbackFromSample(-40, 999999)
        assertEquals(0f, clampedBlack[0])
        assertEquals(65535f, clampedWhite)
        assertTrue(VfLevels.isSane(clampedBlack, clampedWhite))
        // Q6 conversion of fallback output never throws.
        VfLevels.toFixedQ6(black, white)
        VfLevels.toFixedQ6(flatBlack, flatWhite)
        VfLevels.toFixedQ6(clampedBlack, clampedWhite)
    }

    @Test fun `last resort is sane`() {
        val (black, white) = VfLevels.lastResort()
        assertTrue(VfLevels.isSane(black, white))
        VfLevels.toFixedQ6(black, white)
    }
}
