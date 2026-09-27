package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class DngNoiseProfileTest {
    @Test fun mapsEverySensorPatternAndRetainsTheNoisierGreenPair() {
        // Non-uniform black pins per-winning-cell normalization.
        val black = floatArrayOf(10f, 20f, 30f, 40f)
        val white = 1000f
        for (pattern in BayerPattern.entries) {
            var green = 0
            val values = DoubleArray(8)
            var secondGreenCell = -1
            for (cell in 0..3) {
                val pair = when (pattern.colorAt(cell and 1, cell shr 1)) {
                    CfaColor.RED -> 1.0 to 0.1
                    CfaColor.BLUE -> 4.0 to 0.4
                    CfaColor.GREEN -> if (green++ == 0) 2.0 to 0.9 else {
                        secondGreenCell = cell
                        3.0 to 0.2
                    }
                }
                values[cell * 2] = pair.first
                values[cell * 2 + 1] = pair.second
            }
            fun norm(slope: Double, offset: Double, cell: Int): DoubleArray {
                val range = white - black[cell]
                return doubleArrayOf(slope / range, (slope * black[cell] + offset) / (range * range))
            }
            val redCell = (0..3).first { pattern.colorAt(it and 1, it shr 1) == CfaColor.RED }
            val blueCell = (0..3).first { pattern.colorAt(it and 1, it shr 1) == CfaColor.BLUE }
            val r = norm(1.0, 0.1, redCell)
            // Louder green pair (3.0, 0.2) wins, normalized at its own cell.
            val g = norm(3.0, 0.2, secondGreenCell)
            val b = norm(4.0, 0.4, blueCell)
            assertArrayEquals(doubleArrayOf(r[0], r[1], g[0], g[1], b[0], b[1]),
                DngNoiseProfile.toRgb(values, pattern, black, white), 0.0)
        }
    }

    @Test fun acceptsRgbOverridesAndOmitsInvalidProfiles() {
        val rgb = doubleArrayOf(1.0, 0.1, 2.0, 0.2, 3.0, 0.3)
        assertArrayEquals(rgb, DngNoiseProfile.toRgb(rgb, BayerPattern.BGGR), 0.0)
        assertNull(DngNoiseProfile.toRgb(null, BayerPattern.RGGB))
        assertNull(DngNoiseProfile.toRgb(doubleArrayOf(1.0, 2.0), BayerPattern.RGGB))
        rgb[0] = Double.NaN
        assertNull(DngNoiseProfile.toRgb(rgb, BayerPattern.RGGB))
    }

    @Test fun eightCoefficientInputsNeedUsableLevels() {
        val values = doubleArrayOf(1.0, 0.1, 2.0, 0.9, 3.0, 0.2, 4.0, 0.4)
        val black = floatArrayOf(10f, 20f, 30f, 40f)
        assertNull(DngNoiseProfile.toRgb(values, BayerPattern.RGGB))
        assertNull(DngNoiseProfile.toRgb(values, BayerPattern.RGGB, black, null))
        assertNull(DngNoiseProfile.toRgb(values, BayerPattern.RGGB, null, 1000f))
        assertNull(DngNoiseProfile.toRgb(values, BayerPattern.RGGB, floatArrayOf(10f, 20f), 1000f))
        assertNull(DngNoiseProfile.toRgb(values, BayerPattern.RGGB, black, 40f))
        assertNotNull(DngNoiseProfile.toRgb(values, BayerPattern.RGGB, black, 1000f))
    }
}
