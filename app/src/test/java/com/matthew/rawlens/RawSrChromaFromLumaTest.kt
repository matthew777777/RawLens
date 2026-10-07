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

    @Test fun deadTapsStayFiniteAndKeepTheirLanes() {
        // Pure-black taps (G == 0, plus merge undershoot G < 0) inside a live
        // field: the tap division must not spray NaN/inf into neighbors, and
        // the dead centers keep their original lanes like any dark guide.
        val w = 16
        val h = 16
        val rgb = FloatArray(w * h * 3) { o ->
            when (o % 3) { 0 -> 0.25f; 1 -> 0.30f; else -> 0.20f }
        }
        val dead = arrayOf(Triple(4, 4, 0f), Triple(9, 5, -1e-6f), Triple(6, 11, 0f))
        for ((x, y, g) in dead) {
            val o = (y * w + x) * 3
            rgb[o] = 0.001f
            rgb[o + 1] = g
            rgb[o + 2] = 0.001f
        }
        assertEquals(w * h - dead.size, RawSrChromaFromLuma.stabilize(rgb, w, h))
        for (i in rgb.indices) assertTrue("lane $i = ${rgb[i]}", rgb[i].isFinite())
        for ((x, y, g) in dead) {
            val o = (y * w + x) * 3
            assertEquals("R($x,$y)", 0.001f, rgb[o], 0f)
            assertEquals("G($x,$y)", g, rgb[o + 1], 0f)
            assertEquals("B($x,$y)", 0.001f, rgb[o + 2], 0f)
        }
        // Neighbors rebuild toward neutral, never spike: ratios stay inside
        // the live field's band around the flat level.
        for ((x, y) in arrayOf(Pair(5, 4), Pair(4, 5), Pair(10, 5))) {
            val o = (y * w + x) * 3
            assertEquals("R($x,$y)", 0.25f, rgb[o], 0.06f)
            assertEquals("B($x,$y)", 0.20f, rgb[o + 2], 0.06f)
        }
    }

    @Test fun deadTapsContributeZeroDifference() {
        // An isolated live pixel in a black field: every tap but the center
        // is dead, so the smoothed difference is the center tap's difference
        // decaying to zero — pinning what dead taps contribute (not just
        // finiteness). No division anywhere, so black can never poison.
        val w = 9
        val h = 9
        val rgb = FloatArray(w * h * 3) { 0f }
        val o = (4 * w + 4) * 3
        rgb[o] = 0.25f
        rgb[o + 1] = 0.30f
        rgb[o + 2] = 0.20f
        assertEquals(1, RawSrChromaFromLuma.stabilize(rgb, w, h))
        val k = RawSrChromaFromLuma.KERNEL
        val wc = k[0] * k[0] // 2D weight of the lone live (center) tap
        val expR = 0.30f + wc * (0.25f - 0.30f)
        val expB = 0.30f + wc * (0.20f - 0.30f)
        assertEquals(expR, rgb[o], 1e-4f)
        assertEquals(0.30f, rgb[o + 1], 0f)
        assertEquals(expB, rgb[o + 2], 1e-4f)
        for (i in rgb.indices) assertTrue("lane $i = ${rgb[i]}", rgb[i].isFinite())
    }

    @Test fun deepShadowNoiseStaysBounded() {
        // Synthetic deep-shadow noise with G pits (0 and merge undershoot)
        // plus hot R/B taps on near-zero guides (the donut seeds): ratio-CFL
        // turned these into magenta donuts (0.07 input spiking past 3.0);
        // the difference form stays inside the input envelope.
        val w = 16
        val h = 16
        val rgb = FloatArray(w * h * 3)
        val rnd = java.util.Random(7)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            val g = if ((x * 7 + y * 13) % 11 == 0) 0f
            else if ((x * 5 + y * 3) % 17 == 0) -2e-4f
            else 0.002f + 0.001f * rnd.nextFloat()
            rgb[o] = 0.0015f + 0.004f * rnd.nextFloat()
            rgb[o + 1] = g
            rgb[o + 2] = 0.0012f + 0.004f * rnd.nextFloat()
        }
        // Donut seeds: live guides just above the floor under hot lanes.
        var o = (4 * w + 4) * 3
        rgb[o] = 0.05f
        rgb[o + 1] = 1.1e-4f
        o = (9 * w + 10) * 3
        rgb[o + 1] = 1.2e-4f
        rgb[o + 2] = 0.04f
        val maxInR = rgb.filterIndexed { i, _ -> i % 3 == 0 }.max()
        val maxInB = rgb.filterIndexed { i, _ -> i % 3 == 2 }.max()
        RawSrChromaFromLuma.stabilize(rgb, w, h)
        for (i in rgb.indices) assertTrue("lane $i = ${rgb[i]}", rgb[i].isFinite())
        val maxOutR = rgb.filterIndexed { i, _ -> i % 3 == 0 }.max()
        val maxOutB = rgb.filterIndexed { i, _ -> i % 3 == 2 }.max()
        assertTrue("R amplified: $maxOutR vs input $maxInR", maxOutR <= 0.5f * maxInR)
        assertTrue("B amplified: $maxOutB vs input $maxInB", maxOutB <= 0.5f * maxInB)
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
