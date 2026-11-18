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
        assertTrue(CameraToneBalance.gain(.01f) > 3f)
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
        for (mapper in PhotoToneMapper.entries) {
            val settings = JpegOutputSettings(toneMapper = mapper)
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
}
