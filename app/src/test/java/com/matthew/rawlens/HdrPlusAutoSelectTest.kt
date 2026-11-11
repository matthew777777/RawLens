// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class HdrPlusAutoSelectTest {
    private val sigma = 0.01
    private val noiseOnly = 2 * sigma / sqrt(Math.PI)
    private val spatialReference = 0.05
    private fun model(a: Double) = HdrPlusNoiseModel(a, a / 100, 5, doubleArrayOf(0.02, 0.05, 0.1, 0.2, 0.4))

    /** Four frames; every pair differs by [motion] x the noise-only difference. */
    private fun summary(a: Double = 5e-4, motion: Double = 1.0, hot: Double = 0.0, gyro: Double = 0.0) =
        HdrPlusMotionMeter.summarize(
            List(3) { motion * noiseOnly }, List(3) { 1L }, List(3) { 2L }, List(3) { gyro },
            List(3) { hot }, List(4) { 0.1 }, List(4) { 0.01 },
            noiseModel = model(a), codeRange = 959.0, frameNoiseSigma = List(4) { sigma },
            spatialNoiseReference = spatialReference)

    /** Dense cell differences as multiples of noise-only: the same row for every pair. */
    private fun HdrPlusMotionMeter.MotionSummary.withCells(vararg ratio: Double) =
        withPairCells(*Array(3) { ratio })

    private fun HdrPlusMotionMeter.MotionSummary.withPairCells(vararg pairs: DoubleArray) = copy(
        pairMadDense = pairs.map { row -> DoubleArray(row.size) { row[it] * noiseOnly } },
        frameNoiseSigmaCells = List(4) { DoubleArray(pairs[0].size) { sigma } },
        mapCellsX = pairs[0].size, mapCellsY = 1)

    private fun select(m: HdrPlusMotionMeter.MotionSummary = summary(), iso: Int = 100,
                       strength: Float? = null, quality: Boolean? = null) =
        HdrPlusAutoSelect.select(m, 4, iso, strength, quality, 2)

    private val frequency = HdrPlusMergeModel.frequencyGains(model(5e-4), 959.0)
    private val spatial = HdrPlusMergeModel.spatialGains(spatialReference, sigma)!!

    @Test fun measuredNoiseControlsStrengthNotIsoOrBrightness() {
        val clean = select(summary(1e-4))
        val noisy = select(summary(1.5e-3))
        assertTrue(noisy.strength > clean.strength + 4f)
        assertEquals(noisy.strength, select(summary(1.5e-3), iso = 6400).strength, 0f)
        assertEquals(noisy.strength,
            select(summary(1.5e-3).copy(frameMeanLevel = List(4) { 0.01 })).strength, 0f)
        assertEquals((0..3).toList(), clean.keepIndices)
        assertEquals(2, clean.keepIndices[clean.refIndex])
    }

    @Test fun stillBurstMergesAtFrameCountGainAndMotionLowersItToMatchedNoise() {
        val still = select(summary(motion = 1.0))
        assertTrue(still.highQuality)
        assertEquals(HdrPlusMergeModel.strengthFor(frequency, 4.0), still.strength, 1e-4f)
        val moving = select(summary(motion = 50.0), quality = true)
        assertEquals(HdrPlusMergeModel.strengthFor(frequency, 1.0), moving.strength, 0.01f)
        val some = select(summary(motion = 1.5))
        assertTrue(some.strength < still.strength && some.strength > moving.strength)
        // Differences below the predicted noise are not negative motion.
        assertEquals(still.strength, select(summary(motion = 0.8)).strength, 0f)
        assertTrue(still.frameStrengths!!.all { it == still.strength })
    }

    @Test fun oneMovingPairLowersOnlyItsFramesAndTheReportedValue() {
        val base = summary()
        val d = select(base.copy(pairDeltas = listOf(3 * noiseOnly, noiseOnly, noiseOnly)))
        val still = select(base).strength
        assertTrue(d.frameStrengths!![0] < still && d.frameStrengths!![1] < still)
        assertEquals(still, d.frameStrengths!![2], 0f)
        assertEquals(still, d.frameStrengths!![3], 0f)
        assertEquals(d.frameStrengths!![0], d.strength, 0f)
    }

    @Test fun pathIsFrequencyWhenCompanionsHoldAFrameOfStillContent() {
        assertTrue(select(summary()).highQuality)
        // Three companions, each a third still: exactly one frame's worth is not yet "less than one".
        assertTrue(select(summary().withCells(1.0, 30.0)).highQuality)
        assertFalse(select(summary().withCells(1.0, 30.0, 30.0, 30.0)).highQuality)
        assertFalse(select(summary(motion = 50.0)).highQuality)
        // Hot-block and gyro readings are diagnostics, not decisions.
        assertTrue(select(summary(hot = 0.5, gyro = 2.0)).highQuality)
        assertTrue(select(summary(motion = 50.0), quality = true).highQuality)
    }

    @Test fun spatialPathUsesItsOwnNoiseRule() {
        val still = select(summary(), quality = false)
        assertFalse(still.highQuality)
        assertEquals(HdrPlusMergeModel.strengthFor(spatial, 4.0), still.strength, 1e-4f)
        assertNotEquals(select(summary()).strength, still.strength, 0.5f)
        val moving = select(summary(motion = 50.0))
        assertFalse(moving.highQuality)
        assertEquals(HdrPlusMergeModel.strengthFor(spatial, 1.0), moving.strength, 0.01f)
        // Without the spatial reference the path cannot be noise-matched.
        val blind = select(summary().copy(spatialNoiseReference = Double.NaN).withCells(1.0), quality = false)
        assertEquals(HdrPlusSettings.DEFAULT_STRENGTH, blind.strength, 0f)
        assertNull(blind.strengthMaps)
    }

    @Test fun mapsFollowLocalMotionAndSpreadCautionToNeighbors() {
        val still = select(summary()).strength
        val floor = select(summary(motion = 1e6), quality = true).strength
        for (bad in listOf(30.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val d = select(summary().withCells(1.0, 1.0, 1.0, 1.0, bad, 1.0))
            assertTrue(d.highQuality)
            d.strengthMaps!!.forEach { map ->
                assertEquals(still, map[0], 0.02f)
                assertEquals(still, map[2], 0.02f)
                assertEquals(floor, map[4], 0.05f)
                assertEquals(map[4], map[3], 0f)
                assertEquals(map[4], map[5], 0f)
            }
            // The reported strength is what the maps apply.
            assertEquals((3 * still + 3 * d.strengthMaps!![0][4]) / 6, d.strength, 0.02f)
            assertTrue(d.logLine(4).contains("maps(mean="))
        }
    }

    @Test fun stillCellsKeepFullStrengthInAShakenFrame() {
        // Whole-frame difference is mostly texture shift; a flat cell still differs by noise only.
        val d = select(summary(motion = 5.0).withCells(1.0, 1.0, 1.0, 8.0, 8.0, 8.0))
        val map = d.strengthMaps!![0]
        assertTrue(map[0] > d.frameStrengths!![0])
        assertEquals(select(summary()).strength, map[0], 0.02f)
        assertTrue(map[5] < map[0])
    }

    @Test fun framesWithNothingUsableAreDroppedAndDoNotTaintTheirNeighbor() {
        val clean = doubleArrayOf(1.0, 1.0, 1.0, 1.0)
        val broken = doubleArrayOf(1e3, 1e3, 1e3, 1e3)
        val d = select(summary().copy(pairDeltas = listOf(noiseOnly, noiseOnly, 1e3 * noiseOnly),
            frameSharpness = listOf(0.01, 0.01, 0.01, 0.5)).withPairCells(clean, clean, broken))
        assertEquals(listOf(0, 1, 2), d.keepIndices)
        assertEquals(listOf(3), d.droppedIndices)
        // The broken frame is the "sharpest" and still never the reference.
        assertEquals(2, d.keepIndices[d.refIndex])
        assertTrue(d.logLine(4).contains("dropped=[F4]"))
        val still = HdrPlusMergeModel.strengthFor(frequency, 3.0)
        assertEquals(still, d.frameStrengths!![2], 1e-4f)
        assertEquals(still, d.strengthMaps!![2][0], 0.02f)
        // A frame that only half matches is kept: the maps handle it.
        val half = select(summary().withPairCells(clean, clean, doubleArrayOf(1.0, 1.0, 1e3, 1e3)))
        assertEquals((0..3).toList(), half.keepIndices)
        // Whole-frame differences alone never drop (handheld texture shift looks the same).
        val global = select(summary().copy(pairDeltas = listOf(noiseOnly, noiseOnly, 1e3 * noiseOnly)))
        assertEquals((0..3).toList(), global.keepIndices)
    }

    @Test fun referenceIsTheSharpestFrameThatStillMatches() {
        val sharp = summary().copy(frameSharpness = listOf(0.01, 0.03, 0.01, 0.01))
        assertEquals(1, select(sharp).let { it.keepIndices[it.refIndex] })
        val clean = doubleArrayOf(1.0, 1.0)
        val moved = doubleArrayOf(2.0, 2.0)
        // Frame 0 is sharpest but differs from its only neighbor.
        val d = select(summary().copy(frameSharpness = listOf(0.03, 0.012, 0.01, 0.01))
            .withPairCells(moved, clean, clean))
        assertEquals(2, d.keepIndices[d.refIndex])
    }

    @Test fun neverStarvesMergeAndRejectsTooFewInputs() {
        val broken = doubleArrayOf(1e3, 1e3)
        val d = select(summary().withPairCells(broken, broken, broken))
        assertEquals(2, d.keepIndices.size)
        assertTrue(d.refIndex in d.keepIndices.indices)
        assertThrows(IllegalArgumentException::class.java) {
            HdrPlusAutoSelect.select(summary(), 1, 100, null, null, 0)
        }
    }

    @Test fun malformedOrMissingDenseGridsAreNotSentToNative() {
        val cells = summary().withCells(1.0, 1.0)
        assertNull(select(cells.copy(mapCellsX = 2, mapCellsY = 2)).strengthMaps)
        assertNull(select(cells.copy(frameNoiseSigmaCells = emptyList())).strengthMaps)
        assertNull(select(cells.copy(pairMadDense = emptyList())).strengthMaps)
        assertNotNull(select(cells.copy(pairMadDense = emptyList())).frameStrengths)
        assertNotNull(select(cells).strengthMaps)
    }

    @Test fun unknownNoiseOrMotionUsesTheConservativeDefault() {
        for (m in listOf(summary().copy(noiseModel = null), summary().copy(frameNoiseSigma = emptyList()),
            summary().copy(codeRange = Double.NaN), summary().copy(frameNoiseSigma = List(4) { Double.NaN }),
            summary().copy(frameSharpness = emptyList()))) {
            val d = select(m.withCells(1.0))
            assertEquals(HdrPlusSettings.DEFAULT_STRENGTH, d.strength, 0f)
            assertFalse(d.highQuality)
            assertNull(d.frameStrengths)
            assertNull(d.strengthMaps)
            assertEquals((0..3).toList(), d.keepIndices)
            assertTrue(select(m, quality = true).highQuality)
        }
    }

    @Test fun unmeasuredDifferenceCountsAsMotion() {
        val d = select(summary().copy(pairDeltas = List(3) { Double.NaN }), quality = true)
        assertEquals(select(summary(motion = 1e6), quality = true).strength, d.strength, 0.01f)
    }

    @Test fun manualRangeAndQualityRemainExact() {
        for (strength in listOf(1f, 3f, 6f, 16.1f, 22f)) for (quality in listOf(false, true)) {
            val d = select(summary(1.5e-3, 50.0, 0.5, 0.5).withCells(1.0), strength = strength, quality = quality)
            assertEquals(strength, d.strength, 0f)
            assertEquals(quality, d.highQuality)
            assertNull(d.strengthMaps)
            assertNull(d.frameStrengths)
            assertFalse(d.strengthAuto)
            assertFalse(d.pathAuto)
        }
    }
}
