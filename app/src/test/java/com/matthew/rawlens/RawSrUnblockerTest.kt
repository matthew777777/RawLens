// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

/**
 * Unblocker tests. Criteria encode docs/raw-sr-unblocker.md: exact
 * degenerates (flat, Nyquist checker), noise-dominated keeps weight,
 * no-model keeps weight, step edges attenuate partially, and the bake
 * scales weights with an explicit flag.
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

    @Test fun bakeScalesWeightsAndFlagsAttenuation() {
        val frame = RawSrRobustness.FrameRobustness(
            2, 1, floatArrayOf(1f, 1f), intArrayOf(0, RawSrRobustness.FLAG_HOTPIXEL))
        val out = RawSrUnblocker.applyToFrame(frame, floatArrayOf(1f, 0.5f))
        assertArrayEquals(floatArrayOf(1f, 0.5f), out.r, 0f)
        assertEquals(0, out.flags[0])
        assertEquals(
            RawSrRobustness.FLAG_HOTPIXEL or RawSrRobustness.FLAG_UNBLOCKED,
            out.flags[1])
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

    @Test fun bakeRejectsMismatchedGrid() {
        val frame = RawSrRobustness.FrameRobustness(2, 2, FloatArray(4) { 1f }, IntArray(4))
        try {
            RawSrUnblocker.applyToFrame(frame, FloatArray(3) { 1f })
            fail("expected size contract")
        } catch (_: IllegalArgumentException) {
        }
    }
}
