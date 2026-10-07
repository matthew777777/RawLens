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
}
