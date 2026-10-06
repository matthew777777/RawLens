// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raw-unit flow contract (Jamy-L convention): [RawSrAlignment.alignPair]
 * emits flows in raw pixels over the full-resolution lattice, and every CPU
 * consumer must sample and apply them as such —
 * [RawSrRobustness] at quad centers (2q+1) with the reference x0.5 warp,
 * [RawSrBayerMerge] at raw output coordinates with direct application.
 * The fixtures below fail against the old quad-unit consumption (require
 * crash or doubled shift) and pin the migrated behavior exactly.
 */
class RawSrFlowUnitsTest {
    private val tuning = RawSrTuning.forSnr(18.0)
    private val config = RawSrAlignmentConfig()

    private fun rawField(
        w: Int, h: Int, tileSize: Int = 8,
        flowAt: (tx: Int, ty: Int) -> Pair<Float, Float>
    ): RawSrAlignmentField {
        val columns = w / tileSize
        val rows = h / tileSize
        val tiles = List(columns * rows) { i ->
            val (dx, dy) = flowAt(i % columns, i / columns)
            RawSrTileFlow(0f, 0f, dx, dy, 0f, true)
        }
        return RawSrAlignmentField(w, h, tileSize, columns, rows, tiles)
    }

    private fun rampStats(
        qw: Int, qh: Int, variance: Float
    ): Pair<RawSrRobustness.ReferenceStats, RawSrRobustness.MovingStats> {
        val mean = Array(3) { FloatArray(qw * qh) { i -> (i % qw).toFloat() } }
        val ref = RawSrRobustness.ReferenceStats(
            qw, qh, mean.map { it.copyOf() }.toTypedArray(),
            Array(3) { FloatArray(qw * qh) { variance } })
        val mov = RawSrRobustness.MovingStats(qw, qh, mean.map { it.copyOf() }.toTypedArray())
        return ref to mov
    }

    @Test fun robustnessWarpsByHalfTheRawFlow() {
        // Uniform +2 raw px flow warps the X-ramp by exactly one quad:
        // Dogson reproduces the linear ramp, so every channel errs by 1.0
        // (distance 3, variance 3, s2 path) and coerces to R = 1. The right
        // column warps out of bounds (15 + 1 = 16): flags pin the shift
        // magnitude (x0.5, not x1.0 — a direct application would also
        // reject column 14), while the pooled R pins the 5x5 minimum.
        val (ref, mov) = rampStats(16, 12, 1.0f)
        val flow = rawField(32, 24) { _, _ -> 2f to 0f }
        val out = RawSrRobustness.evaluateWithStats(ref, mov, flow, tuning, config)
        for (y in 0 until 12) {
            for (x in 0..12) {
                val o = y * 16 + x
                assertEquals("r[$x,$y]", 1.0f, out.r[o], 0f)
                assertEquals("flags[$x,$y]", 0, out.flags[o])
            }
            // Columns 13-14: own quad accepted (flags 0), pooled R dragged
            // to 0 by the rejected column 15 inside the 5x5 window.
            for (x in 13..14) {
                val o = y * 16 + x
                assertEquals("flags[$x,$y]", 0, out.flags[o])
                assertEquals("r[$x,$y]", 0f, out.r[o], 0f)
            }
            val o = y * 16 + 15
            assertEquals("r[15,$y]", 0f, out.r[o], 0f)
            assertEquals("flags[15,$y]", RawSrRobustness.FLAG_OUT_OF_BOUNDS, out.flags[o])
        }
    }

    @Test fun robustnessTileMappingFollowsTheRawLattice() {
        // Only tile tx=3 (raw x in [24, 32)) carries a huge flow; the warp
        // blends bilinearly at the raw quad center (2x+1), so quads x in
        // [0, 9] sample tiles 0-2 only (in bounds) while x in [10, 15]
        // blend in tile 3's +100 shift and warp out of bounds (quad 10
        // already reads 19% of tile 3: 10 + 100*0.19/2 > 16). The old
        // x/16 mapping over quad coordinates read tile 0 everywhere and
        // accepted all. The 5x5 local minimum spreads the rejection two
        // quads further, so r = 1 exactly only for x in [0, 7].
        val qw = 16
        val qh = 12
        val mean = Array(3) { FloatArray(qw * qh) { 0.5f } }
        val ref = RawSrRobustness.ReferenceStats(
            qw, qh, mean.map { it.copyOf() }.toTypedArray(),
            Array(3) { FloatArray(qw * qh) { 1.0f } })
        val mov = RawSrRobustness.MovingStats(qw, qh, mean.map { it.copyOf() }.toTypedArray())
        val flow = rawField(32, 24) { tx, _ -> if (tx == 3) 100f to 0f else 0f to 0f }
        val out = RawSrRobustness.evaluateWithStats(ref, mov, flow, tuning, config)
        for (y in 0 until qh) {
            for (x in 0..9) {
                val o = y * qw + x
                assertEquals("flags[$x,$y]", 0, out.flags[o])
            }
            for (x in 0..7) {
                val o = y * qw + x
                assertEquals("r[$x,$y]", 1.0f, out.r[o], 0f)
            }
            for (x in 8..15) {
                val o = y * qw + x
                assertEquals("r[$x,$y]", 0f, out.r[o], 0f)
            }
            for (x in 10..15) {
                val o = y * qw + x
                assertEquals("flags[$x,$y]", RawSrRobustness.FLAG_OUT_OF_BOUNDS, out.flags[o])
            }
        }
    }

    @Test fun robustnessMotionPriorComparesRawPixelSpans() {
        // The s1/s2 gate compares the 3x3 flow spread against mTh = 0.8 in
        // RAW pixels (reference cuda_compute_s). A 0.6 span stays on the s2
        // path everywhere (the old mTh/2 = 0.4 threshold would flag it);
        // a 1.0 span takes s1 somewhere (min R < 0.5, unreachable on s2).
        val (ref, mov) = rampStats(16, 12, 0.1f)
        val smooth = rawField(32, 24) { tx, ty -> if ((tx + ty) % 2 == 0) 0f to 0f else 0.6f to 0f }
        val smoothOut = RawSrRobustness.evaluateWithStats(ref, mov, smooth, tuning, config)
        for (o in smoothOut.r.indices) {
            assertEquals("smooth r[$o]", 1.0f, smoothOut.r[o], 0f)
            assertEquals("smooth flags[$o]", 0, smoothOut.flags[o])
        }
        val ragged = rawField(32, 24) { tx, ty -> if ((tx + ty) % 2 == 0) 0f to 0f else 1.0f to 0f }
        val raggedOut = RawSrRobustness.evaluateWithStats(ref, mov, ragged, tuning, config)
        val interiorMin = raggedOut.r.minOrNull()!!
        assertTrue("ragged min R $interiorMin", interiorMin < 0.5f)
    }

    @Test fun mergeAppliesRawUnitFlowAtRawCoordinates() {
        // Moving frame: vertical step at raw x = 16. Uniform +4 raw px flow
        // puts the output transition at x = 11.5 (source x + 0.5 + 4).
        // Columns 8-10 split the old convention (x2 shift, transition at
        // 7.5, bright) from the raw-unit one (dark).
        val w = 32
        val h = 24
        val qw = w / 2
        val qh = h / 2
        val pattern = BayerPattern.RGGB
        fun frame(step: Boolean, flow: RawSrAlignmentField?): RawSrBayerMerge.MergeFrame {
            val samples = FloatArray(w * h) { i ->
                if (step && i % w >= 16) 1f else 0f
            }
            val p = 0.25f
            val covValues = FloatArray(qw * qh * 4)
            for (i in 0 until qw * qh) {
                covValues[i * 4] = p
                covValues[i * 4 + 3] = p
            }
            return RawSrBayerMerge.MergeFrame(
                w, h, samples, pattern.shifted(0, 0), 0, 0,
                RawSrKernelCovariance.MatrixField(qw, qh, covValues),
                flow,
                RawSrRobustness.FrameRobustness(qw, qh, FloatArray(qw * qh) { 1f }, IntArray(qw * qh)))
        }
        val flow = rawField(w, h) { _, _ -> 4f to 0f }
        val result = RawSrBayerMerge.merge(frame(step = false, flow = null), listOf(frame(step = true, flow = flow)))
        assertEquals(w, result.width)
        assertEquals(h, result.height)
        fun bandMean(x0: Int, x1: Int): Double {
            var sum = 0.0
            var n = 0
            for (y in 4 until h - 4) for (x in x0..x1) for (c in 0..2) {
                sum += result.rgb[(y * result.width + x) * 3 + c]
                n++
            }
            return sum / n
        }
        assertTrue("dark ${bandMean(0, 5)}", bandMean(0, 5) < 0.1)
        assertTrue("split ${bandMean(8, 10)}", bandMean(8, 10) < 0.15)
        assertTrue("bright ${bandMean(16, 23)}", bandMean(16, 23) > 0.4)
    }
}
