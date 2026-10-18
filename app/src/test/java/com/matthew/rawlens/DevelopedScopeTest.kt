// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopedScopeTest {
    private fun gray(v: Int, width: Int = 8, height: Int = 8): IntArray {
        val pixel = (0xff shl 24) or (v shl 16) or (v shl 8) or v
        return IntArray(width * height) { pixel }
    }

    @Test fun uniformGrayBinsPeakTogether() {
        // A flat mid-gray developed frame must land every channel and the
        // luma in the same bin — the scope's zero point for "matches file".
        val hist = DevelopedScopeSampler.histogramOfArgb(gray(128))
        val expected = (128 / 255.0 * 63).toInt()
        for (channel in listOf(hist.red, hist.green, hist.blue, hist.luminance)) {
            assertEquals(64, channel.sum())
            assertEquals(64, channel[expected])
        }
        assertTrue(hist.agxApplied)
    }

    @Test fun blackAndWhiteHitEndBins() {
        val black = DevelopedScopeSampler.histogramOfArgb(gray(0))
        assertEquals(64, black.red[0])
        assertEquals(0, black.red[63])
        val white = DevelopedScopeSampler.histogramOfArgb(gray(255))
        assertEquals(64, white.blue[63])
        assertEquals(0, white.blue[0])
        assertEquals(64, white.luminance[63])
    }

    @Test fun lumaWeightsGreenHeaviest() {
        // Pure primaries of equal 8-bit level must order luma
        // green > red > blue (Rec.709), pinning the shared coefficient path.
        fun lumaBin(r: Int, g: Int, b: Int): Int {
            val pixels = IntArray(1) { (0xff shl 24) or (r shl 16) or (g shl 8) or b }
            val hist = DevelopedScopeSampler.histogramOfArgb(pixels)
            return hist.luminance.indexOfFirst { it == 1 }
        }
        val green = lumaBin(0, 255, 0)
        val red = lumaBin(255, 0, 0)
        val blue = lumaBin(0, 0, 255)
        assertTrue("g=$green r=$red b=$blue", green > red && red > blue)
    }

    @Test fun waveformColumnsFollowXAndLevelsFollowValue() {
        // Left-half black / right-half white: column 0 votes level 0, the
        // last column votes the top level; total votes equal pixel count.
        val width = 96
        val height = 48
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val v = if (x < width / 2) 0 else 255
            (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        val wave = DevelopedScopeSampler.waveformOfArgb(pixels, width, height)
        assertEquals(96, wave.columns)
        assertEquals(48, wave.levels)
        assertTrue(wave.agxApplied)
        val leftCell = 0 * 48 + 0
        val rightCell = 95 * 48 + 47
        assertEquals(height, wave.red[leftCell])
        assertEquals(height, wave.red[rightCell])
        assertEquals(width * height, wave.red.sum())
        assertEquals(wave.red.sum(), wave.green.sum())
        assertEquals(wave.red.sum(), wave.blue.sum())
    }

    @Test fun waveformRejectsShapeMismatch() {
        var threw = false
        try {
            DevelopedScopeSampler.waveformOfArgb(IntArray(10), 4, 4)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("shape mismatch accepted", threw)
    }
}
