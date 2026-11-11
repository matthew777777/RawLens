// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class HdrPlusMergeModelTest {
    private fun model(a: Double, b: Double) =
        HdrPlusNoiseModel(a, b, 5, doubleArrayOf(0.02, 0.05, 0.1, 0.2, 0.4))

    @Test fun gainsRiseWithStrengthAndFallWithNoise() {
        val quiet = HdrPlusMergeModel.frequencyGains(model(1e-4, 1e-6), 959.0)
        val noisy = HdrPlusMergeModel.frequencyGains(model(1.5e-3, 1e-5), 959.0)
        assertEquals(22, quiet.size)
        for (i in 1 until quiet.size) assertTrue(quiet[i] > quiet[i - 1])
        for (i in quiet.indices) assertTrue(noisy[i] < quiet[i])
    }

    /** Restates RawMergeHdrPlusGpu.h frequencyNorms + the merge shaders at strength 13. */
    @Test fun gainMatchesTheNativeNormsAtOneLevel() {
        val level = 0.1
        val range = 959.0
        val m = HdrPlusNoiseModel(1e-3, 0.0, 1, doubleArrayOf(level))
        val rev = 0.5 * (26.5 - 13)
        val robustness = Math.pow(2.0, -rev + 7.5)
        val read = Math.pow(Math.pow(2.0, -rev + 10.0), 1.6)
        val maxMotion = Math.pow(1.3, 11.0 - rev)
        val motion = maxMotion - (0.12 - 0.02) * (maxMotion - 1.0) / 0.15
        val norm = (0.25 * level * range + read) * 64.0 * robustness * motion
        val noisePower = 2.0 * 1e-3 * level * range * range * 9.0  // raised-cosine energy = 64 * (3/8)^2
        assertEquals(norm / noisePower, HdrPlusMergeModel.frequencyGains(m, range)[12], 1e-9 * norm / noisePower)
    }

    /** Restates hdrp_blur BW16, RawMergeHdrPlusGpu.h robustness and hdrp_merge_weight at strength 13. */
    @Test fun spatialGainMatchesTheNativeWeightRule() {
        val w = HdrPlusMergeModel.blurWeights
        assertEquals(17, w.size)
        assertEquals(1.0, w.sum(), 1e-12)
        assertEquals(565722720.0 / 601080390.0, w[9] / w[8], 1e-12)
        assertEquals(10518300.0 / 601080390.0, w[0] / w[8], 1e-12)
        val reference = 0.02
        val sigma = 0.004
        val robustness = 0.12 * Math.pow(1.3, 0.5 * (36 - 13)) - 0.4529822
        val noiseDifference = 4 * w.sumOf { it * it } * 2 * sigma / Math.sqrt(Math.PI)
        val gains = HdrPlusMergeModel.spatialGains(reference, sigma)!!
        assertEquals(reference / robustness / noiseDifference - 1.0, gains[12], 1e-9)
        for (i in 1 until gains.size) assertTrue(gains[i] > gains[i - 1])
        // More texture in the reference loosens the native rule; the model sees it.
        assertTrue(HdrPlusMergeModel.spatialGains(2 * reference, sigma)!![12] > gains[12])
        for (bad in listOf(Double.NaN, 0.0, -1.0)) {
            assertNull(HdrPlusMergeModel.spatialGains(bad, sigma))
            assertNull(HdrPlusMergeModel.spatialGains(reference, bad))
        }
    }

    @Test fun strengthInvertsTheGainCurveAndClampsToTheSlider() {
        val gains = HdrPlusMergeModel.frequencyGains(model(5e-4, 5e-6), 959.0)
        for (s in 1..22) assertEquals(s.toFloat(), HdrPlusMergeModel.strengthFor(gains, gains[s - 1]), 1e-4f)
        val mid = HdrPlusMergeModel.strengthFor(gains, Math.sqrt(gains[9] * gains[10]))
        assertEquals(10.5f, mid, 1e-4f)
        assertEquals(HdrPlusSettings.MIN_STRENGTH, HdrPlusMergeModel.strengthFor(gains, 0.0), 0f)
        assertEquals(HdrPlusSettings.MAX_STRENGTH, HdrPlusMergeModel.strengthFor(gains, Double.MAX_VALUE), 0f)
    }

    @Test fun targetGainRunsFromFrameCountDownToMatchedNoise() {
        assertEquals(8.0, HdrPlusMergeModel.targetGain(8, 0.0), 1e-12)
        assertEquals(1.0, HdrPlusMergeModel.targetGain(8, 1.0), 1e-12)
        assertEquals(Math.sqrt(20.0), HdrPlusMergeModel.targetGain(20, 0.5), 1e-12)
        assertTrue(HdrPlusMergeModel.targetGain(20, 0.0) > HdrPlusMergeModel.targetGain(8, 0.0))
        assertEquals(1.0, HdrPlusMergeModel.targetGain(8, 7.0), 1e-12)
    }
}
