// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveletDenoiseTest {
    private val waveletOn = WaveletSettings(enabled = true)

    @Test fun resolveDisablesEverythingOnMergedPath() {
        val r = resolveDenoise(
            DenoiseSettings(aiEnabled = true, wavelet = waveletOn),
            mergedPath = true
        )
        assertEquals(PreDemosaicStage.OFF, r.preDemosaic)
        assertFalse(r.wavelet)
    }

    @Test fun resolveAiStacksWithWavelet() {
        val r = resolveDenoise(
            DenoiseSettings(aiEnabled = true, wavelet = waveletOn),
            mergedPath = false
        )
        assertEquals(PreDemosaicStage.AI, r.preDemosaic)
        assertTrue(r.wavelet)
    }

    @Test fun resolveZeroStrengthDisablesStage() {
        val idle = resolveDenoise(
            DenoiseSettings(aiEnabled = true, wavelet = waveletOn.copy(strength = 0f)),
            mergedPath = false
        )
        // Zero-strength wavelet is off; AI still runs.
        assertEquals(PreDemosaicStage.AI, idle.preDemosaic)
        assertFalse(idle.wavelet)
    }

    @Test fun lumaIsIndependentOfDetail() {
        assertEquals(0.25f, WaveletSettings(enabled = true).forceY, 0f)
        assertEquals(0.25f, waveletOn.copy(detail = 0.5f).forceY, 0f)
        assertEquals(0.4f, waveletOn.copy(luma = 0.4f).forceY, 0f)
    }

    @Test fun waveletSettingsBounded() {
        val b = WaveletSettings(
            enabled = true, strength = 99f, detail = -1f, luma = 5f,
            maxScale = 99, forceUv = -3f
        ).bounded()
        assertEquals(8f, b.strength, 0f)
        assertEquals(0f, b.detail, 0f)
        assertEquals(1f, b.luma, 0f)
        assertEquals(1.8f, waveletOn.copy(detail = 2f).bounded().detail, 0f)
        assertEquals(7, b.maxScale)
        assertEquals(0f, b.forceUv, 0f)
    }

    @Test fun greenNoiseNormalizationMatchesReference() {
        // RAWR: a = S/range, b = (S*black+O)/range^2.
        val (a, b) = WaveletDenoiser.greenNoiseNormalized(0.5f, 0.25f, 64f, 1088f)
        assertEquals((0.5 / 1024.0).toFloat(), a, 0f)
        assertEquals(((0.5 * 64 + 0.25) / (1024.0 * 1024.0)).toFloat(), b, 0f)
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

    @Test fun noiseABBypassesWithoutValidProfile() {
        assertNull(WaveletDenoiser.noiseAB(null, BayerPattern.RGGB, 64f, 1024f))
        assertNull(WaveletDenoiser.noiseAB(DoubleArray(6), BayerPattern.RGGB, 64f, 1024f))
        assertNull(WaveletDenoiser.noiseAB(DoubleArray(8), BayerPattern.RGGB, 64f, 64f))
    }

    @Test fun profileSelectsBothGreenSitesForEveryCfa() {
        val profile = doubleArrayOf(1.0, 0.1, 2.0, 0.2, 4.0, 0.4, 8.0, 0.8)
        for (pattern in BayerPattern.entries) {
            val edgeGreen = pattern == BayerPattern.GRBG || pattern == BayerPattern.GBRG
            val expected = WaveletDenoiser.greenNoiseNormalized(
                if (edgeGreen) 4.5f else 3f, if (edgeGreen) 0.45f else 0.3f, 64f, 1088f)
            assertEquals(expected, WaveletDenoiser.noiseAB(profile, pattern, 64f, 1088f))
        }
        profile[2] = Double.NaN
        assertNull(WaveletDenoiser.noiseAB(profile, BayerPattern.RGGB, 64f, 1088f))
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
