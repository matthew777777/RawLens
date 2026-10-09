// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.HdrPlusMotionMeter.MotionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrPlusAutoSelectTest {
    private fun metering(
        deltas: List<Double>,
        hots: List<Double>,
        means: List<Double>,
        sharps: List<Double>,
        gyro: Double = 0.0,
        ratios: List<DoubleArray> = emptyList(),
        mapW: Int = 0,
        mapH: Int = 0,
        madDense: List<DoubleArray> = emptyList()
    ): MotionSummary {
        val pairs = deltas.size
        val base = 1_000_000_000L
        return HdrPlusMotionMeter.summarize(
            pairDeltas = deltas,
            prevTimestampsNs = List(pairs) { base + it * 33_000_000L },
            currTimestampsNs = List(pairs) { base + (it + 1) * 33_000_000L },
            pairGyroRadS = List(pairs) { gyro },
            pairHotFraction = hots,
            frameMeanLevel = means,
            frameSharpness = sharps,
            pairMismatchRatios = ratios,
            mapCellsX = mapW,
            mapCellsY = mapH,
            pairMadDense = madDense,
            pairTexDense = madDense.map { DoubleArray(it.size) }
        )
    }

    @Test fun stillBurstKeepsAllAtFullStrengthHQ() {
        // burst-0006 anchor: uniform pairs, zero hot blocks, bright.
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(6) { 0.0105 },
                hots = List(6) { 0.0 },
                means = List(7) { 0.096 },
                sharps = listOf(0.0113, 0.0116, 0.0114, 0.0119, 0.0115, 0.0114, 0.0114),
                gyro = 0.005
            ),
            frameCount = 7,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 3
        )
        assertEquals(13f, decision.strength, 0f)
        assertTrue(decision.highQuality)
        assertEquals((0..6).toList(), decision.keepIndices)
        assertEquals(emptyList<Int>(), decision.droppedIndices)
        assertEquals(3, decision.keepIndices[decision.refIndex])
        assertTrue(decision.strengthAuto)
        assertTrue(decision.pathAuto)
    }

    @Test fun streetBurstDropsOutlierAndGoesConservativeFast() {
        // 33TJ anchor: street motion on every pair plus a 4x-bright
        // outlier last frame (48x median MAD).
        val deltas = MutableList(9) { 0.0027 }.also { it[8] = 0.13 }
        val hots = MutableList(9) { 0.0117 }.also { it[8] = 0.9 }
        val means = MutableList(10) { 0.039 }.also { it[9] = 0.169 }
        val sharps = MutableList(10) { 0.004 }.also { it[4] = 0.00403 }
        val decision = HdrPlusAutoSelect.select(
            metering = metering(deltas, hots, means, sharps),
            frameCount = 10,
            sensitivityIso = 40,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 5
        )
        assertEquals(listOf(9), decision.droppedIndices)
        assertEquals((0..8).toList(), decision.keepIndices)
        assertEquals(4, decision.keepIndices[decision.refIndex])
        assertEquals(4.6f, decision.strength, 1e-6f)
        assertFalse(decision.highQuality)
    }

    @Test fun manualOverridesWin() {
        val still = metering(
            deltas = List(3) { 0.01 },
            hots = List(3) { 0.0 },
            means = List(4) { 0.09 },
            sharps = List(4) { 0.011 }
        )
        val manual = HdrPlusAutoSelect.select(
            metering = still, frameCount = 4, sensitivityIso = 50,
            manualStrength = 8f, manualQuality = false, preferredRef = 2
        )
        assertEquals(8f, manual.strength, 0f)
        assertFalse(manual.highQuality)
        assertFalse(manual.strengthAuto)
        assertFalse(manual.pathAuto)
        assertEquals(null, manual.frameStrengths)
        // Drops and base pick stay automatic under manual strength/path.
        assertEquals((0..3).toList(), manual.keepIndices)
    }

    @Test fun minKeepRescuesTopTwo() {
        // Two-frame burst where the brightness band would orphan frame 0:
        // means [0.05, 0.5] center on 0.275, frame 0 falls below half.
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = listOf(0.4),
                hots = listOf(0.0),
                means = listOf(0.05, 0.5),
                sharps = listOf(0.004, 0.004)
            ),
            frameCount = 2,
            sensitivityIso = 100,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 0
        )
        assertEquals(listOf(0, 1), decision.keepIndices)
        assertEquals(emptyList<Int>(), decision.droppedIndices)
        assertEquals(0, decision.keepIndices[decision.refIndex])
    }

    @Test fun basePickPrefersCenterOnTies() {
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(4) { 0.01 },
                hots = List(4) { 0.0 },
                means = List(5) { 0.09 },
                sharps = List(5) { 0.011 }
            ),
            frameCount = 5,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 2
        )
        assertEquals(2, decision.keepIndices[decision.refIndex])
    }

    @Test fun gyroAloneForcesFastAndSoftensStrength() {
        // 0.35 rad/s sits above the fitted Fast/motion lines (0.30):
        // full gyro penalty, Fast path, pixels still.
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(3) { 0.01 },
                hots = List(3) { 0.0 },
                means = List(4) { 0.2 },
                sharps = List(4) { 0.011 },
                gyro = 0.35
            ),
            frameCount = 4,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 2
        )
        assertFalse(decision.highQuality)
        assertEquals(7.0f, decision.strength, 1e-6f)
    }

    @Test fun stillLevelGyroLeavesHqAlone() {
        // 0.12 rad/s is inside the still bursts' observed range
        // (max 0.166): no penalty, HQ path on still pixels.
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(3) { 0.01 },
                hots = List(3) { 0.001 },
                means = List(4) { 0.11 },
                sharps = List(4) { 0.011 },
                gyro = 0.12
            ),
            frameCount = 4,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 2
        )
        assertTrue(decision.highQuality)
        assertEquals(13.0f, decision.strength, 1e-6f)
    }

    @Test fun unusableMeteringFallsBackConservative() {
        val empty = HdrPlusMotionMeter.summarize(
            pairDeltas = listOf(0.01, 0.01),
            prevTimestampsNs = listOf(1L, 2L),
            currTimestampsNs = listOf(2L, 3L),
            pairGyroRadS = listOf(0.01, 0.01)
        )
        val decision = HdrPlusAutoSelect.select(
            metering = empty, frameCount = 3, sensitivityIso = 50,
            manualStrength = null, manualQuality = null, preferredRef = 1
        )
        assertEquals(8f, decision.strength, 0f)
        assertFalse(decision.highQuality)
        assertEquals(listOf(0, 1, 2), decision.keepIndices)
        assertEquals(1, decision.keepIndices[decision.refIndex])
        assertEquals(null, decision.frameStrengths)
    }

    @Test fun decisionLogLine() {
        val deltas = MutableList(9) { 0.0027 }.also { it[8] = 0.13 }
        val hots = MutableList(9) { 0.0117 }.also { it[8] = 0.9 }
        val means = MutableList(10) { 0.039 }.also { it[9] = 0.169 }
        val decision = HdrPlusAutoSelect.select(
            metering = metering(deltas, hots, means, MutableList(10) { 0.004 }),
            frameCount = 10,
            sensitivityIso = 40,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 5
        )
        assertEquals(
            "HDR+ auto: strength=4.6 (auto) path=fast (auto) ref=F6 kept=9/10 dropped=[F10]",
            decision.logLine(10)
        )
    }

    @Test fun mixedBurstRelaxesCleanFramesUpward() {
        // 163712 anchor: mixed shaky burst — calm middle pairs (0.0-0.3%
        // hot) grafted between shaky ones, burst gyro saturated. Burst
        // strength stays at the 3.0 floor on Fast, but the calm frames
        // F4/F5 (1-based) merge hard; frames never drop below the floor.
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(7) { 0.01 },
                hots = listOf(0.0255, 0.0108, 0.0, 0.0, 0.0027, 0.0968, 0.0296),
                means = List(8) { 0.12 },
                sharps = List(8) { 0.011 },
                gyro = 0.5
            ),
            frameCount = 8,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 4
        )
        assertEquals(3.0f, decision.strength, 0f)
        assertFalse(decision.highQuality)
        assertEquals((0..7).toList(), decision.keepIndices)
        assertEquals(3, decision.keepIndices[decision.refIndex])
        assertEquals(
            listOf(3.0f, 3.0f, 3.0f, 13.0f, 11.8f, 3.0f, 3.0f, 3.0f),
            decision.frameStrengths
        )
        assertTrue(decision.frameStrengths!!.minOrNull()!! >= decision.strength)
        assertEquals(
            "HDR+ auto: strength=3.0 (auto) path=fast (auto) ref=F4 kept=8/8" +
                " fm=[3.0,3.0,3.0,13.0,11.8,3.0,3.0,3.0]",
            decision.logLine(8)
        )
    }

    @Test fun fastPathBuildsSmoothedStrengthMaps() {
        // 4x1 map grid: pair 0 hot on the right half, pair 1 clean.
        // Frames adjacent to pair 0 get a smoothed 13 -> 3 gradient;
        // the clean frame stays 13 everywhere.
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(2) { 0.01 },
                hots = listOf(0.05, 0.0),
                means = List(3) { 0.2 },
                sharps = List(3) { 0.011 },
                ratios = listOf(
                    doubleArrayOf(0.5, 0.5, 5.0, 5.0),
                    doubleArrayOf(0.5, 0.5, 0.5, 0.5)
                ),
                mapW = 4,
                mapH = 1
            ),
            frameCount = 3,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 1
        )
        assertEquals(3.0f, decision.strength, 0f)
        assertFalse(decision.highQuality)
        assertEquals(2, decision.keepIndices[decision.refIndex])
        val maps = decision.strengthMaps!!
        assertEquals(3, maps.size)
        val gradient = floatArrayOf(13.0f, 9.6667f, 6.3333f, 3.0f)
        assertMapEquals(gradient, maps[0], 1e-3f)
        assertMapEquals(gradient, maps[1], 1e-3f)
        assertMapEquals(floatArrayOf(13.0f, 13.0f, 13.0f, 13.0f), maps[2], 0f)
        assertEquals(
            "HDR+ auto: strength=3.0 (auto) path=fast (auto) ref=F3 kept=3/3" +
                " fm=[3.0,3.0,13.0] maps",
            decision.logLine(3)
        )
    }

    @Test fun hqPathBuildsMapsToo() {
        // HQ gained a map stage: same builder as Fast (uniform-clean
        // grids here), alongside frame strengths.
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(2) { 0.01 },
                hots = listOf(0.05, 0.0),
                means = List(3) { 0.2 },
                sharps = List(3) { 0.011 },
                ratios = listOf(doubleArrayOf(0.5, 0.5), doubleArrayOf(0.5, 0.5)),
                mapW = 2,
                mapH = 1
            ),
            frameCount = 3,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = true,
            preferredRef = 1
        )
        assertTrue(decision.highQuality)
        assertEquals(listOf(3.0f, 3.0f, 13.0f), decision.frameStrengths)
        val maps = decision.strengthMaps!!
        assertEquals(3, maps.size)
        maps.forEach { assertMapEquals(floatArrayOf(13.0f, 13.0f), it, 0f) }
        assertEquals(
            "HDR+ auto: strength=3.0 (auto) path=hq (manual) ref=F3 kept=3/3" +
                " fm=[3.0,3.0,13.0] maps",
            decision.logLine(3)
        )
    }

    @Test fun manualStrengthDisablesMaps() {
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(2) { 0.01 },
                hots = listOf(0.05, 0.0),
                means = List(3) { 0.2 },
                sharps = List(3) { 0.011 },
                ratios = listOf(doubleArrayOf(0.5, 0.5), doubleArrayOf(0.5, 0.5)),
                mapW = 2,
                mapH = 1
            ),
            frameCount = 3,
            sensitivityIso = 50,
            manualStrength = 8f,
            manualQuality = false,
            preferredRef = 1
        )
        assertEquals(null, decision.frameStrengths)
        assertEquals(null, decision.strengthMaps)
    }

    @Test fun missizedRatioGridsFallBackToFrameStrengths() {
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(2) { 0.01 },
                hots = listOf(0.05, 0.0),
                means = List(3) { 0.2 },
                sharps = List(3) { 0.011 },
                ratios = listOf(doubleArrayOf(0.5, 0.5, 0.5), doubleArrayOf(0.5)),
                mapW = 4,
                mapH = 1
            ),
            frameCount = 3,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = false,
            preferredRef = 1
        )
        assertFalse(decision.highQuality)
        assertEquals(listOf(3.0f, 3.0f, 13.0f), decision.frameStrengths)
        assertEquals(null, decision.strengthMaps)
    }

    @Test fun dangerTermOverridesCleanRatioOnJitter() {
        // 4x1 grids, ratios clean everywhere (0.5 -> 13) but dense MAD
        // hot on the right half of pair 0 (0.02 -> 3): the minimum
        // carries the danger gradient onto frames adjacent to pair 0.
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(2) { 0.01 },
                hots = listOf(0.05, 0.0),
                means = List(3) { 0.2 },
                sharps = List(3) { 0.011 },
                ratios = listOf(
                    doubleArrayOf(0.5, 0.5, 0.5, 0.5),
                    doubleArrayOf(0.5, 0.5, 0.5, 0.5)
                ),
                mapW = 4,
                mapH = 1,
                madDense = listOf(
                    doubleArrayOf(0.001, 0.001, 0.02, 0.02),
                    doubleArrayOf(0.001, 0.001, 0.001, 0.001)
                )
            ),
            frameCount = 3,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 1
        )
        val maps = decision.strengthMaps!!
        val gradient = floatArrayOf(13.0f, 9.6667f, 6.3333f, 3.0f)
        assertMapEquals(gradient, maps[0], 1e-3f)
        assertMapEquals(gradient, maps[1], 1e-3f)
        assertMapEquals(floatArrayOf(13.0f, 13.0f, 13.0f, 13.0f), maps[2], 0f)
    }

    @Test fun dangerCleanLeavesRatioMapUntouched() {
        // Danger below CLEAN everywhere is a minimum no-op: the ratio
        // gradient (5.0 -> 3 on the right half) survives unchanged.
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(2) { 0.01 },
                hots = listOf(0.05, 0.0),
                means = List(3) { 0.2 },
                sharps = List(3) { 0.011 },
                ratios = listOf(
                    doubleArrayOf(0.5, 0.5, 5.0, 5.0),
                    doubleArrayOf(0.5, 0.5, 0.5, 0.5)
                ),
                mapW = 4,
                mapH = 1,
                madDense = listOf(
                    doubleArrayOf(0.001, 0.001, 0.001, 0.001),
                    doubleArrayOf(0.001, 0.001, 0.001, 0.001)
                )
            ),
            frameCount = 3,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 1
        )
        val maps = decision.strengthMaps!!
        val gradient = floatArrayOf(13.0f, 9.6667f, 6.3333f, 3.0f)
        assertMapEquals(gradient, maps[0], 1e-3f)
        assertMapEquals(gradient, maps[1], 1e-3f)
        assertMapEquals(floatArrayOf(13.0f, 13.0f, 13.0f, 13.0f), maps[2], 0f)
    }

    @Test fun nonFiniteDangerGoesStrict() {
        // NaN danger is unmeasurable motion: strict (safe direction),
        // same as a non-finite ratio.
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(2) { 0.01 },
                hots = listOf(0.05, 0.0),
                means = List(3) { 0.2 },
                sharps = List(3) { 0.011 },
                ratios = listOf(
                    doubleArrayOf(0.5, 0.5, 0.5, 0.5),
                    doubleArrayOf(0.5, 0.5, 0.5, 0.5)
                ),
                mapW = 4,
                mapH = 1,
                madDense = listOf(
                    doubleArrayOf(0.001, 0.001, Double.NaN, Double.NaN),
                    doubleArrayOf(0.001, 0.001, 0.001, 0.001)
                )
            ),
            frameCount = 3,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 1
        )
        val maps = decision.strengthMaps!!
        val gradient = floatArrayOf(13.0f, 9.6667f, 6.3333f, 3.0f)
        assertMapEquals(gradient, maps[0], 1e-3f)
        assertMapEquals(gradient, maps[1], 1e-3f)
        assertMapEquals(floatArrayOf(13.0f, 13.0f, 13.0f, 13.0f), maps[2], 0f)
    }

    @Test fun missizedDangerGridsIgnoreDanger() {
        // Dense grids at the wrong resolution disable only the danger
        // term (wrong counts never reach here: summarize() rejects
        // partial lists): the ratio map still builds.
        val decision = HdrPlusAutoSelect.select(
            metering = metering(
                deltas = List(2) { 0.01 },
                hots = listOf(0.05, 0.0),
                means = List(3) { 0.2 },
                sharps = List(3) { 0.011 },
                ratios = listOf(
                    doubleArrayOf(0.5, 0.5, 0.5, 0.5),
                    doubleArrayOf(0.5, 0.5, 0.5, 0.5)
                ),
                mapW = 4,
                mapH = 1,
                madDense = listOf(doubleArrayOf(0.02, 0.02), doubleArrayOf(0.02, 0.02))
            ),
            frameCount = 3,
            sensitivityIso = 50,
            manualStrength = null,
            manualQuality = null,
            preferredRef = 1
        )
        val maps = decision.strengthMaps!!
        val flat = floatArrayOf(13.0f, 13.0f, 13.0f, 13.0f)
        assertMapEquals(flat, maps[0], 0f)
        assertMapEquals(flat, maps[1], 0f)
        assertMapEquals(flat, maps[2], 0f)
    }

    private fun assertMapEquals(expected: FloatArray, actual: FloatArray, delta: Float) {
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { i, v -> assertEquals(v, actual[i], delta) }
    }

    @Test fun rejectsTooFewFrames() {
        assertThrows(IllegalArgumentException::class.java) {
            HdrPlusAutoSelect.select(
                metering = metering(
                    deltas = listOf(0.01),
                    hots = listOf(0.0),
                    means = listOf(0.09, 0.09),
                    sharps = listOf(0.011, 0.011)
                ),
                frameCount = 1,
                sensitivityIso = 50,
                manualStrength = null,
                manualQuality = null,
                preferredRef = 0
            )
        }
    }
}
