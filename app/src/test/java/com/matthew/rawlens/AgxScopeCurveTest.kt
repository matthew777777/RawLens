// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgxScopeCurveTest {
    @Test
    fun scopeCurveMatchesDisplayEncodedNeutralRamp() {
        // The 1D scope curve must track the full pinned transform on neutrals:
        // inset/outset/output matrices are identity there by construction.
        listOf(0f, 0.01f, 0.18f, 0.5f, 1f, 2f, 16f).forEach { input ->
            val full = AgxDisplayTransform.acescgToOutputLinearSrgb(floatArrayOf(input, input, input))
            val encoded = AgxDisplayTransform.srgbOetf(
                full.average().toFloat().coerceIn(0f, 1f)
            )
            assertEquals("linear $input", encoded, AgxDisplayTransform.scopeCurve(input), 2e-3f)
        }
    }

    @Test
    fun scopeCurveIsMonotonicAndBounded() {
        var previous = -1f
        var value = 0f
        while (value <= 65_504f) {
            val curved = AgxDisplayTransform.scopeCurve(value)
            assertTrue("not finite at $value", curved.isFinite())
            assertTrue("out of range at $value: $curved", curved in 0f..1f)
            assertTrue("not monotonic at $value", curved >= previous)
            previous = curved
            value = if (value < 1f) value + 0.02f else value * 1.5f
        }
    }

    @Test
    fun scopeCurveAnchorsBlackAndWhite() {
        val black = AgxDisplayTransform.scopeCurve(0f)
        assertEquals(0f, black, 1e-3f)
        // Negative/NaN clip to the same sigmoid black floor, never below it.
        assertEquals(black, AgxDisplayTransform.scopeCurve(-1f), 0f)
        assertEquals(0f, AgxDisplayTransform.scopeCurve(Float.NaN), 0f)
        // Diffuse white sits bright with headroom above; the shoulder plateau above
        // it is the brightest neutral the JPEG can hold (matches the pinned 0.699
        // linear -> ~0.85 encoded reference in AgxDisplayTransformTest).
        val white = AgxDisplayTransform.scopeCurve(1f)
        assertTrue("diffuse white $white should sit bright", white in 0.7f..0.85f)
        val peak = AgxDisplayTransform.scopeCurve(16f)
        assertTrue("peak $peak should top diffuse white without clipping", peak >= white && peak <= 0.9f)
        val mid = AgxDisplayTransform.scopeCurve(0.18f)
        assertTrue("mid gray $mid should sit mid-scale", mid in 0.3f..0.7f)
    }

    @Test
    fun scopeCurveTracksContrastSlider() {
        val flat = AgxDisplayTransform.scopeCurve(0.35f, JpegOutputSettings(agxContrast = 0.5f))
        val base = AgxDisplayTransform.scopeCurve(0.35f, JpegOutputSettings(agxContrast = 1f))
        val punchy = AgxDisplayTransform.scopeCurve(0.35f, JpegOutputSettings(agxContrast = 1.5f))
        // Above the pivot, more contrast pushes the tone brighter.
        assertTrue("flat=$flat base=$base punchy=$punchy", flat < base && base < punchy)
    }

    @Test
    fun scopeLutMatchesDirectEvaluation() {
        val settings = JpegOutputSettings()
        val lut = AgxDisplayTransform.buildScopeLut(settings)
        assertEquals(AgxDisplayTransform.SCOPE_LUT_SIZE, lut.size)
        for (i in lut.indices step 37) {
            val linear = i.toFloat() / (lut.size - 1)
            assertEquals("lut[$i]", AgxDisplayTransform.scopeCurve(linear, settings), lut[i], 0f)
        }
        var previous = -1f
        lut.forEach { value ->
            assertTrue(value in 0f..1f && value >= previous)
            previous = value
        }
    }

    @Test
    fun invertScopeCurveRoundTripsAcrossSettings() {
        // The meter states its broad-mass guard once in encoded terms and
        // re-solves it per AgX settings: the inversion must round-trip at
        // defaults and at every range/contrast/shoulder extreme, staying
        // monotonic so bisection lands on the graded branch (never a clip).
        val settingsCases = listOf(
            JpegOutputSettings(),
            JpegOutputSettings(agxContrast = 0.5f),
            JpegOutputSettings(agxContrast = 1.5f),
            JpegOutputSettings(agxShadowEv = 4f, agxHighlightEv = 3f),
            JpegOutputSettings(agxShadowEv = 14f, agxHighlightEv = 10f),
            JpegOutputSettings(highlightShoulder = 0f),
            JpegOutputSettings(agxContrast = 1.5f, agxShadowEv = 4f, agxHighlightEv = 3f)
        )
        for (settings in settingsCases) {
            var previous = -1f
            var linear = 0f
            while (linear <= 16f) {
                val encoded = AgxDisplayTransform.scopeCurve(linear, settings)
                // Tolerance for float32 rounding near the shoulder asymptote,
                // where adjacent scene levels differ by ~1e-6 in encoding
                // (the strict form is already pinned at defaults above; the
                // roundtrips below carry the bisection-validity weight).
                assertTrue("regressed at $linear ($settings)", encoded + 1e-5f >= previous)
                previous = encoded
                linear = if (linear < 1f) linear + 0.05f else linear * 1.25f
            }
            for (anchor in listOf(0.1f, 0.35f, 0.85f, 2f)) {
                val encoded = AgxDisplayTransform.scopeCurve(anchor, settings)
                val solved = AgxDisplayTransform.invertScopeCurve(encoded, settings)
                // Inverter contract, plateau-safe: the solution re-encodes to
                // the target level, and it never overshoots the anchor (on a
                // clip plateau it lands on the knee, not past it).
                assertTrue(
                    "anchor $anchor ($settings): re-encode ${AgxDisplayTransform.scopeCurve(solved, settings)}",
                    AgxDisplayTransform.scopeCurve(solved, settings) + 1e-3f >= encoded
                )
                assertTrue("anchor $anchor ($settings): overshoot $solved", solved <= anchor + 1e-3f)
                // Off-plateau anchors additionally round-trip near-exactly.
                if (encoded < 0.999f && AgxDisplayTransform.scopeCurve(anchor * 1.01f, settings) > encoded) {
                    assertEquals("anchor $anchor ($settings)", anchor, solved, 1e-2f)
                }
            }
        }
        // Edges: at/below black returns 0; display white is above the
        // sigmoid ceiling (~0.98 encoded), so it saturates at the scene cap.
        assertEquals(0f, AgxDisplayTransform.invertScopeCurve(0f), 0f)
        assertEquals(
            AgxDisplayTransform.SCOPE_INVERT_CAP,
            AgxDisplayTransform.invertScopeCurve(1f),
            0f
        )
    }
}
