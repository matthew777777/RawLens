// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** rawr AE ports: analog ISO cap + float digital gain, request deadband, VF residual gain. */
class ProgramAeAnalogResidualTest {
    private val limits = ExposureBalanceLimits(50, 6400, 100_000L, 66_666_666L, 33_333_333L)
    private val profile = ProgramAeProfile(digitalGainEv = 0f)

    private fun sample(green: Float): EttrRawSample {
        val bins = Array(4) { IntArray(256) }
        for (c in 1..2) { bins[c][10] = 950; bins[c][200] = 50 }
        return EttrRawSample(EttrChannelLevels(0.5f, 0.5f, 0.5f, 0.5f), bins, IntArray(4),
            intArrayOf(1000, 1000, 1000, 1000), centerWeightedGreen = green)
    }

    @After fun clear() {
        DigitalGain.programStillTotal = 0.0
        DigitalGain.requestedIso = null
    }

    @Test fun darkSceneStopsAtAnalogMaxAndKeepsTheSameReach() {
        val capped = ProgramAeLoop()
        capped.onSample(sample(0.001f), profile, limits, 100, 10_000_000L, 0L, analogIsoMax = 1600)
        capped.tick(1)
        val plain = ProgramAeLoop()
        plain.onSample(sample(0.001f), profile, limits, 100, 10_000_000L, 0L)
        plain.tick(1)
        assertEquals(1600, capped.iso)
        assertTrue(capped.isoLimited)
        assertTrue("digital ${capped.digitalGain}", capped.digitalGain > 1f)
        // Digital gain Off in the profile: the analog cap still reaches the old sensor max.
        assertEquals(plain.stillTotal, capped.stillTotal, plain.stillTotal * 0.02)
    }

    @Test fun brightSceneUnaffectedByAnalogCap() {
        val loop = ProgramAeLoop()
        loop.onSample(sample(0.2f), profile, limits, 100, 10_000_000L, 0L, analogIsoMax = 1600)
        loop.tick(1)
        assertTrue(loop.iso!! <= 1600)
        assertEquals(1f, loop.digitalGain, 0f)
    }

    @Test fun lockedIsoAboveAnalogRunsAnalogPlusDigitalAtTheUsersShutter() {
        val locked = profile.copy(lockMode = ProgramLockMode.ISO_LOCK, lockedIso = 6400)
        val loop = ProgramAeLoop()
        loop.onSample(sample(0.02f), locked, limits, 100, 10_000_000L, 0L, analogIsoMax = 1600)
        loop.tick(1)
        assertEquals(1600, loop.iso)
        assertEquals(4f, loop.digitalGain, 0.05f)
        // Shutter as if ISO 6400 were real: total / 6400.
        assertEquals(loop.filteredTotal / 6400, loop.shutterNanos!!.toDouble(), loop.filteredTotal / 6400 * 0.01)
    }

    @Test fun tinyChangesAreNotResubmitted() {
        val loop = ProgramAeLoop()
        loop.onSample(sample(0.04f), profile, limits, 100, 10_000_000L, 0L)
        repeat(12) { loop.tick(1) }
        val iso = loop.iso!!
        val shutter = loop.shutterNanos!!
        val before = loop.targetTotal
        // Same metered frame, ~3 % darker: outside the AGC stable region, so the
        // filter moves sqrt(0.2) x 3 % = 1.3 %, under the 1/48 EV (1.45 %) deadband.
        loop.onSample(sample(0.04f / 1.03f), profile, limits, 100, 10_000_000L, 0L)
        val ratio = loop.targetTotal / before
        assertTrue("target moved $ratio", ratio > 1.021 && ratio < 1.032)
        val (changed, _) = loop.tick(1)!!
        assertFalse(changed)
        assertEquals(iso, loop.iso)
        assertEquals(shutter, loop.shutterNanos)
        // A large step still goes through.
        loop.onSample(sample(0.01f), profile, limits, 100, 10_000_000L, 0L)
        assertTrue(loop.tick(1)!!.first)
    }

    @Test fun viewfinderResidualBringsAnyFrameToTheStillExposure() {
        DigitalGain.programStillTotal = 800.0 * 20_000_000L
        assertEquals(2f, DigitalGain.viewfinderGainFor(400, 20_000_000L, 6400), 1e-4f)
        assertEquals(0.5f, DigitalGain.viewfinderGainFor(1600, 20_000_000L, 6400), 1e-4f)
        assertEquals(16f, DigitalGain.viewfinderGainFor(10, 1_000_000L, 6400), 0f)
        DigitalGain.requestedIso = 3200   // manual ISO: plain digital gain path
        assertEquals(1f, DigitalGain.viewfinderGainFor(400, 20_000_000L, 6400), 0f)
    }

    @Test fun noProgramMeansPlainDigitalGain() {
        DigitalGain.programStillTotal = 0.0
        DigitalGain.autoGain = 1f
        assertEquals(1f, DigitalGain.viewfinderGainFor(400, 20_000_000L, 6400), 0f)
    }
}
