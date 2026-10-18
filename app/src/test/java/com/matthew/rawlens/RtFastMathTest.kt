// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity against RT xlogf / xexpf / pow_F (sleef.h FastMath).
 *
 * Goldens below were machine-generated from the float32 C++ oracle using the
 * verbatim helpers (see /tmp/hlmap_gf.cpp); the generator script that
 * embedded them read /tmp/rt_fm_goldens.txt, so no hand transcription is
 * involved. Assertion is per-value ULP distance: mlaf is a plain x*y+z
 * expression in both languages, so either side may fuse it.
 */
class RtFastMathTest {
    @Test
    fun logExpPowMatchOracle() {
        val xs = listOf(
            0f, 1e-30f, 1e-10f, 0.5f, 1f, 2f, 10f, 100f, 1e10f, 1e30f,
            -1f, -104f, -103.9f, 0.001f, 65535f
        )
        val logGot = xs.map { RtFastMath.xlogf(it).toBits().toLong() and 0xffffffffL }
        val expGot = xs.map { RtFastMath.xexpf(it).toBits().toLong() and 0xffffffffL }
        val powXs = listOf(0f, 0.25f, 0.5f, 1f)
        val powGot = powXs.map { RtFastMath.powF(it, 2.2f).toBits().toLong() and 0xffffffffL }
        var worst = 0L
        var worstName = ""
        fun check(name: String, e: Long, a: Long) {
            val eb = e.toInt()
            val ab = a.toInt()
            if (eb == 0x7fc00000.toInt() || ab == 0x7fc00000.toInt()) {
                assertEquals("$name NaN bits", e, a)
                return
            }
            if (eb == 0x7f800000.toInt() || eb == 0xff800000.toInt() ||
                ab == 0x7f800000.toInt() || ab == 0xff800000.toInt()
            ) {
                assertEquals("$name inf bits", e, a)
                return
            }
            fun t(u: Long) = if (u and 0x80000000L == 0L) u else 0x100000000L - u
            val d = kotlin.math.abs(t(e) - t(a))
            if (d > worst) {
                worst = d
                worstName = name
            }
        }
        for (k in xs.indices) {
            check("xlogf", GOLDEN_LOG[k].toLong(16), logGot[k])
            check("xexpf", GOLDEN_EXP[k].toLong(16), expGot[k])
        }
        for (k in powXs.indices) {
            check("powF", GOLDEN_POW[k].toLong(16), powGot[k])
        }
        assertTrue("sweep worst=$worst at $worstName", worst <= SWEEP_BOUND)
    }

    private companion object {
        const val SWEEP_BOUND = 4L
        val GOLDEN_LOG = listOf(
            "ff800000",
            "c28a27b5",
            "c1b834f2",
            "bf317218",
            "00000000",
            "3f317218",
            "40135d8e",
            "40935d8e",
            "41b834f2",
            "428a27b5",
            "7fc00000",
            "7fc00000",
            "7fc00000",
            "c0dd0c55",
            "41317208"
        )
        val GOLDEN_EXP = listOf(
            "3f800000",
            "3f800000",
            "3f800000",
            "3fd3094c",
            "402df854",
            "40ec7326",
            "46ac14ef",
            "7f800000",
            "7f800000",
            "7f800000",
            "3ebc5ab2",
            "00000000",
            "00000001",
            "3f8020c9",
            "7f800000"
        )
        val GOLDEN_POW = listOf(
            "00000000",
            "3d420300",
            "3e5edc67",
            "3f800000"
        )
    }
}
