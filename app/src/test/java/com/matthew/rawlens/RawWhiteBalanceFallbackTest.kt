// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawWhiteBalanceFallbackTest {
    private val black = FloatArray(4) { 64f }
    private val white = 1023f

    /** Uniform Bayer frame: each site gets black + level(color) * range. */
    private fun frame(cfa: Int, w: Int, h: Int, level: (CfaColor) -> Double): ByteBuffer {
        val pattern = BayerPattern.fromCamera2(cfa)
        val buf = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
        for (y in 0 until h) for (x in 0 until w) {
            val code = (64 + level(pattern.colorAt(x, y)) * (1023 - 64)).toInt()
            buf.putShort((y * w + x) * 2, code.toShort())
        }
        return buf
    }

    // Field shape 2026-10-29: direct-opened Xiaomi aux lens 4.
    @Test fun `unity gains with NaN neutral is missing AWB`() {
        assertTrue(RawWhiteBalanceFallback.halAwbMissing(
            doubleArrayOf(Double.NaN, Double.NaN, Double.NaN), floatArrayOf(1f, 1f, 1f, 1f)))
        assertTrue(RawWhiteBalanceFallback.halAwbMissing(null, null))
    }

    @Test fun `real HAL white balance is left alone`() {
        // 6/4 on the same sensor.
        assertFalse(RawWhiteBalanceFallback.halAwbMissing(
            doubleArrayOf(0.873, 1.0, 0.3193), floatArrayOf(1.1455f, 1f, 1f, 3.1357f)))
        // Valid gains without a neutral still derive a neutral downstream.
        assertFalse(RawWhiteBalanceFallback.halAwbMissing(null, floatArrayOf(2f, 1f, 1f, 1.5f)))
    }

    @Test fun `gray world recovers the scene neutral for every Bayer layout`() {
        for (cfa in 0..3) {
            val buf = frame(cfa, 256, 192) { when (it) {
                CfaColor.RED -> 0.16; CfaColor.GREEN -> 0.20; CfaColor.BLUE -> 0.08 } }
            val n = RawWhiteBalanceFallback.estimate(buf, 512, 2, 256, 192, cfa, black, white)
            assertNotNull("cfa=$cfa", n)
            assertArrayEquals("cfa=$cfa", doubleArrayOf(0.8, 1.0, 0.4), n, 0.01)
        }
    }

    @Test fun `clipped and dark frames give no estimate`() {
        val clipped = frame(3, 256, 192) { 1.0 }
        assertNull(RawWhiteBalanceFallback.estimate(clipped, 512, 2, 256, 192, 3, black, white))
        val dark = frame(3, 256, 192) { 0.001 }
        assertNull(RawWhiteBalanceFallback.estimate(dark, 512, 2, 256, 192, 3, black, white))
    }

    @Test fun `packed or unsupported layouts give no estimate`() {
        val buf = frame(0, 64, 64) { 0.2 }
        assertNull(RawWhiteBalanceFallback.estimate(buf, 80, 0, 64, 64, 0, black, white))
        assertNull(RawWhiteBalanceFallback.estimate(buf, 128, 2, 64, 64, 4, black, white))
    }

    @Test fun `gains invert the neutral`() {
        assertArrayEquals(floatArrayOf(1.25f, 1f, 1f, 2.5f),
            RawWhiteBalanceFallback.gainsFor(doubleArrayOf(0.8, 1.0, 0.4)), 1e-6f)
    }

    @Test fun `tracker throttles, eases and resets when the HAL recovers`() {
        val t = RawWhiteBalanceFallback.Tracker(intervalMs = 500L, alpha = 0.5)
        var calls = 0
        val a = doubleArrayOf(1.0, 1.0, 0.5)
        val b = doubleArrayOf(0.5, 1.0, 0.5)
        assertArrayEquals(a, t.update(0L, true) { calls++; a }, 1e-9)
        assertArrayEquals(a, t.update(100L, true) { calls++; b }, 1e-9)
        assertEquals(1, calls)
        assertArrayEquals(doubleArrayOf(0.75, 1.0, 0.5), t.update(600L, true) { calls++; b }, 1e-9)
        assertNull(t.update(700L, false) { calls++; a })
        assertNull(t.neutral)
    }

    @Test fun `invalid gains cannot suppress fallback`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            assertTrue(RawWhiteBalanceFallback.halAwbMissing(null, floatArrayOf(bad, 1f, 1f, 2f)))
        }
    }

    @Test fun `tracker throttles unsuccessful estimates too`() {
        val tracker = RawWhiteBalanceFallback.Tracker(intervalMs = 500L)
        var calls = 0
        assertNull(tracker.update(0L, true) { calls++; null })
        assertNull(tracker.update(100L, true) { calls++; null })
        assertEquals(1, calls)
        assertNull(tracker.update(500L, true) { calls++; null })
        assertEquals(2, calls)
    }

    private val identity = ImmutableDoubleValues(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
    /** XYZ -> camera identity: the camera neutral at T is the blackbody XYZ itself. */
    private val locus = requireNotNull(RawWhiteBalanceFallback.Locus.from(identity, identity, null, null, 21, 17))

    private fun neutralAt(k: Double): DoubleArray {
        val xyz = RawWhiteBalanceFallback.Locus.planckXyz(k)
        return doubleArrayOf(xyz.x / xyz.y, 1.0, xyz.z / xyz.y)
    }

    /** Bayer frame whose left [grayShare] holds a neutral surface, the rest one saturated colour. */
    private fun mixedFrame(neutral: DoubleArray, other: DoubleArray, grayShare: Double): ByteBuffer {
        val w = 256; val h = 192
        val pattern = BayerPattern.fromCamera2(0)
        val buf = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
        for (y in 0 until h) for (x in 0 until w) {
            val rgb = if (x < w * grayShare) neutral.map { it * 0.3 } else other.toList()
            val level = when (pattern.colorAt(x, y)) {
                CfaColor.RED -> rgb[0]; CfaColor.GREEN -> rgb[1]; CfaColor.BLUE -> rgb[2] }
            buf.putShort((y * w + x) * 2, (64 + level * (1023 - 64)).toInt().toShort())
        }
        return buf
    }

    @Test fun `locus spans tungsten to shade in the expected direction`() {
        val warm = locus.at(0.0)
        val cool = locus.at((locus.kelvin.size - 1).toDouble())
        assertTrue(warm[0] > cool[0])  // more red at 2300 K
        assertTrue(warm[1] < cool[1])  // less blue at 2300 K
    }

    @Test fun `grass-dominated frame keeps the white point of its neutral patch`() {
        val truth = neutralAt(4500.0)
        val grass = doubleArrayOf(0.05, 0.30, 0.04)
        val buf = mixedFrame(truth, grass, grayShare = 0.25)
        val constrained = RawWhiteBalanceFallback.estimate(buf, 512, 2, 256, 192, 0, black, white, locus)
        assertNotNull(constrained)
        assertArrayEquals(truth, constrained, 0.03)
        // Plain gray-world is dragged far toward green (R/G and B/G collapse).
        val plain = requireNotNull(RawWhiteBalanceFallback.estimate(buf, 512, 2, 256, 192, 0, black, white))
        assertTrue(plain[0] < truth[0] * 0.6)
    }

    @Test fun `one-colour frame stays on the locus between its hue and daylight`() {
        val sky = doubleArrayOf(0.10, 0.18, 0.40)
        val buf = mixedFrame(neutralAt(5500.0), sky, grayShare = 0.0)
        val n = requireNotNull(RawWhiteBalanceFallback.estimate(buf, 512, 2, 256, 192, 0, black, white, locus))
        val p = locus.project(kotlin.math.ln(n[0]), kotlin.math.ln(n[2]))
        assertTrue("off-locus ${p[2]}", p[2] < 1e-6)
        val daylight = locus.indexOf(5500.0)
        assertTrue(p[3] >= daylight)  // pulled toward cool, never past the sky's own direction
    }
}
