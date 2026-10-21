// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AE-1: zeros in the p5..p95 band made the log-average -inf (max lift plus
 * the HDR-scene ceiling) and negatives made the correction NaN. Unusable
 * samples are now dropped before any statistic.
 */
class AdaptiveExposureMeterableSamplesTest {
    private val mids = FloatArray(10_000) { 0.02f + 0.06f * (it % 100) / 100f }

    private fun ev(values: FloatArray) =
        AdaptiveDevelopmentExposure.analyzeSamples(values.copyOf(), values.size).correctionEv

    @Test
    fun zerosAndNegativesDoNotDriveTheMeter() {
        val clean = ev(mids)
        val zeros = ev(FloatArray(10_000) { if (it % 10 == 0) 0f else mids[it] })
        val negatives = ev(FloatArray(10_000) { if (it % 10 == 0) -1e-4f else mids[it] })
        val nans = ev(FloatArray(10_000) { if (it % 10 == 0) Float.NaN else mids[it] })
        for (value in listOf(zeros, negatives, nans)) {
            assertTrue("finite", value.isFinite())
            assertEquals(clean, value, 0.05)
        }
    }

    @Test
    fun hdrPathIgnoresUnusableSamplesToo() {
        val r = AdaptiveDevelopmentExposure.analyzeHdrSamples(
            FloatArray(10_000) { if (it % 10 == 0) 0f else mids[it] }, 10_000
        )
        assertTrue(r.correctionEv.isFinite() && r.logAverage.isFinite())
    }
}
