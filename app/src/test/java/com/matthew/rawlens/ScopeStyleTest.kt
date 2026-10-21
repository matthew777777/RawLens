// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScopeStyleTest {
    @Test
    fun paletteIsSunlightLegibleDarkPill() {
        // No grid: the scope sits on a near-opaque scrim (not sheer glass, so
        // the bright preview cannot wash the traces out) with a hairline
        // baseline and modern primaries tuned for dark glass.
        assertEquals(0xFF080808.toInt(), ScopeStyle.SCRIM)
        assertEquals(0x33FFFFFF, ScopeStyle.BASELINE)
        assertEquals(0xFFFF6B62.toInt(), ScopeStyle.RED)
        assertEquals(0xFF3DDC84.toInt(), ScopeStyle.GREEN)
        assertEquals(0xFF5AA9FF.toInt(), ScopeStyle.BLUE)
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
    fun emptyPixelIsTransparentAndFullOverlapIsBright() {
        assertEquals(0, ScopeStyle.compositeWaveformPixel(0f, 0f, 0f))
        val white = ScopeStyle.compositeWaveformPixel(1f, 1f, 1f)
        val a = white ushr 24 and 0xFF
        val r = white shr 16 and 0xFF
        val g = white shr 8 and 0xFF
        val b = white and 0xFF
        assertEquals(255, a)
        assertTrue("overlap $r,$g,$b should be bright", r > 200 && g > 200 && b > 200)
        // Sequential per-channel passes leave some asymmetry (the last channel
        // ends brightest), same loop-order character as darktable's own code.
        assertTrue("overlap $r,$g,$b should be near-neutral",
            abs(r - g) <= 32 && abs(g - b) <= 32 && abs(r - b) <= 32)
    }

    @Test
    fun faintPixelStaysVisibleAboveAlphaFloor() {
        // The ~30% alpha floor keeps faint traces alive in glare: visible, but
        // still clearly dimmer than a fully lit cell.
        val faint = ScopeStyle.compositeWaveformPixel(0.2f, 0f, 0f)
        val a = faint ushr 24 and 0xFF
        assertTrue("faint alpha $a should stay above the floor", a >= 70)
        val full = ScopeStyle.compositeWaveformPixel(1f, 0f, 0f)
        assertTrue("faint $a should stay dimmer than full", a < (full ushr 24 and 0xFF))
    }

    @Test
    fun edgeTaperRoundsBothEnds() {
        val size = 64
        assertEquals(0f, ScopeStyle.edgeTaper(0, size), 0f)
        assertEquals(0f, ScopeStyle.edgeTaper(size - 1, size), 0f)
        assertEquals(1f, ScopeStyle.edgeTaper(size / 2, size), 0f)
        // Smooth ramp up, never overshooting.
        var previous = 0f
        for (index in 0..8) {
            val taper = ScopeStyle.edgeTaper(index, size)
            assertTrue("taper $taper out of range", taper in 0f..1f)
            assertTrue("taper should rise", taper >= previous)
            previous = taper
        }
    }

    @Test
    fun rasterEdgeFadeDissolvesBordersKeepsCenter() {
        assertEquals(0f, ScopeStyle.rasterEdgeFade(0, 24, 96, 48), 0f)
        assertEquals(0f, ScopeStyle.rasterEdgeFade(95, 24, 96, 48), 0f)
        assertEquals(0f, ScopeStyle.rasterEdgeFade(48, 0, 96, 48), 0f)
        assertEquals(0f, ScopeStyle.rasterEdgeFade(48, 47, 96, 48), 0f)
        assertEquals(1f, ScopeStyle.rasterEdgeFade(48, 24, 96, 48), 0f)
        val faded = ScopeStyle.applyEdgeFade(0xFF808080.toInt(), 0f)
        assertEquals(0, faded ushr 24 and 0xFF)
        assertEquals(0xFF808080.toInt(), ScopeStyle.applyEdgeFade(0xFF808080.toInt(), 1f))
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
