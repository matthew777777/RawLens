// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawProgramMeterTest {
    @Test
    fun darkSceneAsksForBrighterExposure() {
        val shift = RawProgramMeter.correctionEv(measured = 0.05f, evBias = 0.5f)
        assertTrue("expected positive shift, was $shift", shift > 1.0)
    }

    @Test
    fun brightSceneAsksForDarkerExposure() {
        val shift = RawProgramMeter.correctionEv(measured = 0.45f, evBias = 0.5f)
        assertTrue("expected negative shift, was $shift", shift < 0.0)
    }

    @Test
    fun sceneAtBiasedTargetNeedsNoShift() {
        val target = RawProgramMeter.targetLevel(0.5f).toFloat()
        assertEquals(0.0, RawProgramMeter.correctionEv(target, 0.5f), 1e-9)
    }

    @Test
    fun biasBrightensTarget() {
        assertTrue(RawProgramMeter.targetLevel(0.5f) > RawProgramMeter.targetLevel(0f))
        assertTrue(RawProgramMeter.targetLevel(1f) > RawProgramMeter.targetLevel(0.5f))
    }

    @Test
    fun blackFrameAsksForNoShift() {
        assertEquals(0.0, RawProgramMeter.correctionEv(0f, 0.5f), 0.0)
    }

    @Test
    fun correctionStepIsClampedPerUpdate() {
        assertEquals(2.0, RawProgramMeter.correctionEv(0.001f, 0.5f), 1e-9)
        assertEquals(-2.0, RawProgramMeter.correctionEv(1f, -1f), 1e-9)
    }

    @Test
    fun meanLevelWeightsBins() {
        val bins = IntArray(RawEttrSampler.BIN_COUNT)
        bins[RawEttrSampler.BIN_COUNT - 1] = 100
        assertEquals(1f, RawProgramMeter.meanLevel(bins, 100), 0.01f)
        val dark = IntArray(RawEttrSampler.BIN_COUNT)
        dark[0] = 100
        assertEquals(0f, RawProgramMeter.meanLevel(dark, 100), 0.01f)
        assertEquals(0f, RawProgramMeter.meanLevel(IntArray(256), 0), 0f)
    }

    private fun greenSample(
        greenBins: IntArray,
        greenTotal: Int,
        centerWeightedGreen: Float = Float.NaN
    ): EttrRawSample {
        val bins = Array(4) { IntArray(RawEttrSampler.BIN_COUNT) }
        bins[1] = greenBins.copyOf()
        val totals = intArrayOf(0, greenTotal, greenTotal, 0)
        val levels = EttrChannelLevels(0f, 0f, 0f, 0f)
        return EttrRawSample(levels, bins, IntArray(4), totals, centerWeightedGreen)
    }

    @Test
    fun medianIgnoresBrightTail() {
        // Bulk dark, small bright tail: median stays dark while the mean is pulled up.
        val bins = IntArray(RawEttrSampler.BIN_COUNT)
        bins[20] = 9_000
        bins[250] = 1_000
        val sample = greenSample(bins, 10_000, centerWeightedGreen = 0.2f)
        val median = RawProgramMeter.medianBrightness(sample)
        val mean = RawProgramMeter.meanBrightness(sample)
        assertTrue("median should be darker than mean: median=$median mean=$mean", median < mean)
        assertEquals((20 + 0.5f) / RawEttrSampler.BIN_COUNT, median, 0.02f)
    }

    @Test
    fun centerWeightedPrefersSampledCenterValue() {
        val bins = IntArray(RawEttrSampler.BIN_COUNT)
        bins[100] = 1_000
        val sample = greenSample(bins, 1_000, centerWeightedGreen = 0.42f)
        assertEquals(
            0.42f,
            RawProgramMeter.brightness(sample, ProgramMetering.CENTER_WEIGHTED),
            1e-6f
        )
        // Median of the same bins lands on the single filled bin instead.
        assertEquals(
            (100 + 0.5f) / RawEttrSampler.BIN_COUNT,
            RawProgramMeter.brightness(sample, ProgramMetering.MEDIAN),
            0.01f
        )
    }

    @Test
    fun centerWeightedFallsBackToMeanWithoutSpatialSignal() {
        val bins = IntArray(RawEttrSampler.BIN_COUNT)
        bins[100] = 1_000
        val sample = greenSample(bins, 1_000, centerWeightedGreen = Float.NaN)
        assertEquals(
            RawProgramMeter.meanBrightness(sample),
            RawProgramMeter.brightness(sample, ProgramMetering.CENTER_WEIGHTED),
            1e-6f
        )
    }

    @Test
    fun centerWeightZonesPreferMiddle() {
        val center = RawEttrSampler.centerWeight(500, 500, 1000, 1000)
        val mid = RawEttrSampler.centerWeight(700, 500, 1000, 1000)
        val edge = RawEttrSampler.centerWeight(990, 990, 1000, 1000)
        assertTrue("center=$center mid=$mid edge=$edge", center > mid && mid > edge)
    }

    @Test
    fun meteringDefaultsToCenterWeighted() {
        assertEquals(ProgramMetering.CENTER_WEIGHTED, ProgramAeProfile().metering)
        // Legacy JSON without the key migrates to the default, not MEDIAN.
        assertEquals(
            ProgramMetering.CENTER_WEIGHTED,
            ProgramAeProfile.fromJson("{\"balance\":0.5}").metering
        )
    }
}
