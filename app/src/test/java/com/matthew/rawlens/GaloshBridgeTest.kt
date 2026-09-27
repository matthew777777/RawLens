// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class GaloshBridgeTest {
    @Test fun identityPermForEffectiveRgbb() {
        assertTrue(GaloshBayerRemap.isIdentity(
            GaloshBayerRemap.toRgbbPerm(BayerPattern.RGGB, 0, 0)))
        // BGGR at odd/odd already reads RGGB.
        assertTrue(GaloshBayerRemap.isIdentity(
            GaloshBayerRemap.toRgbbPerm(BayerPattern.BGGR, 1, 1)))
        assertFalse(GaloshBayerRemap.isIdentity(
            GaloshBayerRemap.toRgbbPerm(BayerPattern.BGGR, 0, 0)))
        assertFalse(GaloshBayerRemap.isIdentity(
            GaloshBayerRemap.toRgbbPerm(BayerPattern.RGGB, 1, 0)))
    }

    @Test fun remapRoundTripIsIdentityEverywhere() {
        for (pattern in BayerPattern.values()) for (ox in 0..1) for (oy in 0..1) {
            val values = FloatArray(64) { it.toFloat() }
            val perm = GaloshBayerRemap.toRgbbPerm(pattern, ox, oy)
            val there = GaloshBayerRemap.toRgbb(values, 8, 8, perm)
            val back = GaloshBayerRemap.fromRgbb(there, 8, 8, perm)
            assertArrayEquals("$pattern@$ox,$oy", values, back, 0f)
        }
    }

    @Test fun remapPreservesPhysicalChannels() {
        // Distinct value per physical channel; after the remap every quad
        // must read [R, Gr, Gb, B] with Gr/Gb NOT swapped.
        for (pattern in BayerPattern.values()) for (ox in 0..1) for (oy in 0..1) {
            val values = FloatArray(64) { i ->
                val sx = ox + i % 8
                val sy = oy + i / 8
                when (pattern.colorAt(sx, sy)) {
                    // Gr/Gb by sensor row: R row holds Gr.
                    CfaColor.RED -> 10f
                    CfaColor.BLUE -> 40f
                    CfaColor.GREEN -> if (isRRow(pattern, sy)) 20f else 30f
                }
            }
            val perm = GaloshBayerRemap.toRgbbPerm(pattern, ox, oy)
            val remapped = GaloshBayerRemap.toRgbb(values, 8, 8, perm)
            for (qy in 0 until 4) for (qx in 0 until 4) {
                val base = qy * 2 * 8 + qx * 2
                assertEquals("$pattern@$ox,$oy R", 10f, remapped[base], 0f)
                assertEquals("$pattern@$ox,$oy Gr", 20f, remapped[base + 1], 0f)
                assertEquals("$pattern@$ox,$oy Gb", 30f, remapped[base + 8], 0f)
                assertEquals("$pattern@$ox,$oy B", 40f, remapped[base + 9], 0f)
            }
        }
    }

    private fun isRRow(pattern: BayerPattern, sensorY: Int): Boolean {
        for (py in 0..1) for (px in 0..1) {
            if (pattern.colorAt(px, py) == CfaColor.RED) return (sensorY and 1) == py
        }
        error("no red in $pattern")
    }

    @Test fun galoshDngNameGroupsWithCaptureStem() {
        val ts = 1_786_269_212_527L
        val stem = CaptureFileNames.stem(ts)
        assertEquals("${stem}_GALOSH.dng", CaptureFileNames.galoshDng(ts))
        assertEquals("${stem}_F00GALOSH.dng", CaptureFileNames.galoshDng(ts, "F00"))
    }
}
