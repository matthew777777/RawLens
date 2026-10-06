// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.RawViewfinder.Companion.VfTierProbe
import org.junit.Assert.assertEquals
import org.junit.Test

class VfTierFallbackTest {
    // 14-bit binned sensor (Xiaomi 14 Ultra): black 256, white 16383.
    private val black = 256
    private val white = 16383

    @Test
    fun strongInputBlackOutputIsBroken() {
        // The Adreno 750 zero-copy failure: healthy input, exact-zero output.
        assertEquals(
            VfTierProbe.BROKEN,
            RawViewfinder.probeVfTierOutput(inputMax = 12000, black = black, white = white, outputMax = 0)
        )
        assertEquals(
            VfTierProbe.BROKEN,
            RawViewfinder.probeVfTierOutput(inputMax = 12000, black = black, white = white, outputMax = 2)
        )
    }

    @Test
    fun strongInputLitOutputIsVerified() {
        assertEquals(
            VfTierProbe.VERIFIED,
            RawViewfinder.probeVfTierOutput(inputMax = 12000, black = black, white = white, outputMax = 3)
        )
        assertEquals(
            VfTierProbe.VERIFIED,
            RawViewfinder.probeVfTierOutput(inputMax = 12000, black = black, white = white, outputMax = 200)
        )
    }

    @Test
    fun darkSceneIsInconclusiveNeverBroken() {
        // Lens cap / dark room: input barely above black. Must not latch (or
        // verify) — the tier output is correctly dark either way.
        assertEquals(
            VfTierProbe.INCONCLUSIVE,
            RawViewfinder.probeVfTierOutput(inputMax = black + 10, black = black, white = white, outputMax = 0)
        )
        // Boundary: signal at exactly range/8 still inconclusive.
        val range = white - black
        assertEquals(
            VfTierProbe.INCONCLUSIVE,
            RawViewfinder.probeVfTierOutput(inputMax = black + range / 8, black = black, white = white, outputMax = 0)
        )
        assertEquals(
            VfTierProbe.BROKEN,
            RawViewfinder.probeVfTierOutput(inputMax = black + range / 8 + 1, black = black, white = white, outputMax = 0)
        )
    }

    @Test
    fun degenerateLevelsAreInconclusive() {
        assertEquals(
            VfTierProbe.INCONCLUSIVE,
            RawViewfinder.probeVfTierOutput(inputMax = 5000, black = 1023, white = 1023, outputMax = 0)
        )
        assertEquals(
            VfTierProbe.INCONCLUSIVE,
            RawViewfinder.probeVfTierOutput(inputMax = 5000, black = 2000, white = 1023, outputMax = 0)
        )
    }
}
