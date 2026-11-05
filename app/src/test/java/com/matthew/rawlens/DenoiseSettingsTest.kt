// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
class DenoiseSettingsTest {
    @Test fun ffdnetReplacesWaveletOnlyWhenActive() {
        val settings = DenoiseSettings(ffdnet = FfdnetSettings(enabled = true),
            wavelet = WaveletSettings(enabled = true))
        assertEquals(false, resolveDenoise(settings, false).wavelet)
        assertEquals(true, resolveDenoise(settings.copy(ffdnet = FfdnetSettings(true, 0f)), false).wavelet)
        assertEquals(false, resolveDenoise(settings, true).wavelet)
        assertEquals(false, DenoiseSettings().ffdnet.active)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidFfdnetStrength() {
        FfdnetSettings(true, Float.NaN)
    }
    @Test fun defaultsAreAiOffWithArchivalDng() {
        val settings = DenoiseSettings()
        assertEquals(false, settings.aiEnabled)
        assertEquals(true, settings.saveOriginalDng)
        assertEquals(1f, settings.aiStrength, 0f)
    }
    @Test fun cameraRgbNoiseExpandsToBothGreenCfaPlanes() {
        val model = CfaNoiseModel.from(ImmutableDoubleValues(doubleArrayOf(1.0,0.1,2.0,0.2,3.0,0.3)))
        assertArrayEquals(floatArrayOf(1f,2f,2f,3f), model.scale, 0f)
        assertArrayEquals(floatArrayOf(0.1f,0.2f,0.2f,0.3f), model.offset, 0f)
    }
}
