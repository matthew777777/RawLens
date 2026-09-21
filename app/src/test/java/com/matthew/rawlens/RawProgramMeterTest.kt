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
    fun programStepCapBoundsGlideAuthority() {
        // The retune fixes the cap at a full stop; the ramp + EMA absorb it.
        assertEquals(1.0, RawProgramMeter.PROGRAM_MAX_STEP_EV, 0.0)
    }

    @Test
    fun programStepCapNudgesInsteadOfJumping() {
        // Same dark scene: default clamp allows the full +2 EV jump…
        assertEquals(2.0, RawProgramMeter.correctionEv(0.001f, 0.5f), 1e-9)
        // …while the PROGRAM glide cap limits one measurement to half a stop.
        assertEquals(
            RawProgramMeter.PROGRAM_MAX_STEP_EV,
            RawProgramMeter.correctionEv(
                0.001f, 0.5f, RawProgramMeter.PROGRAM_MAX_STEP_EV
            ),
            1e-9
        )
        assertEquals(
            -RawProgramMeter.PROGRAM_MAX_STEP_EV,
            RawProgramMeter.correctionEv(
                1f, -1f, RawProgramMeter.PROGRAM_MAX_STEP_EV
            ),
            1e-9
        )
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
        assertEquals(0.18, RawProgramMeter.targetLevel(ProgramAeProfile().evBias), 1e-6)
    }

    @Test
    fun guardCapsBrighteningAgainstHotHighlights() {
        // Daylight mids want +1 EV but the 99.9th percentile sits at 0.95:
        // brightening must not push it past the 0.9 guard (slight darken instead).
        val guarded = RawProgramMeter.guardShiftEv(1.0, hottest = 0.95f)
        assertTrue("expected capped shift, was $guarded", guarded < 0.0)
        assertEquals(Math.log((0.9 / 0.95)) / Math.log(2.0), guarded, 1e-6)
    }

    @Test
    fun guardLeavesNormalScenesAlone() {
        assertEquals(1.0, RawProgramMeter.guardShiftEv(1.0, hottest = 0.3f), 1e-9)
        assertEquals(0.0, RawProgramMeter.guardShiftEv(1.0, hottest = 0.9f), 1e-9)
    }

    @Test
    fun guardPassesDarkeningThrough() {
        assertEquals(-1.0, RawProgramMeter.guardShiftEv(-1.0, hottest = 0.99f), 1e-9)
        assertEquals(0.0, RawProgramMeter.guardShiftEv(0.0, hottest = 0.99f), 1e-9)
    }
}
