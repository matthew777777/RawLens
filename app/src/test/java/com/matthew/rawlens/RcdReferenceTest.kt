// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM gates for [RcdReference] (CPU mirror of the `vf_rcd` GPU port).
 * Non-self-fulfilling: flat-field invariance, mosaic passthrough, and
 * range hold for ANY correct RCD implementation, not just this code.
 */
class RcdReferenceTest {
    private val channels = intArrayOf(0, 1, 2, 3) // RGGB
    private val zeros = FloatArray(4)

    @Test fun flatFieldIsInvariant() {
        val w = 16
        val h = 16
        val codes = IntArray(w * h) { 512 }
        val out = RcdReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        val expect = 512f / 1023f
        var maxDev = 0f
        for (v in out) maxDev = maxDev.coerceAtLeast(abs(v - expect))
        assertTrue("flat 512 deviates $maxDev (expect < 1e-3)", maxDev < 1e-3f)
    }

    @Test fun flatFieldBlackLevelIsInvariant() {
        // Nonzero black: flat at black level demosaics to flat zero.
        val w = 16
        val h = 16
        val codes = IntArray(w * h) { 64 }
        val out = RcdReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels,
            floatArrayOf(64f, 64f, 64f, 64f), 1023f
        )
        var maxAbs = 0f
        for (v in out) maxAbs = maxAbs.coerceAtLeast(abs(v))
        assertTrue("flat black deviates $maxAbs (expect < 1e-3)", maxAbs < 1e-3f)
    }

    @Test fun mosaicSitesPassThrough() {
        // Each pixel's mosaic-color output equals its normalized input.
        val w = 12
        val h = 12
        val codes = IntArray(w * h) { i -> 100 + (i * 37) % 900 }
        val out = RcdReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        for (y in 0 until h) {
            for (x in 0 until w) {
                val q = ((y and 1) shl 1) or (x and 1)
                // Canonical (R, Gr, Gb, B) -> RGB output channel.
                val color = when (channels.indexOf(q)) {
                    0 -> 0
                    3 -> 2
                    else -> 1
                }
                val expect = codes[y * w + x] / 1023f
                val got = out[(y * w + x) * 3 + color]
                assertTrue(
                    "mosaic passthrough broken at ($x,$y): $got vs $expect",
                    abs(got - expect) < 1e-6f
                )
            }
        }
    }

    @Test fun rampStaysFiniteAndSane() {
        // Step edge + ramp: exercises direction logic; outputs stay
        // finite and near the input range (small CDIFF overshoot allowed,
        // matching RT's unclamped planes — the grade clamps downstream).
        val w = 32
        val h = 32
        val codes = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x < w / 2) 200 + y * 4 else 800 - y * 2
        }
        val out = RcdReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        for (v in out) {
            assertTrue("non-finite RCD output", v.isFinite())
            assertTrue("RCD output out of range: $v", v > -0.05f && v < 1.3f)
        }
        // Mean preservation (demosaic neither creates nor destroys light).
        var sumIn = 0.0
        var sumOut = 0.0
        for (i in codes.indices) {
            sumIn += codes[i] / 1023.0
            sumOut += (out[i * 3] + out[i * 3 + 1] + out[i * 3 + 2]) / 3.0
        }
        val drift = abs(sumOut - sumIn) / sumIn
        assertTrue("mean drift $drift (expect < 5%)", drift < 0.05)
    }
}
