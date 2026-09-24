// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class RawSrHighlightsTest {
    @Test fun neutralNormalizationKeepsTheWhiteBalanceRayInsideDngRange() {
        assertArrayEquals(floatArrayOf(0.75f, 1f, 0.375f),
            RawSrHighlights.normalizeNeutral(doubleArrayOf(1.5, 2.0, 0.75)), 0f)
        for (bad in listOf(null, doubleArrayOf(1.0), doubleArrayOf(1.0, 0.0, 1.0),
            doubleArrayOf(Double.NaN, 1.0, 1.0))) {
            assertArrayEquals(floatArrayOf(1f, 1f, 1f), RawSrHighlights.normalizeNeutral(bad), 0f)
        }
    }

    @Test fun rolloffIsContinuousAndLeavesUnsaturatedDataAlone() {
        val value = 0.24
        assertEquals(value, RawSrHighlights.resolve(value, 0.94, 0.37f), 0.0)
        // Near-white but uncensored taps keep their colour: no 3x3 bloom.
        assertEquals(value, RawSrHighlights.resolve(value, 0.97, 0.37f), 0.0)
        assertEquals(value, RawSrHighlights.resolve(value, Double.NaN, 0.37f), 0.0)
        var last = value
        for (i in 0..10) {
            val v = RawSrHighlights.resolve(value, 0.99 + i * 0.001, 0.37f)
            assertTrue(v >= last)
            assertTrue(v - last < 0.02)
            last = v
        }
        assertEquals(0.37f.toDouble(), RawSrHighlights.resolve(0.0, 1.5, 0.37f), 0.0)
    }
}
