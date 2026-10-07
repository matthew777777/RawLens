// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPeakingTest {
    @Test
    fun policyShowsOnlyWhileFocusing() {
        assertTrue(FocusPeakingPolicy.shouldShow(true, manualFocus = true, touchFocusActive = false))
        assertTrue(FocusPeakingPolicy.shouldShow(true, manualFocus = false, touchFocusActive = true))
        assertTrue(FocusPeakingPolicy.shouldShow(true, manualFocus = true, touchFocusActive = true))
        assertFalse(FocusPeakingPolicy.shouldShow(true, manualFocus = false, touchFocusActive = false))
        assertFalse(FocusPeakingPolicy.shouldShow(false, manualFocus = true, touchFocusActive = true))
    }

    @Test
    fun colorRoundTripsPreferenceAndDefaultsToGreen() {
        FocusPeakingColor.entries.forEach { color ->
            assertEquals(color, FocusPeakingColor.fromPreference(color.preferenceValue))
        }
        assertEquals(FocusPeakingColor.GREEN, FocusPeakingColor.fromPreference(null))
        assertEquals(FocusPeakingColor.GREEN, FocusPeakingColor.fromPreference("bogus"))
    }

    @Test
    fun colorNextCyclesAllEntries() {
        var color = FocusPeakingColor.GREEN
        repeat(FocusPeakingColor.entries.size) { color = color.next() }
        assertEquals(FocusPeakingColor.GREEN, color)
    }

    @Test
    fun rowsHoldCellsSquareAndClamp() {
        assertEquals(72, RawFocusPeakingSampler.rowsFor(4000, 3000))
        assertEquals(54, RawFocusPeakingSampler.rowsFor(4000, 2250))
        assertEquals(RawFocusPeakingSampler.MIN_ROWS, RawFocusPeakingSampler.rowsFor(4000, 100))
        assertEquals(RawFocusPeakingSampler.MAX_ROWS, RawFocusPeakingSampler.rowsFor(1000, 4000))
    }

    @Test
    fun greenOffsetsMatchCfaLayouts() {
        // RGGB / BGGR carry green off the diagonal, GRBG / GBRG on it.
        assertArrayEquals(intArrayOf(1, 0, 0, 1), RawFocusPeakingSampler.greenOffsets(0))
        assertArrayEquals(intArrayOf(0, 0, 1, 1), RawFocusPeakingSampler.greenOffsets(1))
        assertArrayEquals(intArrayOf(0, 0, 1, 1), RawFocusPeakingSampler.greenOffsets(2))
        assertArrayEquals(intArrayOf(1, 0, 0, 1), RawFocusPeakingSampler.greenOffsets(3))
    }

    @Test
    fun flatGridScoresZeroEverywhere() {
        val scores = RawFocusPeakingSampler.scoresOf(FloatArray(25) { 0.5f }, 5, 5)
        assertTrue(scores.all { it == 0f })
    }

    @Test
    fun verticalEdgeScoresOnlyBesideTheStep() {
        // Left half bright, right half black: central differences fire only at x=1..2.
        val green = FloatArray(25) { i -> if (i % 5 < 2) 0.6f else 0f }
        val scores = RawFocusPeakingSampler.scoresOf(green, 5, 5)
        for (y in 1..3) for (x in 1..3) {
            val expected = if (x == 1 || x == 2) 0.6f else 0f
            assertEquals(expected, scores[y * 5 + x], 1e-6f)
        }
        // Border stays zero.
        for (i in scores.indices) {
            val x = i % 5
            val y = i / 5
            if (x == 0 || y == 0 || x == 4 || y == 4) assertEquals(0f, scores[i], 0f)
        }
    }

    @Test
    fun maskMarksBrightEdgeAndGatesBlackSide() {
        val green = FloatArray(25) { i -> if (i % 5 < 3) 0.5f else 0f }
        val mask = RawFocusPeakingSampler.maskOf(green, 5, 5)
        // x=2 (bright, beside the step) passes; x=3 (black side) is gated by
        // the black-mean floor even though its gradient is just as strong.
        val expected = BooleanArray(25) { i -> i % 5 == 2 && i / 5 in 1..3 }
        assertArrayEquals(expected, mask)
    }

    @Test
    fun washGuardRetiresholdsBusyFrames() {
        // Smooth ramp: every interior gradient (0.2) clears the default 0.15,
        // so the wash guard doubles once and nothing passes at 0.3.
        val cols = 6
        val rows = 6
        val green = FloatArray(cols * rows) { i -> ((i % cols) + (i / cols)) * 0.05f + 0.1f }
        val mask = RawFocusPeakingSampler.maskOf(green, cols, rows)
        assertTrue(mask.none { it })
    }

    @Test
    fun geometryInvertsViewfinderTransform() {
        val rotations = intArrayOf(0, 90, 180, 270)
        val mirrors = booleanArrayOf(false, true)
        val samples = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        for (rotation in rotations) for (mirrored in mirrors) for (x in samples) for (y in samples) {
            val (su, sv) = RawPreviewGeometry.sensorPoint(x, y, rotation, mirrored)
            val (vx, vy) = FocusPeakingGeometry.viewPoint(su, sv, rotation, mirrored)
            assertEquals("rot=$rotation mirror=$mirrored x=$x", x, vx, 1e-6f)
            assertEquals("rot=$rotation mirror=$mirrored y=$y", y, vy, 1e-6f)
        }
    }

    @Test
    fun cellsCoverTheFrameUnderRotation() {
        for (rotation in intArrayOf(0, 90, 180, 270)) for (mirrored in booleanArrayOf(false, true)) {
            var minX = 1f
            var minY = 1f
            var maxX = 0f
            var maxY = 0f
            for (row in 0..1) for (col in 0..1) {
                val cell = FocusPeakingGeometry.viewCell(col, row, 2, 2, rotation, mirrored)
                minX = minOf(minX, cell[0])
                minY = minOf(minY, cell[1])
                maxX = maxOf(maxX, cell[2])
                maxY = maxOf(maxY, cell[3])
            }
            assertEquals(0f, minX, 1e-6f)
            assertEquals(0f, minY, 1e-6f)
            assertEquals(1f, maxX, 1e-6f)
            assertEquals(1f, maxY, 1e-6f)
        }
        // Spot check: sensor top-left lands view top-right at 90° (portrait sensor).
        assertArrayEquals(
            floatArrayOf(0.5f, 0f, 1f, 0.5f),
            FocusPeakingGeometry.viewCell(0, 0, 2, 2, 90, false),
            1e-6f
        )
    }
}
