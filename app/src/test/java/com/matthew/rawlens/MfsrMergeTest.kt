// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MfsrMergeTest {
    private fun cfaOf(pattern: BayerPattern, w: Int = 6, h: Int = 4): UnpackedRawCfa {
        // Color sentinels per physical site (R=10, G=2, B=3): the output
        // must read RGGB everywhere, pinning the physical layout with no
        // reference to the DNG label names.
        val values = FloatArray(w * h) { i ->
            when (pattern.colorAt(i % w, i / w)) {
                CfaColor.RED -> 10f
                CfaColor.GREEN -> 2f
                CfaColor.BLUE -> 3f
            }
        }
        return UnpackedRawCfa(w, h, pattern, values, RawCrop(0, 0, w, h))
    }

    @Test fun decodeRowsEmitsPhysicalRggb() {
        for (pattern in BayerPattern.entries) {
            val cfa = cfaOf(pattern)
            val rows = MfsrMerge.decodeRows(cfa)
            for (y in 0 until cfa.height) for (x in 0 until cfa.width) {
                val want = when {
                    y % 2 == 0 && x % 2 == 0 -> 10f
                    y % 2 == 1 && x % 2 == 1 -> 3f
                    else -> 2f
                }
                assertEquals("pattern $pattern @($x,$y)", want, rows[y][x], 0f)
            }
        }
    }

    @Test fun decodeRowsRejectsOddDimensions() {
        val cfa = cfaOf(BayerPattern.RGGB, w = 5, h = 4)
        assertThrows(IllegalArgumentException::class.java) { MfsrMerge.decodeRows(cfa) }
    }

    @Test fun decodeRowsRggbIsIdentity() {
        val cfa = cfaOf(BayerPattern.RGGB)
        val rows = MfsrMerge.decodeRows(cfa)
        for (y in 0 until cfa.height) {
            assertArrayEquals(cfa.values.copyOfRange(y * cfa.width, (y + 1) * cfa.width), rows[y], 0f)
        }
    }
}
