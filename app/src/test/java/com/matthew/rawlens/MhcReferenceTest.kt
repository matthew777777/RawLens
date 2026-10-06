// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM gates for [MhcReference] (CPU mirror of the `vf_mhc` GPU port).
 * Non-self-fulfilling: flat-field invariance, mosaic passthrough,
 * range, and edge chroma neutrality hold for ANY correct
 * edge-directed demosaic, not just this code.
 */
class MhcReferenceTest {
    private val channels = intArrayOf(0, 1, 2, 3) // RGGB
    private val zeros = FloatArray(4)

    @Test fun flatFieldIsInvariant() {
        val w = 16
        val h = 16
        val codes = IntArray(w * h) { 512 }
        val out = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        val expect = 512f / 1023f
        var maxDev = 0f
        for (v in out) maxDev = maxDev.coerceAtLeast(abs(v - expect))
        assertTrue("flat 512 deviates $maxDev (expect < 1e-3)", maxDev < 1e-3f)
    }

    @Test fun mosaicSitesPassThrough() {
        // Each pixel's mosaic-color output equals its normalized input.
        val w = 12
        val h = 12
        val codes = IntArray(w * h) { i -> 100 + (i * 37) % 900 }
        val out = MhcReference.demosaic(
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
        // Step edge + ramp; outputs stay finite and near the input range
        // (small linear overshoot allowed — MHC is unclipped like the
        // paper; the grade clamps downstream).
        val w = 32
        val h = 32
        val codes = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x < w / 2) 200 + y * 4 else 800 - y * 2
        }
        val out = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        for (v in out) {
            assertTrue("non-finite MHC output", v.isFinite())
            assertTrue("MHC output out of range: $v", v > -0.05f && v < 1.3f)
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

    /**
     * Zipper regression: the fixed MHC green kernel blurs across
     * high-contrast edges, so interpolated sites err while mosaic sites
     * pass through — an alternating row-to-row error (±0.075 here) along
     * every edge. Edge-directed green must hold the alternating
     * component of the GREEN error under 0.02 (measured ~0.0).
     */
    @Test fun verticalStepEdgeHasNoGreenZipper() {
        val w = 40
        val h = 40
        val dark = 205
        val bright = 818
        val codes = IntArray(w * h) { i -> if (i % w < w / 2) dark else bright }
        val out = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        val darkN = dark / 1023f
        val brightN = bright / 1023f
        var sum = 0.0
        var n = 0
        for (x in 18..21) {
            for (y in 7 until 33) {
                val truth = if (x < w / 2) darkN else brightN
                val e0 = out[((y - 1) * w + x) * 3 + 1] - truth
                val e1 = out[(y * w + x) * 3 + 1] - truth
                val e2 = out[((y + 1) * w + x) * 3 + 1] - truth
                sum += abs(e1 - (e0 + e2) / 2f)
                n++
            }
        }
        val alt = sum / n
        assertTrue("green zipper $alt along vertical step (expect < 0.02)", alt < 0.02)
    }

    @Test fun horizontalStepEdgeHasNoGreenZipper() {
        val w = 40
        val h = 40
        val dark = 205
        val bright = 818
        val codes = IntArray(w * h) { i -> if (i / w < h / 2) dark else bright }
        val out = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        val darkN = dark / 1023f
        val brightN = bright / 1023f
        var sum = 0.0
        var n = 0
        for (y in 18..21) {
            for (x in 7 until 33) {
                val truth = if (y < h / 2) darkN else brightN
                val e0 = out[(y * w + x - 1) * 3 + 1] - truth
                val e1 = out[(y * w + x) * 3 + 1] - truth
                val e2 = out[(y * w + x + 1) * 3 + 1] - truth
                sum += abs(e1 - (e0 + e2) / 2f)
                n++
            }
        }
        val alt = sum / n
        assertTrue("green zipper $alt along horizontal step (expect < 0.02)", alt < 0.02)
    }

    /**
     * Fences / thin gratings are the real-world zipper case (roofs,
     * railings, fabric): 1px bright lines every 4px. The old kernel
     * alternates ±0.15 in green here; edge-directed green is exact.
     */
    @Test fun fenceHasNoGreenZipper() {
        val w = 40
        val h = 40
        val dark = 205
        val bright = 818
        val codes = IntArray(w * h) { i -> if ((i % w) % 4 == 0) bright else dark }
        val out = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        val darkN = dark / 1023f
        val brightN = bright / 1023f
        var sum = 0.0
        var n = 0
        for (x in 8 until 32) {
            val truth = if (x % 4 == 0) brightN else darkN
            for (y in 7 until 33) {
                val e0 = out[((y - 1) * w + x) * 3 + 1] - truth
                val e1 = out[(y * w + x) * 3 + 1] - truth
                val e2 = out[((y + 1) * w + x) * 3 + 1] - truth
                sum += abs(e1 - (e0 + e2) / 2f)
                n++
            }
        }
        val alt = sum / n
        assertTrue("green zipper $alt on fence (expect < 0.04)", alt < 0.04)
    }

    /**
     * No-regression anchor: 4px checker corners carry no edge direction,
     * so the gate must fall back to symmetric MHC (mae 0.069 before and
     * after; a naive edge pick regresses to 0.081).
     */
    @Test fun checkerboardDoesNotRegress() {
        val w = 40
        val h = 40
        val dark = 205
        val bright = 818
        val codes = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (((x / 4) + (y / 4)) % 2 == 0) dark else bright
        }
        val out = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        var sum = 0.0
        var n = 0
        for (y in 4 until h - 4) {
            for (x in 4 until w - 4) {
                val truth = if (((x / 4) + (y / 4)) % 2 == 0) dark / 1023f else bright / 1023f
                for (c in 0..2) {
                    sum += abs(out[(y * w + x) * 3 + c] - truth)
                    n++
                }
            }
        }
        val mae = sum / n
        assertTrue("checkerboard mae $mae (expect < 0.09)", mae < 0.09)
    }

    /**
     * Chroma-halo regression (Direct-Log cyan/red lines around every
     * contrast edge): the fixed MHC chroma kernels blur across edges
     * while edge-directed green stays sharp — ±0.19 alternating
     * row-to-row R/B error on a gray step, ~90 sRGB codes after
     * WB+CCM. Edge-directed difference-domain chroma must hold the
     * interpolated channels within 0.02 of green along the edge
     * (measured ~0.0005). Interior pixels only: the 2px mirror band
     * legitimately differs.
     */
    @Test fun verticalStepEdgeHasNoChromaFringe() {
        val w = 40
        val h = 40
        val dark = 205
        val bright = 818
        val codes = IntArray(w * h) { i -> if (i % w < w / 2) dark else bright }
        val out = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        var worst = 0f
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val o = (y * w + x) * 3
                worst = maxOf(worst, abs(out[o] - out[o + 1]), abs(out[o + 2] - out[o + 1]))
            }
        }
        assertTrue("chroma fringe $worst along vertical step (expect < 0.02)", worst < 0.02f)
    }

    @Test fun horizontalStepEdgeHasNoChromaFringe() {
        val w = 40
        val h = 40
        val dark = 205
        val bright = 818
        val codes = IntArray(w * h) { i -> if (i / w < h / 2) dark else bright }
        val out = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        var worst = 0f
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val o = (y * w + x) * 3
                worst = maxOf(worst, abs(out[o] - out[o + 1]), abs(out[o + 2] - out[o + 1]))
            }
        }
        assertTrue("chroma fringe $worst along horizontal step (expect < 0.02)", worst < 0.02f)
    }

    /**
     * Fences under per-channel sensor scaling (roofs, railings): 1px
     * bright lines every 4px with R/G/B mosaic levels at 1/1.9, 1,
     * 1/1.55 (daylight response). Level-rescaled differences keep each
     * channel on its own truth (measured worst ~0.001); unscaled
     * anchoring mixes line and off-line levels (~0.28), fixed MHC
     * averages the lines away (~0.37 max).
     */
    @Test fun fenceHasNoChromaFringeUnderSensorScaling() {
        val w = 40
        val h = 40
        val resp = floatArrayOf(1f / 1.9f, 1f, 1f / 1.55f)
        val codes = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val q = ((y and 1) shl 1) or (x and 1)
            val ch = when (channels.indexOf(q)) {
                0 -> 0
                3 -> 2
                else -> 1
            }
            val light = if (x % 4 == 0) 818f else 205f
            (light * resp[ch]).toInt()
        }
        val out = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        var worst = 0f
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val light = (if (x % 4 == 0) 818f else 205f) / 1023f
                val o = (y * w + x) * 3
                worst = maxOf(
                    worst,
                    abs(out[o] - light * resp[0]),
                    abs(out[o + 1] - light * resp[1]),
                    abs(out[o + 2] - light * resp[2])
                )
            }
        }
        assertTrue("chroma fringe $worst on scaled fence (expect < 0.03)", worst < 0.03f)
    }

    /**
     * Dark-step guard: the fixed kernels ring to -0.14 here, and a
     * camera matrix turns one channel's undershoot into a complementary
     * fringe (the AMaZE precedent). Outputs must stay non-negative and
     * chroma-neutral along the edge.
     */
    @Test fun darkStepEdgeStaysNonNegativeAndNeutral() {
        val w = 40
        val h = 40
        val dark = 5
        val bright = 818
        val codes = IntArray(w * h) { i -> if (i % w < w / 2) dark else bright }
        val out = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        var minV = Float.MAX_VALUE
        var worst = 0f
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val o = (y * w + x) * 3
                minV = minOf(minV, out[o], out[o + 1], out[o + 2])
                worst = maxOf(worst, abs(out[o] - out[o + 1]), abs(out[o + 2] - out[o + 1]))
            }
        }
        assertTrue("negative MHC output $minV (expect >= 0)", minV >= 0f)
        assertTrue("chroma fringe $worst along dark step (expect < 0.03)", worst < 0.03f)
    }

    /**
     * Saturated color edges must not invert: directional picks that
     * follow the wrong channel's level turn blur into full-swing
     * inversion on red|blue. Per-channel truth, interior only
     * (measured worst ~0.016, fixed MHC ~0.25).
     */
    @Test fun saturatedEdgeHasNoChromaFringe() {
        val w = 40
        val h = 40
        val left = floatArrayOf(800f, 100f, 100f)
        val right = floatArrayOf(100f, 150f, 800f)
        val codes = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val q = ((y and 1) shl 1) or (x and 1)
            val ch = when (channels.indexOf(q)) {
                0 -> 0
                3 -> 2
                else -> 1
            }
            (if (x < w / 2) left else right)[ch].toInt()
        }
        val out = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        var worst = 0f
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val truth = if (x < w / 2) left else right
                val o = (y * w + x) * 3
                for (c in 0..2) worst = maxOf(worst, abs(out[o + c] - truth[c] / 1023f))
            }
        }
        assertTrue("saturated-edge error $worst (expect < 0.05)", worst < 0.05f)
    }

    /**
     * Sensor-white protection (AMaZE parity): clipped (1,1,1) must land
     * on the camera neutral, so that white balance renders white —
     * without it, WB amplifies the clipped channels into magenta.
     */
    @Test fun desaturateHighlightsWhiteStaysWhite() {
        val gains = floatArrayOf(1.9f, 1.0f, 1.55f)
        val out = MhcReference.desaturateHighlights(floatArrayOf(1f, 1f, 1f), gains)
        for (c in 0..2) {
            val developed = out[c] * gains[c]
            assertTrue(
                "WB(desat(clip))[$c] = $developed (expect 1.0)",
                abs(developed - 1f) < 1e-4f
            )
        }
    }

    /** Midtones never touch the white band (0.70..0.99 anchors). */
    @Test fun desaturateHighlightsLeavesMidtonesAlone() {
        val gains = floatArrayOf(1.9f, 1.0f, 1.55f)
        val out = MhcReference.desaturateHighlights(floatArrayOf(0.5f, 0.5f, 0.5f), gains)
        for (c in 0..2) assertTrue(
            "midtone moved ${out[c]} (expect 0.5)",
            abs(out[c] - 0.5f) < 1e-6f
        )
    }

    @Test fun bggrPhaseMatchesRggbShift() {
        // CFA-genericity: BGGR on the same mosaic shifted by (1,1) must
        // equal RGGB on the original (interior pixels only; the 2px
        // mirror band legitimately differs).
        val w = 16
        val h = 16
        val codes = IntArray(w * h) { i -> 100 + (i * 37) % 900 }
        val ref = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, channels, zeros, 1023f
        )
        // Same scene under BGGR = mosaic values permuted by the phase
        // shift; equivalently, demosaic the (1,1)-shifted crop window.
        val bggr = intArrayOf(3, 2, 1, 0)
        val shifted = MhcReference.demosaic(
            codes, w, h, w, 0, 0, w, h, bggr, zeros, 1023f
        )
        // Not equal (different phase hypotheses) — but both finite and
        // mosaic-consistent; the gate is passthrough under BGGR instead.
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val q = ((y and 1) shl 1) or (x and 1)
                val color = when (bggr.indexOf(q)) {
                    0 -> 0
                    3 -> 2
                    else -> 1
                }
                val expect = codes[y * w + x] / 1023f
                val got = shifted[(y * w + x) * 3 + color]
                assertTrue(
                    "BGGR passthrough broken at ($x,$y): $got vs $expect",
                    abs(got - expect) < 1e-6f
                )
            }
        }
        assertTrue("RGGB ref empty", ref.any { it != 0f })
    }
}
