// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** RawSrFftPlan tests: stage chains, digit-reverse, twiddle rows. */
class RawSrFftPlanTest {
    @Test fun singleStageForPrimesAndIdentityForOne() {
        assertTrue(RawSrFftPlan.stages(1).isEmpty())
        for (p in listOf(2, 3, 5, 7, 11, 13, 17, 19)) {
            val chain = RawSrFftPlan.stages(p)
            assertEquals(listOf(RawSrFftPlan.Stage(p, p)), chain)
        }
    }

    @Test fun smallestFactorFirstAtEveryLevel() {
        // 4000 = 2^5 * 5^3: smallest factor first, m chain divides down.
        val chain = RawSrFftPlan.stages(4000)
        assertEquals(listOf(2, 2, 2, 2, 2, 5, 5, 5), chain.map { it.radix })
        assertEquals(listOf(4000, 2000, 1000, 500, 250, 125, 25, 5), chain.map { it.m })
        // 3000 = 2^3 * 3 * 5^3.
        assertEquals(listOf(2, 2, 2, 3, 5, 5, 5), RawSrFftPlan.stages(3000).map { it.radix })
        // 17*19 composite keeps factor order.
        assertEquals(listOf(17, 19), RawSrFftPlan.stages(17 * 19).map { it.radix })
    }

    @Test fun chainsCoverCameraSizes() {
        for (n in listOf(3000, 3060, 3072, 3648, 4000, 4032, 4080, 4096, 5472, 6000, 6144, 8000, 8192)) {
            val chain = RawSrFftPlan.stages(n)
            assertEquals(n, chain.fold(1) { acc, s -> acc * s.radix })
            assertTrue(chain.size <= RawSrFftPlan.MAX_LEVELS)
        }
    }

    @Test fun largePrimeFactorRejectedLikeCpu() {
        try {
            RawSrFftPlan.stages(23)
            assertTrue("expected rejection of prime 23", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("prime factor"))
        }
        try {
            RawSrFftPlan.stages(2 * 23)
            assertTrue("expected rejection of factor 23", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("prime factor"))
        }
    }

    @Test fun flattenIndexMapsNaturalToStageOrder() {
        // Independent construction: natural k has mixed-radix digits
        // d1 = k % p1 (least significant) etc.; the flattened stage
        // order carries d1 most significant. flattenIndex must map k to
        // that flattened index for every k, and the map must permute.
        for (n in listOf(12, 60, 120, 323, 3000, 4000)) {
            val factors = RawSrFftPlan.stages(n).map { it.radix }
            val seen = BooleanArray(n)
            for (k in 0 until n) {
                // Place-value spelling (independent shape from the
                // Horner fold under test): d1 carries the largest place.
                val digits = IntArray(factors.size)
                var rest = k
                for (s in factors.indices) {
                    digits[s] = rest % factors[s]
                    rest = (rest - digits[s]) / factors[s]
                }
                var o = 0
                var place = 1
                for (s in factors.indices.reversed()) {
                    o += digits[s] * place
                    place *= factors[s]
                }
                assertEquals("n=$n k=$k", o, RawSrFftPlan.flattenIndex(k, factors))
                assertTrue("n=$n duplicate flat $o", !seen[o])
                seen[o] = true
            }
            assertTrue("n=$n", seen.all { it })
        }
    }

    @Test fun flattenIndexIsIdentityForSingleStage() {
        // One stage: flattened order is already natural (o = k1).
        assertEquals(0, RawSrFftPlan.flattenIndex(0, listOf(5)))
        assertEquals(4, RawSrFftPlan.flattenIndex(4, listOf(5)))
        assertEquals(0, RawSrFftPlan.flattenIndex(0, emptyList()))
    }

    @Test fun twiddleRowsMatchCpuTablesRoundedToFloat() {
        for (n in listOf(2, 5, 19, 125, 4000)) {
            for (inverse in listOf(false, true)) {
                val row = RawSrFftPlan.twiddleRow(n, inverse)
                assertEquals(n * 4, row.size)
                for (k in 0 until n) {
                    val angle = 2.0 * PI * k / n
                    assertEquals(cos(angle).toFloat(), row[k * 4], 0f)
                    val want = (if (inverse) sin(angle) else -sin(angle)).toFloat()
                    assertEquals(want, row[k * 4 + 1], 0f)
                    assertEquals(0f, row[k * 4 + 2], 0f)
                    assertEquals(0f, row[k * 4 + 3], 0f)
                }
            }
        }
    }
}
