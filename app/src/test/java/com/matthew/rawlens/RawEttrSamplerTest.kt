// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawEttrSamplerTest {
    @Test
    fun phaseChannelLutCoversAllCfaLayouts() {
        // Index is (y&1)*2+(x&1); values are canonical R=0, Gr=1, Gb=2, B=3.
        assertArrayEquals(intArrayOf(0, 1, 2, 3), RawEttrSampler.phaseChannelLut(0)) // RGGB
        assertArrayEquals(intArrayOf(1, 0, 3, 2), RawEttrSampler.phaseChannelLut(1)) // GRBG
        assertArrayEquals(intArrayOf(1, 3, 0, 2), RawEttrSampler.phaseChannelLut(2)) // GBRG
        assertArrayEquals(intArrayOf(3, 1, 2, 0), RawEttrSampler.phaseChannelLut(3)) // BGGR
    }

    @Test
    fun invRangeMatchesDivision() {
        assertEquals(1.0 / 959.0, RawEttrSampler.invRange(1023, 64).toDouble(), 1e-6)
        // Degenerate range never divides by zero.
        assertEquals(1f, RawEttrSampler.invRange(64, 64), 0f)
        assertEquals(1f, RawEttrSampler.invRange(10, 64), 0f)
    }

    @Test
    fun normalizeSampleSubtractsBlackAndClamps() {
        val inv = RawEttrSampler.invRange(1023, 64)
        assertEquals(36.0 / 959.0, RawEttrSampler.normalizeSample(100, 64, inv).toDouble(), 1e-6)
        assertEquals(0f, RawEttrSampler.normalizeSample(10, 64, inv), 0f)
        assertEquals(1f, RawEttrSampler.normalizeSample(5000, 64, inv), 0f)
    }

    @Test
    fun zoneCoordSpansCenterToEdge() {
        assertEquals(0.0, RawEttrSampler.zoneCoord(500, 1000), 0.01)
        assertEquals(1.0, RawEttrSampler.zoneCoord(0, 1000), 0.01)
        assertEquals(1.0, RawEttrSampler.zoneCoord(999, 1000), 0.01)
        assertEquals(1.0, RawEttrSampler.zoneCoord(0, 0), 0.0)
    }

    @Test
    fun centerWeightMatchesZoneTable() {
        // Spot checks against the documented nested zones, via the same helper
        // the sampler loop uses.
        assertEquals(500.0, RawEttrSampler.centerWeight(500, 500, 1000, 1000), 0.0)
        assertEquals(50.0, RawEttrSampler.centerWeight(990, 990, 1000, 1000), 0.0)
        assertTrue(RawEttrSampler.isSpot(500, 500, 1000, 1000))
        assertTrue(!RawEttrSampler.isSpot(990, 990, 1000, 1000))
    }

    @Test
    fun meteringScanRegionCropsPerMode() {
        // Spot covers the 0.158-center rectangle.
        val spot = RawEttrSampler.meteringScanRegion(4080, 3060, ProgramMetering.SPOT)
        assertArrayEquals(intArrayOf(1717, 1288, 2362, 1771), spot)
        // Center covers the 70% zone.
        val center = RawEttrSampler.meteringScanRegion(4080, 3060, ProgramMetering.CENTER_WEIGHTED)
        assertArrayEquals(intArrayOf(612, 459, 3468, 2601), center)
        // Average covers the full frame.
        assertArrayEquals(
            intArrayOf(0, 0, 4080, 3060),
            RawEttrSampler.meteringScanRegion(4080, 3060, ProgramMetering.AVERAGE)
        )
    }

    @Test
    fun meteringScanRegionNeverDegenerates() {
        val tiny = RawEttrSampler.meteringScanRegion(4, 4, ProgramMetering.SPOT)
        assertTrue(tiny[2] > tiny[0] && tiny[3] > tiny[1])
        assertArrayEquals(intArrayOf(0, 0, 0, 0), RawEttrSampler.meteringScanRegion(0, 0, ProgramMetering.SPOT))
    }
}
