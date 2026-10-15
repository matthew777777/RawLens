// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Chroma-from-luma: damps lane oscillation, never touches the guide. */
class RawSrChromaFromLumaTest {
    @Test fun flatFieldIsUnchanged() {
        val w = 16
        val h = 12
        val rgb = FloatArray(w * h * 3) { o ->
            when (o % 3) { 0 -> 0.20f; 1 -> 0.35f; else -> 0.15f }
        }
        val before = rgb.copyOf()
        assertEquals(w * h, RawSrChromaFromLuma.stabilize(rgb, w, h))
        for (i in rgb.indices) assertEquals("lane $i", before[i], rgb[i], 1e-6f)
    }

    @Test fun rowAlternatingChromaIsDampedAndGuideBitIdentical() {
        // R/B latch ±40% row-to-row (the staircase mechanism) under smooth G.
        val w = 16
        val h = 16
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            val flip = if (y % 2 == 0) 1.4f else 0.6f
            rgb[o] = 0.25f * flip
            rgb[o + 1] = 0.30f
            rgb[o + 2] = 0.20f * (2f - flip)
        }
        val guideBefore = FloatArray(w * h) { rgb[it * 3 + 1] }
        RawSrChromaFromLuma.stabilize(rgb, w, h)
        for (i in guideBefore.indices) assertEquals("G $i", guideBefore[i], rgb[i * 3 + 1], 0f)
        // Interior oscillation amplitude must collapse (sigma-1.0 kills Nyquist).
        var maxStep = 0f
        for (y in 4 until h - 4) {
            val d = abs(rgb[(y * w + 8) * 3] - rgb[((y - 1) * w + 8) * 3])
            maxStep = maxOf(maxStep, d)
        }
        assertTrue("residual step=$maxStep", maxStep < 0.02f)
        // Mean level preserved (no chroma shift).
        var mean = 0.0
        for (y in 0 until h) mean += rgb[(y * w + 8) * 3]
        assertEquals(0.25, mean / h, 0.005)
    }

    @Test fun darkGuideKeepsOriginalLanes() {
        val w = 8
        val h = 8
        val rgb = FloatArray(w * h * 3) { 0.3f }
        rgb[1] = 0f
        rgb[0] = 0.11f
        rgb[2] = 0.07f
        RawSrChromaFromLuma.stabilize(rgb, w, h)
        assertEquals(0.11f, rgb[0], 0f)
        assertEquals(0f, rgb[1], 0f)
        assertEquals(0.07f, rgb[2], 0f)
    }

    @Test fun deadGuideNeighbourKeepsEveryLaneFinite() {
        // A pure-black pixel (guide exactly 0: fallback/healed cell, deep
        // shadow) amid live pixels: tap ratios over it are undefined (x/0),
        // so stabilized neighbours must keep their original lanes — finite —
        // never NaN/Inf (which the DNG writer refuses, failing the save).
        val w = 16
        val h = 16
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            val flip = if (y % 2 == 0) 1.4f else 0.6f
            rgb[o] = 0.25f * flip
            rgb[o + 1] = 0.30f
            rgb[o + 2] = 0.20f * (2f - flip)
        }
        // Pure black (0/0 ratios) and chroma-only (x/0 ratios) dead taps.
        val black = (8 * w + 8) * 3
        rgb[black] = 0f
        rgb[black + 1] = 0f
        rgb[black + 2] = 0f
        val red = (8 * w + 12) * 3
        val redR = rgb[red]
        val redB = rgb[red + 2]
        rgb[red + 1] = 0f
        val before = rgb.copyOf()
        assertEquals(w * h - 2, RawSrChromaFromLuma.stabilize(rgb, w, h))
        for (i in rgb.indices) assertTrue("lane $i=${rgb[i]}", rgb[i].isFinite())
        // Dead centers keep their original lanes (center guide floor).
        assertEquals(0f, rgb[black], 0f)
        assertEquals(0f, rgb[black + 1], 0f)
        assertEquals(0f, rgb[black + 2], 0f)
        assertEquals(redR, rgb[red], 0f)
        assertEquals(0f, rgb[red + 1], 0f)
        assertEquals(redB, rgb[red + 2], 0f)
        // Live pixels whose 7x7 window covers a dead tap keep their
        // original lanes (undefined ratios fall back to passthrough).
        for ((x, y) in listOf(8 to 5, 5 to 8, 11 to 11)) {
            val o = (y * w + x) * 3
            assertEquals("R($x,$y)", before[o], rgb[o], 0f)
            assertEquals("B($x,$y)", before[o + 2], rgb[o + 2], 0f)
        }
        // Pixels clear of every dead tap still stabilize (Nyquist
        // oscillation damped).
        val far = (2 * w + 2) * 3
        assertTrue("far pixel stabilized", abs(rgb[far] - before[far]) > 1e-4f)
    }

    @Test fun smoothRampSurvivesWithoutShift() {
        // A smooth chroma ramp must pass through nearly unchanged (no blur
        // artifacts on legitimate gradients), up to border clamping inside.
        val w = 24
        val h = 8
        val rgb = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            rgb[o] = 0.1f + 0.01f * x
            rgb[o + 1] = 0.3f
            rgb[o + 2] = 0.2f
        }
        val before = rgb.copyOf()
        RawSrChromaFromLuma.stabilize(rgb, w, h)
        for (y in 0 until h) for (x in 4 until w - 4) {
            val o = (y * w + x) * 3
            assertEquals("R($x,$y)", before[o], rgb[o], 0.004f)
        }
    }
}
