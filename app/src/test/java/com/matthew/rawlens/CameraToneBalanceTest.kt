// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class CameraToneBalanceTest {
    @Test fun toeIsMonotonicHasNoPedestalAndLeavesHighlightsUntouched() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), CameraToneBalance.map(floatArrayOf(0f, 0f, 0f)), 0f)
        var previous = 0f
        for (i in 0..20000) {
            val x = i / 10000f
            val y = x * CameraToneBalance.gain(x)
            assertTrue("monotonic at $x", y >= previous)
            assertTrue(CameraToneBalance.gain(x) in 1f..4f)
            if (x >= .6f) assertEquals(x, y, 0f)
            previous = y
        }
        assertTrue(CameraToneBalance.gain(.01f) in 1f..1.5f)
        assertEquals(1f, CameraToneBalance.gain(.0005f), 0f)
        assertTrue(CameraToneBalance.gain(.18f) < 1.2f)
    }

    @Test fun sharedScalePreservesRgbRatiosAndProtectsSaturatedHighlights() {
        val color = floatArrayOf(.04f, .02f, -.002f)
        val mapped = CameraToneBalance.map(color)
        val scale = mapped[0] / color[0]
        for (i in 0..2) assertEquals(color[i] * scale, mapped[i], 1e-7f)
        val bright = floatArrayOf(.8f, .03f, .01f)
        assertArrayEquals(bright, CameraToneBalance.map(bright), 0f)
    }

    @Test fun scopesMatchCameraRenderingForEveryMapperAndRemainInvertible() {
        for (mapper in PhotoToneMapper.entries) for (lift in floatArrayOf(0f, .2f, 1f)) for (recovery in floatArrayOf(0f, .5f, 1f)) {
            val settings = JpegOutputSettings(toneMapper = mapper, shadowLift = lift, highlightRecovery = recovery)
            for (x in floatArrayOf(.001f, .01f, .05f, .18f, .4f, .8f)) {
                val code = AgxDisplayTransform.scopeCurve(x, settings)
                val rgb = AgxDisplayTransform.acescgToOutputLinearSrgb(floatArrayOf(x,x,x), settings)
                assertEquals("$mapper at $x", code, AgxDisplayTransform.srgbOetf(rgb[0]), .005f)
                // The AgX polynomial clips its deepest toe to display black.
                if (code > AgxDisplayTransform.scopeCurve(0f, settings) + 1e-5f)
                    assertEquals("$mapper inverse at $x", x, AgxDisplayTransform.invertScopeCurve(code, settings), .0001f)
            }
        }
    }
    @Test fun highlightRecoveryMatchesRapidRawFlatFieldResponseAndNeverFolds() {
        // Independent RapidRAW 1.7 neutral-ramp values, start -3 EV, knee 1,
        // cap -1 EV, rate 1.6. The local guided base is deliberately not ported.
        assertEquals(1f, CameraToneBalance.gain(.125f, 0f, 1f), 0f)
        assertEquals(.6827025f, CameraToneBalance.gain(.25f, 0f, 1f), 1e-5f)
        assertEquals(.5095599f, CameraToneBalance.gain(1f, 0f, 1f), 1e-5f)
        for (lift in floatArrayOf(0f, .2f, 1f)) for (recovery in floatArrayOf(0f, .5f, 1f)) {
            var previous = 0f
            for (i in 0..50000) {
                val x = i / 5000f
                val y = x * CameraToneBalance.gain(x, lift, recovery)
                assertTrue("fold at $x lift=$lift recovery=$recovery", y >= previous)
                previous = y
            }
        }
    }

    @Test fun zeroControlsBypassAndInvalidControlsResolveSafely() {
        val rgb = floatArrayOf(.03f, .01f, -.002f)
        assertArrayEquals(rgb, CameraToneBalance.map(rgb,
            JpegOutputSettings(shadowLift = 0f, highlightRecovery = 0f)), 0f)
        val resolved = JpegOutputSettings(shadowLift = Float.NaN,
            highlightRecovery = Float.POSITIVE_INFINITY).resolvedForPlatform()
        assertEquals(.2f, resolved.shadowLift, 0f)
        assertEquals(0f, resolved.highlightRecovery, 0f)
        val a = JpegOutputSettings(toneMapper = PhotoToneMapper.SIGMOID)
        val b = a.copy(highlightRecovery = 1f)
        assertEquals(AdaptiveExposureTuning.fromOutputSettings(a),
            AdaptiveExposureTuning.fromOutputSettings(b))
    }

}
