// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class HdrPlusAutoSelectTest {
    private fun summary(noise: Double = 0.04, hot: Double = 0.0, gyro: Double = 0.0) =
        HdrPlusMotionMeter.summarize(
            List(3) { 0.01 }, List(3) { 1L }, List(3) { 2L }, List(3) { gyro },
            List(3) { hot }, List(4) { 0.1 }, List(4) { 0.01 },
            frameRelativeNoise = List(4) { noise })

    private fun select(m: HdrPlusMotionMeter.MotionSummary = summary(), iso: Int = 100,
                       strength: Float? = null, quality: Boolean? = null) =
        HdrPlusAutoSelect.select(m, 4, iso, strength, quality, 2)

    @Test fun measuredNoiseControlsStrengthNotIsoOrBrightness() {
        val clean = select(summary(0.01))
        val noisy = select(summary(0.1))
        assertTrue(noisy.strength > clean.strength)
        assertTrue(clean.highQuality)
        assertEquals(noisy.strength, select(summary(0.1), iso = 6400).strength, 0f)
        assertEquals(noisy.strength,
            select(summary(0.1).copy(frameMeanLevel = List(4) { 0.01 })).strength, 0f)
        assertEquals((0..3).toList(), clean.keepIndices)
        assertEquals(2, clean.keepIndices[clean.refIndex])
    }

    @Test fun motionAndGyroConstrainEveryFrameAndMap() {
        for ((hot, gyro) in listOf(0.1 to 0.0, 0.0 to 0.5)) {
            val decision = select(summary(0.15, hot, gyro).copy(
                pairMismatchRatios = List(3) { doubleArrayOf(0.0, 0.0) },
                mapCellsX = 2, mapCellsY = 1))
            assertFalse(decision.highQuality)
            assertEquals(HdrPlusSettings.MIN_STRENGTH, decision.strength, 0f)
            assertTrue(decision.frameStrengths!!.all { it == decision.strength })
            assertTrue(decision.strengthMaps!!.all { map -> map.all { it <= decision.strength } })
        }
    }

    @Test fun mapsCannotUndoLocalRejectionOrExceedFrameBudget() {
        for (bad in listOf(5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val decision = select(summary(0.1).copy(
                pairMismatchRatios = List(3) { doubleArrayOf(0.0, 0.0, bad, 0.0) },
                mapCellsX = 4, mapCellsY = 1))
            decision.strengthMaps!!.forEachIndexed { slot, map ->
                assertTrue(map.all { it <= decision.frameStrengths!![slot] })
                assertEquals(HdrPlusSettings.MIN_STRENGTH, map[2], 0f)
                assertEquals(HdrPlusSettings.MIN_STRENGTH, map[1], 0f)
                assertTrue(map[0] > map[2])
            }
        }
    }

    @Test fun denseMismatchAlsoConstrainsCleanRatioMaps() {
        val d = select(summary().copy(
            pairMismatchRatios = List(3) { doubleArrayOf(0.0) },
            pairMadDense = List(3) { doubleArrayOf(0.02) }, mapCellsX = 1, mapCellsY = 1))
        assertTrue(d.strengthMaps!!.all { it[0] == HdrPlusSettings.MIN_STRENGTH })
    }

    @Test fun malformedMapsAreNotSentToNative() {
        val d = select(summary().copy(pairMismatchRatios = List(3) { doubleArrayOf(0.0) },
            mapCellsX = 2, mapCellsY = 2))
        assertNull(d.strengthMaps)
        assertNotNull(d.frameStrengths)
    }

    @Test fun missingNoiseUsesConservativeFallbackAndMissingMotionCannotIncreaseIt() {
        val m = summary().copy(frameRelativeNoise = emptyList())
        assertEquals(HdrPlusSettings.DEFAULT_STRENGTH, select(m).strength, 0f)
        assertEquals(HdrPlusSettings.MIN_STRENGTH,
            select(m.copy(pairHotFraction = List(3) { Double.NaN })).strength, 0f)
        val empty = select(m.copy(frameMeanLevel = emptyList()))
        assertEquals(HdrPlusSettings.DEFAULT_STRENGTH, empty.strength, 0f)
        assertFalse(empty.highQuality)
        assertNull(empty.strengthMaps)
        assertEquals((0..3).toList(), empty.keepIndices)
    }

    @Test fun manualRangeAndQualityRemainExact() {
        for (strength in listOf(1f, 3f, 6f, 16.1f, 22f)) for (quality in listOf(false, true)) {
            val d = select(summary(0.2, 0.5, 0.5), strength = strength, quality = quality)
            assertEquals(strength, d.strength, 0f)
            assertEquals(quality, d.highQuality)
            assertNull(d.strengthMaps)
            assertNull(d.frameStrengths)
            assertFalse(d.strengthAuto)
            assertFalse(d.pathAuto)
        }
    }

    @Test fun dropsBrightnessOutlierAndReturnsReferenceInKeptCoordinates() {
        val d = select(summary().copy(frameMeanLevel = listOf(0.1, 0.1, 0.1, 0.9),
            frameSharpness = listOf(0.01, 0.02, 0.01, 0.01)))
        assertEquals(listOf(0, 1, 2), d.keepIndices)
        assertEquals(listOf(3), d.droppedIndices)
        assertEquals(1, d.keepIndices[d.refIndex])
        assertTrue(d.logLine(4).contains("dropped=[F4]"))
    }

    @Test fun neverStarvesMergeAndRejectsTooFewInputs() {
        val m = summary().copy(frameMeanLevel = listOf(0.01, 0.1, 0.9, 20.0))
        assertTrue(select(m).keepIndices.size >= 2)
        assertThrows(IllegalArgumentException::class.java) {
            HdrPlusAutoSelect.select(summary(), 1, 100, null, null, 0)
        }
    }
}
