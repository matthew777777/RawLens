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
    fun singleFrameHdrBacklitInteriorLiftsWhileLdrGardenKeepsSkyGuard() {
        // Single-frame HDR scene: dark room (90% at 0.008) + dim walls (9% at
        // 0.08) + small clipped window (1% at 1.0). The legacy p99.5 spike
        // guard pinned this near 0 EV; the 1% clip budget meters p99 (0.08)
        // so the room lifts toward natural brightness with broad mass inside
        // the p95 shoulder, while only the tiny window rides into AgX white.
        val interior = FloatArray(10_240) { index ->
            when {
                index < 9_216 -> 0.008f
                index < 10_138 -> 0.08f
                else -> 1.0f
            }
        }
        val lifted = AdaptiveDevelopmentExposure.analyze(cfa(interior))
        assertTrue("HDR interior still dark: $lifted", lifted.correctionEv > 1.5)
        assertTrue("runaway lift: $lifted", lifted.correctionEv <= 2.5)
        val pushedBroad = 0.08 * Math.pow(2.0, lifted.correctionEv)
        assertTrue("broad mass pushed past shoulder: $pushedBroad", pushedBroad <= 0.86)

        // Same LDR garden fixture as brightSkyGardenKeepsHighlightHeadroom:
        // broad bright sky means the p95 guard still binds and the sky stays
        // at/below white — HDR relief must not move it.
        val garden = FloatArray(10_240) { index ->
            when {
                index < 9_216 -> 0.03f
                index < 10_112 -> 0.5f
                else -> 0.75f
            }
        }
        val gardenResult = AdaptiveDevelopmentExposure.analyze(cfa(garden))
        assertEquals(0.415, gardenResult.correctionEv, 0.05)
        val pushedSpike = gardenResult.highlight * Math.pow(2.0, gardenResult.correctionEv)
        assertTrue("garden sky blown out: $gardenResult pushed=$pushedSpike", pushedSpike <= 1.001)
    }

    @Test
    fun singleFrameNightPlatformWithLitConcreteLiftsToLitNight() {
        // Night platform/bus shape: dark scene (93% at 0.004) + lit concrete
        // (5.5% at 0.03) + lamps (1.5% at 0.8). The bright fraction straddles
        // p99, so the p99 spike guard pins the frame near +0.08 and low-key
        // damping keeps it there — far darker than a sparser 1%-lamp street
        // the guard never sees (+1.37). The night tier must relieve toward the
        // LDR cap without day-ifying, with lamps inside the tier headroom.
        val platform = FloatArray(10_240) { index ->
            when {
                index < 9_523 -> 0.004f
                index < 10_086 -> 0.03f
                else -> 0.8f
            }
        }
        val result = AdaptiveDevelopmentExposure.analyze(cfa(platform))
        assertTrue(result.lowKey)
        assertTrue("night platform still crushed: $result", result.correctionEv >= 1.0)
        assertTrue("night day-ified: $result", result.correctionEv <= 1.5)
        val pushedLamp = result.highlight * Math.pow(2.0, result.correctionEv)
        assertTrue("lamps past tier headroom: $pushedLamp", pushedLamp <= 4.01)
        // ETTR frames skip the tier so the residual cap stays exact.
        val ettr = AdaptiveDevelopmentExposure.analyze(cfa(platform), ettrHeadroomEv = 0.3f)
        assertTrue("ETTR tier leak: $ettr", ettr.correctionEv < 0.5)
    }

    @Test
    fun singleFrameNightStreetWithLampStaysNight() {
        // 99% dark street (0.004) + 1% lamp (0.8): the spike-to-midtone range
        // is large but broad mass is night-dark, so the HDR-scene exemption
        // must not fire — low-key damping keeps the night look instead of
        // day-ifying toward +2.5 EV.
        val night = FloatArray(10_240) { index -> if (index < 10_138) 0.004f else 0.8f }
        val result = AdaptiveDevelopmentExposure.analyze(cfa(night))
        assertTrue(result.lowKey)
        assertTrue("night lifted to day: $result", result.correctionEv in 1.0..1.5)
    }

    @Test
    fun hdrInteriorLiftsMidsWhileSpikesRideToAgxTop() {
        // Observed HDR file shape (IMG_20261008_142321_830, ±2EV): dark
        // interior (compensated logavg ~0.014) + bright tail (p95 ~0.11,
        // clipped spikes ~1.1). Legacy tuning pins spikes at 1.0 and the
        // whole frame stays dark; HDR tuning must lift past the LDR
        // budget with spikes inside AgX's range and broad mass inside
        // the shoulder. The meter applies the scene-linear shoulder before
        // statistics, so the reported spike is the post-shoulder 1.092 that
        // AgX actually receives (1.12 x4 compensated, knee 0.9).
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
        assertEquals(1.092, result.highlight, 0.02)
    }

    @Test
    fun brightContentMetersPostShoulderAndShoulderZeroIsPinnedFilament() {
        // Balanced frame (mids on target) with a 2% tail at 2.0x white: AgX
        // receives the shouldered 1.498, so the p99 spike guard must darken
        // by log2(1/1.498) ≈ -0.58 — not the pre-shoulder -1.0 a
        // shoulder-blind meter would demand (which would develop visibly
        // dark). Strength 0 keeps the pinned-Filament meter.
        val values = FloatArray(4096) { index ->
            when {
                index < 3_809 -> 0.18f
                index < 4_014 -> 0.5f
                else -> 2f
            }
        }
        val bright = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertEquals(-0.583, bright.correctionEv, 0.005)
        assertEquals(1.498, bright.highlight, 0.005)
        val pinned = AdaptiveDevelopmentExposure.analyze(
            cfa(values),
            tuning = AdaptiveExposureTuning(highlightShoulder = 0f)
        )
        assertEquals(-1.0, pinned.correctionEv, 1e-9)
        // Shoulder bounds: negative clamps to 0 (pinned), above 1 clamps to
        // full on both tunings.
        assertEquals(0f, AdaptiveExposureTuning(highlightShoulder = -1f).bounded().highlightShoulder, 0f)
        assertEquals(1f, AdaptiveExposureTuning(highlightShoulder = 2f).bounded().highlightShoulder, 0f)
        assertEquals(0f, HdrAdaptiveTuning(highlightShoulder = -1f).bounded().highlightShoulder, 0f)
        assertEquals(1f, HdrAdaptiveTuning(highlightShoulder = 2f).bounded().highlightShoulder, 0f)
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
    fun ettrBrightFrameCapsAtResidualToTarget() {
        // ETTR shape (IMG_20261004 whites): dim mids (big legacy need) with
        // a nearly converged hot tail (p99.5 ~0.78, just under the 0.81 loop
        // target). ETTR develop completes the tail to target (~+0.05) instead
        // of the legacy +0.35 — hot pixels stop at the level ETTR exposed
        // for, so skies keep color while mids still lift.
        val values = FloatArray(4096) { index ->
            if (index < 3686) 0.05f else 0.3f + (index - 3686) / 410f * 0.51f
        }
        // Legacy meters the p99 spike under the 1% clip budget (was p99.5);
        // the ETTR residual still completes p99.5 to the loop target.
        val legacy = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertEquals(0.400, legacy.correctionEv, 0.02)
        val ettr = AdaptiveDevelopmentExposure.analyze(cfa(values), ettrHeadroomEv = 0.3f)
        assertEquals(0.054, ettr.correctionEv, 0.02)
    }

    @Test
    fun ettrTrackingFrameKeepsPartialCompletionLift() {
        // Hot tail below the ETTR target (loop still tracking, like the
        // 175945 frame at p99.5 ~0.62): develop completes the tail to the
        // 0.81 target (+0.39) instead of the legacy +0.69 — shadows lift
        // while the hot tail stops where ETTR exposed for (regression: a
        // hard-zero tier here rendered too-dark shadows on device).
        val values = FloatArray(4096) { index ->
            if (index < 3686) 0.05f else 0.3f + (index - 3686) / 410f * 0.34f
        }
        // Legacy meters the p99 spike under the 1% clip budget (was p99.5);
        // the ETTR residual still completes p99.5 to the loop target.
        val legacy = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertEquals(0.725, legacy.correctionEv, 0.03)
        val ettr = AdaptiveDevelopmentExposure.analyze(cfa(values), ettrHeadroomEv = 0.3f)
        assertEquals(0.386, ettr.correctionEv, 0.03)
    }

    @Test
    fun ettrMidDarkFrameFallsBackToResidualCap() {
        // With a dim hot tail (p99.5 ~0.31) the residual-to-target cap
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
    fun ettrZeroHeadroomDegradesToSpikeGuard() {
        // Headroom 0 exposes to white itself, so the residual target is 1.0
        // and the ETTR cap degrades to the spike guard: it binds a half
        // percentile tighter (p99.5) than the 1% clip-budget guard (p99), so
        // on ramp-top fixtures ETTR holds the hot tail exactly at white while
        // legacy lets the top percent ride a touch past — skies stay blue
        // either way (the cap only bites when ETTR deliberately kept headroom
        // below white).
        val bright = FloatArray(4096) { index ->
            if (index < 3686) 0.05f else 0.3f + (index - 3686) / 410f * 0.51f
        }
        val legacyBright = AdaptiveDevelopmentExposure.analyze(cfa(bright))
        val ettrBright = AdaptiveDevelopmentExposure.analyze(cfa(bright), ettrHeadroomEv = 0f)
        assertEquals(0.400, legacyBright.correctionEv, 0.02)
        assertEquals(0.354, ettrBright.correctionEv, 0.02)
        assertTrue(ettrBright.correctionEv <= legacyBright.correctionEv)
        val pushedTail = ettrBright.highlight * Math.pow(2.0, ettrBright.correctionEv)
        assertTrue("headroom-0 tail past white: $pushedTail", pushedTail <= 1.001)
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

    @Test
    fun mergedUniformHdrPlusMetersThroughLdrPath() {
        // HDR+ shape (IMG_20261017_115155_950 daylight field): dull mids
        // (logavg mid ~0.056, wants +1.68) + broad sky (p95 ~0.15) + cloud
        // spikes (p99.5 ~0.39). A same-exposure average carries nothing above
        // white, so the merged JPEG must meter through the single-frame LDR
        // path on the merged CFA itself: the LDR clamp binds (+1.5) instead
        // of the fixed 0 EV the merged path used, the p99 guard level stays
        // at/below white, and only the sub-1% cloud tail rides into AgX white
        // under the 1% clip budget — exactly what single-frame would do.
        val values = FloatArray(10_240) { index ->
            when {
                index < 8_704 -> 0.05f
                index < 9_728 -> 0.15f
                index < 10_180 -> 0.24f
                else -> 0.387f
            }
        }
        val settings = RawDevelopmentSettings(adaptiveExposureStrength = 1f)
        val resolved = resolveMergedAdaptive(cfa(values), settings)
        require(resolved != null)
        assertEquals(1.5, resolved.correctionEv, 1e-9)
        val pushedTail = resolved.highlight * Math.pow(2.0, resolved.correctionEv)
        assertTrue("cloud tail past clip budget: $pushedTail", pushedTail <= 1.10)
        assertTrue("uniform merge keyed low: $resolved", !resolved.lowKey)
        // The resolver must delegate to the single-frame meter bit-exactly:
        // same domain, same statistic, no HDR remapping for uniform merges.
        val direct = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertEquals(direct.correctionEv, resolved.correctionEv, 1e-12)
        assertEquals(direct.logAverage, resolved.logAverage, 1e-12)
    }

    @Test
    fun mergedAboveWhiteContentTakesHdrPlacement() {
        // Dim merge with lens-boosted bright corners (2.3% at 1.5x): the LDR
        // white-pin would darken the whole frame to protect the corners, so
        // above-white content meters through the HDR path instead — mids lift
        // toward target while the tail rides inside AgX's range.
        val values = FloatArray(4096) { index -> if (index < 4000) 0.02f else 1.5f }
        val settings = RawDevelopmentSettings(adaptiveExposureStrength = 1f)
        val resolved = resolveMergedAdaptive(cfa(values), settings)
        require(resolved != null)
        assertEquals(3.17, resolved.correctionEv, 0.03)
        val pushedSpike = resolved.highlight * Math.pow(2.0, resolved.correctionEv)
        assertTrue("tail past AgX top: $pushedSpike", pushedSpike <= 16.01)
        // Same call the resolver must delegate to, bit-exactly.
        val hdr = AdaptiveDevelopmentExposure.analyzeHdr(cfa(values), 1.0)
        assertEquals(hdr.correctionEv, resolved.correctionEv, 1e-12)
        // …and the LDR path would have pinned it dark instead.
        val ldr = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertTrue("LDR did not pin corners: $ldr", ldr.correctionEv < 0.0)
    }

    @Test
    fun mergedAtWhiteStaysOnLdrPath() {
        // The routing boundary is strict: content peaking exactly at sensor
        // saturation keeps single-frame placement (near-zero here); only
        // above-white signal takes the HDR path.
        val values = FloatArray(4096) { index -> if (index < 4000) 0.02f else 1.0f }
        val settings = RawDevelopmentSettings(adaptiveExposureStrength = 1f)
        val resolved = resolveMergedAdaptive(cfa(values), settings)
        require(resolved != null)
        assertTrue("at-white merge moved: $resolved", resolved.correctionEv in -0.05..0.1)
        val direct = AdaptiveDevelopmentExposure.analyze(cfa(values))
        assertEquals(direct.correctionEv, resolved.correctionEv, 1e-12)
    }

    @Test
    fun mergedZeroStrengthKeepsLegacyFixedExposure() {
        // Strength 0 (e.g. MANUAL mode) must preserve the legacy fixed-EV
        // merged develop: no analysis runs at all.
        val values = FloatArray(4096) { 0.02f }
        val settings = RawDevelopmentSettings(adaptiveExposureStrength = 0f)
        assertTrue(resolveMergedAdaptive(cfa(values), settings) == null)
    }

    @Test
    fun mergedHdrTuningTakesPrecedenceOverLdrStrength() {
        // HDR brackets keep HDR-tuned placement even when LDR strength is
        // armed: spikes ride toward AgX's range instead of pinning at white.
        val values = FloatArray(10_240) { index ->
            when {
                index < 9_216 -> 0.0036f
                index < 10_112 -> 0.028f
                else -> 0.28f
            }
        }
        val settings = RawDevelopmentSettings(
            exposureEv = 2.0,
            adaptiveExposureStrength = 1f,
            hdrAdaptiveTuning = HdrAdaptiveTuning()
        )
        val resolved = resolveMergedAdaptive(cfa(values), settings)
        require(resolved != null)
        assertEquals(2.92, resolved.correctionEv, 0.03)
        val pushedSpike = resolved.highlight * Math.pow(2.0, resolved.correctionEv)
        assertTrue("HDR spike past AgX top: $pushedSpike", pushedSpike <= 16.01)
    }

    @Test
    fun outputSettingsBroadGuardReproducesAnchorAndTracksAgxWindow() {
        // The broad-mass guard is the encoded 0.85 anchor re-solved per AgX
        // settings: defaults reproduce 0.85 exactly (no behavior change for
        // existing saves), a narrowed highlight window tightens the guard so
        // broad skies keep gradation, and a widened window loosens it.
        val base = AdaptiveExposureTuning.fromOutputSettings(JpegOutputSettings())
        assertEquals(0.85, base.highlightSoftHeadroom, 1e-4)
        assertEquals(1f, base.highlightShoulder, 0f)
        val narrow = AdaptiveExposureTuning.fromOutputSettings(
            JpegOutputSettings(agxShadowEv = 4f, agxHighlightEv = 3f)
        )
        assertTrue("narrow window did not tighten: $narrow", narrow.highlightSoftHeadroom < 0.85)
        assertTrue("narrow guard out of range: $narrow", narrow.highlightSoftHeadroom in 0.6..0.85)
        val wide = AdaptiveExposureTuning.fromOutputSettings(
            JpegOutputSettings(agxShadowEv = 14f, agxHighlightEv = 10f)
        )
        assertTrue("wide window did not loosen: $wide", wide.highlightSoftHeadroom > 0.85)
        assertTrue("wide guard out of range: $wide", wide.highlightSoftHeadroom in 0.85..1.0)
        val flatShoulder = AdaptiveExposureTuning.fromOutputSettings(
            JpegOutputSettings(highlightShoulder = 0f)
        )
        assertEquals(0f, flatShoulder.highlightShoulder, 0f)
    }

    @Test
    fun centerWeightFollowsSubjectKeepsSkyGuard() {
        // Landscape layout: bright sky on top (rows 0..47 at 0.3), dark
        // subject center (rows 48..111 at 0.08), dark ground (0.03). The
        // center vote must lift toward the subject (more than uniform), while
        // the global broad guard still caps the sky — edges never lose
        // protection.
        val values = FloatArray(10_240) { index ->
            when (val row = index / 64) {
                in 0 until 48 -> 0.3f
                in 48 until 112 -> 0.08f
                else -> 0.03f
            }
        }
        val uniform = AdaptiveDevelopmentExposure.analyze(cfa(values))
        val weighted = AdaptiveDevelopmentExposure.analyze(
            cfa(values), tuning = AdaptiveExposureTuning(centerWeight = 2f)
        )
        assertTrue("weight did not follow subject: $weighted vs $uniform",
            weighted.correctionEv > uniform.correctionEv)
        val pushedSky = weighted.highlight * Math.pow(2.0, weighted.correctionEv)
        assertTrue("sky guard lost: $pushedSky", pushedSky <= 0.86)
        assertTrue("over-lifted: $weighted", weighted.correctionEv <= 1.5)
        assertTrue("keyed low: $weighted", !weighted.lowKey)
    }

    @Test
    fun centerWeightFlatFieldChangesNothing() {
        // Weighting rebalances votes; on a flat field every vote agrees, so
        // the placement must be (near-)identical with the weight armed.
        val flat = FloatArray(4096) { 0.05f }
        val uniform = AdaptiveDevelopmentExposure.analyze(cfa(flat))
        val weighted = AdaptiveDevelopmentExposure.analyze(
            cfa(flat), tuning = AdaptiveExposureTuning(centerWeight = 2f)
        )
        assertEquals(uniform.correctionEv, weighted.correctionEv, 1e-9)
        assertEquals(uniform.logAverage, weighted.logAverage, 1e-9)
    }

    @Test
    fun tapFocusMovesVoteCenterKeepsGuards() {
        // Same landscape layout as the center test, but the tap sits on the
        // bright sky (0.5, 0.1): the vote follows the tap, so the lift must
        // drop below the center-vote lift — while the global guards still
        // cap the sky. An explicit region-center focus must equal the null
        // default bit-exactly.
        val values = FloatArray(10_240) { index ->
            when (val row = index / 64) {
                in 0 until 48 -> 0.3f
                in 48 until 112 -> 0.08f
                else -> 0.03f
            }
        }
        val center = AdaptiveDevelopmentExposure.analyze(
            cfa(values), tuning = AdaptiveExposureTuning(centerWeight = 2f)
        )
        val tapped = AdaptiveDevelopmentExposure.analyze(
            cfa(values),
            tuning = AdaptiveExposureTuning(centerWeight = 2f, focusX = 0.5f, focusY = 0.1f)
        )
        assertTrue("tap did not move vote: $tapped vs $center",
            tapped.correctionEv < center.correctionEv)
        val pushedSky = tapped.highlight * Math.pow(2.0, tapped.correctionEv)
        assertTrue("sky guard lost: $pushedSky", pushedSky <= 0.86)
        val explicitCenter = AdaptiveDevelopmentExposure.analyze(
            cfa(values),
            tuning = AdaptiveExposureTuning(centerWeight = 2f, focusX = 0.5f, focusY = 0.5f)
        )
        assertEquals(center.correctionEv, explicitCenter.correctionEv, 1e-12)
    }

    @Test
    fun focusBoundsAndPassesThroughOutputSettings() {
        val bounded = AdaptiveExposureTuning(focusX = 1.5f, focusY = -0.2f).bounded()
        assertEquals(1f, bounded.focusX ?: Float.NaN, 0f)
        assertEquals(0f, bounded.focusY ?: Float.NaN, 0f)
        val hdr = HdrAdaptiveTuning(focusX = 2f, focusY = -1f).bounded()
        assertEquals(1f, hdr.focusX ?: Float.NaN, 0f)
        assertEquals(0f, hdr.focusY ?: Float.NaN, 0f)
        var threw = false
        try {
            AdaptiveExposureTuning(focusX = Float.NaN)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("NaN focus accepted", threw)
        val tapped = AdaptiveExposureTuning.fromOutputSettings(
            JpegOutputSettings(), 0.2f to 0.7f
        )
        assertEquals(0.2f, tapped.focusX ?: Float.NaN, 0f)
        assertEquals(0.7f, tapped.focusY ?: Float.NaN, 0f)
        val untapped = AdaptiveExposureTuning.fromOutputSettings(JpegOutputSettings())
        assertTrue(untapped.focusX == null && untapped.focusY == null)
    }

    @Test
    fun spatialWeightFollowsFocusPoint() {
        // Peak vote sits at the focus point; an explicit region center
        // equals the default call.
        val atFocus = AdaptiveDevelopmentExposure.spatialWeight(32, 80, 64, 160, 2f, 0.5f, 0.5f)
        assertEquals(3f, atFocus, 1e-3f)
        val cornerDefault = AdaptiveDevelopmentExposure.spatialWeight(0, 0, 64, 160, 2f)
        val cornerExplicit = AdaptiveDevelopmentExposure.spatialWeight(0, 0, 64, 160, 2f, 0.5f, 0.5f)
        assertEquals(cornerDefault, cornerExplicit, 0f)
        val cornerFocus = AdaptiveDevelopmentExposure.spatialWeight(0, 0, 64, 160, 2f, 0f, 0f)
        assertTrue("focus did not move peak: $cornerFocus vs $cornerDefault", cornerFocus > cornerDefault)
        val farFromFocus = AdaptiveDevelopmentExposure.spatialWeight(63, 159, 64, 160, 2f, 0f, 0f)
        assertTrue("far corner over-weighted: $farFromFocus", farFromFocus < cornerFocus)
    }

    @Test
    fun centerWeightBoundsAndPassesThroughOutputSettings() {
        assertEquals(0f, AdaptiveExposureTuning(centerWeight = -1f).bounded().centerWeight, 0f)
        assertEquals(3f, AdaptiveExposureTuning(centerWeight = 9f).bounded().centerWeight, 0f)
        assertEquals(0f, HdrAdaptiveTuning(centerWeight = -1f).bounded().centerWeight, 0f)
        val tuned = AdaptiveExposureTuning.fromOutputSettings(JpegOutputSettings(centerWeight = 1.5f))
        assertEquals(1.5f, tuned.centerWeight, 0f)
        val off = AdaptiveExposureTuning.fromOutputSettings(JpegOutputSettings())
        assertEquals(0f, off.centerWeight, 0f)
    }

    @Test fun chaseTowardSnapsBigMovesAndDampsFlap() {
        // Big scene moves snap the target in one update (no delay/staircase);
        // the viewfinder's one-pole still glides the render smoothly.
        assertEquals(1.5f, AdaptiveDevelopmentExposure.chaseToward(0f, 1.5f, 0.05f), 1e-6f)
        assertEquals(-1.5f, AdaptiveDevelopmentExposure.chaseToward(0f, -1.5f, 0.05f), 1e-6f)
        // Small errors ease in at half rate; inside the deadband it holds.
        assertEquals(0.3f, AdaptiveDevelopmentExposure.chaseToward(0f, 0.6f, 0.05f), 1e-6f)
        assertEquals(0f, AdaptiveDevelopmentExposure.chaseToward(0f, 0.04f, 0.05f), 1e-6f)
        // Flicker oscillation averages out instead of pumping: alternating
        // +/-0.4 readings settle near zero, far below the reading amplitude
        // (a slew limiter would triangle-wave the full +/-0.4).
        var v = 0f
        repeat(20) { i ->
            v = AdaptiveDevelopmentExposure.chaseToward(v, if (i % 2 == 0) 0.4f else -0.4f, 0.05f)
        }
        assertTrue("flap not damped: $v", kotlin.math.abs(v) < 0.2f)
        // Small steady errors still converge (no overshoot/oscillation).
        v = 0f
        repeat(20) { v = AdaptiveDevelopmentExposure.chaseToward(v, 0.6f, 0.05f) }
        assertEquals(0.6f, v, 0.06f)
        // Non-finite inputs never poison the display EV.
        assertEquals(0.5f, AdaptiveDevelopmentExposure.chaseToward(Float.NaN, 0.5f, 0.05f), 1e-6f)
        assertEquals(0.5f, AdaptiveDevelopmentExposure.chaseToward(0.5f, Float.NaN, 0.05f), 1e-6f)
    }
}
