// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawProgramMeterTest {








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
        centerWeightedGreen: Float = Float.NaN,
        spotGreen: Float = Float.NaN
    ): EttrRawSample {
        val bins = Array(4) { IntArray(RawEttrSampler.BIN_COUNT) }
        bins[1] = greenBins.copyOf()
        val totals = intArrayOf(0, greenTotal, greenTotal, 0)
        val levels = EttrChannelLevels(0f, 0f, 0f, 0f)
        return EttrRawSample(levels, bins, IntArray(4), totals, centerWeightedGreen, spotGreen)
    }

    @Test
    fun averageIsUnweightedMean() {
        // Bulk dark, small bright tail: the average is pulled up by the tail.
        val bins = IntArray(RawEttrSampler.BIN_COUNT)
        bins[20] = 9_000
        bins[250] = 1_000
        val sample = greenSample(bins, 10_000, centerWeightedGreen = 0.2f)
        val average = RawProgramMeter.brightness(sample, ProgramMetering.AVERAGE)
        assertEquals(RawProgramMeter.meanBrightness(sample), average, 1e-6f)
        // The bright tail pulls the average above the dark bulk bin level.
        assertTrue("average should exceed the dark bulk level: $average", average > 20.5f / 256)
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
        // Average of the same bins is the unweighted mean, not the spatial value.
        assertEquals(
            RawProgramMeter.meanBrightness(sample),
            RawProgramMeter.brightness(sample, ProgramMetering.AVERAGE),
            1e-6f
        )
    }

    @Test
    fun spotPrefersSpotValue() {
        val bins = IntArray(RawEttrSampler.BIN_COUNT)
        bins[100] = 1_000
        val sample = greenSample(bins, 1_000, centerWeightedGreen = 0.42f, spotGreen = 0.7f)
        assertEquals(0.7f, RawProgramMeter.brightness(sample, ProgramMetering.SPOT), 1e-6f)
    }

    @Test
    fun spotFallsBackThroughCenterToMean() {
        val bins = IntArray(RawEttrSampler.BIN_COUNT)
        bins[100] = 1_000
        val noSpot = greenSample(bins, 1_000, centerWeightedGreen = 0.42f)
        assertEquals(
            0.42f, RawProgramMeter.brightness(noSpot, ProgramMetering.SPOT), 1e-6f
        )
        val noSpatial = greenSample(bins, 1_000)
        assertEquals(
            RawProgramMeter.meanBrightness(noSpatial),
            RawProgramMeter.brightness(noSpatial, ProgramMetering.SPOT),
            1e-6f
        )
    }

    @Test
    fun spotZoneIsStrictlyCentral() {
        assertTrue(RawEttrSampler.isSpot(500, 500, 1000, 1000))
        assertTrue(RawEttrSampler.isSpot(560, 500, 1000, 1000))
        assertTrue(!RawEttrSampler.isSpot(700, 500, 1000, 1000))
        assertTrue(!RawEttrSampler.isSpot(990, 990, 1000, 1000))
    }

    @Test
    fun legacyMedianJsonMigratesToAverage() {
        assertEquals(
            ProgramMetering.AVERAGE,
            ProgramAeProfile.fromJson("{\"metering\":\"MEDIAN\"}").metering
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
        // JSON without the key falls back to the default.
        assertEquals(
            ProgramMetering.CENTER_WEIGHTED,
            ProgramAeProfile.fromJson("{\"balance\":0.5}").metering
        )
    }

    @Test
    fun defaultsAreIsoPriorityAndNeutralEv() {
        assertEquals(0f, ProgramAeProfile().balance, 0f)
        assertEquals(0f, ProgramAeProfile().evBias, 0f)
    }



}
