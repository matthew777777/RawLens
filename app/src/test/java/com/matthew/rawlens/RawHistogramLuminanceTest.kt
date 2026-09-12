// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawHistogramLuminanceTest {
    @Test
    fun whiteIsOneBlackIsZero() {
        assertEquals(1.0, RawHistogramSampler.luminanceOf(1.0, 1.0, 1.0), 1e-9)
        assertEquals(0.0, RawHistogramSampler.luminanceOf(0.0, 0.0, 0.0), 0.0)
    }

    @Test
    fun greenDominatesRec709Weights() {
        val red = RawHistogramSampler.luminanceOf(1.0, 0.0, 0.0)
        val green = RawHistogramSampler.luminanceOf(0.0, 1.0, 0.0)
        val blue = RawHistogramSampler.luminanceOf(0.0, 0.0, 1.0)
        assertEquals(0.2126, red, 1e-9)
        assertEquals(0.7152, green, 1e-9)
        assertEquals(0.0722, blue, 1e-9)
        assertTrue(green > red && green > blue)
    }
}
