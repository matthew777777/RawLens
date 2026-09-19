// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class RawEttrMeterTest {
    private val limits = EttrLimits(
        isoMin = 100,
        isoMax = 6400,
        shutterMinNanos = 1_000_000L,
        shutterMaxNanos = 200_000_000L,
        safeShutterNanos = 66_000_000L
    )

    @Test
    fun underexposedSceneAsksForBrighterExposure() {
        val shift = RawEttrMeter.correctionEv(hottest = 0.25f, headroomEv = 0.3f)
        // Target is 2^-0.3 ≈ 0.81; log2(0.81/0.25) ≈ +1.7 EV.
        assertTrue("expected positive shift, was $shift", shift > 1.0)
        assertTrue("expected shift below clamp, was $shift", shift < 2.0)
    }

    @Test
    fun nearClippingSceneAsksForDarkerExposure() {
        val shift = RawEttrMeter.correctionEv(hottest = 0.95f, headroomEv = 0.3f)
        assertTrue("expected negative shift, was $shift", shift < 0.0)
        assertEquals(-0.23, shift, 0.05)
    }

    @Test
    fun sceneAtTargetNeedsNoShift() {
        val target = 2.0.pow(-0.3).toFloat()
        assertEquals(0.0, RawEttrMeter.correctionEv(target, 0.3f), 0.02)
    }

    @Test
    fun blackFrameAsksForNoShift() {
        assertEquals(0.0, RawEttrMeter.correctionEv(0f, 0.3f), 0.0)
    }

    @Test
    fun correctionStepIsClampedPerUpdate() {
        assertEquals(2.0, RawEttrMeter.correctionEv(0.01f, 0.3f), 1e-9)
        assertEquals(-2.0, RawEttrMeter.correctionEv(4f, 0.3f), 1e-9)
    }

    @Test
    fun brighteningExtendsShutterBeforeRaisingIso() {
        // +1 EV from 10 ms @ ISO 100: shutter doubles, gain stays at minimum.
        val result = RawEttrMeter.solve(100, 10_000_000L, 1.0, limits)
        assertEquals(100, result.iso)
        assertEquals(20_000_000L, result.shutterNanos)
        assertTrue(result.converged)
    }

    @Test
    fun brighteningRaisesIsoOnlyPastSafeShutter() {
        // +4 EV from 10 ms @ ISO 100 needs 160 ms; safe ceiling is 66 ms.
        val result = RawEttrMeter.solve(100, 10_000_000L, 4.0, limits)
        assertEquals(66_000_000L, result.shutterNanos)
        assertTrue("gain must take over past the safe shutter, was ${result.iso}",
            result.iso > 100)
        assertTrue(result.converged)
    }

    @Test
    fun darkeningDropsGainBeforeShorteningShutter() {
        // -1 EV from ISO 800: gain falls toward minimum instead of cutting shutter.
        val result = RawEttrMeter.solve(800, 10_000_000L, -1.0, limits)
        assertTrue("expected gain reduction, was ${result.iso}", result.iso < 800)
        assertEquals(100, result.iso)
    }

    @Test
    fun isoCeilingPrefersUnderexposureOverGainAndBlur() {
        // ETTR ISO ceiling binds first: gain stops at the cap and the shutter
        // refuses to cross the hand-motion ceiling, so the frame stays darker
        // (sharp) instead of noisier or blurred.
        val capped = limits.copy(isoMax = 200)
        val result = RawEttrMeter.solve(100, 10_000_000L, 4.0, capped)
        assertEquals(200, result.iso)
        assertEquals(66_000_000L, result.shutterNanos)
        assertFalse(result.converged)
    }

    @Test
    fun unreachableTargetReportsNotConverged() {
        val tiny = limits.copy(isoMax = 100, safeShutterNanos = 10_000_000L,
            shutterMaxNanos = 10_000_000L)
        val result = RawEttrMeter.solve(100, 10_000_000L, 2.0, tiny)
        assertFalse(result.converged)
        assertEquals(10_000_000L, result.shutterNanos)
        assertEquals(100, result.iso)
    }

    @Test
    fun stationaryHandAllowsFullCeiling() {
        val safe = RawEttrMeter.safeShutterNanos(
            motionRadiansPerSecond = 0f,
            focalLength35mmEquiv = 24f,
            oisEnabled = false,
            ceilingNanos = 66_000_000L,
            sensorMinNanos = 1_000_000L,
            sensorMaxNanos = 500_000_000L
        )
        assertEquals(66_000_000L, safe)
    }

    @Test
    fun gyroBiasAndDriftStayAtFullCeiling() {
        // Phone gyro bias instability alone is ~0.002 rad/s; readings in this band
        // carry no hand-motion information and must not shorten the shutter.
        val biased = RawEttrMeter.safeShutterNanos(
            0.005f, 24f, false, 66_000_000L, 1_000_000L, 500_000_000L
        )
        assertEquals(66_000_000L, biased)
    }

    @Test
    fun mildHandshakeKeepsFullCeiling() {
        // 0.05 rad/s is ordinary hand motion; the old 2.5 mrad allowance cut this
        // to 50 ms and pushed the shortfall into ISO.
        val safe = RawEttrMeter.safeShutterNanos(
            0.05f, 24f, false, 66_000_000L, 1_000_000L, 500_000_000L
        )
        assertEquals(66_000_000L, safe)
    }

    @Test
    fun fastMotionShortensSafeShutter() {
        val slow = RawEttrMeter.safeShutterNanos(0.02f, 24f, false, 66_000_000L, 1_000_000L, 500_000_000L)
        val fast = RawEttrMeter.safeShutterNanos(0.4f, 24f, false, 66_000_000L, 1_000_000L, 500_000_000L)
        assertTrue("fast motion must shorten the ceiling: slow=$slow fast=$fast", fast < slow)
        assertTrue(fast >= 1_000_000L)
    }

    @Test
    fun strongShakeCostsAboutOneStopNotTwo() {
        // 0.1 rad/s used to cap a 66 ms ceiling at 25 ms (2.6x ISO); it must now
        // stay within ~1 stop of the ceiling.
        val safe = RawEttrMeter.safeShutterNanos(
            0.1f, 24f, false, 66_000_000L, 1_000_000L, 500_000_000L
        )
        assertTrue("expected near-ceiling, was $safe", safe >= 33_000_000L)
        assertTrue("expected below ceiling, was $safe", safe < 66_000_000L)
    }

    @Test
    fun oisRelaxesMotionCeiling() {
        val without = RawEttrMeter.safeShutterNanos(0.2f, 24f, false, 200_000_000L, 1_000_000L, 500_000_000L)
        val with = RawEttrMeter.safeShutterNanos(0.2f, 24f, true, 200_000_000L, 1_000_000L, 500_000_000L)
        assertTrue("OIS must relax the cap: $without -> $with", with > without)
        assertEquals((without * 2).toDouble(), with.toDouble(), 2.0)
    }

    @Test
    fun longerLensShortensMotionCeiling() {
        val wide = RawEttrMeter.safeShutterNanos(0.1f, 24f, false, 500_000_000L, 1_000_000L, 1_000_000_000L)
        val tele = RawEttrMeter.safeShutterNanos(0.1f, 120f, false, 500_000_000L, 1_000_000L, 1_000_000_000L)
        assertTrue("tele must be stricter: wide=$wide tele=$tele", tele < wide)
    }

    @Test
    fun percentileIgnoresHotPixelTail() {
        val bins = IntArray(RawEttrSampler.BIN_COUNT)
        bins[200] = 9_000 // bulk of the channel
        bins[255] = 5 // a few hot pixels must not define the level
        val level = RawEttrSampler.percentileLevel(bins, 9_005, 0.999)
        val expected = (200 + 0.5f) / RawEttrSampler.BIN_COUNT
        assertEquals(expected, level, 0.002f)
    }

    @Test
    fun emptyChannelReportsZero() {
        assertEquals(0f, RawEttrSampler.percentileLevel(IntArray(256), 0, 0.999), 0f)
    }
}
