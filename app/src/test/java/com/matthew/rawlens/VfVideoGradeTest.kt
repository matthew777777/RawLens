// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VfVideoGradeTest {
    @Test
    fun packLayoutMatchesPushBlock() {
        val (ip, fp) = VfVideoGrade.packGrade(
            intArrayOf(960, 540),
            floatArrayOf(1.9f, 1f, 1.55f, 1f),
            VfVideoGrade.IDENTITY_CCM,
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
        assertEquals(0f, fp[16], 0f) // vibrance default (saturation folds into the CCM)
        assertEquals(0f, fp[17], 0f) // BT709 wire id default
    }

    @Test
    fun packCarriesExplicitKnobs() {
        val (_, fp) = VfVideoGrade.packGrade(
            intArrayOf(8, 8), floatArrayOf(1f, 1f, 1f, 1f),
            VfVideoGrade.IDENTITY_CCM, 0f, false, 1.2f, 0.8f, vibrance = 0.3f
        )
        assertEquals(1.2f, fp[15], 0f)
        assertEquals(0.3f, fp[16], 0f)
        assertEquals(VfVideoGrade.saturateCcm(VfVideoGrade.IDENTITY_CCM, 0.8f)[0], fp[4], 0f)
    }

    @Test
    fun packCarriesProfileWireId() {
        for (p in EncodedVideoProfile.entries) {
            val (_, fp) = VfVideoGrade.packGrade(
                intArrayOf(8, 8), floatArrayOf(1f, 1f, 1f, 1f),
                VfVideoGrade.IDENTITY_CCM, 0f, false,
                profile = p
            )
            assertEquals(p.wireId.toFloat(), fp[17], 0f)
        }
    }

    @Test
    fun packBypassFlag() {
        val (_, fp) = VfVideoGrade.packGrade(
            intArrayOf(8, 8), floatArrayOf(1f, 1f, 1f, 1f),
            VfVideoGrade.IDENTITY_CCM, 0f, true
        )
        assertEquals(1f, fp[14], 0f)
    }

    @Test
    fun slog3BlackIsZeroWhiteIsMidLog() {
        assertEquals(0f, VfVideoGrade.workingLog(0f), 1e-6f)
        // S-Log3 maps reference white to ~0.5 (log compression signature).
        assertEquals(0.4972f, VfVideoGrade.workingLog(1f), 1e-3f)
    }

    @Test
    fun slog3ContinuousAtCut() {
        val c = VfVideoGrade.LOG_CUT
        val e = 1e-6f
        // Continuous by construction: toe meets the log branch at the cut.
        assertEquals(VfVideoGrade.workingLog(c), VfVideoGrade.workingLog(c - e), 1e-4f)
        assertEquals(VfVideoGrade.workingLog(c), VfVideoGrade.workingLog(c + e), 1e-4f)
    }

    @Test
    fun slog3MonotonicAndCompressive() {
        var prev = VfVideoGrade.workingLog(0f)
        var x = 0.05f
        while (x <= 2f) {
            val v = VfVideoGrade.workingLog(x)
            assertTrue("not monotonic at $x", v > prev)
            prev = v
            x += 0.05f
        }
        // Highlights compress: the 1.0->2.0 step is smaller than 0.0->0.5.
        val lowGain = VfVideoGrade.workingLog(0.5f) - VfVideoGrade.workingLog(0f)
        val highGain = VfVideoGrade.workingLog(2f) - VfVideoGrade.workingLog(1f)
        assertTrue("not compressive: low=$lowGain high=$highGain", highGain < lowGain)
    }

    @Test
    fun slog3MidGrayLandsNearPointFourTwo() {
        // Anchors the device readback expectation for ~0.5 linear input.
        assertEquals(0.4214f, VfVideoGrade.workingLog(0.5f), 1e-3f)
    }

    @Test
    fun contrastNeutralIsIdentityEndpointsPinned() {
        for (v in floatArrayOf(0f, 0.1f, 0.31f, 0.5f, 0.8f, 1f)) {
            assertEquals(v, VfVideoGrade.contrastCurve(v, 1f), 1e-6f)
        }
        assertEquals(0f, VfVideoGrade.contrastCurve(0f, 1.5f), 1e-6f)
        assertEquals(1f, VfVideoGrade.contrastCurve(1f, 1.5f), 1e-6f)
        val pivot = VfVideoGrade.LOG_PIVOT
        assertEquals(pivot, VfVideoGrade.contrastCurve(pivot, 0.5f), 1e-6f)
        assertEquals(pivot, VfVideoGrade.contrastCurve(pivot, 1.5f), 1e-6f)
    }

    @Test
    fun contrastPushesAwayFromPivot() {
        // Above pivot brightens, below darkens; stronger knob = bigger push.
        val pivot = VfVideoGrade.LOG_PIVOT
        val hi = VfVideoGrade.contrastCurve(pivot + 0.2f, 1.5f)
        val lo = VfVideoGrade.contrastCurve(pivot - 0.15f, 1.5f)
        assertTrue(hi > pivot + 0.2f)
        assertTrue(lo < pivot - 0.15f)
        assertTrue(
            VfVideoGrade.contrastCurve(pivot + 0.2f, 1.5f) >
                VfVideoGrade.contrastCurve(pivot + 0.2f, 1.2f)
        )
    }

    @Test
    fun contrastMonotonicOverSliderRange() {
        for (c in floatArrayOf(0.5f, 1f, 1.5f)) {
            var prev = -1f
            var v = 0f
            while (v <= 1.0001f) {
                val y = VfVideoGrade.contrastCurve(v.coerceAtMost(1f), c)
                assertTrue("not monotonic at $v (c=$c)", y >= prev)
                prev = y
                v += 0.01f
            }
        }
    }

    @Test
    fun saturationNeutralAndMono() {
        assertEquals(0.7f, VfVideoGrade.saturate(0.7f, 0.5f, 1f), 1e-6f)
        assertEquals(0.5f, VfVideoGrade.saturate(0.7f, 0.5f, 0f), 1e-6f)
        assertEquals(0.9f, VfVideoGrade.saturate(0.7f, 0.5f, 2f), 1e-6f)
    }

    @Test
    fun srgbOetfKnownPoints() {
        assertEquals(0f, VfVideoGrade.srgbOetf(0f), 1e-6f)
        assertEquals(1f, VfVideoGrade.srgbOetf(1f), 1e-6f)
        // Linear toe meets the power branch at the cut, continuously.
        val cut = 0.0031308f
        assertEquals(12.92f * cut, VfVideoGrade.srgbOetf(cut), 1e-5f)
        assertEquals(VfVideoGrade.srgbOetf(cut), VfVideoGrade.srgbOetf(cut + 1e-6f), 1e-4f)
        // Mid-log 0.4214 (slog3 of 0.5 linear) encodes near 0.68.
        assertEquals(0.6811f, VfVideoGrade.srgbOetf(0.4214f), 1e-3f)
    }

    @Test
    fun sonySlog3MatchesPublishedAnchors() {
        // Sony S-Log3 whitepaper codes (10-bit): 0 -> 95, cut -> 171.21,
        // 18% gray -> 420, 90% white -> ~597.9.
        assertEquals(95f / 1023f, VfVideoGrade.sonySlog3(0f), 1e-6f)
        assertEquals(171.2103f / 1023f, VfVideoGrade.sonySlog3(0.01125f), 1e-4f)
        assertEquals(420f / 1023f, VfVideoGrade.sonySlog3(0.18f), 1e-6f)
        assertEquals(0.5845f, VfVideoGrade.sonySlog3(0.9f), 1e-3f)
        assertEquals(0.5960f, VfVideoGrade.sonySlog3(1f), 1e-3f)
    }

    @Test
    fun sonySlog3ContinuousAtCut() {
        val c = VfVideoGrade.SLOG3_CUT
        val e = 1e-6f
        assertEquals(VfVideoGrade.sonySlog3(c), VfVideoGrade.sonySlog3(c - e), 1e-4f)
        assertEquals(VfVideoGrade.sonySlog3(c), VfVideoGrade.sonySlog3(c + e), 1e-4f)
    }

    @Test
    fun sonySlog3MonotonicAndCompressive() {
        var prev = VfVideoGrade.sonySlog3(0f)
        var x = 0.05f
        while (x <= 2f) {
            val v = VfVideoGrade.sonySlog3(x)
            assertTrue("not monotonic at $x", v > prev)
            prev = v
            x += 0.05f
        }
        val lowGain = VfVideoGrade.sonySlog3(0.5f) - VfVideoGrade.sonySlog3(0f)
        val highGain = VfVideoGrade.sonySlog3(2f) - VfVideoGrade.sonySlog3(1f)
        assertTrue("not compressive: low=$lowGain high=$highGain", highGain < lowGain)
    }

    @Test
    fun invSonySlog3RoundTrips() {
        var x = 0.001f
        while (x <= 2f) {
            val rt = VfVideoGrade.invSonySlog3(VfVideoGrade.sonySlog3(x))
            assertEquals("at $x", x, rt, 2e-4f * x.coerceAtLeast(1e-3f))
            x *= 1.25f
        }
        // Sony's linear toe extends below black; clipping here changes hue.
        assertEquals(-0.0140237f, VfVideoGrade.invSonySlog3(0f), 1e-7f)
        for (signed in listOf(-0.1f, -0.01f, -0.001f, 0f)) {
            assertEquals(signed, VfVideoGrade.invSonySlog3(VfVideoGrade.sonySlog3(signed)), 1e-7f)
        }
    }

    @Test
    fun bt709OetfKnownPoints() {
        assertEquals(0f, VfVideoGrade.bt709Oetf(0f), 0f)
        assertEquals(1f, VfVideoGrade.bt709Oetf(1f), 1e-5f)
        assertEquals(0.4090f, VfVideoGrade.bt709Oetf(0.18f), 1e-3f)
        assertEquals(0.7055f, VfVideoGrade.bt709Oetf(0.5f), 1e-3f)
        // The published branches meet with a slight kink at 0.018
        // (0.0810 vs ~0.0813); both formulas are used verbatim.
        assertEquals(0.081f, VfVideoGrade.bt709Oetf(0.018f - 1e-6f), 1e-3f)
        assertEquals(0.0812f, VfVideoGrade.bt709Oetf(0.018f), 1e-3f)
    }

    @Test
    fun hlgOetfKnownPoints() {
        assertEquals(0f, VfVideoGrade.hlgOetf(0f), 0f)
        assertEquals(1f, VfVideoGrade.hlgOetf(1f), 1e-5f)
        // sqrt branch meets the log branch at 1/12 -> 0.5, continuously.
        val cut = 1f / 12f
        assertEquals(0.5f, VfVideoGrade.hlgOetf(cut), 1e-5f)
        assertEquals(VfVideoGrade.hlgOetf(cut), VfVideoGrade.hlgOetf(cut + 1e-6f), 1e-4f)
        assertEquals(0.6724f, VfVideoGrade.hlgOetf(0.18f), 1e-3f)
        // Diffuse-white anchor: ~0.265 linear lands at 75% signal.
        assertEquals(0.75f, VfVideoGrade.hlgOetf(0.265f), 1e-3f)
    }

    @Test
    fun srgbToBt2020WhitePreservingAndBounded() {
        val white = VfVideoGrade.linearSrgbToBt2020(floatArrayOf(1f, 1f, 1f))
        assertEquals(1f, white[0], 1e-5f)
        assertEquals(1f, white[1], 1e-5f)
        assertEquals(1f, white[2], 1e-5f)
        // sRGB primaries land inside the 2020 volume (all components 0..1).
        for (p in arrayOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f)
        )) {
            val c = VfVideoGrade.linearSrgbToBt2020(p)
            for (v in c) assertTrue("primary $p -> $v out of gamut", v in 0f..1f)
        }
        // Gray in, gray out (rows sum to 1).
        val gray = VfVideoGrade.linearSrgbToBt2020(floatArrayOf(0.18f, 0.18f, 0.18f))
        assertEquals(0.18f, gray[0], 1e-5f)
        assertEquals(0.18f, gray[1], 1e-5f)
        assertEquals(0.18f, gray[2], 1e-5f)
    }

    @Test
    fun encodePixel709IsDirectOetf() {
        // No warp, no saturation: per-channel BT.709 OETF, nothing else.
        for (v in floatArrayOf(0f, 0.18f, 0.5f, 1f)) {
            val out = VfVideoGrade.encodePixel709(floatArrayOf(v, v, v))
            assertEquals("at $v", VfVideoGrade.bt709Oetf(v), out[0], 1e-6f)
            assertEquals(out[0], out[1], 0f)
            assertEquals(out[0], out[2], 0f)
        }
        val mixed = VfVideoGrade.encodePixel709(floatArrayOf(1f, 0.5f, 0.25f))
        assertEquals(VfVideoGrade.bt709Oetf(1f), mixed[0], 1e-6f)
        assertEquals(VfVideoGrade.bt709Oetf(0.5f), mixed[1], 1e-6f)
        assertEquals(VfVideoGrade.bt709Oetf(0.25f), mixed[2], 1e-6f)
    }

    @Test
    fun encodePixelHlgIs2020PlusHlg() {
        // Gray passes the gamut matrix unchanged, then HLG encodes it.
        val gray = VfVideoGrade.encodePixelHlg(floatArrayOf(0.18f, 0.18f, 0.18f))
        assertEquals(0.6724f, gray[0], 1e-3f)
        assertEquals(gray[0], gray[1], 1e-6f)
        assertEquals(gray[0], gray[2], 1e-6f)
        val black = VfVideoGrade.encodePixelHlg(floatArrayOf(0f, 0f, 0f))
        assertEquals(0f, black[0], 0f)
        val white = VfVideoGrade.encodePixelHlg(floatArrayOf(1f, 1f, 1f))
        assertEquals(1f, white[0], 1e-5f)
    }

    @Test
    fun encodePixelSlog3IsFlatSonyLog() {
        // 18% gray at code 420, black at the 95 pedestal — no look applied.
        val gray = VfVideoGrade.encodePixelSlog3(floatArrayOf(0.18f, 0.18f, 0.18f))
        assertEquals(420f / 1023f, gray[0], 1e-6f)
        assertEquals(gray[0], gray[1], 0f)
        assertEquals(gray[0], gray[2], 0f)
        val black = VfVideoGrade.encodePixelSlog3(floatArrayOf(0f, 0f, 0f))
        assertEquals(95f / 1023f, black[0], 1e-6f)
    }

    @Test
    fun invSlog3RoundTrips() {
        assertEquals(0f, VfVideoGrade.inverseWorkingLog(0f), 1e-7f)
        var x = 0.001f
        while (x <= 2f) {
            val rt = VfVideoGrade.inverseWorkingLog(VfVideoGrade.workingLog(x))
            assertEquals("at $x", x, rt, 1e-4f * x.coerceAtLeast(1e-3f))
            x *= 1.25f
        }
    }

    @Test
    fun bakeLutLayoutAndDeterminism() {
        val lut = VfVideoGrade.bakeLut(1f)
        assertEquals(129 * 4, lut.size)
        // Alpha is always 1.0 (0x3C00); bake is deterministic.
        val one = 0x3C00.toShort()
        var i = 3
        while (i < lut.size) {
            assertEquals(one, lut[i])
            i += 4
        }
        assertArrayEquals(lut, VfVideoGrade.bakeLut(1f))
    }

    @Test
    fun bakeLutContrastChangesContent() {
        // The LUT carries contrast (saturation rides a uniform instead).
        val a = VfVideoGrade.bakeLut(1f)
        val b = VfVideoGrade.bakeLut(1.5f)
        assertTrue(a.zip(b.toList()).any { (x, y) -> x != y })
    }

    @Test
    fun bakeLutGrayRampMonotonic() {
        // Neutral 1D ramp: scene-linear rises with the lattice, unclipped
        // (the shader encodes sRGB after the fetch). Top center is
        // 2^((128.5/129)*14-10) = ~15.5 linear.
        val lut = VfVideoGrade.bakeLut(1f)
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
            val out = VfVideoGrade.developLinear(floatArrayOf(x, x, x), 1f)
            assertEquals("at $x", x, out[0], 1e-4f * x)
            assertEquals("at $x", x, out[1], 1e-4f * x)
            assertEquals("at $x", x, out[2], 1e-4f * x)
            x *= 1.5f
        }
    }

    @Test
    fun floatToHalfBitsKnownPoints() {
        assertEquals(0x0000, VfVideoGrade.floatToHalfBits(0f))
        assertEquals(0x3C00, VfVideoGrade.floatToHalfBits(1f))
        assertEquals(0x3800, VfVideoGrade.floatToHalfBits(0.5f))
        assertEquals(0xBC00, VfVideoGrade.floatToHalfBits(-1f))
    }

    @Test
    fun fusedAssetSelectsFp16VariantOnCapability() {
        assertEquals("shaders/vf/vf_mhcyuv_f16.spv", VfVideoGrade.fusedAsset(true))
        assertEquals("shaders/vf/vf_mhcyuv.spv", VfVideoGrade.fusedAsset(false))
    }
}
