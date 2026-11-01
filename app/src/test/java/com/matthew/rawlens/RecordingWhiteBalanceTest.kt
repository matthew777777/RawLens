// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class RecordingWhiteBalanceTest {
    @Test fun `valid camera neutral wins without sampling`() {
        val n = doubleArrayOf(0.7, 1.0, 0.3)
        val selected = RecordingWhiteBalance.select(n, null) { error("must not sample") }
        assertArrayEquals(n, selected.neutral!!, 0.0)
        assertEquals("camera", selected.source)
    }
    @Test fun `valid gains replace invalid neutral`() {
        val selected = RecordingWhiteBalance.select(doubleArrayOf(Double.NaN, 1.0, 1.0),
            floatArrayOf(2f, 1f, 1f, 4f)) { error("must not sample") }
        assertArrayEquals(doubleArrayOf(0.5, 1.0, 0.25), selected.neutral!!, 0.0)
        assertEquals("cameraGains", selected.source)
    }
    @Test fun `direct Xiaomi unity gains and NaN neutral use frame estimate`() {
        val sample = doubleArrayOf(0.7451, 1.0, 0.3004)
        val selected = RecordingWhiteBalance.select(DoubleArray(3) { Double.NaN }, FloatArray(4) { 1f }) { sample }
        assertArrayEquals(sample, selected.neutral!!, 0.0)
        assertEquals("rawEstimate", selected.source)
    }
    @Test fun `dark frame without estimate retains RAW with unavailable WB`() {
        val selected = RecordingWhiteBalance.select(null, null) { null }
        assertNull(selected.neutral)
        assertEquals("unavailable", selected.source)
    }
    @Test fun `invalid estimates never enter JSON`() {
        for (n in listOf(doubleArrayOf(0.0, 1.0, 1.0), doubleArrayOf(Double.NaN, 1.0, 1.0), doubleArrayOf(1.0))) {
            assertNull(RecordingWhiteBalance.select(null, null) { n }.neutral)
        }
    }
}
