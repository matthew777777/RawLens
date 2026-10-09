// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Unblocker input-domain pins (regression 2026-10-08): the noise gate
 * `Vn = A·gray + B` is specified over the plain normalized quad mean,
 * but the merge fed it GAT-stabilized guide gray — stabilized variance
 * (noise floor ~0.25) against a linear noise estimate slashed every
 * noisy flat (REDMI5 robustness mean 0.93 → 0.04). These pins hold the
 * producer linear, the fold a no-op on model noise, and the stabilized
 * plane disqualified as gate input.
 */
class RawSrUnblockerDomainTest {
    companion object {
        private const val W = 32
        private const val H = 32
        private const val WHITE = 1023f
        private const val ALPHA = 2e-4
        private const val BETA = 1e-6

        private fun packed(codes: ShortArray): RawSrPackedFrame {
            require(codes.size == W * H)
            val plane = ByteBuffer.allocate(W * H * 2).order(ByteOrder.nativeOrder())
            for (c in codes) plane.putShort(c)
            plane.flip()
            return RawSrPackedFrame(
                plane,
                RawPlaneLayout(W, H, W * 2, 2),
                RawCrop(0, 0, W, H),
                RawNormalization(BayerPattern.RGGB, listOf(0f, 0f, 0f, 0f), WHITE),
                null,
                ImmutableDoubleValues(doubleArrayOf(ALPHA, BETA, ALPHA, BETA, ALPHA, BETA))
            )
        }

        private fun flatFrame(level: Float): RawSrPackedFrame {
            val code = (level * WHITE).roundToInt().toShort()
            return packed(ShortArray(W * H) { code })
        }
    }

    @Test fun plainMeanReturnsLinearQuadMean() {
        // Flat field: every quad mean is exactly the normalized level —
        // no GAT, no noise-table shaping. The guide of the same frame
        // stabilizes (STABILIZED) and lives far above, pinning the two
        // domains apart.
        val frame = flatFrame(0.25f)
        val plain = RawSrCovarianceGuide.plainMean(frame)
        assertEquals(W / 2, plain.width)
        assertEquals(H / 2, plain.height)
        val expected = (0.25f * WHITE).roundToInt() / WHITE.toDouble()
        for (v in plain.values) assertEquals(expected, v.toDouble(), 1e-6)
        val guide = RawSrCovarianceGuide.guide(frame)
        assertEquals(RawSrCovarianceGuide.Status.STABILIZED, guide.status)
        val gMean = guide.gray.values.average()
        assertTrue("GAT gray mean $gMean must live far above linear $expected", gMean > 1.0)
    }

    @Test fun unblockerFoldKeepsModelNoise() {
        // Flat level plus Gaussian noise at exactly the profile variance
        // (var = ALPHA*v + BETA): noise carries no detail to protect, so
        // the gate must keep weight ~1 everywhere and flag nothing.
        val level = 0.2
        val sigmaCode = WHITE * sqrt(ALPHA * level + BETA)
        val rnd = java.util.Random(11)
        val codes = ShortArray(W * H) {
            (level * WHITE + sigmaCode * rnd.nextGaussian())
                .roundToInt().coerceIn(0, WHITE.toInt()).toShort()
        }
        val plain = RawSrCovarianceGuide.plainMean(packed(codes))
        val u = RawSrUnblocker.computeFrame(plain, ALPHA, BETA)
        val w = plain.width
        val h = plain.height
        val robust = RawSrRobustness.FrameRobustness(w, h, FloatArray(w * h) { 1f }, IntArray(w * h))
        val folded = RawSrUnblocker.applyToFrameAndSpread(robust, u)
        val mean = folded.r.average()
        val min = folded.r.min()
        assertTrue("model noise must keep weight ~1, mean=$mean min=$min", mean > 0.99 && min > 0.9)
        assertTrue("model noise must flag nothing",
            folded.flags.all { it and RawSrRobustness.FLAG_UNBLOCKED == 0 })
    }

    @Test fun stabilizedGraySlashesTheSameNoise() {
        // The regressed input, pinned as disqualified: the stabilized
        // guide gray of the same model-noise frame carries variance
        // floor ~0.25 against the linear noise estimate, so the gate
        // slashes it (~0.25 weight) instead of passing it through.
        // This is why the merge must feed the gate plainMean, never
        // guide().gray.
        val level = 0.2
        val sigmaCode = WHITE * sqrt(ALPHA * level + BETA)
        val rnd = java.util.Random(11)
        val codes = ShortArray(W * H) {
            (level * WHITE + sigmaCode * rnd.nextGaussian())
                .roundToInt().coerceIn(0, WHITE.toInt()).toShort()
        }
        val guide = RawSrCovarianceGuide.guide(packed(codes))
        assertEquals(RawSrCovarianceGuide.Status.STABILIZED, guide.status)
        val u = RawSrUnblocker.computeFrame(guide.gray, ALPHA, BETA)
        val mean = u.average()
        assertTrue("stabilized gray must slash model noise, mean=$mean", mean < 0.5)
    }
}
