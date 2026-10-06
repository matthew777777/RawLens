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
            assertEquals(raw, tuning.alignmentConfig().tileSize)
            assertEquals(3, tuning.alignmentConfig().lkIterations)
            assertEquals(4, tuning.alignmentConfig().searchRadius)
            assertEquals(0.12, tuning.alignmentConfig().maxMeanAbsoluteResidual, 0.0)
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

    @Test fun analyticAdapterConvertsLinearRatioToDb() {
        // Normalized-domain profile (reference alpha/beta convention):
        // variance 0.0025 at mean brightness 0.5 gives linear ratio 10.0,
        // converted to the dB domain `forSnr` tunes on: 20*log10(10) = 20
        // dB -> 32px tiles. (A linear-domain misread would keep 64px tiles;
        // quad-averaged noise would inflate the ratio.)
        val profile = ImmutableDoubleValues(DoubleArray(8) { if (it % 2 == 0) 0.0 else 0.0025 })
        val estimate = RawSrTuning.estimate(0.5, profile)
        assertEquals(0.0025, estimate.variance!!, 1e-12)
        assertEquals(10.0, estimate.linearSnr!!, 1e-12)
        assertEquals(20.0, estimate.tuning.snr, 1e-9)
        assertEquals(32, estimate.tuning.rawTileSize)
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

    @Test fun forSnrStaysCoupledLegacy() {
        for (snr in listOf(6.0, 18.0, 30.0)) {
            assertNull(RawSrTuning.forSnr(snr).flatSigma)
            assertNull(RawSrTuning.forSnr(snr).detailFloor)
        }
    }

    @Test fun fixedFactoryPinsEveryConstant() {
        val t = RawSrTuning.fixed(
            rawTileSize = 32, kDetail = 0.08, kDenoise = 5.0, dTh = 0.25, dTr = 0.30,
            kStretch = 1.0, kShrink = 8.0, t = 0.09, s1 = 1.5, s2 = 9.0, mTh = 0.65,
            flatSigma = 0.5, detailFloor = 0.4)
        assertEquals(32, t.rawTileSize)
        assertEquals(16, t.alignmentTileQuads)
        assertEquals(32, t.alignmentConfig().tileSize)
        assertEquals(0.08, t.kDetail, 0.0)
        assertEquals(5.0, t.kDenoise, 0.0)
        assertEquals(0.25, t.dTh, 0.0)
        assertEquals(0.30, t.dTr, 0.0)
        assertEquals(1.0, t.kStretch, 0.0)
        assertEquals(8.0, t.kShrink, 0.0)
        assertEquals(0.09, t.t, 0.0)
        assertEquals(1.5, t.s1, 0.0)
        assertEquals(9.0, t.s2, 0.0)
        assertEquals(0.65, t.mTh, 0.0)
        assertEquals(0.5, t.flatSigma!!, 0.0)
        assertEquals(0.4, t.detailFloor!!, 0.0)
    }

    @Test fun decoupledSharpPresetMatchesMeasuredExif() {
        // RAWR MF12 multiframe EXIF (2026-09-25): kDetail 0.080, kDenoise 5.0,
        // dThreshold 0.250, dTransition 0.300, kStretch 1.0, kShrink 8.0,
        // robustness T/S1/S2 0.090/1.500/9.000, motion 0.650, flat sigma 0.5.
        // Detail floor 0.4 is a RawLens latch-guardrail addition (RAWR: off).
        val t = RawSrTuning.decoupledSharp()
        assertEquals(0.08, t.kDetail, 0.0)
        assertEquals(5.0, t.kDenoise, 0.0)
        assertEquals(0.25, t.dTh, 0.0)
        assertEquals(0.30, t.dTr, 0.0)
        assertEquals(1.0, t.kStretch, 0.0)
        assertEquals(8.0, t.kShrink, 0.0)
        assertEquals(0.09, t.t, 0.0)
        assertEquals(1.5, t.s1, 0.0)
        assertEquals(9.0, t.s2, 0.0)
        assertEquals(0.65, t.mTh, 0.0)
        assertEquals(0.5, t.flatSigma!!, 0.0)
        assertEquals(0.4, t.detailFloor!!, 0.0)
    }

    @Test fun decoupledSharpAcceptsScannedFlat() {
        val t = RawSrTuning.decoupledSharp(flatSigma = 0.767, snrDb = 29.45)
        assertEquals(0.767, t.flatSigma!!, 0.0)
        assertEquals(29.45, t.snr, 0.0)
        assertEquals(0.08, t.kDetail, 0.0)
        assertEquals(5.0, t.kDenoise, 0.0)
        assertEquals(0.4, t.detailFloor!!, 0.0)
        // Bare call stays RAWR-exact (flat 0.5, label SNR 30).
        assertEquals(0.5, RawSrTuning.decoupledSharp().flatSigma!!, 0.0)
    }

    @Test fun withDetailFloorCopiesAndValidates() {
        val base = RawSrTuning.forSnr(18.0)
        val floored = base.withDetailFloor(0.4)
        assertEquals(0.4, floored.detailFloor!!, 0.0)
        assertEquals(base.kDetail, floored.kDetail, 0.0)
        assertNull(floored.flatSigma)
        for (bad in listOf(0.0, -0.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { base.withDetailFloor(bad) }
        }
    }

    @Test fun withFlatSigmaCopiesAndValidates() {
        val base = RawSrTuning.forSnr(18.0)
        val decoupled = base.withFlatSigma(0.5)
        assertEquals(0.5, decoupled.flatSigma!!, 0.0)
        assertEquals(base.kDetail, decoupled.kDetail, 0.0)
        assertEquals(base.kDenoise, decoupled.kDenoise, 0.0)
        assertEquals(base.dTh, decoupled.dTh, 0.0)
        assertEquals(base.rawTileSize, decoupled.rawTileSize)
        for (bad in listOf(0.0, -0.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { base.withFlatSigma(bad) }
        }
        val sharper = base.withKDetail(0.08)
        assertEquals(0.08, sharper.kDetail, 0.0)
        assertEquals(base.kDenoise, sharper.kDenoise, 0.0)
        assertNull(sharper.flatSigma)
        for (bad in listOf(0.0, -0.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { base.withKDetail(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawSrTuning.fixed(rawTileSize = 8, kDetail = 0.1, kDenoise = 5.0,
                dTh = 0.25, dTr = 0.3, flatSigma = 0.5)
        }
    }

    @Test fun debugSummaryNamesFlatSigma() {
        assertTrue(RawSrTuning.estimate(0.5, null).debugSummary().contains("flatSigma"))
        val summary = RawSrTuning.Estimate(
            RawSrTuning.decoupledSharp(), 0.5, 10.0, 0.0025,
            RawSrTuning.Status.ESTIMATED).debugSummary()
        assertTrue(summary, summary.contains("flatSigma=0.5"))
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
            // Code-domain Camera2-style S/O (O = 0.0025 codes): normalized
            // beta ~= 2.5e-9, so the per-sample ratio is huge (~80 dB) and
            // clips to MAX_SNR -> 16px tiles. The pinned behaviors are the
            // exact mean brightness, the clip, determinism, and the frozen
            // plane/position/lens-independence below.
            val profile = ImmutableDoubleValues(DoubleArray(8) { if (it % 2 == 0) 0.0 else 0.0025 })
            val lens = LensShadingModel(1, 1, FloatArray(4) { 2f }, IntRectSnapshot(0, 0, 8, 8))
            val frame = RawSrPackedFrame(bytes, layout, RawCrop(1, 1, 4, 4), normalization, lens, profile)
            val before = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
            val first = RawSrTuning.fromReference(frame)
            assertEquals(0.5, first.brightness!!, 0.0)
            assertTrue("linearSnr=${first.linearSnr}", first.linearSnr!! > 9000.0)
            assertEquals(30.0, first.tuning.snr, 0.0)
            assertEquals(16, first.tuning.rawTileSize)
            assertEquals(first, RawSrTuning.fromReference(frame))
            assertEquals(4, bytes.position())
            assertArrayEquals(before, ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) })
        }
    }

    @Test fun describeRecordsEveryConstantForProvenance() {
        val line = RawSrTuning.forSnr(30.0)
            .withFlatSigma(0.5).withDetailFloor(0.4).describe()
        val tuning = RawSrTuning.forSnr(30.0).withFlatSigma(0.5).withDetailFloor(0.4)
        for (key in listOf(
            "tileRaw=${tuning.rawTileSize}", "kDetail=${tuning.kDetail}",
            "kDenoise=${tuning.kDenoise}", "Dth=${tuning.dTh}", "Dtr=${tuning.dTr}",
            "kStretch=${tuning.kStretch}", "kShrink=${tuning.kShrink}",
            "t=${tuning.t}", "s1=${tuning.s1}", "s2=${tuning.s2}",
            "mTh=${tuning.mTh}", "flatSigma=0.5", "detailFloor=0.4",
            "snr=${tuning.snr}"
        )) assertTrue("missing $key", line.contains(key))
    }

    @Test fun flowRegularizeSigmaDefaultsOffAndValidates() {
        assertEquals(null, RawSrTuning.forSnr(30.0).flowRegularizeSigma)
        val tuned = RawSrTuning.forSnr(30.0).withFlowRegularizeSigma(1.5)
        assertEquals(1.5, tuned.flowRegularizeSigma!!, 0.0)
        assertTrue(tuned.describe().contains("flowReg=1.5"))
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                RawSrTuning.forSnr(30.0).withFlowRegularizeSigma(bad)
                org.junit.Assert.fail("withFlowRegularizeSigma($bad) must reject")
            } catch (_: IllegalArgumentException) { }
        }
    }

}
