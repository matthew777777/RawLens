package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSrFrameRejectionTest {
    private fun field(
        cols: Int = 4,
        rows: Int = 4,
        reliable: (Int) -> Boolean = { true },
        flow: (Int) -> Pair<Float, Float> = { 0f to 0f }
    ) = RawSrAlignmentField(
        imageWidth = cols, imageHeight = rows, tileSize = 1, columns = cols, rows = rows,
        tiles = List(cols * rows) { i ->
            val (dx, dy) = flow(i)
            RawSrTileFlow(0f, 0f, dx, dy, 0f, reliable(i))
        }
    )

    private fun robustness(values: FloatArray) =
        RawSrRobustness.FrameRobustness(2, values.size / 2, values, IntArray(values.size))

    @Test fun healthyFrameKeeps() {
        val verdict = RawSrFrameRejection.judge(
            field(), robustness(FloatArray(16) { 0.8f }))
        assertTrue(verdict.keep)
        assertNull(verdict.rejectReason)
        assertEquals(1.0, verdict.reliableFraction, 0.0)
        assertEquals(0.8, verdict.meanRobustness, 1e-6)
        assertEquals(1.0, verdict.supportFraction, 0.0)
    }

    @Test fun lowReliableFractionRejects() {
        // 1 of 16 tiles reliable (6.25%) with a strict 50% policy.
        val verdict = RawSrFrameRejection.judge(
            field(reliable = { it == 0 }),
            robustness(FloatArray(16) { 0.8f }),
            RawSrFrameRejection.Policy(minReliableFraction = 0.5))
        assertFalse(verdict.keep)
        assertEquals("low-reliable-frac", verdict.rejectReason)
        assertEquals(1.0 / 16, verdict.reliableFraction, 1e-9)
    }

    @Test fun lowMeanRobustnessRejects() {
        val verdict = RawSrFrameRejection.judge(
            field(), robustness(FloatArray(16) { 0.001f }))
        assertFalse(verdict.keep)
        assertEquals("low-mean-r", verdict.rejectReason)
    }

    @Test fun lowSupportRejectsDespiteDecentMean() {
        // One hot quad (R=0.9) lifts the mean above the floor, but support
        // (1/16 = 6.25% above 0.05) passes the default 5% — so tighten
        // support to 50% to prove the gate fires independently of the mean.
        val r = FloatArray(16) { 0.0f }.also { it[0] = 0.9f }
        val verdict = RawSrFrameRejection.judge(
            field(), robustness(r),
            RawSrFrameRejection.Policy(minMeanRobustness = 0.0, minSupportFraction = 0.5))
        assertFalse(verdict.keep)
        assertEquals("low-support", verdict.rejectReason)
        assertEquals(1.0 / 16, verdict.supportFraction, 1e-9)
    }

    @Test fun globalMotionVetoRejectsHandshake() {
        // Checkerboard flow (±10 quads): every 3x3 span is 20 quads, far past
        // the 4-quad veto, while reliability and R stay healthy.
        val verdict = RawSrFrameRejection.judge(
            field(flow = { if (it % 2 == 0) 10f to 0f else -10f to 0f }),
            robustness(FloatArray(16) { 0.8f }))
        assertFalse(verdict.keep)
        assertEquals("global-motion", verdict.rejectReason)
        assertTrue(verdict.medianFlowSpanPx > 4f)
    }

    @Test fun smoothFlowPassesMotionVeto() {
        // Gentle ramp (0.1 quad per tile): 3x3 spans stay ~0.2 quads.
        val verdict = RawSrFrameRejection.judge(
            field(flow = { (it % 4) * 0.1f to (it / 4) * 0.1f }),
            robustness(FloatArray(16) { 0.8f }))
        assertTrue(verdict.keep)
    }

    @Test fun nonFiniteRobustnessCountsAsZero() {
        val r = FloatArray(16) { 0.8f }.also {
            it[0] = Float.NaN
            it[1] = Float.POSITIVE_INFINITY
        }
        assertEquals(0.8 * 14 / 16, RawSrFrameRejection.meanRobustness(r), 1e-6)
        assertEquals(14.0 / 16, RawSrFrameRejection.supportFraction(r), 1e-9)
    }

    @Test fun policyRejectsOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) {
            RawSrFrameRejection.Policy(maxMergeFrames = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawSrFrameRejection.Policy(minMeanRobustness = 2.0)
        }
    }

    @Test fun logLineNamesVerdict() {
        val verdict = RawSrFrameRejection.judge(
            field(), robustness(FloatArray(16) { 0.001f }))
        val line = RawSrFrameRejection.logLine("F6", "-2", verdict, kept = false)
        assertTrue(line, line.contains("F6") && line.contains("REJECTED") && line.contains("low-mean-r"))
    }
}
