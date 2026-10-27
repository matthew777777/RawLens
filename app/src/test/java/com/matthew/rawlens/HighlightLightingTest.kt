// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class HighlightLightingTest {
    private fun scene(highlights: Int = 100, channel: Int? = null): EttrRawSample {
        val bins = Array(4) { c -> IntArray(256).apply {
            this[10] = 10_000 - highlights
            this[if (channel == null || c == channel) 245 else 10] += highlights
        } }
        return EttrRawSample(EttrChannelLevels(0.96f, 0.96f, 0.96f, 0.96f), bins,
            IntArray(4), IntArray(4) { 10_000 }, centerWeightedGreen = 0.04f, spotGreen = 0.96f)
    }

    @Test fun smallBrightSubjectOutvotesDarkBackgroundButSingleSpikeDoesNot() {
        val subject = RawProgramMeter.brightness(scene(), ProgramMetering.HIGHLIGHT_WEIGHTED)
        val spike = RawProgramMeter.brightness(scene(1), ProgramMetering.HIGHLIGHT_WEIGHTED)
        assertTrue("subject $subject", subject > 0.8f)
        assertTrue("area must matter: $spike vs $subject", spike < subject / 3)
    }

    @Test fun redAndBlueHighlightsCannotHideInGreen() {
        for (channel in listOf(0, 3)) {
            assertTrue(RawProgramMeter.highlightBrightness(scene(channel = channel)) > 0.8f)
        }
    }

    @Test fun croppedBinsCannotHideBrightEdges() {
        val full = scene()
        val cropped = EttrRawSample(full.levels, Array(4) { IntArray(256).apply { this[10] = 100 } },
            IntArray(4), IntArray(4) { 100 }, frameBins = full.bins)
        assertEquals(RawProgramMeter.highlightBrightness(full), RawProgramMeter.highlightBrightness(cropped), 0f)
        assertArrayEquals(intArrayOf(0, 0, 400, 300),
            RawEttrSampler.meteringScanRegion(400, 300, ProgramMetering.HIGHLIGHT_WEIGHTED))
        assertFalse(ScopeMeterNative.resolveGuard(ScopeMeterNative.ScopeMeterRequest(
            metering = ProgramMetering.HIGHLIGHT_WEIGHTED, scopeLut = null)))
    }

    private fun target(sample: EttrRawSample, metering: ProgramMetering, lighting: ActiveDLighting): Double {
        val loop = ProgramAeLoop()
        loop.onSample(sample, ProgramAeProfile(metering = metering),
            ExposureBalanceLimits(50, 6400, 100_000L, 66_666_666L, 33_333_333L),
            100, 10_000_000L, 0L, lighting = lighting)
        return loop.targetTotal / 1e9
    }

    @Test fun highlightModeProtectsSubjectWhereCenterFailsAndLiftsWhereSpotFails() {
        val sample = scene()
        val highlight = target(sample, ProgramMetering.HIGHLIGHT_WEIGHTED, ActiveDLighting.OFF)
        assertTrue(highlight < target(sample, ProgramMetering.CENTER_WEIGHTED, ActiveDLighting.OFF))
        assertTrue(highlight > target(sample, ProgramMetering.SPOT, ActiveDLighting.OFF))
        assertTrue(highlight * RawProgramMeter.highlightBrightness(sample) <= 0.800001)
    }

    @Test fun protectionIsMonotonicAndDoesNotStackWithHighlightMetering() {
        var previous = Double.POSITIVE_INFINITY
        for (level in ActiveDLighting.values()) {
            val gain = target(scene(), ProgramMetering.HIGHLIGHT_WEIGHTED, level)
            assertTrue(gain <= previous)
            assertEquals(RawProgramMeter.protectedGain(scene(), 100.0, level), gain, 1e-6)
            previous = gain
        }
        assertEquals(2f, ActiveDLighting.EXTRA_HIGH.ettrHeadroom(2f), 0f)
        assertEquals(1.5f, ActiveDLighting.EXTRA_HIGH.ettrHeadroom(0.5f), 0f)
    }

    @Test fun greyRestoredShadowsLiftAndHighlightsStayBelowOffAfterCaptureBias() {
        val base = JpegOutputSettings(highlightShoulder = 0f)
        fun code(x: Float, settings: JpegOutputSettings) = AgxDisplayTransform.scopeCurve(x, settings)
        for (level in ActiveDLighting.values().drop(1)) {
            val look = base.copy(activeDLighting = level)
            val scale = 2.0.pow(-level.protectionEv).toFloat()
            assertEquals("grey $level", code(0.18f, base), code(0.18f * scale, look), 2e-6f)
            assertTrue("shadow $level", code(0.01f * scale, look) > code(0.01f, base))
            assertTrue("highlight $level", code(0.8f * scale, look) < code(0.8f, base))
        }
    }

    @Test fun ettrActualPairFollowsStrengthAndKeepsSaferUserTarget() {
        val histogram = IntArray(EttrQuadStats.BINS).apply { this[EttrQuadStats.binOf(0.5f)] = 1000 }
        val sample = EttrRawSample(EttrChannelLevels(0.5f, 0.5f, 0.5f, 0.5f),
            emptyArray(), IntArray(4), IntArray(4) { 1000 },
            quads = EttrQuadStats(histogram, 1000, 0))
        var previous = Double.POSITIVE_INFINITY
        for (level in ActiveDLighting.values()) {
            val loop = EttrLoop(EttrSettings(enabled = true))
            loop.update(sample, 100, 10_000_000L, 50, 6400, 100_000L, 1_000_000_000L,
                66_000_000L, lighting = level)
            val energy = loop.iso!!.toDouble() * loop.shutterNanos!!
            assertTrue("ETTR $level", energy < previous)
            previous = energy
        }
    }

    @Test fun repeatedResolutionDoesNotCompoundTheLookAndExtremeKnobsStayFinite() {
        for (level in ActiveDLighting.values()) {
            val initial = JpegOutputSettings(activeDLighting = level, agxShadowEv = 14f, agxHighlightEv = 3f)
            val resolved = initial.resolvedForPlatform()
            assertEquals(resolved, resolved.resolvedForPlatform())
            assertTrue(resolved.effectiveAgxShadowEv.isFinite())
            assertTrue(resolved.effectiveAgxShadowEv <= 18f)
        }
    }

    @Test fun adaptiveMeterDoesNotCompensatePackagedCaptureBiasTwice() {
        for (level in ActiveDLighting.values()) {
            val look = JpegOutputSettings(activeDLighting = level)
            val scale = 2.0.pow(-level.protectionEv).toFloat()
            val result = AdaptiveDevelopmentExposure.analyzeSamples(
                FloatArray(1024) { 0.18f * scale }, 1024,
                AdaptiveExposureTuning.fromOutputSettings(look))
            assertEquals("adaptive grey $level", 0.0, result.correctionEv, 1e-4) // Float curve inversion, well below one output code.
        }
    }

    @Test fun cfaGridVisitsEveryBayerPhaseIncludingSmallSpotAndSparseGuard() {
        for ((width, height, target) in listOf(Triple(644, 483, 16000),
            Triple(4080, 3060, 4096), Triple(4000, 3000, 16000))) {
            val step = meterCfaGridStep(width.toLong() * height, target)
            val phases = mutableSetOf<Int>()
            for (y in 0 until height step step) for (x in 0 until width step step)
                phases += ((y and 1) shl 1) or (x and 1)
            assertEquals("$width x $height stride=$step", setOf(0, 1, 2, 3), phases)
        }
    }

    @Test fun defaultsAndPersistencePreserveOldBehaviour() {
        assertEquals(ActiveDLighting.OFF, ActiveDLighting.fromName("future"))
        val base = JpegOutputSettings()
        assertEquals(base.agxShadowEv, base.effectiveAgxShadowEv, 0f)
        assertEquals(ProgramMetering.HIGHLIGHT_WEIGHTED,
            ProgramAeProfile.fromJson(ProgramAeProfile(metering = ProgramMetering.HIGHLIGHT_WEIGHTED).toJson()).metering)
        assertEquals(ProgramMetering.CENTER_WEIGHTED, ProgramAeProfile.fromJson(null).metering)
    }
}
