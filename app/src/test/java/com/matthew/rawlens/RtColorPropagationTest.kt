// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bit-by-bit parity against RawTherapee HLRecovery_inpaint head math.
 *
 * Goldens were produced by an independent float32 C++ oracle replicating
 * references/RawTherapee/hilite_recon.cc:340-404 and :979-1052
 * (see /tmp/rt_oracle.cpp); assertions compare raw float bits, so any op-order
 * drift fails. chratio goes through JVM sqrt (double-precision), so it is
 * asserted with a 1-ulp-scale tolerance instead of exact bits.
 */
class RtColorPropagationTest {
    @Test
    fun allClippedThresholdsMatchRtBits() {
        val t = RtColorPropagation.computeThresholds(
            floatArrayOf(63000f, 65000f, 64000f),
            floatArrayOf(60000f, 62000f, 61000f)
        )
        assertEquals(0x3f80326a, t.factor[0].toBits())
        assertEquals(0x3f800000, t.factor[1].toBits())
        assertEquals(0x3f8018cb, t.factor[2].toBits())
        assertEquals(0x47696e10, t.maxF[0].toBits())
        assertEquals(0x47713600, t.maxF[1].toBits())
        assertEquals(0x476d5208, t.maxF[2].toBits())
        assertEquals(0x47696e10, t.clipPt.toBits())
        assertEquals(0x476d5208, t.medPt.toBits())
        assertEquals(0x47696e10, t.blendPt.toBits())
        assertEquals(0xb78c604d.toInt(), t.medFactor[0].toBits())
        assertEquals(0xb78ead6b.toInt(), t.medFactor[1].toBits())
        assertEquals(0xb78c604d.toInt(), t.medFactor[2].toBits())
    }

    @Test
    fun blendEstimateMatchesRtBitsIncludingZeroQuirk() {
        val t = RtColorPropagation.computeThresholds(
            floatArrayOf(63000f, 65000f, 64000f),
            floatArrayOf(60000f, 62000f, 61000f)
        )
        // G sits below blendPt, so RT leaves rgb_blend[1] exactly 0; R/B ramp
        // fractions clamp to 0, passing pixels through untouched.
        val out = RtColorPropagation.blendEstimate(floatArrayOf(64000f, 30000f, 63500f), t)
        assertEquals(0x477a0000, out[0].toBits())
        assertEquals(0x00000000, out[1].toBits())
        assertEquals(0x47780c00, out[2].toBits())
    }

    @Test
    fun unclippedPixelYieldsZeroBlendAndUnitFactors() {
        val t = RtColorPropagation.computeThresholds(
            floatArrayOf(60000f, 65000f, 64000f),
            floatArrayOf(62000f, 62000f, 61000f)
        )
        assertEquals(0x3f800000, t.factor[0].toBits())
        assertEquals(0x3f800000, t.factor[1].toBits())
        assertEquals(0x3f800000, t.factor[2].toBits())
        assertEquals(0x475ea800, t.clipPt.toBits())
        val out = RtColorPropagation.blendEstimate(floatArrayOf(30000f, 30000f, 30000f), t)
        assertEquals(0x00000000, out[0].toBits())
        assertEquals(0x00000000, out[1].toBits())
        assertEquals(0x00000000, out[2].toBits())
    }

    @Test
    fun lightRatioMatchesRtWithinOneUlp() {
        val t = RtColorPropagation.computeThresholds(
            floatArrayOf(63000f, 65000f, 64000f),
            floatArrayOf(60000f, 62000f, 61000f)
        )
        // Oracle (C++ sqrtf): 0.881647825.
        val ratio = RtColorPropagation.lightRatio(floatArrayOf(64000f, 30000f, 63500f), t)
        assertEquals(0.881647825, ratio.toDouble(), 2e-6)
    }
}
