// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScopeStyleTest {
    @Test
    fun paletteMatchesDarktableTheme() {
        assertEquals(0xFF262626.toInt(), ScopeStyle.BACKGROUND)
        assertEquals(0xFF111111.toInt(), ScopeStyle.GRID)
        assertEquals(0xFFED1E14.toInt(), ScopeStyle.RED)
        assertEquals(0xFF1CEB1A.toInt(), ScopeStyle.GREEN)
        assertEquals(0xFF0E0EE9.toInt(), ScopeStyle.BLUE)
    }

    @Test
    fun primariesSumToNeutralOverlap() {
        // The theme's own invariant: every channel sums to the same 279 so ADD
        // overlaps clip to neutral instead of tinting.
        val channels = listOf(
            Triple(237, 30, 20), Triple(28, 235, 26), Triple(14, 14, 233)
        )
        assertEquals(279, channels.sumOf { it.first })
        assertEquals(279, channels.sumOf { it.second })
        assertEquals(279, channels.sumOf { it.third })
    }

    @Test
    fun hlgOetfAnchorsAndMonotonic() {
        assertEquals(0f, ScopeStyle.hlgOetf(0f), 0f)
        assertEquals(1f, ScopeStyle.hlgOetf(1f), 1e-3f)
        val boundary = 1f / 12f
        assertTrue(abs(ScopeStyle.hlgOetf(boundary + 1e-5f) - ScopeStyle.hlgOetf(boundary - 1e-5f)) < 1e-3f)
        var previous = -1f
        var value = 0f
        while (value <= 1f) {
            val encoded = ScopeStyle.hlgOetf(value)
            assertTrue("not monotonic at $value", encoded >= previous)
            previous = encoded
            value += 0.01f
        }
        // Perceptual lift: faint densities stay visible.
        assertTrue(ScopeStyle.hlgOetf(0.025f) > 0.15f)
    }

    @Test
    fun areaScaleIsZeroWithoutSamplesAndDimsUniformSpread() {
        assertEquals(0f, ScopeStyle.areaScale(48, 96, 0), 0f)
        // Uniform spread over every cell lands dim; a concentrated cell lands bright.
        val uniformPerCell = 10
        val total = uniformPerCell * 96 * 48
        val scale = ScopeStyle.areaScale(48, 96, total)
        val uniformLinear = (uniformPerCell * scale).coerceAtMost(1f)
        val concentratedLinear = (uniformPerCell * 48 * scale).coerceAtMost(1f)
        assertTrue("uniform $uniformLinear should stay dim", uniformLinear < 0.05f)
        assertEquals(1f, concentratedLinear, 0f)
    }

    @Test
    fun emptyPixelIsBackgroundAndFullOverlapIsNeutralWhite() {
        assertEquals(ScopeStyle.BACKGROUND, ScopeStyle.compositeWaveformPixel(0f, 0f, 0f))
        val white = ScopeStyle.compositeWaveformPixel(1f, 1f, 1f)
        val r = white shr 16 and 0xFF
        val g = white shr 8 and 0xFF
        val b = white and 0xFF
        assertTrue("overlap $r,$g,$b should be bright", r > 200 && g > 200 && b > 200)
        // Sequential per-channel passes leave a few LSB of asymmetry (the last
        // channel ends brightest), identical to darktable's own loop order.
        assertTrue("overlap $r,$g,$b should be near-neutral",
            abs(r - g) <= 12 && abs(g - b) <= 12 && abs(r - b) <= 12)
    }

    @Test
    fun singleChannelMaskStaysInItsHue() {
        val red = ScopeStyle.compositeWaveformPixel(1f, 0f, 0f)
        val r = red shr 16 and 0xFF
        val g = red shr 8 and 0xFF
        val b = red and 0xFF
        assertTrue("red-dominant $r,$g,$b expected", r > g && r > b && r > 150)
        val green = ScopeStyle.compositeWaveformPixel(0f, 1f, 0f)
        val gr = green shr 16 and 0xFF
        val gg = green shr 8 and 0xFF
        val gb = green and 0xFF
        assertTrue("green-dominant $gr,$gg,$gb expected", gg > gr && gg > gb && gg > 150)
    }
}
