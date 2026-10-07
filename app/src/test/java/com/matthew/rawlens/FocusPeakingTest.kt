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
    fun greenColStartMatchesCfaLayouts() {
        // RGGB / BGGR carry green where x-parity != y-parity, GRBG / GBRG
        // where it matches.
        for (y in 0..7) {
            assertEquals(1 - (y and 1), RawFocusPeakingSampler.greenColStart(0, y))
            assertEquals(y and 1, RawFocusPeakingSampler.greenColStart(1, y))
            assertEquals(y and 1, RawFocusPeakingSampler.greenColStart(2, y))
            assertEquals(1 - (y and 1), RawFocusPeakingSampler.greenColStart(3, y))
        }
    }

    @Test
    fun tenengradFlatIsZero() {
        assertEquals(0f, RawFocusPeakingSampler.tenengrad(FloatArray(16) { 0.5f }), 0f)
    }

    @Test
    fun tenengradStepEdgeEqualsStepSquared() {
        // Vertical step of height a: every interior gx is ±a, gy is 0.
        val step = FloatArray(16) { i -> if (i % 4 < 2) 0f else 0.5f }
        assertEquals(0.25f, RawFocusPeakingSampler.tenengrad(step), 1e-6f)
    }

    @Test
    fun despeckleDropsIsolatedKeepsPairs() {
        val mask = BooleanArray(16)
        mask[0] = true // isolated corner: noise spike, dropped
        mask[6] = true // adjacent pair: edge fragment, kept
        mask[7] = true
        val expected = BooleanArray(16)
        expected[6] = true
        expected[7] = true
        assertArrayEquals(expected, RawFocusPeakingSampler.despeckle(mask, 4, 4))
    }

    @Test
    fun maskMarksSharpGatesBlackAndDropsIsolated() {
        val energy = FloatArray(16)
        val mean = FloatArray(16)
        // Adjacent sharp pair: survives threshold + despeckle.
        energy[5] = 0.02f; mean[5] = 0.5f
        energy[6] = 0.02f; mean[6] = 0.5f
        // Isolated sharp cell (no passing 8-neighbor): dropped by despeckle.
        energy[12] = 0.02f; mean[12] = 0.5f
        // Sharp but black: gated by the black-mean floor.
        energy[15] = 0.02f; mean[15] = 0f
        val mask = RawFocusPeakingSampler.maskOf(energy, mean, 4, 4)
        val expected = BooleanArray(16)
        expected[5] = true
        expected[6] = true
        assertArrayEquals(expected, mask)
    }

    @Test
    fun washGuardRetiresholdsBusyFrames() {
        // Every cell clears the default 0.008, so the wash guard doubles once
        // and nothing passes at 0.016.
        val energy = FloatArray(16) { 0.01f }
        val mean = FloatArray(16) { 0.5f }
        val mask = RawFocusPeakingSampler.maskOf(energy, mean, 4, 4)
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
