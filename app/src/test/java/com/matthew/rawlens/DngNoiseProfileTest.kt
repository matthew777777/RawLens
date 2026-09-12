package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class DngNoiseProfileTest {
    @Test fun mapsEverySensorPatternAndRetainsTheNoisierGreenPair() {
        for (pattern in BayerPattern.entries) {
            var green = 0
            val values = DoubleArray(8)
            for (cell in 0..3) {
                val pair = when (pattern.colorAt(cell and 1, cell shr 1)) {
                    CfaColor.RED -> 1.0 to 0.1
                    CfaColor.BLUE -> 4.0 to 0.4
                    CfaColor.GREEN -> if (green++ == 0) 2.0 to 0.9 else 3.0 to 0.2
                }
                values[cell * 2] = pair.first
                values[cell * 2 + 1] = pair.second
            }
            assertArrayEquals(doubleArrayOf(1.0, 0.1, 3.0, 0.2, 4.0, 0.4),
                DngNoiseProfile.toRgb(values, pattern), 0.0)
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
}
