// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * darktable raw-strength port: uniform per-sample blend, no re-inference.
 * 0 = source, 1 = full model output (darktable 100%).
 */
class RawNindBlendTest {
    @Test fun fullStrengthReturnsDenoised() {
        val src = floatArrayOf(0.1f, 0.2f, 0.3f)
        val den = floatArrayOf(0.5f, 0.6f, 0.7f)
        assertSame(den, RawNindBlend.mix(src, den, 1f))
    }

    @Test fun zeroStrengthReturnsSource() {
        val src = floatArrayOf(0.1f, 0.2f, 0.3f)
        val den = floatArrayOf(0.5f, 0.6f, 0.7f)
        assertSame(src, RawNindBlend.mix(src, den, 0f))
    }

    @Test fun halfStrengthAveragesPerSample() {
        val src = floatArrayOf(0f, 1f, 0.25f, 0.75f)
        val den = floatArrayOf(1f, 0f, 0.75f, 0.25f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), RawNindBlend.mix(src, den, 0.5f), 1e-6f)
    }

    @Test fun strengthIsClamped() {
        val src = floatArrayOf(0.2f)
        val den = floatArrayOf(0.8f)
        assertArrayEquals(floatArrayOf(0.8f), RawNindBlend.mix(src, den, 2f), 0f)
        assertArrayEquals(floatArrayOf(0.2f), RawNindBlend.mix(src, den, -1f), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedBuffersRejected() {
        RawNindBlend.mix(floatArrayOf(1f), floatArrayOf(1f, 2f), 0.5f)
    }

    @Test fun packedExpandReplicatesQuadRgb() {
        // One RGGB quad: R=0.4, G1=0.5, G2=0.7 (-> G=0.6), B=0.8.
        val rgb = RawNindBlend.expandPackedToRgb(floatArrayOf(0.4f, 0.5f, 0.7f, 0.8f), 2, 2)
        assertEquals(12, rgb.size)
        for (p in 0 until 4) {
            assertArrayEquals(
                floatArrayOf(0.4f, 0.6f, 0.8f),
                rgb.copyOfRange(p * 3, p * 3 + 3), 1e-6f
            )
        }
    }
}
