// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class LkrHdrDecisionTest {
    private val tile = 0.015   // one 424 px tile of a 4080 x 3060 frame
    private fun noise(a: Double, b: Double) = RawNoiseEstimate(a, b, 16, DoubleArray(0))
    private fun stats(clip: Double, median: Double = 0.2, p10: Double = 0.05, n: RawNoiseEstimate? = noise(2e-5, 1e-7)) =
        LkrHdrDecision.Stats(clip, median, p10, n)

    @Test fun offNeverBrackets() {
        assertFalse(LkrHdrDecision.decide(LkrHdrMode.OFF, stats(0.5), tile, true, 8).any)
    }

    @Test fun onAlwaysBracketsAndKeepsTotal() {
        val p = LkrHdrDecision.decide(LkrHdrMode.ON, null, tile, true, 8)
        assertArrayEquals(floatArrayOf(-2f, -2f, 1.5f), p.evs, 0f)
        assertArrayEquals(floatArrayOf(-2f, -2f), LkrHdrDecision.decide(LkrHdrMode.ON, null, tile, false, 8).evs, 0f)
    }

    @Test fun autoShortFramesOnlyForClippedAreaOfAtLeastOneTile() {
        assertFalse(LkrHdrDecision.decide(LkrHdrMode.AUTO, stats(tile / 2), tile, false, 8).any)
        assertArrayEquals(floatArrayOf(-2f, -2f),
            LkrHdrDecision.decide(LkrHdrMode.AUTO, stats(tile), tile, false, 8).evs, 0f)
    }

    @Test fun autoLongFrameWhenShadowsAreReadNoiseLimited() {
        // b > a * p10: read noise dominates the shadows.
        val readLimited = stats(0.0, median = 0.1, p10 = 0.001, n = noise(2e-5, 1e-6))
        assertArrayEquals(floatArrayOf(1.5f), LkrHdrDecision.decide(LkrHdrMode.AUTO, readLimited, tile, true, 8).evs, 0f)
        // Shot noise dominates: more frames are as good as a longer one.
        val shotLimited = stats(0.0, median = 0.1, p10 = 0.05, n = noise(2e-5, 1e-7))
        assertFalse(LkrHdrDecision.decide(LkrHdrMode.AUTO, shotLimited, tile, true, 8).any)
        // The long frame would clip more than half the scene.
        val bright = stats(0.0, median = 0.6, p10 = 0.001, n = noise(2e-5, 1e-6))
        assertFalse(LkrHdrDecision.decide(LkrHdrMode.AUTO, bright, tile, true, 8).any)
        // Over the hand-shake cap.
        assertFalse(LkrHdrDecision.decide(LkrHdrMode.AUTO, readLimited, tile, false, 8).any)
    }

    @Test fun keepsTwoZeroEvFrames() {
        assertArrayEquals(floatArrayOf(-2f), LkrHdrDecision.decide(LkrHdrMode.ON, null, tile, true, 3).evs, 0f)
        assertFalse(LkrHdrDecision.decide(LkrHdrMode.ON, null, tile, true, 2).any)
    }

    @Test fun bracketDetectionIgnoresHalRounding() {
        assertFalse(LkrMerge.isBracket(floatArrayOf(1f, 1.02f, 0.99f)))
        assertTrue(LkrMerge.isBracket(floatArrayOf(1f, 1f, 0.25f)))
        assertFalse(LkrMerge.isBracket(floatArrayOf(1f)))
    }
}
