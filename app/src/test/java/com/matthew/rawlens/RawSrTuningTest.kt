// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class RawSrTuningTest {
    @Test fun exactBoundariesAndQuadUnits() {
        for ((snr, raw) in listOf(
            -10.0 to 64, 6.0 to 64, 14.0 to 64, Math.nextUp(14.0) to 32,
            22.0 to 32, Math.nextUp(22.0) to 16, 30.0 to 16, 100.0 to 16)) {
            val tuning = RawSrTuning.forSnr(snr)
            assertEquals(raw, tuning.rawTileSize)
            assertEquals(raw / 2, tuning.alignmentTileQuads)
            assertEquals(raw / 2, tuning.alignmentConfig().tileSize)
            assertEquals(3, tuning.alignmentConfig().lkIterations)
            assertEquals(4, tuning.alignmentConfig().searchRadius)
            assertEquals(0.12f, tuning.alignmentConfig().maxMeanAbsoluteResidual, 0f)
        }
        assertEquals(6.0, RawSrTuning.forSnr(Double.NEGATIVE_INFINITY).snr, 0.0)
        assertEquals(30.0, RawSrTuning.forSnr(Double.POSITIVE_INFINITY).snr, 0.0)
        assertThrows(IllegalArgumentException::class.java) { RawSrTuning.forSnr(Double.NaN) }
    }

    @Test fun endpointsMidpointAndQuarterInterpolation() {
        for ((snr, expected) in listOf(
            6.0 to doubleArrayOf(0.33, 5.0, 0.81, 1.24),
            12.0 to doubleArrayOf(0.31, 4.5, 0.785, 1.18),
            18.0 to doubleArrayOf(0.29, 4.0, 0.76, 1.12),
            30.0 to doubleArrayOf(0.25, 3.0, 0.71, 1.0))) {
            val t = RawSrTuning.forSnr(snr)
            assertArrayEquals(expected, doubleArrayOf(t.kDetail, t.kDenoise, t.dTh, t.dTr), 1e-12)
        }
        assertEquals(RawSrTuning.forSnr(6.0), RawSrTuning.forSnr(0.0))
        assertEquals(RawSrTuning.forSnr(30.0), RawSrTuning.forSnr(100.0))
    }

    @Test fun noiseAgnosticConstantsAndDebugSummary() {
        for (snr in listOf(6.0, 14.0, 22.0, 30.0)) {
            val t = RawSrTuning.forSnr(snr)
            assertEquals(4.0, t.kStretch, 0.0); assertEquals(2.0, t.kShrink, 0.0)
            assertEquals(0.12, t.t, 0.0); assertEquals(2.0, t.s1, 0.0)
            assertEquals(12.0, t.s2, 0.0); assertEquals(0.8, t.mTh, 0.0)
        }
        val message = RawSrTuning.estimate(0.5, null).debugSummary()
        for (name in listOf("MISSING_PROFILE", "linearSnr", "clippedSnr", "tileRaw", "tileQuads",
            "kDetail", "kDenoise", "Dth", "Dtr", "kStretch", "kShrink", "t=", "s1=", "s2=", "Mth"))
            assertTrue(message.contains(name))
    }

    @Test fun usesLinearSnrNotDbAndNotQuadAveragingGain() {
        val profile = ImmutableDoubleValues(DoubleArray(8) { if (it % 2 == 0) 0.0 else 0.0025 })
        val estimate = RawSrTuning.estimate(0.5, profile)
        assertEquals(0.0025, estimate.variance!!, 1e-12)
        assertEquals(10.0, estimate.linearSnr!!, 1e-12)
        assertEquals(64, estimate.tuning.rawTileSize) // dB or quad-noise incorrectly gives 20
        assertEquals(RawSrTuning.Status.ESTIMATED, estimate.status)
    }

    @Test fun rgbDngNoiseWeightsGreenTwiceAndMatchesFourCfaPairs() {
        val rgb = ImmutableDoubleValues(doubleArrayOf(0.001, 0.0001, 0.002, 0.0002, 0.004, 0.0004))
        val cfa = ImmutableDoubleValues(doubleArrayOf(0.002, 0.0002, 0.004, 0.0004,
            0.001, 0.0001, 0.002, 0.0002)) // GBRG order
        val estimate = RawSrTuning.estimate(0.4, rgb)
        assertEquals(estimate, RawSrTuning.estimate(0.4, cfa))
        val variance = (0.0005 + 2 * 0.001 + 0.002) / 4
        assertEquals(variance, estimate.variance!!, 1e-12)
        assertEquals(0.4 / sqrt(variance), estimate.linearSnr!!, 1e-12)
    }

    @Test fun missingAndInvalidModelsUseExplicitLowSnrFallback() {
        assertEquals(RawSrTuning.Status.MISSING_PROFILE, RawSrTuning.estimate(0.5, null).status)
        for (profile in listOf(DoubleArray(2), DoubleArray(7),
            DoubleArray(8) { Double.NaN }, DoubleArray(8) { Double.POSITIVE_INFINITY },
            doubleArrayOf(1.0, -0.1, 1.0, 0.0, 1.0, 0.0))) {
            val result = RawSrTuning.estimate(0.5, ImmutableDoubleValues(profile))
            assertEquals(RawSrTuning.Status.INVALID_PROFILE, result.status)
            assertEquals(6.0, result.tuning.snr, 0.0); assertNull(result.linearSnr)
        }
        assertEquals(RawSrTuning.Status.INVALID_BRIGHTNESS, RawSrTuning.estimate(Double.NaN, null).status)
    }

    @Test fun blackClippedAndNoiselessInputsHaveDefinedBehavior() {
        val profile = ImmutableDoubleValues(DoubleArray(8))
        assertEquals(6.0, RawSrTuning.estimate(-0.1, profile).tuning.snr, 0.0)
        assertEquals(30.0, RawSrTuning.estimate(0.5, profile).tuning.snr, 0.0)
        assertEquals(RawSrTuning.Status.ZERO_NOISE, RawSrTuning.estimate(0.5, profile).status)
        assertEquals(1.0, RawSrTuning.estimate(1.1, profile).brightness!!, 0.0)
        val lowNoise = RawSrTuning.estimate(0.5, ImmutableDoubleValues(DoubleArray(8) { 0.0001 }))
        val highNoise = RawSrTuning.estimate(0.5, ImmutableDoubleValues(DoubleArray(8) { 0.01 }))
        assertTrue(highNoise.tuning.snr < lowNoise.tuning.snr)
        assertTrue(highNoise.tuning.rawTileSize > lowNoise.tuning.rawTileSize)
    }

    @Test fun packedReferenceUsesFrozenBlackWhiteCropAndPlanePositionBeforeLensGains() {
        for (pattern in BayerPattern.entries) {
            val layout = RawPlaneLayout(6, 6, 16, 2, 1, 0)
            val normalization = RawNormalization(pattern, listOf(20f, 40f, 60f, 80f), 1020f)
            val bytes = ByteBuffer.allocateDirect(100).order(ByteOrder.nativeOrder()).apply { position(4) }
            for (y in 0..5) for (x in 0..5) {
                val black = normalization.blackAt(x + 1, y).toInt()
                bytes.putShort(4 + y * 16 + x * 2, (black + (1020 - black) / 2).toShort())
            }
            val profile = ImmutableDoubleValues(DoubleArray(8) { if (it % 2 == 0) 0.0 else 0.0025 })
            val lens = LensShadingModel(1, 1, FloatArray(4) { 2f }, IntRectSnapshot(0, 0, 8, 8))
            val frame = RawSrPackedFrame(bytes, layout, RawCrop(1, 1, 4, 4), normalization, lens, profile)
            val before = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
            val first = RawSrTuning.fromReference(frame)
            assertEquals(0.5, first.brightness!!, 0.0)
            assertEquals(10.0, first.linearSnr!!, 1e-12)
            assertEquals(first, RawSrTuning.fromReference(frame))
            assertEquals(4, bytes.position())
            assertArrayEquals(before, ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) })
        }
    }
}
