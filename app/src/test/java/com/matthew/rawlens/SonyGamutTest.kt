// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class SonyGamutTest {
    @Test fun d65NeutralIsPreservedAtEveryExposure() {
        for (gray in listOf(-.01f, 0f, .001f, .18f, .9f, 16f)) {
            val result = SonyGamut.fromLinearSrgb(floatArrayOf(gray, gray, gray))
            result.forEach { assertEquals(gray, it, 2e-6f) }
        }
    }

    @Test fun srgbPrimaryConversionsMatchSonyChromaticities() {
        // Inverse of the Sony gamut matrix times the BT.709 matrix, D65.
        val expected = arrayOf(
            floatArrayOf(.64567948f, .08752999f, .03695742f),
            floatArrayOf(.25911455f, .75969956f, .12928090f),
            floatArrayOf(.09520598f, .15277045f, .83376168f))
        repeat(3) { axis ->
            val rgb = FloatArray(3) { if (it == axis) 1f else 0f }
            assertArrayEquals(expected[axis], SonyGamut.fromLinearSrgb(rgb), 1e-7f)
            val encoded = VfLogGrade.encodePixelSlog3(rgb)
            repeat(3) { assertEquals(VfLogGrade.sonySlog3(expected[axis][it]), encoded[it], 1e-7f) }
        }
    }

    @Test fun negativeChannelsSurviveMatrixAndToe() {
        val developed = floatArrayOf(-.1f, .01f, .02f)
        val cine = SonyGamut.fromLinearSrgb(developed)
        assertTrue(cine[0] < 0f)
        val encoded = VfLogGrade.encodePixelSlog3(developed)
        repeat(3) { assertEquals(cine[it], VfLogGrade.invSonySlog3(encoded[it]), 2e-7f) }
    }
}
