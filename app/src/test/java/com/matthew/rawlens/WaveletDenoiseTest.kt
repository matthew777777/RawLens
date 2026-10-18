// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveletDenoiseTest {
    private val waveletOn = WaveletSettings(enabled = true)

    @Test fun resolveDisablesEverythingOnMergedPath() {
        val r = resolveDenoise(
            DenoiseSettings(aiEnabled = true, wavelet = waveletOn),
            GaloshSettings(enabled = true), mergedPath = true
        )
        assertEquals(PreDemosaicStage.OFF, r.preDemosaic)
        assertFalse(r.wavelet)
    }

    @Test fun resolveGaloshWinsOverAiAndWavelet() {
        val r = resolveDenoise(
            DenoiseSettings(aiEnabled = true, wavelet = waveletOn),
            GaloshSettings(enabled = true), mergedPath = false
        )
        assertEquals(PreDemosaicStage.GALOSH, r.preDemosaic)
        assertFalse(r.wavelet)
    }

    @Test fun resolveAiStacksWithWavelet() {
        val r = resolveDenoise(
            DenoiseSettings(aiEnabled = true, wavelet = waveletOn),
            GaloshSettings(), mergedPath = false
        )
        assertEquals(PreDemosaicStage.AI, r.preDemosaic)
        assertTrue(r.wavelet)
    }

    @Test fun resolveZeroStrengthDisablesStage() {
        val idle = resolveDenoise(
            DenoiseSettings(aiEnabled = true, wavelet = waveletOn.copy(strength = 0f)),
            GaloshSettings(enabled = true, strength = 0f), mergedPath = false
        )
        // Zero-strength Galosh is off, so AI runs and wavelet stays off.
        assertEquals(PreDemosaicStage.AI, idle.preDemosaic)
        assertFalse(idle.wavelet)
    }

    @Test fun forceYFollowsDetailGate() {
        assertEquals(0.25f, WaveletSettings(enabled = true).forceY, 0f)
        assertEquals(0f, waveletOn.copy(detail = 0.5f).forceY, 0f)
        assertEquals(0.4f, waveletOn.copy(luma = 0.4f).forceY, 0f)
    }

    @Test fun waveletSettingsBounded() {
        val b = WaveletSettings(
            enabled = true, strength = 99f, detail = -1f, luma = 5f,
            shadows = 2f, maxScale = 99, forceUv = -3f
        ).bounded()
        assertEquals(8f, b.strength, 0f)
        assertEquals(0f, b.detail, 0f)
        assertEquals(1f, b.luma, 0f)
        assertEquals(1f, b.shadows, 0f)
        assertEquals(7, b.maxScale)
        assertEquals(0f, b.forceUv, 0f)
    }

    @Test fun greenNoiseNormalizationMatchesReference() {
        // GreenNoiseToNormalized: a = S/range, b = O/range^2.
        val (a, b) = WaveletDenoiser.greenNoiseNormalized(0.5, 0.25, 1024.0)
        assertEquals((0.5 / 1024.0).toFloat(), a, 0f)
        assertEquals((0.25 / (1024.0 * 1024.0)).toFloat(), b, 0f)
    }

    @Test fun wbGainsNormalizeGreen() {
        assertArrayEquals(
            floatArrayOf(2f, 1f, 1.5f),
            WaveletDenoiser.wbGainsGreen1(doubleArrayOf(0.5, 1.0, 2.0 / 3.0)), 1e-6f
        )
        // Bad input falls back to unity rather than poisoning the run.
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), WaveletDenoiser.wbGainsGreen1(null), 0f)
        assertArrayEquals(
            floatArrayOf(1f, 1f, 1f),
            WaveletDenoiser.wbGainsGreen1(doubleArrayOf(0.0, 1.0, 1.0)), 0f
        )
    }

    @Test fun noiseABFallsBackWithoutProfile() {
        val (a, b) = WaveletDenoiser.noiseAB(null, 1024.0)
        assertEquals(CfaNoiseModel.FALLBACK_SCALE, a, 0f)
        assertEquals(CfaNoiseModel.FALLBACK_OFFSET, b, 0f)
        val (a2, b2) = WaveletDenoiser.noiseAB(doubleArrayOf(0.5, 0.25, 0.5, 0.25, 0.5, 0.25), 0.0)
        assertEquals(CfaNoiseModel.FALLBACK_SCALE, a2, 0f)
        assertEquals(CfaNoiseModel.FALLBACK_OFFSET, b2, 0f)
    }

    @Test fun cfaModelGreenPlaneMatchesNoiseProfile() {
        // SENSOR_NOISE_PROFILE order is R,G,B pairs; green feeds the wavelet.
        val model = CfaNoiseModel.from(
            ImmutableDoubleValues(doubleArrayOf(1.0, 0.1, 2.0, 0.2, 3.0, 0.3))
        )
        assertEquals(2f, model.scale[1], 0f)
        assertEquals(0.2f, model.offset[1], 0f)
    }
}
