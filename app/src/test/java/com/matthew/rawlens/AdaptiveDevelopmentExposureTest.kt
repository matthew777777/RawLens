// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDevelopmentExposureTest {
    @Test
    fun placesOrdinaryMidtonesNearTargetWithinBoundedCorrection() {
        val under = AdaptiveDevelopmentExposure.analyze(cfa(FloatArray(4096) { 0.09f }))
        assertEquals(1.0, under.correctionEv, 0.02)
        val bright = AdaptiveDevelopmentExposure.analyze(cfa(FloatArray(4096) { 0.5f }))
        assertEquals(-1.474, bright.correctionEv, 0.02)
        assertTrue(under.correctionEv in -1.5..1.5 && bright.correctionEv in -1.5..1.5)
    }

    @Test
    fun sparseSpecularsDoNotDominateLogAverage() {
        val values = FloatArray(10_240) { if (it < 51) 1f else 0.09f }
        val result = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertTrue("specular tail suppressed useful lift: $result", result.correctionEv > 0.8)
    }

    @Test
    fun lowKeySceneReceivesOnlyConservativePositiveLift() {
        val result = AdaptiveDevelopmentExposure.analyze(cfa(FloatArray(4096) { 0.005f }))
        assertTrue(result.lowKey)
        assertTrue(result.correctionEv in 0.0..1.3)
    }

    @Test
    fun brightSkyGardenKeepsHighlightHeadroomInsteadOfFullLift() {
        // Garden DNG IMG_20260919_151022_831: dark foliage (geom ~0.03) + broad sky
        // (p95=0.45, p99.5=0.69). Old HEADROOM=4.0 gave +1.5EV -> sky 1.95x white.
        // New hard 1.0 + soft p95->0.85 guard must keep correction <= 0.
        val values = FloatArray(10_240) { index ->
            when {
                index < 9_216 -> 0.03f
                index < 10_112 -> 0.5f
                else -> 0.75f
            }
        }
        val result = AdaptiveDevelopmentExposure.analyze(cfa(values))
        // Hard spike guard (p99.5->1.0) + soft broad guard (p95->0.85): tail must
        // land at/below white instead of the old +1.5EV blowout (0.75->2.12).
        val pushedSpike = result.highlight * Math.pow(2.0, result.correctionEv)
        assertTrue("garden sky blown out: $result pushed=$pushedSpike", pushedSpike <= 1.001)
        assertTrue("over-darkened: $result", result.correctionEv >= -1.5)
        assertTrue("old headroom would have lifted +1.5EV", result.correctionEv < 1.0)
        assertTrue(result.highlight in 0.5..1.0)
    }

    @Test
    fun hdrInteriorLiftsMidsWhileSpikesRideToAgxTop() {
        // Observed HDR file shape (IMG_20261008_142321_830, ±2EV): dark
        // interior (compensated logavg ~0.014) + bright tail (p95 ~0.11,
        // clipped spikes ~1.1). Legacy tuning pins spikes at 1.0 and the
        // whole frame stays dark; HDR tuning must lift past the LDR
        // budget with spikes inside AgX's range and broad mass inside
        // the shoulder.
        val values = FloatArray(10_240) { index ->
            when {
                index < 9_216 -> 0.0036f
                index < 10_112 -> 0.028f
                else -> 0.28f
            }
        }
        val result = AdaptiveDevelopmentExposure.analyzeHdr(cfa(values), 4.0)
        // Broad guard binds: log2(0.85/0.112) ≈ +2.92 — past the LDR
        // budget, with broad mass on the LDR anchor and spikes inside
        // AgX's range.
        assertEquals(2.92, result.correctionEv, 0.03)
        val pushedSpike = result.highlight * Math.pow(2.0, result.correctionEv)
        assertTrue("spike past AgX top: $pushedSpike", pushedSpike <= 16.01)
        assertEquals(1.12, result.highlight, 0.02)
    }

    @Test
    fun hdrNightWithoutBrightContentKeepsLdrBudget() {
        // Dim merge with no bright tail: the DR budget must hold the lift
        // at the LDR budget instead of day-ifying the night. The low-key
        // gate runs on compensated values (median 0.02 is not keyed).
        val dim = AdaptiveDevelopmentExposure.analyzeHdr(cfa(FloatArray(4096) { 0.002f }), 4.0)
        assertTrue(dim.lowKey)
        assertEquals(0.375, dim.correctionEv, 1e-9)
        val evening = AdaptiveDevelopmentExposure.analyzeHdr(cfa(FloatArray(4096) { 0.005f }), 4.0)
        assertTrue(!evening.lowKey)
        assertEquals(1.5, evening.correctionEv, 1e-9)
        val brighter = AdaptiveDevelopmentExposure.analyzeHdr(cfa(FloatArray(4096) { 0.008f }), 4.0)
        assertTrue(!brighter.lowKey)
        assertEquals(1.5, brighter.correctionEv, 1e-9)
    }

    @Test
    fun hdrUniformContentMatchesLegacyPlacement() {
        // Content that fits LDR develops identically on both paths.
        val values = FloatArray(4096) { 0.1f }
        val hdr = AdaptiveDevelopmentExposure.analyzeHdr(cfa(values), 1.0)
        val legacy = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertEquals(legacy.correctionEv, hdr.correctionEv, 1e-12)
        assertEquals(legacy.logAverage, hdr.logAverage, 1e-12)
    }

    @Test
    fun hdrGainFoldingMatchesPreamplifiedSamples() {
        val values = FloatArray(4096) { 0.002f + (it % 64) * 0.0001f }
        val folded = AdaptiveDevelopmentExposure.analyzeHdr(cfa(values), 4.0)
        val amplified = AdaptiveDevelopmentExposure.analyzeHdr(
            cfa(FloatArray(values.size) { values[it] * 4f }), 1.0)
        assertEquals(amplified.correctionEv, folded.correctionEv, 1e-12)
        assertEquals(amplified.logAverage, folded.logAverage, 1e-12)
    }

    @Test
    fun hdrTuningBoundsAndGainValidation() {
        val bounded = HdrAdaptiveTuning(
            spikeHeadroom = 100.0, broadHeadroom = 0.1,
            maxCorrectionEv = 20.0, ldrLiftBudgetEv = -1.0, ldrFitRangeEv = 100.0
        ).bounded()
        assertEquals(32.0, bounded.spikeHeadroom, 0.0)
        assertEquals(0.6, bounded.broadHeadroom, 0.0)
        assertEquals(8.0, bounded.maxCorrectionEv, 0.0)
        assertEquals(0.0, bounded.ldrLiftBudgetEv, 0.0)
        assertEquals(8.0, bounded.ldrFitRangeEv, 0.0)
        try {
            HdrAdaptiveTuning(spikeHeadroom = Double.NaN)
            assertTrue("NaN headroom accepted", false)
        } catch (expected: IllegalArgumentException) { /* required */ }
        try {
            AdaptiveDevelopmentExposure.analyzeHdr(cfa(FloatArray(4096) { 0.1f }), 0.0)
            assertTrue("zero gain accepted", false)
        } catch (expected: IllegalArgumentException) { /* required */ }
    }

    @Test
    fun ettrVerifiedBrightFrameKeepsZeroLift() {
        // ETTR shape (IMG_20261004 whites): dim mids (big legacy need) with
        // the hot tail confirming ETTR-like brightness (p99.5 ~0.78, above
        // the 0.45 verify level). Legacy lifts +0.35 and washes the sky;
        // verified ETTR develop must hold zero — the exposure is already
        // right, and a bright haze gradient needs ~0 EV to stay blue.
        val values = FloatArray(4096) { index ->
            if (index < 3686) 0.05f else 0.3f + (index - 3686) / 410f * 0.51f
        }
        val legacy = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertEquals(0.353, legacy.correctionEv, 0.03)
        val ettr = AdaptiveDevelopmentExposure.analyze(cfa(values), ettrHeadroomEv = 0.3f)
        assertEquals(0.0, ettr.correctionEv, 1e-9)
    }

    @Test
    fun ettrBrightFrameVerifiesRegardlessOfConvergence() {
        // Hot tail below the ETTR target (loop still tracking, like the
        // 175945 frame) but bright enough to verify (p99.5 ~0.62): what
        // matters for rendering is the absolute level, not the distance
        // to target — develop holds zero instead of the legacy +0.69.
        val values = FloatArray(4096) { index ->
            if (index < 3686) 0.05f else 0.3f + (index - 3686) / 410f * 0.34f
        }
        val legacy = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertEquals(0.685, legacy.correctionEv, 0.03)
        val ettr = AdaptiveDevelopmentExposure.analyze(cfa(values), ettrHeadroomEv = 0.3f)
        assertEquals(0.0, ettr.correctionEv, 1e-9)
    }

    @Test
    fun ettrMidDarkFrameFallsBackToResidualCap() {
        // Below the verify level (p99.5 ~0.31) the residual-to-target tier
        // governs: +1.40 completes toward the ETTR target without reaching
        // the legacy +1.5 clamp — a darker frame still gets its lift.
        val values = FloatArray(4096) { index ->
            if (index < 3686) 0.03f else 0.1f + (index - 3686) / 410f * 0.22f
        }
        val legacy = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertEquals(1.5, legacy.correctionEv, 1e-9)
        val ettr = AdaptiveDevelopmentExposure.analyze(cfa(values), ettrHeadroomEv = 0.3f)
        assertEquals(1.40, ettr.correctionEv, 0.03)
    }

    @Test
    fun ettrDarkFrameKeepsLegacyClamp() {
        // Dark/stale ETTR frame (no hot tail): the residual cap is loose,
        // so the legacy ±1.5 clamp still governs — never darker or
        // brighter than a non-ETTR frame with the same histogram.
        val values = FloatArray(4096) { index ->
            if (index < 3891) 0.02f else 0.02f + (index - 3891) / 205f * 0.13f
        }
        val legacy = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertEquals(1.5, legacy.correctionEv, 1e-9)
        val ettr = AdaptiveDevelopmentExposure.analyze(cfa(values), ettrHeadroomEv = 0.3f)
        assertEquals(legacy.correctionEv, ettr.correctionEv, 1e-9)
    }

    @Test
    fun ettrZeroHeadroomHoldsWhiteAndMatchesLegacyWhenDark() {
        // Headroom 0 exposes to white itself: a bright frame verifies and
        // holds zero (any lift only clips harder), while a dark frame
        // misses verification and its residual degrades exactly to the
        // spike guard — identical to legacy develop.
        val bright = FloatArray(4096) { index ->
            if (index < 3686) 0.05f else 0.3f + (index - 3686) / 410f * 0.51f
        }
        val ettrBright = AdaptiveDevelopmentExposure.analyze(cfa(bright), ettrHeadroomEv = 0f)
        assertEquals(0.0, ettrBright.correctionEv, 1e-9)
        val dark = FloatArray(4096) { index ->
            if (index < 3891) 0.02f else 0.02f + (index - 3891) / 205f * 0.13f
        }
        val legacyDark = AdaptiveDevelopmentExposure.analyze(cfa(dark))
        val ettrDark = AdaptiveDevelopmentExposure.analyze(cfa(dark), ettrHeadroomEv = 0f)
        assertEquals(legacyDark.correctionEv, ettrDark.correctionEv, 1e-12)
        assertEquals(legacyDark.logAverage, ettrDark.logAverage, 1e-12)
        try {
            AdaptiveDevelopmentExposure.analyze(cfa(bright), ettrHeadroomEv = Float.NaN)
            assertTrue("NaN headroom accepted", false)
        } catch (expected: IllegalArgumentException) { /* required */ }
    }

    @Test
    fun sharedStateComputesOnlyTheFirstFrameCorrection() {
        val state = SharedAdaptiveExposure()
        var analyses = 0
        val first = state.resolve {
            analyses++
            AdaptiveExposureResult(0.75, -3.0, 1.0, false)
        }
        val second = state.resolve {
            analyses++
            AdaptiveExposureResult(-1.0, -1.0, 1.0, false)
        }
        assertEquals(1, analyses)
        assertEquals(first.correctionEv, second.correctionEv, 0.0)
    }

    @Test
    fun directRawSamplingMatchesCpuNormalizationAndLensShading() {
        val width = 64
        val height = 64
        val rowStride = width * 2 + 8
        val source = ByteBuffer.allocate(rowStride * height).order(ByteOrder.nativeOrder())
        for (y in 0 until height) for (x in 0 until width) {
            source.putShort(y * rowStride + x * 2, (96 + (x * 7 + y * 11) % 700).toShort())
        }
        source.position(0)
        val layout = RawPlaneLayout(width, height, rowStride, 2, sensorOriginX = 3, sensorOriginY = 5)
        val normalization = RawNormalization(
            BayerPattern.GBRG, listOf(64f, 65f, 66f, 67f), 1023f
        )
        val crop = RawCrop(2, 4, 60, 56)
        val lens = LensShadingModel(
            2, 2,
            floatArrayOf(
                1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f,
                1.8f, 1.9f, 2f, 2.1f, 2.2f, 2.3f, 2.4f, 2.5f
            ),
            IntRectSnapshot(3, 5, 67, 69)
        )
        val cpu = LensShadingCorrector.applyOwnedInPlace(
            RawSensorUnpacker.unpackNormalized(source, layout, normalization, crop), lens
        ).cfa
        val reference = AdaptiveDevelopmentExposure.analyze(cpu)
        val direct = AdaptiveDevelopmentExposure.analyzeRaw(source, layout, normalization, crop, lens)

        assertEquals(reference.correctionEv, direct.correctionEv, 1e-12)
        assertEquals(reference.logAverage, direct.logAverage, 1e-12)
        assertEquals(reference.highlight, direct.highlight, 1e-12)
    }

    private fun cfa(values: FloatArray): UnpackedRawCfa {
        val width = 64
        val height = values.size / width
        return UnpackedRawCfa(
            width, height, BayerPattern.RGGB, values,
            RawCrop(0, 0, width, height)
        )
    }
}
