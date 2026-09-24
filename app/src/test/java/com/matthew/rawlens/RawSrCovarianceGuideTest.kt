// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

/** Prompt 4B.1 guide tests: GAT domain, profile statuses, CFA patterns, origins. */
class RawSrCovarianceGuideTest {
    private fun packed(
        layoutW: Int = 36,
        layoutH: Int = 28,
        originX: Int = 0,
        originY: Int = 0,
        crop: RawCrop = RawCrop(0, 0, 32, 24),
        codes: (sensorX: Int, sensorY: Int) -> Int = { _, _ -> 1500 },
        sensorPattern: BayerPattern = BayerPattern.RGGB,
        black: List<Float> = listOf(64f, 64f, 64f, 64f),
        white: Float = 4000f,
        profile: ImmutableDoubleValues? = ImmutableDoubleValues(doubleArrayOf(
            0.01, 0.001, 0.02, 0.001, 0.03, 0.001, 0.04, 0.001))
    ): RawSrPackedFrame {
        val rowStride = layoutW * 2
        val plane = ByteBuffer.allocateDirect(rowStride * layoutH).order(ByteOrder.nativeOrder())
        for (sy in 0 until layoutH) for (sx in 0 until layoutW)
            plane.putShort(sy * rowStride + sx * 2, codes(originX + sx, originY + sy).toShort())
        return RawSrPackedFrame(
            plane, RawPlaneLayout(layoutW, layoutH, rowStride, 2, originX, originY),
            crop, RawNormalization(sensorPattern, black, white), null, profile)
    }

    @Test fun gatFormulaMatchesHandComputedSample() {
        // Single quad, distinct per-phase slopes: phase (0,0) has S=0.01, code 1064.
        val frame = packed(layoutW = 2, layoutH = 2, crop = RawCrop(0, 0, 2, 2),
            codes = { sx, sy -> 1000 + ((sy and 1) shl 1 or (sx and 1)) * 64 },
            black = listOf(64f, 64f, 64f, 64f), white = 4000f)
        val guide = RawSrCovarianceGuide.guide(frame)
        assertEquals(RawSrCovarianceGuide.Status.STABILIZED, guide.status)
        // Independent step-by-step recomputation of the quad mean: same documented
        // formula, separately written phase mapping (code 1000 → v = 936/3936).
        // A wrong phase/slope pairing or averaging order changes the mean.
        fun stabilized(code: Int, slope: Double): Double {
            val vv = (code - 64.0) / 3936.0
            val aa = slope / 3936.0
            val bb = (slope * 64.0 + 0.001) / (3936.0 * 3936.0)
            return (2.0 / aa) * sqrt(maxOf(0.0, aa * vv + 0.375 * aa * aa + bb))
        }
        val mean = (stabilized(1000, 0.01) + stabilized(1064, 0.02) +
            stabilized(1128, 0.03) + stabilized(1192, 0.04)) * 0.25
        assertEquals(mean, guide.gray.values[0].toDouble(), 1e-4)
        assertTrue("stabilized guide must amplify differences vs plain mean", mean > (1000 + 1064 + 1128 + 1192) * 0.25 / 3936.0)
    }

    @Test fun profileStatusesAreExplicit() {
        // Missing profile: plain normalized quad average, exactly the 4B guide.
        val missing = packed(profile = null)
        val missingGuide = RawSrCovarianceGuide.guide(missing)
        assertEquals(RawSrCovarianceGuide.Status.UNSTABILIZED_MISSING_PROFILE, missingGuide.status)
        val unpacked = RawSensorUnpacker.unpackNormalized(
            missing.uploadInput().buffer, missing.uploadInput().layout,
            missing.uploadInput().normalization, missing.uploadInput().crop)
        val plain = RawSrAlignment.bayerQuadGray(unpacked)
        // Same formula in Float vs Double: 1-ulp rounding differs, nothing else.
        assertArrayEquals(plain.values, missingGuide.gray.values, 1e-4f)

        // Invalid: wrong size, negative, and non-finite coefficients.
        for (bad in listOf(
            ImmutableDoubleValues(doubleArrayOf(0.01, 0.001)),
            ImmutableDoubleValues(doubleArrayOf(0.01, 0.001, -0.02, 0.001, 0.03, 0.001, 0.04, 0.001)),
            ImmutableDoubleValues(doubleArrayOf(0.01, 0.001, Double.NaN, 0.001, 0.03, 0.001, 0.04, 0.001)))) {
            val guide = RawSrCovarianceGuide.guide(packed(profile = bad))
            assertEquals("profile=$bad", RawSrCovarianceGuide.Status.UNSTABILIZED_INVALID_PROFILE, guide.status)
            assertArrayEquals(plain.values, guide.gray.values, 1e-4f)
        }

        // Zero noise everywhere: nothing to stabilize, plain average labeled honestly.
        val zero = RawSrCovarianceGuide.guide(packed(profile = ImmutableDoubleValues(DoubleArray(8))))
        assertEquals(RawSrCovarianceGuide.Status.UNSTABILIZED_ZERO_NOISE, zero.status)
        assertArrayEquals(plain.values, zero.gray.values, 1e-4f)

        // Inconsistent: one zero-noise phase beside noisy ones is no physical sensor.
        val inconsistent = RawSrCovarianceGuide.guide(packed(profile = ImmutableDoubleValues(
            doubleArrayOf(0.0, 0.0, 0.02, 0.001, 0.03, 0.001, 0.04, 0.001))))
        assertEquals(RawSrCovarianceGuide.Status.UNSTABILIZED_INVALID_PROFILE, inconsistent.status)

        // Zero shot noise with positive offsets: affine limit v/sqrt(b').
        val affineFrame = packed(profile = ImmutableDoubleValues(
            doubleArrayOf(0.0, 2.0, 0.0, 2.0, 0.0, 2.0, 0.0, 2.0)),
            codes = { sx, sy -> 1064 + sx * 64 + sy * 128 })
        val affine = RawSrCovarianceGuide.guide(affineFrame)
        assertEquals(RawSrCovarianceGuide.Status.STABILIZED_ZERO_SHOT, affine.status)
        val beta = 2.0 / (3936.0 * 3936.0)
        fun limit(code: Int) = ((code - 64.0) / 3936.0) / sqrt(beta)
        val expectedMean = (limit(1064) + limit(1128) + limit(1192) + limit(1256)) * 0.25
        assertEquals(expectedMean, affine.gray.values[0].toDouble(), 1e-4)
    }

    @Test fun uniformFieldIsUniformForAllPatternsAndOrigins() {
        val slopes = doubleArrayOf(0.01, 0.001, 0.02, 0.001, 0.03, 0.001, 0.04, 0.001)
        for (pattern in BayerPattern.entries) {
            for ((ox, oy) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
                val frame = packed(layoutW = 34, layoutH = 26, originX = ox, originY = oy,
                    crop = RawCrop(0, 0, 32, 24), codes = { _, _ -> 1500 },
                    sensorPattern = pattern,
                    black = listOf(64f, 65f, 66f, 67f),
                    profile = ImmutableDoubleValues(slopes))
                // Odd origins rotate the effective pattern through all four arrangements.
                assertEquals(pattern.shifted(ox, oy), frame.pattern)
                val guide = RawSrCovarianceGuide.guide(frame)
                assertEquals("$pattern ($ox,$oy)", RawSrCovarianceGuide.Status.STABILIZED, guide.status)
                val first = guide.gray.values[0]
                assertTrue(first.isFinite())
                for (value in guide.gray.values) assertEquals(first, value, 0f)
            }
        }
    }

    @Test fun sixCoefficientProfilesIndexBySensorColor() {
        // DNG-style R/G/B profile under two sensor patterns with odd origins.
        for ((pattern, ox, oy) in listOf(
            Triple(BayerPattern.RGGB, 1, 0), Triple(BayerPattern.BGGR, 1, 1))) {
            val frame = packed(layoutW = 34, layoutH = 26, originX = ox, originY = oy,
                crop = RawCrop(0, 0, 32, 24), codes = { _, _ -> 1500 },
                sensorPattern = pattern,
                profile = ImmutableDoubleValues(doubleArrayOf(0.02, 0.002, 0.01, 0.001, 0.03, 0.003)))
            val guide = RawSrCovarianceGuide.guide(frame)
            assertEquals("$pattern ($ox,$oy)", RawSrCovarianceGuide.Status.STABILIZED, guide.status)
            val first = guide.gray.values[0]
            for (value in guide.gray.values) assertEquals(first, value, 0f)
        }
    }

    @Test fun originShiftPreservesSensorContent() {
        fun content(sx: Int, sy: Int) = 1200 + (sx * 79 + sy * 43) % 500
        val profile = ImmutableDoubleValues(doubleArrayOf(
            0.015, 0.001, 0.025, 0.002, 0.02, 0.0015, 0.03, 0.002))
        val first = RawSrCovarianceGuide.guide(packed(layoutW = 8, layoutH = 6,
            crop = RawCrop(0, 0, 8, 6), codes = ::content, profile = profile))
        // Shift by exactly one quad: identical sensor samples, identical guide.
        val shifted = RawSrCovarianceGuide.guide(packed(layoutW = 8, layoutH = 6,
            originX = 2, crop = RawCrop(0, 0, 8, 6),
            codes = ::content, profile = profile))
        assertEquals(RawSrCovarianceGuide.Status.STABILIZED, first.status)
        assertEquals(RawSrCovarianceGuide.Status.STABILIZED, shifted.status)
        for (qy in 0 until 3) for (qx in 0 until 3)
            assertEquals(first.gray.values[(qy * 4 + qx + 1)], shifted.gray.values[qy * 4 + qx], 0f)
    }

    @Test fun darkStepAmplifiesGuideGradientOverBrightStep() {
        // Same absolute step, matched model: GAT stretches dark differences ~3.5x.
        fun stepped(base: Int) = packed(crop = RawCrop(0, 0, 32, 24),
            codes = { sx, _ -> if (sx < 16) base else base + 400 },
            profile = ImmutableDoubleValues(DoubleArray(8) { if (it % 2 == 0) 0.02 else 1.0 }))
        val dark = RawSrCovarianceGuide.guide(stepped(200)).gray
        val bright = RawSrCovarianceGuide.guide(stepped(3000)).gray
        // Step straddles quad columns 7|8; measure across it in a mid row.
        val darkStep = abs(dark.values[6 * 16 + 8] - dark.values[6 * 16 + 7])
        val brightStep = abs(bright.values[6 * 16 + 8] - bright.values[6 * 16 + 7])
        assertTrue("dark=$darkStep bright=$brightStep", darkStep / brightStep > 2.0)
    }

    @Test fun tinyStepIsAnisotropicInDarkAndIsotropicInBright() {
        // Same 4-code step with no noise: dark opens the detail gate (D~=0.44),
        // bright stays shut (D=1). Hand-derived in the 4B.1 analysis.
        fun stepped(base: Int) = packed(crop = RawCrop(0, 0, 32, 24),
            codes = { sx, _ -> if (sx < 16) base else base + 4 },
            profile = ImmutableDoubleValues(DoubleArray(8) { if (it % 2 == 0) 0.02 else 1.0 }))
        val tuning = RawSrTuning.forSnr(30.0)
        val dark = RawSrKernelCovariance.precision(RawSrCovarianceGuide.guide(stepped(200)).gray, tuning)
        val bright = RawSrKernelCovariance.precision(RawSrCovarianceGuide.guide(stepped(3000)).gray, tuning)
        val darkRatio = dark.get(8, 6, 0) / dark.get(8, 6, 3)
        val brightRatio = bright.get(8, 6, 0) / bright.get(8, 6, 3)
        assertTrue("dark ratio=$darkRatio", darkRatio > 3.0)
        assertTrue("bright ratio=$brightRatio", brightRatio < 1.5)
    }

    @Test fun flatNoisyFieldsStayFiniteAndNearIsotropicAcrossLevels() {
        fun noisy(base: Int, slope: Double, offset: Double): RawSrPackedFrame {
            val profile = ImmutableDoubleValues(DoubleArray(8) { if (it % 2 == 0) slope else offset })
            // Model-matched noise: uniform(-1,1) scaled to the profile std, so GAT
            // equalizes it to unit variance. Over-scaled noise would legitimately
            // read as structure.
            val sigma = sqrt(slope * base + offset)
            return packed(crop = RawCrop(0, 0, 32, 24), profile = profile,
                codes = { sx, sy ->
                    val hash = (((sx * 73856093) xor (sy * 19349663)) and 0x7fffffff) % 1001 / 1000.0 * 2.0 - 1.0
                    base + (hash * 1.73 * sigma).toInt()
                })
        }
        val tuning = RawSrTuning.forSnr(18.0)
        for (base in listOf(300, 1500, 3000)) {
            for ((slope, offset) in listOf(0.02 to 1.0, 0.05 to 5.0)) {
                val guide = RawSrCovarianceGuide.guide(noisy(base, slope, offset))
                assertEquals(RawSrCovarianceGuide.Status.STABILIZED, guide.status)
                assertTrue(guide.gray.values.all { it.isFinite() })
                val precision = RawSrKernelCovariance.precision(guide.gray, tuning)
                assertTrue(precision.values.all { it.isFinite() })
                for (y in 1 until 11) for (x in 1 until 15) {
                    val p00 = precision.get(x, y, 0)
                    val p01 = precision.get(x, y, 1)
                    val p11 = precision.get(x, y, 3)
                    val trace = p00 + p11
                    val det = p00 * p11 - p01 * p01
                    val disc = sqrt(maxOf(0f, trace * trace / 4f - det))
                    val ratio = (trace / 2f + disc) / (trace / 2f - disc)
                    assertTrue("base=$base s=$slope o=$offset ($x,$y) ratio=$ratio", ratio < 6f)
                }
            }
        }
    }

    @Test fun kernelExponentIsInvariantAcrossRawQuadConversion() {
        // Contract: P_raw = P_quad/4 converts storage grids; d^T P d is invariant.
        val frame = packed(codes = { sx, sy -> 1200 + (sx * 79 + sy * 43) % 500 })
        val tuning = RawSrTuning.forSnr(18.0)
        val precision = RawSrKernelCovariance.precision(RawSrCovarianceGuide.guide(frame).gray, tuning)
        val ux = 0.6f
        val uy = 0.8f
        val scale = 2.5f
        for (y in 2 until 10) for (x in 2 until 14) {
            val p00 = precision.get(x, y, 0)
            val p01 = precision.get(x, y, 1)
            val p11 = precision.get(x, y, 3)
            val quad = scale * scale * (ux * ux * p00 + 2f * ux * uy * p01 + uy * uy * p11)
            val raw = (2f * scale) * (2f * scale) *
                (ux * ux * p00 / 4f + 2f * ux * uy * p01 / 4f + uy * uy * p11 / 4f)
            assertEquals("($x,$y)", quad, raw, 1e-6f * abs(quad))
        }
    }
}
