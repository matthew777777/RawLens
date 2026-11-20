// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneLookTest {
    @Test fun defaultSigmoidEqualsDarktableDefaults() {
        // darktable 5.6 defaults: contrast 1.5, skew 0, white 100 %, black 0.0152 %.
        val p = SigmoidDisplayTransform.Params.of(ToneLookSettings())
        assertEquals(1f, p.magnitude, 0f)
        assertEquals(1.5f, p.filmPower, 1e-4f)
        assertEquals(1f, p.paperPower, 1e-6f)
        assertEquals(0.0014263709f, p.filmFog, 1e-7f)
        assertEquals(0.35435542f, p.paperExposure, 1e-5f)
        // Middle grey maps to itself.
        assertEquals(0.1845f, SigmoidDisplayTransform.curve(0.1845f, p), 2e-4f)
    }

    @Test fun skewKeepsGreySlopeAndTargetsHoldEnds() {
        for (skew in floatArrayOf(-0.8f, 0f, 0.8f)) {
            val p = SigmoidDisplayTransform.Params.of(ToneLookSettings(sigmoidSkew = skew, sigmoidWhite = .9f))
            assertTrue(SigmoidDisplayTransform.curve(1e6f, p) <= .9001f)
            var prev = -1f
            for (i in 0..2000) { val y = SigmoidDisplayTransform.curve(i / 100f, p); assertTrue(y >= prev); prev = y }
        }
    }

    @Test fun neutralAcesAndBasicLookIsIdentity() {
        val rgb = floatArrayOf(.3f, .12f, .05f)
        for (m in listOf(PhotoToneMapper.ACES_2, PhotoToneMapper.BASIC)) {
            val out = ToneLook.pre(rgb, JpegOutputSettings(toneMapper = m))
            for (c in 0..2) assertEquals(rgb[c], out[c], 1e-5f)
        }
    }

    @Test fun clippedPhotositesLeaveSingleFrameHighlightGuards() {
        // Dim frame with 3 % clipped sun: merges keep protecting it, single frames do not.
        val values = FloatArray(4096) { if (it < 3970) 0.02f else 1.0f }
        val cfa = UnpackedRawCfa(64, 64, BayerPattern.RGGB, values, RawCrop(0, 0, 64, 64))
        val merged = AdaptiveDevelopmentExposure.analyze(cfa)
        val single = AdaptiveDevelopmentExposure.analyze(cfa, singleFrame = true)
        assertTrue("single ${single.correctionEv} merged ${merged.correctionEv}", single.correctionEv > merged.correctionEv + 1.0)
    }
}
