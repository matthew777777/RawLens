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
