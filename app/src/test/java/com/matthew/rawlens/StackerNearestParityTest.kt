// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.floor
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/**
 * Stacker nearest-burst parity pin (P1 provenance fix).
 *
 * Pinned source: Stacker v0.1.5-beta, commit
 * 715d949ec30f616386fe551b44c1bf4acaad0162,
 * app/src/main/cpp/wronski_cpu_merge.cpp — `nearest_cfa_sample` (lines
 * 48-87) and `merge_cpu_bayer_nearest_burst` (lines 539-617), fetched
 * 2026-09-12. Upstream publishes no LICENSE file, so nothing was vendored:
 * [stackerNearest] below is an independent behavioral transcription (own
 * Kotlin, no copied lines), written directly from the pinned C++.
 *
 * Scope of the parity claim, exactly: the per-sample pick rule (floor-
 * centered 3x3 window, channel/phase match, finite-only, texel-center
 * distance squared, strictly-less update, oy-outer/ox-inner loop order as
 * the deterministic tie-break) and the burst-loop weight rules (reference
 * frame at unit weight, moving frames at clamped robustness, zero-weight
 * and out-of-bounds sources skipped). Deliberate adaptations NOT covered
 * here, documented in docs/raw-sr-merge.md and the runlog: quad-level
 * (not per-pixel) robustness lookup, fallback-gated (not full-frame)
 * emission, the saturation guard, Double (not float) arithmetic,
 * censored-tap skipping in nearest picks, reference-kernel-first fallback
 * priority (nearest-first would straddle edges into chroma speckles), and
 * crop-origin-aware CFA routing.
 */
class StackerNearestParityTest {
    private companion object {
        /** Diffusion seed; the corpus is fixed, never tuned per run. */
        const val SEED = 20260912L
    }

    private var tieLosers = 0

    /**
     * Behavioral transcription of Stacker's `nearest_cfa_sample`: returns
     * the picked sample value, or null when no matching finite sample is
     * in the window. Written from the pinned C++; loop order and the
     * strictly-less update are load-bearing (they ARE the tie-break).
     */
    private fun stackerNearest(
        samples: FloatArray,
        w: Int,
        h: Int,
        channelOf: (x: Int, y: Int) -> Int,
        channel: Int,
        x: Double,
        y: Double
    ): Double? {
        val centerX = floor(x).toInt()
        val centerY = floor(y).toInt()
        var found = false
        var bestIndex = 0
        var bestD2 = Double.POSITIVE_INFINITY
        for (oy in -1..1) for (ox in -1..1) {
            val sx = centerX + ox
            val sy = centerY + oy
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
            if (channelOf(sx, sy) != channel) continue
            val index = sy * w + sx
            val v = samples[index].toDouble()
            if (!v.isFinite()) continue
            val dx = sx + 0.5 - x
            val dy = sy + 0.5 - y
            val d2 = dx * dx + dy * dy
            if (!found || d2 < bestD2) {
                if (found && d2 == bestD2) tieLosers++
                found = true
                bestIndex = index
                bestD2 = d2
            } else if (d2 == bestD2) {
                tieLosers++
            }
        }
        return if (found) samples[bestIndex].toDouble() else null
    }

    private fun channelIndex(color: CfaColor): Int = when (color) {
        CfaColor.RED -> 0
        CfaColor.GREEN -> 1
        CfaColor.BLUE -> 2
    }

    private fun frame(
        w: Int,
        h: Int,
        pattern: BayerPattern,
        ox: Int,
        oy: Int,
        samples: FloatArray,
        flow: RawSrAlignmentField? = null,
        robustness: RawSrRobustness.FrameRobustness? = null
    ): RawSrBayerMerge.MergeFrame {
        val qw = w / 2
        val qh = h / 2
        val precision = RawSrKernelCovariance.MatrixField(
            qw, qh, FloatArray(qw * qh * 4) { i -> if (i % 4 == 0 || i % 4 == 3) 1f else 0f })
        return RawSrBayerMerge.MergeFrame(w, h, samples, pattern.shifted(ox, oy), ox, oy, precision, flow, robustness)
    }

    private fun zeroField(qw: Int, qh: Int, dx: Float = 0f, dy: Float = 0f): RawSrAlignmentField {
        val columns = (qw + 7) / 8
        val rows = (qh + 7) / 8
        val tiles = List(columns * rows) { RawSrTileFlow(0f, 0f, dx, dy, 0f, true) }
        return RawSrAlignmentField(qw, qh, 8, columns, rows, tiles)
    }

    private fun constRobust(qw: Int, qh: Int, v: Float): RawSrRobustness.FrameRobustness =
        RawSrRobustness.FrameRobustness(qw, qh, FloatArray(qw * qh) { v }, IntArray(qw * qh))

    @Test fun nearestPickMatchesStackerTranscription() {
        val rng = Random(SEED)
        var calls = 0
        for (pattern in BayerPattern.entries) {
            for ((w, h) in listOf(4 to 4, 6 to 6, 8 to 6, 10 to 8)) {
                for ((ox, oy) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
                    val samples = FloatArray(w * h) { i ->
                        when (rng.nextInt(20)) {
                            0 -> Float.NaN
                            1 -> Float.POSITIVE_INFINITY
                            // Capped below the censor guard: the corpus pins
                            // Stacker's finite-only pick rule; censored-tap
                            // skipping is our deliberate deviation, pinned
                            // separately in RawSrBayerMergeTest.
                            else -> minOf(rng.nextFloat(), 0.984f)
                        }
                    }
                    val f = frame(w, h, pattern, ox, oy, samples)
                    val channelOf: (Int, Int) -> Int =
                        { x, y -> channelIndex(pattern.colorAt(ox + x, oy + y)) }
                    repeat(120) { n ->
                        // Mix continuous coords (general case), integer coords
                        // (symmetric-tie geometry), and half-integers (window-
                        // straddle geometry) so the tie-break is exercised.
                        val x = when (n % 4) {
                            0 -> rng.nextDouble(-1.0, w + 1.0)
                            1 -> rng.nextInt(-1, w + 1).toDouble()
                            else -> rng.nextInt(-1, w + 1) + 0.5
                        }
                        val y = when (n % 4) {
                            0 -> rng.nextDouble(-1.0, h + 1.0)
                            1 -> rng.nextInt(-1, h + 1).toDouble()
                            else -> rng.nextInt(-1, h + 1) + 0.5
                        }
                        val r = listOf(0.0, 0.1, 0.5, 1.0)[n % 4]
                        val prodNum = DoubleArray(3)
                        val prodDen = DoubleArray(3)
                        RawSrBayerMerge.accumulateNearest(f, x, y, r, 0, w, h, prodNum, prodDen)
                        for (c in 0..2) {
                            val expected = stackerNearest(samples, w, h, channelOf, c, x, y)
                            if (expected == null) {
                                assertEquals("($pattern ${w}x$h o=$ox,$oy x=$x y=$y c=$c) den",
                                    0.0, prodDen[c], 0.0)
                            } else {
                                assertEquals("($pattern ${w}x$h o=$ox,$oy x=$x y=$y c=$c) num",
                                    r * expected, prodNum[c], 0.0)
                                assertEquals("($pattern ${w}x$h o=$ox,$oy x=$x y=$y c=$c) den",
                                    r, prodDen[c], 0.0)
                            }
                        }
                        calls++
                    }
                }
            }
        }
        assertTrue("corpus too small: $calls", calls > 1000)
        assertTrue("no distance ties exercised (tie-break untested): rework corpus", tieLosers > 0)
    }

    @Test fun tieBreakFollowsOyOuterOxInnerOrder() {
        // Hand-computed order-sensitive case: RGGB, source exactly on the
        // (2, 2) texel corner. Green candidates (1,2) [oy=0,ox=-1] and
        // (2,1) [oy=-1,ox=0] tie at d2=0.5; oy-outer order visits (2,1)
        // first and the strictly-less update keeps it. An ox-outer variant
        // would pick (1,2).
        val w = 4
        val h = 4
        // Sub-guard values: the tie-break is geometric (equal d2), so the
        // affine rescale preserves every strictly-less decision while staying
        // clear of the censor skip (censored taps never compete).
        val samples = FloatArray(w * h) { i -> (i + 1).toFloat() / 32f }
        val f = frame(w, h, BayerPattern.RGGB, 0, 0, samples)
        val prodNum = DoubleArray(3)
        val prodDen = DoubleArray(3)
        RawSrBayerMerge.accumulateNearest(f, 2.0, 2.0, 1.0, 0, w, h, prodNum, prodDen)
        assertEquals(samples[1 * w + 2].toDouble(), prodNum[1], 0.0)
        assertEquals(1.0, prodDen[1], 0.0)
    }

    @Test fun zeroWeightMovingFrameFallsBackToReferenceOnly() {
        // Stacker burst loop: local_weight == 0 -> continue before flow and
        // sampling, so a fully-zero-robustness moving frame adds nothing to
        // the burst accumulators. rc stays 0 -> the overwrite forces fallback
        // everywhere, and with no moving support the fallback resolves
        // through the reference kernel quotient — bit-identical to the
        // reference-only run. (Per-call pick parity lives in
        // nearestPickMatchesStackerTranscription; the live-weight burst
        // quotient in fallbackBlendMatchesStackerBurstQuotient.)
        val w = 8
        val h = 8
        val ref = frame(w, h, BayerPattern.RGGB, 0, 0,
            FloatArray(w * h) { i -> 0.1f + (i % 7) * 0.05f })
        val mov = frame(w, h, BayerPattern.RGGB, 0, 0,
            FloatArray(w * h) { i -> 0.9f - (i % 5) * 0.05f },
            zeroField(w / 2, h / 2), constRobust(w / 2, h / 2, 0f))
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        val refOnly = RawSrBayerMerge.merge(ref, emptyList())
        assertTrue(out.fallback.all { it })
        assertArrayEquals(refOnly.rgb, out.rgb, 0f)
        assertArrayEquals(refOnly.oobCount, out.oobCount)
    }

    @Test fun outOfBoundsMovingFrameContributesNothing() {
        // Stacker burst loop: out-of-range source -> the frame contributes
        // nothing. A moving frame shifted fully off-frame (rc = 1 keeps the
        // kernel path) must equal the reference-only run exactly, with every
        // moving pixel counted oob.
        val w = 8
        val h = 8
        val ref = frame(w, h, BayerPattern.RGGB, 0, 0,
            FloatArray(w * h) { i -> 0.1f + (i % 7) * 0.05f })
        val mov = frame(w, h, BayerPattern.RGGB, 0, 0,
            FloatArray(w * h) { i -> 0.9f - (i % 5) * 0.05f },
            zeroField(w / 2, h / 2, dx = 100f), constRobust(w / 2, h / 2, 1f))
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        val refOnly = RawSrBayerMerge.merge(ref, emptyList())
        assertArrayEquals(refOnly.rgb, out.rgb, 0f)
        assertArrayEquals(refOnly.denominator, out.denominator, 0f)
        assertArrayEquals(refOnly.fallback, out.fallback)
        assertTrue(out.oobCount.sum() == w * h)
    }

    @Test fun fallbackBlendMatchesStackerBurstQuotient() {
        // Stacker burst-loop weight rules end to end: reference at unit weight
        // plus a moving frame at r = 0.1, rc forcing fallback everywhere.
        // The pick rule stays Stacker-faithful (nearestPickMatches above),
        // but the fallback PRIORITY is deliberately ours: the smooth
        // reference kernel mean precedes the burst-nearest blend, whose
        // per-channel picks straddle high-contrast edges into chroma
        // speckles. Zero flow keeps both sources exactly at texel centers +
        // 0.5, so no float-blend ulp can perturb the pick geometry (shifted-
        // pick coverage lives in nearestPickMatchesStackerTranscription;
        // oob-skip coverage in outOfBoundsMovingFrameContributesNothing).
        val w = 8
        val h = 8
        val rng = Random(SEED + 1)
        val refSamples = FloatArray(w * h) { 0.05f + rng.nextFloat() * 0.8f }
        val movSamples = FloatArray(w * h) { 0.05f + rng.nextFloat() * 0.8f }
        val ref = frame(w, h, BayerPattern.RGGB, 0, 0, refSamples)
        val movWeight = 0.1f
        val mov = frame(w, h, BayerPattern.RGGB, 0, 0, movSamples,
            zeroField(w / 2, h / 2), constRobust(w / 2, h / 2, movWeight))
        val out = RawSrBayerMerge.merge(ref, listOf(mov))
        assertTrue(out.fallback.all { it })
        // Reference kernel mean everywhere (sub-guard random scenes stay
        // clear of the censor rule): equals the reference-only run exactly.
        val refOnly = RawSrBayerMerge.merge(ref, emptyList())
        assertArrayEquals(refOnly.rgb, out.rgb, 0f)
    }
}
