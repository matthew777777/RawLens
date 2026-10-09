// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VfLogGradeTest {
    @Test
    fun packLayoutMatchesPushBlock() {
        val (ip, fp) = VfLogGrade.packGrade(
            intArrayOf(960, 540),
            floatArrayOf(1.9f, 1f, 1.55f, 1f),
            VfLogGrade.IDENTITY_CCM,
            0f, false
        )
        // C++ reads dims[2] + 18 floats (gains[4], ccm row-major[9],
        // exposure, bypass, contrast, saturation, outMode); neutral knobs
        // and BT709 when omitted.
        assertEquals(2, ip.size)
        assertEquals(960, ip[0])
        assertEquals(540, ip[1])
        assertEquals(18, fp.size)
        assertEquals(1.9f, fp[0], 0f)
        assertEquals(1f, fp[4], 0f) // ccm[0]
        assertEquals(0f, fp[5], 0f) // ccm[1]
        assertEquals(0f, fp[13], 0f) // exposure
        assertEquals(0f, fp[14], 0f) // bypass
        assertEquals(1f, fp[15], 0f) // contrast default
        assertEquals(1f, fp[16], 0f) // saturation default
        assertEquals(0f, fp[17], 0f) // BT709 wire id default
    }

    @Test
    fun packCarriesExplicitKnobs() {
        val (_, fp) = VfLogGrade.packGrade(
            intArrayOf(8, 8), floatArrayOf(1f, 1f, 1f, 1f),
            VfLogGrade.IDENTITY_CCM, 0f, false, 1.2f, 0.8f
        )
        assertEquals(1.2f, fp[15], 0f)
        assertEquals(0.8f, fp[16], 0f)
    }

    @Test
    fun packCarriesProfileWireId() {
        for (p in DirectLogProfile.entries) {
            val (_, fp) = VfLogGrade.packGrade(
                intArrayOf(8, 8), floatArrayOf(1f, 1f, 1f, 1f),
                VfLogGrade.IDENTITY_CCM, 0f, false,
                profile = p
            )
            assertEquals(p.wireId.toFloat(), fp[17], 0f)
        }
    }

    @Test
    fun packBypassFlag() {
        val (_, fp) = VfLogGrade.packGrade(
            intArrayOf(8, 8), floatArrayOf(1f, 1f, 1f, 1f),
            VfLogGrade.IDENTITY_CCM, 0f, true
        )
        assertEquals(1f, fp[14], 0f)
    }

    @Test
    fun slog3BlackIsZeroWhiteIsMidLog() {
        assertEquals(0f, VfLogGrade.slog3(0f), 1e-6f)
        // S-Log3 maps reference white to ~0.5 (log compression signature).
        assertEquals(0.4972f, VfLogGrade.slog3(1f), 1e-3f)
    }

    @Test
    fun slog3ContinuousAtCut() {
        val c = VfLogGrade.LOG_CUT
        val e = 1e-6f
        // Continuous by construction: toe meets the log branch at the cut.
        assertEquals(VfLogGrade.slog3(c), VfLogGrade.slog3(c - e), 1e-4f)
        assertEquals(VfLogGrade.slog3(c), VfLogGrade.slog3(c + e), 1e-4f)
    }

    @Test
    fun slog3MonotonicAndCompressive() {
        var prev = VfLogGrade.slog3(0f)
        var x = 0.05f
        while (x <= 2f) {
            val v = VfLogGrade.slog3(x)
            assertTrue("not monotonic at $x", v > prev)
            prev = v
            x += 0.05f
        }
        // Highlights compress: the 1.0->2.0 step is smaller than 0.0->0.5.
        val lowGain = VfLogGrade.slog3(0.5f) - VfLogGrade.slog3(0f)
        val highGain = VfLogGrade.slog3(2f) - VfLogGrade.slog3(1f)
        assertTrue("not compressive: low=$lowGain high=$highGain", highGain < lowGain)
    }

    @Test
    fun slog3MidGrayLandsNearPointFourTwo() {
        // Anchors the device readback expectation for ~0.5 linear input.
        assertEquals(0.4214f, VfLogGrade.slog3(0.5f), 1e-3f)
    }

    @Test
    fun contrastNeutralIsIdentityEndpointsPinned() {
        for (v in floatArrayOf(0f, 0.1f, 0.31f, 0.5f, 0.8f, 1f)) {
            assertEquals(v, VfLogGrade.contrastCurve(v, 1f), 1e-6f)
        }
        assertEquals(0f, VfLogGrade.contrastCurve(0f, 1.5f), 1e-6f)
        assertEquals(1f, VfLogGrade.contrastCurve(1f, 1.5f), 1e-6f)
        val pivot = VfLogGrade.LOG_PIVOT
        assertEquals(pivot, VfLogGrade.contrastCurve(pivot, 0.5f), 1e-6f)
        assertEquals(pivot, VfLogGrade.contrastCurve(pivot, 1.5f), 1e-6f)
    }

    @Test
    fun contrastPushesAwayFromPivot() {
        // Above pivot brightens, below darkens; stronger knob = bigger push.
        val pivot = VfLogGrade.LOG_PIVOT
        val hi = VfLogGrade.contrastCurve(pivot + 0.2f, 1.5f)
        val lo = VfLogGrade.contrastCurve(pivot - 0.15f, 1.5f)
        assertTrue(hi > pivot + 0.2f)
        assertTrue(lo < pivot - 0.15f)
        assertTrue(
            VfLogGrade.contrastCurve(pivot + 0.2f, 1.5f) >
                VfLogGrade.contrastCurve(pivot + 0.2f, 1.2f)
        )
    }

    @Test
    fun contrastMonotonicOverSliderRange() {
        for (c in floatArrayOf(0.5f, 1f, 1.5f)) {
            var prev = -1f
            var v = 0f
            while (v <= 1.0001f) {
                val y = VfLogGrade.contrastCurve(v.coerceAtMost(1f), c)
                assertTrue("not monotonic at $v (c=$c)", y >= prev)
                prev = y
                v += 0.01f
            }
        }
    }

    @Test
    fun saturationNeutralAndMono() {
        assertEquals(0.7f, VfLogGrade.saturate(0.7f, 0.5f, 1f), 1e-6f)
        assertEquals(0.5f, VfLogGrade.saturate(0.7f, 0.5f, 0f), 1e-6f)
        assertEquals(0.9f, VfLogGrade.saturate(0.7f, 0.5f, 2f), 1e-6f)
    }

    @Test
    fun srgbOetfKnownPoints() {
        assertEquals(0f, VfLogGrade.srgbOetf(0f), 1e-6f)
        assertEquals(1f, VfLogGrade.srgbOetf(1f), 1e-6f)
        // Linear toe meets the power branch at the cut, continuously.
        val cut = 0.0031308f
        assertEquals(12.92f * cut, VfLogGrade.srgbOetf(cut), 1e-5f)
        assertEquals(VfLogGrade.srgbOetf(cut), VfLogGrade.srgbOetf(cut + 1e-6f), 1e-4f)
        // Mid-log 0.4214 (slog3 of 0.5 linear) encodes near 0.68.
        assertEquals(0.6811f, VfLogGrade.srgbOetf(0.4214f), 1e-3f)
    }

    @Test
    fun sonySlog3MatchesPublishedAnchors() {
        // Sony S-Log3 whitepaper codes (10-bit): 0 -> 95, cut -> 171.21,
        // 18% gray -> 420, 90% white -> ~597.9.
        assertEquals(95f / 1023f, VfLogGrade.sonySlog3(0f), 1e-6f)
        assertEquals(171.2103f / 1023f, VfLogGrade.sonySlog3(0.01125f), 1e-4f)
        assertEquals(420f / 1023f, VfLogGrade.sonySlog3(0.18f), 1e-6f)
        assertEquals(0.5845f, VfLogGrade.sonySlog3(0.9f), 1e-3f)
        assertEquals(0.5960f, VfLogGrade.sonySlog3(1f), 1e-3f)
    }

    @Test
    fun sonySlog3ContinuousAtCut() {
        val c = VfLogGrade.SLOG3_CUT
        val e = 1e-6f
        assertEquals(VfLogGrade.sonySlog3(c), VfLogGrade.sonySlog3(c - e), 1e-4f)
        assertEquals(VfLogGrade.sonySlog3(c), VfLogGrade.sonySlog3(c + e), 1e-4f)
    }

    @Test
    fun sonySlog3MonotonicAndCompressive() {
        var prev = VfLogGrade.sonySlog3(0f)
        var x = 0.05f
        while (x <= 2f) {
            val v = VfLogGrade.sonySlog3(x)
            assertTrue("not monotonic at $x", v > prev)
            prev = v
            x += 0.05f
        }
        val lowGain = VfLogGrade.sonySlog3(0.5f) - VfLogGrade.sonySlog3(0f)
        val highGain = VfLogGrade.sonySlog3(2f) - VfLogGrade.sonySlog3(1f)
        assertTrue("not compressive: low=$lowGain high=$highGain", highGain < lowGain)
    }

    @Test
    fun invSonySlog3RoundTrips() {
        var x = 0.001f
        while (x <= 2f) {
            val rt = VfLogGrade.invSonySlog3(VfLogGrade.sonySlog3(x))
            assertEquals("at $x", x, rt, 2e-4f * x.coerceAtLeast(1e-3f))
            x *= 1.25f
        }
        // Codes below the black pedestal clip to 0, never negative.
        assertEquals(0f, VfLogGrade.invSonySlog3(0f), 0f)
    }

    @Test
    fun bt709OetfKnownPoints() {
        assertEquals(0f, VfLogGrade.bt709Oetf(0f), 0f)
        assertEquals(1f, VfLogGrade.bt709Oetf(1f), 1e-5f)
        assertEquals(0.4090f, VfLogGrade.bt709Oetf(0.18f), 1e-3f)
        assertEquals(0.7055f, VfLogGrade.bt709Oetf(0.5f), 1e-3f)
        // The published branches meet with a slight kink at 0.018
        // (0.0810 vs ~0.0813); both formulas are used verbatim.
        assertEquals(0.081f, VfLogGrade.bt709Oetf(0.018f - 1e-6f), 1e-3f)
        assertEquals(0.0812f, VfLogGrade.bt709Oetf(0.018f), 1e-3f)
    }

    @Test
    fun hlgOetfKnownPoints() {
        assertEquals(0f, VfLogGrade.hlgOetf(0f), 0f)
        assertEquals(1f, VfLogGrade.hlgOetf(1f), 1e-5f)
        // sqrt branch meets the log branch at 1/12 -> 0.5, continuously.
        val cut = 1f / 12f
        assertEquals(0.5f, VfLogGrade.hlgOetf(cut), 1e-5f)
        assertEquals(VfLogGrade.hlgOetf(cut), VfLogGrade.hlgOetf(cut + 1e-6f), 1e-4f)
        assertEquals(0.6724f, VfLogGrade.hlgOetf(0.18f), 1e-3f)
        // Diffuse-white anchor: ~0.265 linear lands at 75% signal.
        assertEquals(0.75f, VfLogGrade.hlgOetf(0.265f), 1e-3f)
    }

    @Test
    fun srgbToBt2020WhitePreservingAndBounded() {
        val white = VfLogGrade.linearSrgbToBt2020(floatArrayOf(1f, 1f, 1f))
        assertEquals(1f, white[0], 1e-5f)
        assertEquals(1f, white[1], 1e-5f)
        assertEquals(1f, white[2], 1e-5f)
        // sRGB primaries land inside the 2020 volume (all components 0..1).
        for (p in arrayOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f)
        )) {
            val c = VfLogGrade.linearSrgbToBt2020(p)
            for (v in c) assertTrue("primary $p -> $v out of gamut", v in 0f..1f)
        }
        // Gray in, gray out (rows sum to 1).
        val gray = VfLogGrade.linearSrgbToBt2020(floatArrayOf(0.18f, 0.18f, 0.18f))
        assertEquals(0.18f, gray[0], 1e-5f)
        assertEquals(0.18f, gray[1], 1e-5f)
        assertEquals(0.18f, gray[2], 1e-5f)
    }

    @Test
    fun encodePixel709IsDirectOetf() {
        // No warp, no saturation: per-channel BT.709 OETF, nothing else.
        for (v in floatArrayOf(0f, 0.18f, 0.5f, 1f)) {
            val out = VfLogGrade.encodePixel709(floatArrayOf(v, v, v))
            assertEquals("at $v", VfLogGrade.bt709Oetf(v), out[0], 1e-6f)
            assertEquals(out[0], out[1], 0f)
            assertEquals(out[0], out[2], 0f)
        }
        val mixed = VfLogGrade.encodePixel709(floatArrayOf(1f, 0.5f, 0.25f))
        assertEquals(VfLogGrade.bt709Oetf(1f), mixed[0], 1e-6f)
        assertEquals(VfLogGrade.bt709Oetf(0.5f), mixed[1], 1e-6f)
        assertEquals(VfLogGrade.bt709Oetf(0.25f), mixed[2], 1e-6f)
    }

    @Test
    fun encodePixelHlgIs2020PlusHlg() {
        // Gray passes the gamut matrix unchanged, then HLG encodes it.
        val gray = VfLogGrade.encodePixelHlg(floatArrayOf(0.18f, 0.18f, 0.18f))
        assertEquals(0.6724f, gray[0], 1e-3f)
        assertEquals(gray[0], gray[1], 1e-6f)
        assertEquals(gray[0], gray[2], 1e-6f)
        val black = VfLogGrade.encodePixelHlg(floatArrayOf(0f, 0f, 0f))
        assertEquals(0f, black[0], 0f)
        val white = VfLogGrade.encodePixelHlg(floatArrayOf(1f, 1f, 1f))
        assertEquals(1f, white[0], 1e-5f)
    }

    @Test
    fun encodePixelSlog3IsFlatSonyLog() {
        // 18% gray at code 420, black at the 95 pedestal — no look applied.
        val gray = VfLogGrade.encodePixelSlog3(floatArrayOf(0.18f, 0.18f, 0.18f))
        assertEquals(420f / 1023f, gray[0], 1e-6f)
        assertEquals(gray[0], gray[1], 0f)
        assertEquals(gray[0], gray[2], 0f)
        val black = VfLogGrade.encodePixelSlog3(floatArrayOf(0f, 0f, 0f))
        assertEquals(95f / 1023f, black[0], 1e-6f)
    }

    @Test
    fun invSlog3RoundTrips() {
        assertEquals(0f, VfLogGrade.invSlog3(0f), 1e-7f)
        var x = 0.001f
        while (x <= 2f) {
            val rt = VfLogGrade.invSlog3(VfLogGrade.slog3(x))
            assertEquals("at $x", x, rt, 1e-4f * x.coerceAtLeast(1e-3f))
            x *= 1.25f
        }
    }

    @Test
    fun bakeLutLayoutAndDeterminism() {
        val lut = VfLogGrade.bakeLut(1f)
        assertEquals(129 * 4, lut.size)
        // Alpha is always 1.0 (0x3C00); bake is deterministic.
        val one = 0x3C00.toShort()
        var i = 3
        while (i < lut.size) {
            assertEquals(one, lut[i])
            i += 4
        }
        assertArrayEquals(lut, VfLogGrade.bakeLut(1f))
    }

    @Test
    fun bakeLutContrastChangesContent() {
        // The LUT carries contrast (saturation rides a uniform instead).
        val a = VfLogGrade.bakeLut(1f)
        val b = VfLogGrade.bakeLut(1.5f)
        assertTrue(a.zip(b.toList()).any { (x, y) -> x != y })
    }

    @Test
    fun bakeLutGrayRampMonotonic() {
        // Neutral 1D ramp: scene-linear rises with the lattice, unclipped
        // (the shader encodes sRGB after the fetch). Top center is
        // 2^((128.5/129)*14-10) = ~15.5 linear.
        val lut = VfLogGrade.bakeLut(1f)
        fun halfToFloat(bits: Int): Float {
            val s = (bits ushr 15) and 1
            val e = (bits ushr 10) and 0x1F
            val m = bits and 0x3FF
            val f = when (e) {
                0 -> m / 16777216f
                31 -> Float.POSITIVE_INFINITY
                else -> (m + 1024) / 1024f * Math.pow(2.0, (e - 15).toDouble()).toFloat()
            }
            return if (s == 1) -f else f
        }
        var prev = -1f
        for (d in 0 until 129) {
            val o = d * 4
            val r = halfToFloat(lut[o].toInt() and 0xFFFF)
            val g = halfToFloat(lut[o + 1].toInt() and 0xFFFF)
            val b = halfToFloat(lut[o + 2].toInt() and 0xFFFF)
            assertEquals("cell $d r/g", r, g, 1e-3f * r.coerceAtLeast(1e-3f))
            assertEquals("cell $d r/b", r, b, 1e-3f * r.coerceAtLeast(1e-3f))
            assertTrue("cell $d not rising: $r <= $prev", r > prev)
            prev = r
        }
        // No white clip in the LUT: the top cell holds ~15.5x linear.
        assertTrue("top clipped: $prev", prev > 14f && prev < 16f)
    }

    @Test
    fun developLinearNeutralIsIdentity() {
        // The LUT warp must be transparent at neutral contrast (identity,
        // so the proofs hold their tight tolerance).
        var x = 0.002f
        while (x <= 8f) {
            val out = VfLogGrade.developLinear(floatArrayOf(x, x, x), 1f)
            assertEquals("at $x", x, out[0], 1e-4f * x)
            assertEquals("at $x", x, out[1], 1e-4f * x)
            assertEquals("at $x", x, out[2], 1e-4f * x)
            x *= 1.5f
        }
    }

    @Test
    fun floatToHalfBitsKnownPoints() {
        assertEquals(0x0000, VfLogGrade.floatToHalfBits(0f))
        assertEquals(0x3C00, VfLogGrade.floatToHalfBits(1f))
        assertEquals(0x3800, VfLogGrade.floatToHalfBits(0.5f))
        assertEquals(0xBC00, VfLogGrade.floatToHalfBits(-1f))
    }

    @Test
    fun fusedAssetSelectsFp16VariantOnCapability() {
        assertEquals("shaders/vf/vf_mhcyuv_f16.spv", VfLogGrade.fusedAsset(true))
        assertEquals("shaders/vf/vf_mhcyuv.spv", VfLogGrade.fusedAsset(false))
    }
}
