// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Covers the exact packing contract shared with python/rawnind-train:
 * canonical [R,G1,G2,B] order, all four Bayer patterns, and the sigma
 * formula. If any of these break, the on-device model sees a different
 * domain than training and output is garbage.
 */
class RawNindPackTest {
    @Test fun canonicalPermMatchesTrainerTables() {
        // Stored [TL,TR,BL,BR] -> canonical [R,G1,G2,B] with G1 = even-row
        // green, G2 = odd-row green. This is the stored->slot direction;
        // dng_loader._TO_RGGB_PERM lists the inverse (slot->stored) form:
        // RGGB (0,1,2,3), GRBG (1,0,3,2), GBRG (2,0,3,1), BGGR (3,1,2,0).
        // RGGB/GRBG/BGGR are self-inverse; GBRG is not ([1,3,0,2] here).
        assertArrayEquals(intArrayOf(0, 1, 2, 3), RawNindPack.canonicalPerm(BayerPattern.RGGB))
        assertArrayEquals(intArrayOf(1, 0, 3, 2), RawNindPack.canonicalPerm(BayerPattern.GRBG))
        assertArrayEquals(intArrayOf(1, 3, 0, 2), RawNindPack.canonicalPerm(BayerPattern.GBRG))
        assertArrayEquals(intArrayOf(3, 1, 2, 0), RawNindPack.canonicalPerm(BayerPattern.BGGR))
    }

    @Test fun packUnpackRoundTripsEveryPattern() {
        val rng = Random(7)
        for (pattern in BayerPattern.entries) {
            val w = 64
            val h = 48
            val bayer = FloatArray(w * h) { rng.nextFloat() }
            val packed = RawNindPack.packCanonical(bayer, w, h, pattern)
            assertEquals(w / 2 * h / 2 * 4, packed.size)
            assertTrue(packed.all { it.isFinite() })
            assertArrayEquals(bayer, RawNindPack.unpackCanonical(packed, w, h, pattern), 0f)
        }
    }

    @Test fun canonicalChannelsHoldExpectedColors() {
        // 4x4 BGGR with distinct per-color values: TL=B=1, TR=G1=2 (even row),
        // BL=G2=3 (odd row), BR=R=4.
        val bayer = floatArrayOf(
            1f, 2f, 1f, 2f,
            3f, 4f, 3f, 4f,
            1f, 2f, 1f, 2f,
            3f, 4f, 3f, 4f
        )
        val packed = RawNindPack.packCanonical(bayer, 4, 4, BayerPattern.BGGR)
        assertEquals(2 * 2 * 4, packed.size)
        for (qi in 0 until 4) {
            assertEquals(4f, packed[qi * 4])      // R
            assertEquals(2f, packed[qi * 4 + 1])  // G1 (even-row green)
            assertEquals(3f, packed[qi * 4 + 2])  // G2 (odd-row green)
            assertEquals(1f, packed[qi * 4 + 3])  // B
        }
    }

    @Test fun grbgCanonicalChannelsHoldExpectedColors() {
        // 2x2 GRBG: TL=G1=10, TR=R=20, BL=B=30, BR=G2=40.
        val packed = RawNindPack.packCanonical(floatArrayOf(10f, 20f, 30f, 40f), 2, 2, BayerPattern.GRBG)
        assertArrayEquals(floatArrayOf(20f, 10f, 40f, 30f), packed, 0f)
    }

    @Test fun oddDimensionsAreRejected() {
        var threw = false
        try {
            RawNindPack.packCanonical(FloatArray(3 * 4), 3, 4, BayerPattern.RGGB)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun sigmaMatchesPoissonGaussianProfile() {
        // sqrt(2.5e-4 * 0.18 + 2.5e-6) ~= 0.00689.
        val sigma = RawNindPack.sigmaFor(0.18f, 2.5e-4f, 2.5e-6f)
        assertEquals(0.00689f, sigma, 1e-5f)
        assertEquals(0f, RawNindPack.sigmaFor(-1f, 2.5e-4f, 0f), 0f)
    }

    @Test fun aiDefaultsStayOptInWithOriginalKept() {
        val settings = DenoiseSettings()
        assertEquals(false, settings.aiEnabled)
        assertEquals(true, settings.saveOriginalDng)
    }
}
