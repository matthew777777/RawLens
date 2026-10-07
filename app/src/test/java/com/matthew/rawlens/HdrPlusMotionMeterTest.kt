// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrPlusMotionMeterTest {
    private fun u16(vararg values: Int): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.nativeOrder()).also { buf ->
            values.forEach { buf.putShort(it.toShort()) }
            buf.rewind()
        }

    @Test fun identicalFramesScoreZero() {
        val frame = u16(100, 200, 300, 400)
        assertEquals(
            0.0,
            HdrPlusMotionMeter.meanAbsDiffNormalized(frame, frame, 4, 1, 1000.0),
            0.0
        )
    }

    @Test fun constantOffsetNormalizesByRange() {
        val prev = u16(100, 100, 100, 100)
        val curr = u16(200, 200, 200, 200)
        assertEquals(
            0.1,
            HdrPlusMotionMeter.meanAbsDiffNormalized(prev, curr, 4, 1, 1000.0),
            1e-12
        )
    }

    @Test fun strideSamplesEveryNthPixelOnly() {
        // Diffs live on odd indices; stride 2 samples evens -> blind to them.
        val prev = u16(0, 0, 0, 0, 0, 0, 0, 0)
        val curr = u16(0, 500, 0, 500, 0, 500, 0, 500)
        assertEquals(
            0.0,
            HdrPlusMotionMeter.meanAbsDiffNormalized(prev, curr, 8, 2, 1000.0),
            0.0
        )
        assertEquals(
            0.25,
            HdrPlusMotionMeter.meanAbsDiffNormalized(prev, curr, 8, 1, 1000.0),
            1e-12
        )
    }

    @Test fun samplesAreUnsigned() {
        val prev = u16(0xFFFF)
        val curr = u16(0x0000)
        assertEquals(
            1.0,
            HdrPlusMotionMeter.meanAbsDiffNormalized(prev, curr, 1, 1, 65535.0),
            1e-12
        )
    }

    @Test fun unmeasurablePairsYieldNaN() {
        val frame = u16(100, 200)
        listOf(0.0, -5.0, Double.NaN).forEach { range ->
            assertTrue(
                HdrPlusMotionMeter.meanAbsDiffNormalized(frame, frame, 2, 1, range).isNaN()
            )
        }
        assertTrue(HdrPlusMotionMeter.meanAbsDiffNormalized(frame, frame, 0, 1, 1000.0).isNaN())
        assertTrue(HdrPlusMotionMeter.meanAbsDiffNormalized(frame, frame, 2, 0, 1000.0).isNaN())
        assertTrue(HdrPlusMotionMeter.meanAbsDiffNormalized(frame, frame, 99, 1, 1000.0).isNaN())
    }

    @Test fun summarizeAggregatesPairs() {
        val summary = HdrPlusMotionMeter.summarize(
            pairDeltas = listOf(0.002, 0.006),
            prevTimestampsNs = listOf(1_000_000_000L, 1_033_000_000L),
            currTimestampsNs = listOf(1_033_000_000L, 1_066_000_000L),
            pairGyroRadS = listOf(0.01, 0.03)
        )
        assertEquals(0.004, summary.meanDelta, 1e-12)
        assertEquals(0.006, summary.maxDelta, 1e-12)
        assertEquals(33.0, summary.meanDtMillis, 1e-9)
        assertEquals(0.02, summary.meanGyro, 1e-12)
        assertEquals(0.03, summary.maxGyro, 1e-12)
    }

    @Test fun summarizeSkipsUnreportedPairs() {
        val summary = HdrPlusMotionMeter.summarize(
            pairDeltas = listOf(Double.NaN, 0.006),
            prevTimestampsNs = listOf(0L, 1_033_000_000L),
            currTimestampsNs = listOf(0L, 1_066_000_000L),
            pairGyroRadS = listOf(Double.NaN, Double.NaN)
        )
        assertEquals(0.006, summary.meanDelta, 1e-12)
        assertEquals(33.0, summary.meanDtMillis, 1e-9)
        assertTrue(summary.meanGyro.isNaN())
        assertTrue(summary.maxGyro.isNaN())
        val empty = HdrPlusMotionMeter.summarize(
            emptyList(), emptyList(), emptyList(), emptyList()
        )
        assertTrue(empty.meanDelta.isNaN())
        assertTrue(empty.meanDtMillis.isNaN())
    }

    @Test fun summarizeRejectsMismatchedInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            HdrPlusMotionMeter.summarize(listOf(0.1), listOf(1L), listOf(2L), emptyList())
        }
    }

    @Test fun logLineFormatsGcamStyle() {
        val summary = HdrPlusMotionMeter.summarize(
            pairDeltas = listOf(0.0042),
            prevTimestampsNs = listOf(1_000_000_000L),
            currTimestampsNs = listOf(1_033_100_000L),
            pairGyroRadS = listOf(0.012)
        )
        assertEquals(
            "HDR+ motion N=2 dtMean=33.1ms deltaMean=0.0042 deltaMax=0.0042 " +
                "gyroMean=0.012 gyroMax=0.012 rad/s",
            summary.logLine(2)
        )
        val unknown = HdrPlusMotionMeter.summarize(
            listOf(Double.NaN), listOf(0L), listOf(0L), listOf(Double.NaN)
        )
        assertEquals(
            "HDR+ motion N=2 dtMean=?ms deltaMean=? deltaMax=? gyroMean=? gyroMax=? rad/s",
            unknown.logLine(2)
        )
    }

    @Test fun frameStatsMeanAndSharpness() {
        val flat = u16(100, 100, 100, 100)
        val flatStats = HdrPlusMotionMeter.frameStats(flat, 4, 1, 1, 1000.0)
        assertEquals(0.1, flatStats.meanLevel, 1e-12)
        assertEquals(0.0, flatStats.sharpness, 0.0)
        val striped = u16(0, 100, 0, 100)
        val stripedStats = HdrPlusMotionMeter.frameStats(striped, 4, 1, 1, 1000.0)
        assertEquals(0.05, stripedStats.meanLevel, 1e-12)
        assertEquals(0.1, stripedStats.sharpness, 1e-12)
    }

    @Test fun frameStatsRejectsBadInputs() {
        val frame = u16(100, 100)
        val badRange = HdrPlusMotionMeter.frameStats(frame, 2, 1, 1, 0.0)
        assertTrue(badRange.meanLevel.isNaN())
        assertTrue(badRange.sharpness.isNaN())
        val short = HdrPlusMotionMeter.frameStats(frame, 4, 4, 1, 1000.0)
        assertTrue(short.meanLevel.isNaN())
        assertTrue(short.sharpness.isNaN())
    }

    @Test fun hotFractionCountsMismatchedBlocks() {
        // 16x16, stride 1: 2x2 full blocks. One block steps +500 DN in
        // curr while staying flat inside (sharp 0) -> exactly 1/4 hot.
        val prev = u16(*IntArray(256) { 100 })
        val curr = u16(*IntArray(256) { i ->
            val x = i % 16
            val y = i / 16
            if (x < 8 && y < 8) 600 else 100
        })
        assertEquals(
            0.25,
            HdrPlusMotionMeter.blockHotFraction(prev, curr, 16, 16, 1, 1000.0, 2.0),
            1e-12
        )
        assertEquals(
            0.0,
            HdrPlusMotionMeter.blockHotFraction(prev, prev, 16, 16, 1, 1000.0, 2.0),
            0.0
        )
    }

    @Test fun hotFractionIgnoresTextureMatchedGain() {
        // Identical checkerboards with a small global lift: MAD stays far
        // below texture, so no block trips (gain is the brightness rule).
        val prev = u16(*IntArray(256) { i -> if ((i % 16 + i / 16) % 2 == 0) 0 else 100 })
        val curr = u16(*IntArray(256) { i -> (if ((i % 16 + i / 16) % 2 == 0) 0 else 100) + 10 })
        assertEquals(
            0.0,
            HdrPlusMotionMeter.blockHotFraction(prev, curr, 16, 16, 1, 1000.0, 2.0),
            0.0
        )
    }

    @Test fun hotFractionDegenerateInputs() {
        val tiny = u16(0, 0, 0, 0)
        assertEquals(
            0.0,
            HdrPlusMotionMeter.blockHotFraction(tiny, tiny, 2, 2, 1, 1000.0, 2.0),
            0.0
        )
        assertTrue(
            HdrPlusMotionMeter.blockHotFraction(tiny, tiny, 2, 2, 1, 0.0, 2.0).isNaN()
        )
    }

    @Test fun summarizeCarriesFrameFacts() {
        val summary = HdrPlusMotionMeter.summarize(
            pairDeltas = listOf(0.002, 0.006),
            prevTimestampsNs = listOf(1_000_000_000L, 1_033_000_000L),
            currTimestampsNs = listOf(1_033_000_000L, 1_066_000_000L),
            pairGyroRadS = listOf(0.01, 0.03),
            pairHotFraction = listOf(0.0, 0.012),
            frameMeanLevel = listOf(0.05, 0.05, 0.05),
            frameSharpness = listOf(0.004, 0.005, 0.004)
        )
        assertEquals(listOf(0.0, 0.012), summary.pairHotFraction)
        assertEquals(0.012, summary.maxHotFraction, 0.0)
        assertEquals(listOf(0.05, 0.05, 0.05), summary.frameMeanLevel)
        assertEquals(listOf(0.004, 0.005, 0.004), summary.frameSharpness)
    }

    @Test fun summarizeRejectsMismatchedFrameFacts() {
        assertThrows(IllegalArgumentException::class.java) {
            HdrPlusMotionMeter.summarize(
                pairDeltas = listOf(0.1),
                prevTimestampsNs = listOf(1L),
                currTimestampsNs = listOf(2L),
                pairGyroRadS = listOf(0.01),
                pairHotFraction = listOf(0.0, 0.1)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HdrPlusMotionMeter.summarize(
                pairDeltas = listOf(0.1),
                prevTimestampsNs = listOf(1L),
                currTimestampsNs = listOf(2L),
                pairGyroRadS = listOf(0.01),
                frameMeanLevel = listOf(0.05)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HdrPlusMotionMeter.summarize(
                pairDeltas = listOf(0.1),
                prevTimestampsNs = listOf(1L),
                currTimestampsNs = listOf(2L),
                pairGyroRadS = listOf(0.01),
                pairMismatchRatios = listOf(doubleArrayOf(0.5), doubleArrayOf(0.5))
            )
        }
    }

    @Test fun mapBlockGeometryMatchesTuning() {
        assertEquals(
            HdrPlusAutoTuning.MAP_BLOCK_PX,
            HdrPlusMotionMeter.MAP_BLOCK_SAMPLES * HdrPlusMotionMeter.SAMPLE_STRIDE
        )
    }

    @Test fun identicalFramesScoreZeroRatios() {
        val frame = u16(100, 200, 300, 400)
        val ratios = HdrPlusMotionMeter.blockMismatchRatios(frame, frame, 2, 2, 1, 2, 1000.0)
        assertTrue(ratios!!.contentEquals(doubleArrayOf(0.0)))
    }

    @Test fun offsetBelowTextureScoresClean() {
        // 500 DN shift against 1000 DN texture: ratio 0.5 (clean).
        val prev = u16(0, 1000)
        val curr = u16(500, 1500)
        val ratios = HdrPlusMotionMeter.blockMismatchRatios(prev, curr, 2, 1, 1, 2, 1000.0)
        assertEquals(1, ratios!!.size)
        assertEquals(0.5, ratios[0], 1e-9)
    }

    @Test fun offsetOnFlatScoresStrict() {
        // No texture to explain a 100 DN shift: huge ratio (strict).
        val prev = u16(0, 0, 0, 0)
        val curr = u16(100, 100, 100, 100)
        val ratios = HdrPlusMotionMeter.blockMismatchRatios(prev, curr, 2, 2, 1, 2, 1000.0)
        assertEquals(1, ratios!!.size)
        assertTrue(ratios[0] > HdrPlusAutoTuning.MAP_RATIO_MOTION)
    }

    @Test fun ratioGridCoversCeilWithPartials() {
        // 5x4 at stride 2 -> 3x2 grid, 2-sample blocks -> 2x1 ceil cover.
        val prev = u16(*IntArray(20) { 100 })
        val curr = u16(*IntArray(20) { 100 })
        val ratios = HdrPlusMotionMeter.blockMismatchRatios(prev, curr, 5, 4, 2, 2, 1000.0)
        assertEquals(2, ratios!!.size)
        assertTrue(ratios.all { it == 0.0 })
    }

    @Test fun badRangeOrGeometryYieldsNullRatios() {
        val frame = u16(100, 200)
        assertTrue(
            HdrPlusMotionMeter.blockMismatchRatios(frame, frame, 2, 1, 1, 2, 0.0) == null
        )
        assertTrue(
            HdrPlusMotionMeter.blockMismatchRatios(frame, frame, 0, 1, 1, 2, 1000.0) == null
        )
        assertTrue(
            HdrPlusMotionMeter.blockMismatchRatios(frame, frame, 99, 1, 1, 2, 1000.0) == null
        )
    }
}
