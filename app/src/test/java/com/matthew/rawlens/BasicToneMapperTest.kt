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
        assertEquals(1f, BasicDisplayTransform.encode(16f), 1e-6f)
        // Independent double precision values for the Basic RAW response.
        assertEquals(0.49308327f, BasicDisplayTransform.encode(0.18f), 1e-5f)
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
        assertEquals(PhotoToneMapper.BASIC, PhotoToneMapper.fromPreference("BASIC"))
    }

    @Test fun shoulderPreservesMidsAndHighlightGradationWithoutChangingRgbRatios() {
        for (n in floatArrayOf(0f, .01f, .18f, .6f)) {
            assertEquals(BasicDisplayTransform.encode(n), BasicDisplayTransform.encode(n, 1f), 0f)
        }
        var previous = 0f
        for (n in floatArrayOf(.6f, .8f, 1f, 1.5f, 2f, 4f, 16f)) {
            val mapped = BasicDisplayTransform.encode(n, 1f)
            assertTrue(mapped > previous)
            assertTrue(mapped < 1f)
            previous = mapped
        }
        val encoded = BasicDisplayTransform.encodeRgb(floatArrayOf(2f, 1f, .5f), 1f)
        // The shoulder applies one RGB scale before the existing per-channel
        // Basic display curve (the latter deliberately changes display saturation).
        val scale = (.6f + 1.4f / (1f + 1.4f / .4f)) / 2f
        assertArrayEquals(floatArrayOf(2f, 1f, .5f).map {
            BasicDisplayTransform.encode(it * scale, 0f)
        }.toFloatArray(), encoded, 1e-6f)
        assertEquals(1f, BasicDisplayTransform.encode(2f, 0f), 1e-6f)
        // The sky guard stays at the user's sensor-linear setting, not a
        // brighter target bought by the new display shoulder.
        assertEquals(AdaptiveExposureTuning.fromOutputSettings(basic.copy(highlightShoulder = 0f)),
            AdaptiveExposureTuning.fromOutputSettings(basic.copy(highlightShoulder = 1f)))
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
