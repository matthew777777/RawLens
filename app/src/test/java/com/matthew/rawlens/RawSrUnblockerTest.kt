// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

/**
 * Unblocker tests. Criteria encode docs/raw-sr-unblocker.md: exact
 * degenerates (flat, Nyquist checker), noise-dominated keeps weight,
 * no-model keeps weight, step edges attenuate partially, and the bake
 * caps weights (min, never compounding) with an explicit flag.
 *
 * Fixture rule (see the doc): noise fixtures must be model-plausible —
 * white noise against a clean model is indistinguishable from stuck taps
 * and fires every single-frame gate correctly. Model variance here always
 * covers the fixture's actual variance with margin.
 */
class RawSrUnblockerTest {
    private fun gray(w: Int, h: Int, v: (x: Int, y: Int) -> Float) =
        RawSrGrayImage(w, h, FloatArray(w * h) { i -> v(i % w, i / w) })

    @Test fun flatFieldYieldsExactOnes() {
        val u = RawSrUnblocker.computeFrame(gray(16, 12, { _, _ -> 0.5f }), 0.02, 1e-6)
        assertEquals(16 * 12, u.size)
        assertTrue(u.all { it == 1f })
    }

    @Test fun nyquistCheckerYieldsExactZeros() {
        // 2x2 boxing averages every checker cell to flat: total variance loss.
        val u = RawSrUnblocker.computeFrame(
            gray(16, 12, { x, y -> ((x + y) and 1).toFloat() }), 1e-6, 1e-9)
        assertTrue(u.all { it == 0f })
    }

    @Test fun noiseDominatedFieldKeepsWeight() {
        // Uniform ±0.01 noise (variance ~3.3e-5) under a 5x-covering model:
        // nothing here is signal, so nothing attenuates.
        val random = kotlin.random.Random(0xB10C)
        val g = gray(16, 12, { _, _ -> 0.5f + (random.nextFloat() * 2f - 1f) * 0.01f })
        val u = RawSrUnblocker.computeFrame(g, 0.0, 5.0 * 3.4e-5)
        assertTrue(u.all { it == 1f })
    }

    @Test fun stepEdgeAttenuatesPartially() {
        // A vertical step survives boxing softened but present: straddling
        // quads land strictly inside (0, 1), far quads stay 1. The step sits
        // on an odd boundary on purpose: a step aligned to the half grid
        // loses no variance under boxing (both windows see the same bimodal
        // split) and correctly keeps weight 1.
        val u = RawSrUnblocker.computeFrame(
            gray(16, 12, { x, _ -> if (x < 9) 0.2f else 0.8f }), 1e-6, 1e-9)
        assertEquals(1f, u[0], 0f)
        assertEquals(1f, u[11 * 16 + 15], 0f)
        var partial = 0
        for (y in 0 until 12) for (x in 7..10) {
            val w = u[y * 16 + x]
            assertTrue("quad ($x,$y) weight=$w", w.isFinite())
            if (w > 0f && w < 1f) partial++
        }
        assertTrue("no partially attenuated quad", partial > 0)
    }

    @Test fun missingModelKeepsWeight() {
        val g = gray(8, 8, { x, y -> ((x * y) % 3).toFloat() / 2f })
        assertTrue(RawSrUnblocker.computeFrame(g, 0.0, 0.0).all { it == 1f })
        assertTrue(RawSrUnblocker.computeFrame(g, Double.NaN, 1e-6).all { it == 1f })
        assertTrue(RawSrUnblocker.computeFrame(g, 0.02, Double.NaN).all { it == 1f })
    }

    @Test fun nonFiniteTapsAreNeverAttenuated() {
        val g = gray(8, 8, { x, y -> ((x + y) and 1).toFloat() })
        g.values[4 * 8 + 4] = Float.NaN
        val u = RawSrUnblocker.computeFrame(g, 1e-6, 1e-9)
        // Untouched checker region still fully attenuated.
        assertEquals(0f, u[0], 0f)
        // Every quad whose window or noise estimate touches the NaN keeps 1.
        for (y in 3..5) for (x in 3..5) {
            assertEquals("quad ($x,$y)", 1f, u[y * 8 + x], 0f)
        }
    }

    @Test fun oddGridsStayFinite() {
        val u = RawSrUnblocker.computeFrame(gray(7, 5, { _, _ -> 0.5f }), 0.02, 1e-6)
        assertEquals(7 * 5, u.size)
        assertTrue(u.all { it == 1f })
    }

    @Test fun bakeCapsWeightsAndFlagsAttenuation() {
        val frame = RawSrRobustness.FrameRobustness(
            2, 1, floatArrayOf(1f, 1f), intArrayOf(0, RawSrRobustness.FLAG_HOTPIXEL))
        val out = RawSrUnblocker.applyToFrame(frame, floatArrayOf(1f, 0.5f))
        assertArrayEquals(floatArrayOf(1f, 0.5f), out.r, 0f)
        assertEquals(0, out.flags[0])
        assertEquals(
            RawSrRobustness.FLAG_HOTPIXEL or RawSrRobustness.FLAG_UNBLOCKED,
            out.flags[1])
    }

    @Test fun bakeCapsRatherThanCompounding() {
        // Sabre `min(1 - unblocker, frame_weight)`: partial agreement
        // capped by a partial keep-weight keeps the weaker factor (0.5),
        // not the product (0.25).
        val frame = RawSrRobustness.FrameRobustness(
            2, 1, floatArrayOf(0.5f, 0.3f), IntArray(2))
        val out = RawSrUnblocker.applyToFrame(frame, floatArrayOf(0.5f, 0.8f))
        assertArrayEquals(floatArrayOf(0.5f, 0.3f), out.r, 0f)
        assertEquals(RawSrRobustness.FLAG_UNBLOCKED, out.flags[0])
        assertEquals(RawSrRobustness.FLAG_UNBLOCKED, out.flags[1])
    }

    @Test fun bakeSanitizesNonFiniteProducts() {
        val frame = RawSrRobustness.FrameRobustness(3, 1, floatArrayOf(1f, 1f, 1f), IntArray(3))
        val out = RawSrUnblocker.applyToFrame(frame, floatArrayOf(Float.NaN, 0f, 2f))
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), out.r, 0f)
        // NaN weight zeroes the product but raises no flag: there is no
        // trustworthy attenuation to report, only a sanitized zero.
        assertEquals(0, out.flags[0])
        assertEquals(RawSrRobustness.FLAG_UNBLOCKED, out.flags[1])
        assertEquals(0, out.flags[2])
    }

    @Test fun spreadDilatesAttenuationCores() {
        // A lone attenuated core (u = 0.1 at the center of a 7x7 ones
        // field) spreads to the full 5x5 window; quads 3+ away keep 1.
        // Flags stay own-quad: only r spreads.
        val frame = RawSrRobustness.FrameRobustness(7, 7, FloatArray(49) { 1f }, IntArray(49))
        val u = FloatArray(49) { 1f }
        u[3 * 7 + 3] = 0.1f
        val out = RawSrUnblocker.applyToFrameAndSpread(frame, u)
        assertEquals(0.1f, out.r[3 * 7 + 3], 0f)
        assertEquals(0.1f, out.r[1 * 7 + 1], 0f)
        assertEquals(0.1f, out.r[5 * 7 + 5], 0f)
        assertEquals(1f, out.r[0], 0f)
        assertEquals(1f, out.r[6 * 7 + 6], 0f)
        assertEquals(RawSrRobustness.FLAG_UNBLOCKED, out.flags[3 * 7 + 3])
        assertEquals(0, out.flags[1 * 7 + 1])
    }

    @Test fun vetoZeroesContestedGhostsOnly() {
        // 4x2 tiles over a 16x8 quad grid (tileSize 8): columns 0-1 still,
        // columns 2-3 shifted 5px. u = 0.4 everywhere (below veto). Quad
        // (6,2) sits over tile 1 whose window touches the 5px step
        // (spread 5 > mTh 0.8 -> irregular -> vetoed to exactly 0), while
        // quad (1,2) is regular AND 3+ quads from any vetoed site, so it
        // keeps the 0.4 cap through the spread. The veto needs BOTH
        // signals: a smooth-flow twin keeps 0.4 everywhere.
        val tiles = List(8) { i ->
            val dx = if (i % 4 < 2) 0f else 5f
            RawSrTileFlow(0f, 0f, dx, 0f, 0f, true)
        }
        val flow = RawSrAlignmentField(32, 16, 8, 4, 2, tiles)
        val frame = RawSrRobustness.FrameRobustness(16, 8, FloatArray(128) { 1f }, IntArray(128))
        val u = FloatArray(128) { 0.4f }
        val out = RawSrUnblocker.applyToFrameAndSpread(frame, u, flow, 0.8f)
        assertEquals(0f, out.r[2 * 16 + 6], 0f)
        assertEquals(0.4f, out.r[2 * 16 + 1], 0f)
        val calm = RawSrAlignmentField(32, 16, 8, 4, 2,
            List(8) { RawSrTileFlow(0f, 0f, 0f, 0f, 0f, true) })
        val kept = RawSrUnblocker.applyToFrameAndSpread(frame, u, calm, 0.8f)
        assertTrue(kept.r.all { it == 0.4f })
    }

    @Test fun bakeRejectsMismatchedGrid() {
        val frame = RawSrRobustness.FrameRobustness(2, 2, FloatArray(4) { 1f }, IntArray(4))
        try {
            RawSrUnblocker.applyToFrame(frame, FloatArray(3) { 1f })
            fail("expected size contract")
        } catch (_: IllegalArgumentException) {
        }
    }
}
