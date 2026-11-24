// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class BasicToneMapperTest {
    private val basic = JpegOutputSettings(toneMapper = PhotoToneMapper.BASIC)

    @Test fun basicRampIsBoundedMonotonicAndPreservesEndpoints() {
        var previous = -1f
        for (i in 0..4096) {
            val encoded = AgxDisplayTransform.scopeCurve(i / 2048f - 0.25f, basic)
            assertTrue(encoded in 0f..1f)
            assertTrue(encoded >= previous)
            previous = encoded
        }
        assertEquals(0f, BasicDisplayTransform.encode(-1f), 0f)
        assertTrue(BasicDisplayTransform.encode(16f) < 1f)
        // Midtones are now ordinary photographic linear light plus the sRGB transfer.
        assertEquals(0.46135613f, BasicDisplayTransform.encode(0.18f), 1e-5f)
    }

    @Test fun switchingDoesNotLoseAgxTuningAndBasicIgnoresIt() {
        val tuned = basic.copy(agxContrast = 1.5f, agxSaturation = 0f,
            agxPurityBoost = 2f, agxLook = AgxLook.PUNCHY)
        val scene = floatArrayOf(0.7f, 0.2f, -0.1f)
        assertArrayEquals(AgxDisplayTransform.acescgToOutputLinearSrgb(scene, basic),
            AgxDisplayTransform.acescgToOutputLinearSrgb(scene, tuned), 0f)
        assertEquals(AgxLook.PUNCHY, tuned.copy(toneMapper = PhotoToneMapper.AGX).agxLook)
        assertEquals(PhotoToneMapper.SIGMOID, JpegOutputSettings().toneMapper)
        assertEquals(PhotoToneMapper.SIGMOID, PhotoToneMapper.fromPreference(null))
        assertEquals(PhotoToneMapper.SIGMOID, PhotoToneMapper.fromPreference("unknown"))
        assertEquals(PhotoToneMapper.AGX, PhotoToneMapper.fromPreference("AGX"))
        assertEquals(PhotoToneMapper.SIGMOID, PhotoToneMapper.fromPreference("BASIC"))
        assertFalse(PhotoToneMapper.BASIC in PhotoToneMapper.selectable)
    }

    @Test fun shoulderPreservesMidsAndHighlightGradationAtEveryStrength() {
        for (strength in floatArrayOf(0f, .25f, .5f, 1f)) {
            for (n in floatArrayOf(0f, .01f, .18f, .6f))
                assertEquals(AgxDisplayTransform.srgbOetf(n), BasicDisplayTransform.encode(n, strength), 1e-6f)
            var previous = 0f
            for (n in floatArrayOf(.6f, .8f, 1f, 1.5f, 2f, 4f, 16f, 64f)) {
                val mapped = BasicDisplayTransform.encode(n, strength)
                assertTrue(mapped > previous)
                assertTrue(mapped < 1f)
                previous = mapped
            }
        }
        assertEquals(AdaptiveExposureTuning.fromOutputSettings(basic.copy(highlightShoulder = 0f)),
            AdaptiveExposureTuning.fromOutputSettings(basic.copy(highlightShoulder = 1f)))
    }

    @Test fun highlightsKeepHueOrderingAndApproachWhiteSmoothly() {
        var previousChroma = 1f
        for (ev in 0..12) {
            val exposure = Math.scalb(1f, ev)
            val encoded = BasicDisplayTransform.encodeRgb(floatArrayOf(1f, .5f, .25f).map { it * exposure }.toFloatArray(), 1f)
            val rgb = encoded.map { BasicDisplayTransform.decode(it) }
            assertTrue(rgb[0] >= rgb[1] && rgb[1] >= rgb[2])
            // Affine compression toward white preserves the middle channel's hue position.
            assertEquals(1f / 3f, (rgb[1] - rgb[2]) / (rgb[0] - rgb[2]), 3e-3f)
            val chroma = (rgb[0] - rgb[2]) / rgb[0]
            assertTrue(chroma < previousChroma)
            previousChroma = chroma
        }
        for (rgb in listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(-1f, .2f, 2f), floatArrayOf(65504f, 0f, 0f))) {
            assertTrue(BasicDisplayTransform.encodeRgb(rgb, 1f).all { it.isFinite() && it in 0f..1f })
        }
    }

    @Test fun kneeIsContinuousInValueAndSlope() {
        for (strength in floatArrayOf(0f, .5f, 1f)) {
            val k = .76f - .16f * strength
            fun linear(x: Float) = BasicDisplayTransform.decode(BasicDisplayTransform.encode(x, strength))
            val h = .001f
            assertEquals(k, linear(k), 2e-6f)
            assertEquals(1f, (linear(k + h) - linear(k - h)) / (2f * h), .01f)
        }
    }

    @Test fun meterAndScopeUseBasicWithoutAgxShoulder() {
        val tuning = AdaptiveExposureTuning.fromOutputSettings(basic)
        assertEquals(0f, tuning.highlightShoulder, 0f)
        assertEquals(basic.highlightSoftHeadroom.toDouble(), tuning.highlightSoftHeadroom, 1e-5)
        for (n in floatArrayOf(0.001f, 0.18f, 0.5f, 0.95f)) {
            val code = AgxDisplayTransform.scopeCurve(n, basic)
            assertEquals(n, AgxDisplayTransform.invertScopeCurve(code, basic), 1e-5f)
            val linear = AgxDisplayTransform.acescgToOutputLinearSrgb(floatArrayOf(n, n, n), basic)
            for (channel in linear) assertEquals(code, AgxDisplayTransform.srgbOetf(channel), 2e-4f)
        }
        val cache = ScopeLutCache()
        val agx = cache.get(JpegOutputSettings(toneMapper = PhotoToneMapper.AGX))
        val switched = cache.get(basic)
        assertNotSame(agx, switched)
        assertSame(switched, cache.get(basic))
    }
}
