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
    fun probationSignalCarriesMaxSampleNotMin() {
        // Field regression (OnePlus 12 / Adreno 750): the signal fed the
        // MINIMUM sample into dataMax, so every verdict wedged at
        // INCONCLUSIVE, a black zero-copy tier presented forever with no
        // log line, and the gpu-copy tier never engaged. Sample triple is
        // (min, max, mean), as returned by the raw-code sampler.
        val signal = RawViewfinder.inputSignalFromSample(
            Triple(64, 458, 123),
            floatArrayOf(64f, 64f, 64f, 64f), 1023f
        )
        assertEquals(458, signal?.dataMax)
        assertEquals(64, signal?.black)
        assertEquals(1023, signal?.white)
    }

    @Test
    fun probationSignalUsesHighestBlackLevel() {
        val signal = RawViewfinder.inputSignalFromSample(
            Triple(100, 9000, 1200),
            floatArrayOf(256f, 260f, 258f, 262f), 16383f
        )
        assertEquals(9000, signal?.dataMax)
        assertEquals(262, signal?.black)
    }

    @Test
    fun probationSignalNullSampleKeepsPrevious() {
        assertEquals(
            null,
            RawViewfinder.inputSignalFromSample(null, floatArrayOf(64f, 64f, 64f, 64f), 1023f)
        )
    }

    @Test
    fun fieldSignalConvictsBlackTierAndVerifiesLitTier() {
        // End to end on the logcat session values (black 64, white 1023,
        // dataMax 458 then 612): a zero-reading tier must latch BROKEN so
        // the chain advances to gpu-copy the same frame.
        for (dataMax in intArrayOf(458, 612)) {
            assertEquals(
                VfTierProbe.BROKEN,
                RawViewfinder.probeVfTierOutput(dataMax, 64, 1023, 0)
            )
            assertEquals(
                VfTierProbe.VERIFIED,
                RawViewfinder.probeVfTierOutput(dataMax, 64, 1023, 200)
            )
        }
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
