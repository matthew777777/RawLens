// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EttrLoopTest {
    /** All quads at [hot] (max-RGB level), as the Magic Lantern projections see them. */
    private fun quads(hot: Float) = EttrQuadStats(
        IntArray(EttrQuadStats.BINS).also { it[EttrQuadStats.binOf(hot)] = 1000 }, 1000,
        if (hot >= EttrQuadStats.OVEREXPOSED_LEVEL) 1000 else 0)

    private fun sample(hot: Float, saturated: IntArray, totals: IntArray = intArrayOf(100, 100, 100, 100)) =
        EttrRawSample(EttrChannelLevels(hot, hot * 0.8f, hot * 0.8f, hot * 0.5f), emptyArray(), saturated, totals,
            quads = quads(hot))

    @Test fun clipStatePoolsGreensAndRanksHottestTwo() {
        val loop = EttrLoop(EttrSettings(enabled = true))
        // R 10/100, G (5+15)/200 = 10 %, B 30/100.
        loop.update(sample(0.5f, intArrayOf(10, 5, 15, 30)), 100, 10_000_000L,
            50, 6400, 100_000L, 1_000_000_000L, 66_000_000L)
        assertEquals(0.30f, loop.clipHot, 1e-6f)
        assertEquals(0.10f, loop.clipSecond, 1e-6f)
        assertEquals(0.5f, loop.hottest, 0f)
        assertTrue(loop.iso != null && loop.shutterNanos != null)
    }

    @Test fun clearKeepsSettings() {
        val loop = EttrLoop(EttrSettings(enabled = true, headroomEv = 0.5f))
        loop.update(sample(0.9f, intArrayOf(0, 0, 0, 0)), 100, 10_000_000L,
            50, 6400, 100_000L, 1_000_000_000L, 66_000_000L)
        loop.clearLive()
        assertNull(loop.iso)
        assertNull(loop.shutterNanos)
        assertTrue(loop.clipHot.isNaN())
        assertEquals(0.5f, loop.settings.headroomEv, 0f)
    }
}
