// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected values hand-derived from Magic Lantern modules/ettr/ettr.c @ 142472e. */
class MagicLanternEttrTest {
    private fun at(level: Float) = EttrQuadStats(
        IntArray(EttrQuadStats.BINS).also { it[EttrQuadStats.binOf(level)] = 1000 }, 1000,
        if (level >= EttrQuadStats.OVEREXPOSED_LEVEL) 1000 else 0)

    /** 6000 quads evenly over [fromEv, toEv], clipped at white. */
    private fun spread(fromEv: Double, toEv: Double): EttrQuadStats {
        val hist = IntArray(EttrQuadStats.BINS)
        var over = 0
        val n = 6000
        for (i in 0 until n) {
            val ev = minOf(0.0, fromEv + (toEv - fromEv) * (i + 0.5) / n)
            val level = Math.pow(2.0, ev).toFloat()
            hist[EttrQuadStats.binOf(level)]++
            if (level >= EttrQuadStats.OVEREXPOSED_LEVEL) over++
        }
        return EttrQuadStats(hist, n, over)
    }

    @Test fun percentileIsEvBelowWhite() {
        assertEquals(-1.0, at(0.5f).percentileEv(999), 1e-9)
        assertEquals(0.0, at(1f).percentileEv(999), 1e-9)
    }

    @Test fun fullCorrectionToTheDefaultLevel() {
        // Highlights at -3 EV, level -1 EV: +2 EV in one step (no clamp).
        val r = MagicLanternEttr.correction(at(0.125f), MagicLanternEttr.Memory(), -1.0, Double.NaN)
        assertEquals(2.0, r.correctionEv, 1e-9)
    }

    @Test fun targetNeverCloserThanHalfAStop() {
        val r = MagicLanternEttr.correction(at(0.125f), MagicLanternEttr.Memory(), -0.2, Double.NaN)
        assertEquals(2.5, r.correctionEv, 1e-9)
    }

    @Test fun fullyClippedFrameFallsBackToMinusTenEv() {
        // No memory: -log2(1 + 100^2) = -13.3, limited to -10 EV.
        val r = MagicLanternEttr.correction(at(1f), MagicLanternEttr.Memory(), -1.0, Double.NaN)
        assertTrue(r.overexposed)
        assertEquals(-10.0, r.correctionEv, 1e-9)
    }

    @Test fun clippedFrameExtrapolatesFromTheLastGoodFrame() {
        val memory = MagicLanternEttr.Memory()
        // Good frame: highlights at -2 EV.
        MagicLanternEttr.correction(spread(-8.0, -2.0), memory, -1.0, Double.NaN)
        // Same scene 3 EV brighter: top sixth clipped; lower percentiles + remembered
        // spacing put the true highlight near +1 EV -> correction near -2 EV.
        val r = MagicLanternEttr.correction(spread(-5.0, 1.0), memory, -1.0, Double.NaN)
        assertTrue(r.overexposed)
        assertEquals(-2.0, r.correctionEv, 0.05)
    }

    @Test fun midtoneSnrLimitRaisesTheCorrection() {
        // Highlight = median = shadow = -3 EV, correction +2. DR 8: 8 - 3 + 2 = 7 >= 6, kept.
        assertEquals(2.0, MagicLanternEttr.correction(at(0.125f), MagicLanternEttr.Memory(), -1.0, 8.0).correctionEv, 1e-9)
        // DR 5: midtone 4 < 6 -> +2 more (shadow 4 >= 2).
        assertEquals(4.0, MagicLanternEttr.correction(at(0.125f), MagicLanternEttr.Memory(), -1.0, 5.0).correctionEv, 1e-9)
    }

    @Test fun dynamicRangeFromNoiseOffset() {
        assertEquals(10.0, MagicLanternEttr.dynamicRangeEv(Math.pow(2.0, -20.0)), 1e-9)
        assertTrue(MagicLanternEttr.dynamicRangeEv(0.0).isNaN())
    }
    /** Same scene, G four stops above R/B: MAX tail flattens, MIN still slopes. */
    private fun greenClips(exposureEv: Double): EttrQuadStats {
        val maxHist = IntArray(EttrQuadStats.BINS)
        val minHist = IntArray(EttrQuadStats.BINS)
        var over = 0
        val n = 6000
        for (i in 0 until n) {
            val green = Math.pow(2.0, -4.0 + 2.0 * (i + 0.5) / n + exposureEv)
            val g = minOf(1.0, green).toFloat()
            val rb = minOf(1.0, green / 16).toFloat()
            maxHist[EttrQuadStats.binOf(g)]++
            minHist[EttrQuadStats.binOf(rb)]++
            if ((g + 2 * rb) / 3 >= EttrQuadStats.OVEREXPOSED_LEVEL) over++
        }
        return EttrQuadStats(maxHist, n, over, minHist)
    }

    @Test fun minChannelsRetainOvershootWhenGreenTailIsEntirelyClipped() {
        val memory = MagicLanternEttr.Memory()
        MagicLanternEttr.correction(greenClips(0.0), memory, -0.5, Double.NaN)
        val result = MagicLanternEttr.correction(greenClips(4.0), memory, -0.5, Double.NaN)
        assertTrue(result.overexposed)
        // True G highlight near +2 EV: go back 2.5 EV, even though AVG is below 80%.
        assertEquals(-2.5, result.correctionEv, 0.04)
    }

    @Test fun clearingMemoryDropsBothProjectionHistories() {
        val memory = MagicLanternEttr.Memory()
        MagicLanternEttr.correction(greenClips(0.0), memory, -0.5, Double.NaN)
        assertTrue(memory.hasMinGradient)
        memory.clear()
        assertTrue(!memory.hasMinGradient)
        assertTrue(memory.diffFromMin.all { it == 0.0 })
        assertTrue(memory.diffFromLower.all { it == 0.0 })
    }

    @Test fun disabledSnrLimitsDoNotAddTheClippedFrameProbe() {
        val r = MagicLanternEttr.correction(at(1f), MagicLanternEttr.Memory(),
            -0.5, 10.0, midtoneSnrEv = 0.0, shadowSnrEv = 0.0)
        assertEquals(-10.0, r.correctionEv, 0.0)
    }

}
