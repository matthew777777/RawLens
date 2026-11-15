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
            agxPurityBoost = 2f, agxLook = AgxLook.PUNCHY, highlightShoulder = 0f)
        val scene = floatArrayOf(0.7f, 0.2f, -0.1f)
        assertArrayEquals(AgxDisplayTransform.acescgToOutputLinearSrgb(scene, basic),
            AgxDisplayTransform.acescgToOutputLinearSrgb(scene, tuned), 0f)
        assertEquals(AgxLook.PUNCHY, tuned.copy(toneMapper = PhotoToneMapper.AGX).agxLook)
        assertEquals(PhotoToneMapper.AGX, PhotoToneMapper.fromPreference(null))
        assertEquals(PhotoToneMapper.AGX, PhotoToneMapper.fromPreference("unknown"))
        assertEquals(PhotoToneMapper.BASIC, PhotoToneMapper.fromPreference("BASIC"))
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
        val agx = cache.get(JpegOutputSettings())
        val switched = cache.get(basic)
        assertNotSame(agx, switched)
        assertSame(switched, cache.get(basic))
    }
}
