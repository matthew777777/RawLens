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
        assertEquals(96, RawFocusPeakingSampler.rowsFor(4000, 3000))
        assertEquals(72, RawFocusPeakingSampler.rowsFor(4000, 2250))
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
    fun laplacianFlatIsZero() {
        assertEquals(0f, RawFocusPeakingSampler.laplacian(FloatArray(16) { 0.5f }), 0f)
    }

    @Test
    fun laplacianStepEdgeEqualsStepSquared() {
        // Sharp steps match Tenengrad, so the min-combiner keeps full recall.
        val step = FloatArray(16) { i -> if (i % 4 < 2) 0f else 0.5f }
        assertEquals(0.25f, RawFocusPeakingSampler.laplacian(step), 1e-6f)
    }

    @Test
    fun combinedEnergyKeepsSharpAtAnyContrastRetiresBlur() {
        // Sharp steps have ratio ~1.0 whatever the contrast: full recall.
        assertEquals(0.25f, RawFocusPeakingSampler.combinedEnergy(0.25f, 0.25f), 0f)
        assertEquals(0.01f, RawFocusPeakingSampler.combinedEnergy(0.01f, 0.01f), 0f)
        // Mild blur of a strong edge (ten 0.13, lap 0.0176, ratio ~0.135):
        // retired even though both clear the absolute floor.
        assertEquals(0f, RawFocusPeakingSampler.combinedEnergy(0.13f, 0.0176f), 0f)
        // Moderate blur (ratio ~0.39): retired.
        assertEquals(0f, RawFocusPeakingSampler.combinedEnergy(0.1764f, 0.069f), 0f)
        // Bright mild blur at ratio ~0.54 (the laptop-screen case): retired.
        assertEquals(0f, RawFocusPeakingSampler.combinedEnergy(0.13f, 0.07f), 0f)
        // Boundary ratio exactly 0.65: kept; just below: retired.
        assertEquals(0.065f, RawFocusPeakingSampler.combinedEnergy(0.1f, 0.065f), 0f)
        assertEquals(0f, RawFocusPeakingSampler.combinedEnergy(0.1f, 0.06f), 0f)
        // Flat cells short-circuit to zero, never NaN.
        assertEquals(0f, RawFocusPeakingSampler.combinedEnergy(0f, 0f), 0f)
        assertEquals(0f, RawFocusPeakingSampler.combinedEnergy(0f, 0.02f), 0f)
    }

    @Test
    fun laplacianIgnoresLinearRampTenengradDotsIt() {
        // Smooth gradient (defocus hallmark, binary-exact 0.25 steps):
        // Tenengrad answers 0.25 and the old metric would dot it; the
        // Laplacian scores exactly 0, so the minimum retires the cell.
        val ramp = FloatArray(16) { i -> (i % 4) * 0.25f }
        assertEquals(0.25f, RawFocusPeakingSampler.tenengrad(ramp), 0f)
        assertEquals(0f, RawFocusPeakingSampler.laplacian(ramp), 0f)
        assertTrue(
            minOf(
                RawFocusPeakingSampler.tenengrad(ramp),
                RawFocusPeakingSampler.laplacian(ramp)
            ) < RawFocusPeakingSampler.EDGE_THRESHOLD
        )
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
    fun laptopSceneDotsSharpNotBlurAndKeepsDimSharp() {
        // Full pipeline (ten/lap pairs through the ratio combiner into the
        // mask): a sharp bright pair dots, bright mild-blur background
        // (ratio ~0.54) does not, and a dim-but-sharp pair (ratio ~1.0)
        // dots EVEN NEXT TO the bright plane. The old peak-relative gate
        // inverted the last two: the dim pair fell below 25% of the peak
        // while the bright blur cleared it.
        val cols = 8
        val rows = 8
        val ten = FloatArray(cols * rows)
        val lap = FloatArray(cols * rows)
        // Sharp bright pair (adjacent: survives despeckle).
        ten[27] = 0.25f; lap[27] = 0.25f
        ten[28] = 0.25f; lap[28] = 0.25f
        // Bright mild-blur background (adjacent: would survive despeckle).
        for (i in intArrayOf(40, 41, 42, 43)) { ten[i] = 0.13f; lap[i] = 0.07f }
        // Dim-but-sharp pair (adjacent: survives despeckle).
        ten[50] = 0.015f; lap[50] = 0.015f
        ten[51] = 0.015f; lap[51] = 0.015f
        val energy = FloatArray(cols * rows) { i ->
            RawFocusPeakingSampler.combinedEnergy(ten[i], lap[i])
        }
        val mean = FloatArray(cols * rows) { 0.5f }
        val mask = RawFocusPeakingSampler.maskOf(energy, mean, cols, rows)
        assertTrue(mask[27])
        assertTrue(mask[28])
        assertTrue(mask[50])
        assertTrue(mask[51])
        for (i in intArrayOf(40, 41, 42, 43)) {
            assertFalse("mild-blur background cell $i must not dot", mask[i])
        }
        assertEquals(4, mask.count { it })
    }

    @Test
    fun lowContrastSharpShowsWhileRackingWithoutBrightPeak() {
        // No bright plane anywhere: the absolute floor still governs, so dim
        // but sharp texture is not hidden by a peak that is not there.
        val cols = 8
        val rows = 8
        val energy = FloatArray(cols * rows)
        val mean = FloatArray(cols * rows) { 0.5f }
        energy[27] = 0.015f
        energy[28] = 0.015f
        val mask = RawFocusPeakingSampler.maskOf(energy, mean, cols, rows)
        assertTrue(mask[27])
        assertTrue(mask[28])
        assertEquals(2, mask.count { it })
    }

    @Test
    fun loneHotPixelDotsNothing() {
        // A single spiking cell clears any threshold but has no passing
        // 8-neighbor, so the despeckle still retires it.
        val cols = 8
        val rows = 8
        val energy = FloatArray(cols * rows)
        val mean = FloatArray(cols * rows) { 0.5f }
        energy[30] = 1f
        assertTrue(maskOfNone(energy, mean, cols, rows))
    }

    @Test
    fun fullFrameSharpWallStillDots() {
        // Uniformly sharp (e.g. a focused wall): the wash guard doubles once
        // but the wall still clears it, so coverage is not blanked.
        val energy = FloatArray(16) { 0.05f }
        val mean = FloatArray(16) { 0.5f }
        assertEquals(16, RawFocusPeakingSampler.maskOf(energy, mean, 4, 4).count { it })
    }

    @Test
    fun dimSharpDotsNextToBrightPeakWithoutRelativeGate() {
        // The laptop-scene inversion at mask level: with the peak-relative
        // gate gone, a fixed noise floor keeps dim-but-sharp texture dotted
        // next to a bright plane (ratio already decided sharpness upstream).
        val cols = 8
        val rows = 8
        val energy = FloatArray(cols * rows)
        val mean = FloatArray(cols * rows) { 0.5f }
        energy[27] = 0.25f
        energy[28] = 0.25f
        energy[50] = 0.015f
        energy[51] = 0.015f
        val mask = RawFocusPeakingSampler.maskOf(energy, mean, cols, rows)
        assertTrue(mask[27])
        assertTrue(mask[28])
        assertTrue(mask[50])
        assertTrue(mask[51])
        assertEquals(4, mask.count { it })
    }

    private fun maskOfNone(energy: FloatArray, mean: FloatArray, cols: Int, rows: Int): Boolean =
        RawFocusPeakingSampler.maskOf(energy, mean, cols, rows).none { it }

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
