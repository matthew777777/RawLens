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

    @Test fun bayerOriginIsTheRSiteOfEveryPattern() {
        assertEquals(0 to 0, RawNindPack.bayerOrigin(BayerPattern.RGGB))
        assertEquals(0 to 1, RawNindPack.bayerOrigin(BayerPattern.GRBG))
        assertEquals(1 to 0, RawNindPack.bayerOrigin(BayerPattern.GBRG))
        assertEquals(1 to 1, RawNindPack.bayerOrigin(BayerPattern.BGGR))
        for (pattern in BayerPattern.entries) {
            val (y0, x0) = RawNindPack.bayerOrigin(pattern)
            assertEquals(CfaColor.RED, pattern.colorAt(x0, y0))
        }
    }

    @Test fun bayerShiftedPackIsRgbbOrderedOnEveryPattern() {
        // Distinct value per stored quad position; after the R-shift the
        // packed channels must read [R, G1, G2, B] with G1 on working-even
        // rows — no permutation. 6x6 keeps a full quad past every origin.
        for (pattern in BayerPattern.entries) {
            val w = 6
            val h = 6
            val bayer = FloatArray(w * h) { i ->
                when (pattern.colorAt(i % w, i / w)) {
                    CfaColor.RED -> 10f
                    CfaColor.GREEN -> if ((i / w) % 2 == 0) 20f else 30f
                    CfaColor.BLUE -> 40f
                }
            }
            val pack = RawNindPack.bayerPackShifted(bayer, w, h, pattern)
            val (y0, x0) = RawNindPack.bayerOrigin(pattern)
            assertEquals(y0, pack.y0)
            assertEquals(x0, pack.x0)
            assertEquals((w - x0) / 2, pack.w2)
            assertEquals((h - y0) / 2, pack.h2)
            for (qi in 0 until pack.w2 * pack.h2) {
                assertEquals("$pattern R", 10f, pack.packed[qi * 4], 0f)
                assertEquals("$pattern B", 40f, pack.packed[qi * 4 + 3], 0f)
                // G1/G2 follow the working (RGGB-phase) rows, not the
                // sensor rows: first packed row sits on sensor row y0.
                val qy = qi / pack.w2
                val g1 = if ((y0 + qy * 2) % 2 == 0) 20f else 30f
                val g2 = if ((y0 + qy * 2 + 1) % 2 == 0) 20f else 30f
                assertEquals("$pattern G1", g1, pack.packed[qi * 4 + 1], 0f)
                assertEquals("$pattern G2", g2, pack.packed[qi * 4 + 2], 0f)
            }
        }
    }

    @Test fun bayerShiftedPackMatchesCanonicalPackOnRgbb() {
        val rng = Random(11)
        val w = 8
        val h = 6
        val bayer = FloatArray(w * h) { rng.nextFloat() }
        val shifted = RawNindPack.bayerPackShifted(bayer, w, h, BayerPattern.RGGB)
        assertEquals(0, shifted.y0)
        assertEquals(0, shifted.x0)
        assertArrayEquals(
            RawNindPack.packCanonical(bayer, w, h, BayerPattern.RGGB),
            shifted.packed, 0f
        )
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
