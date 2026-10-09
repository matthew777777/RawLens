// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeMeterABTest {
    private fun frame(
        hist: IntArray = IntArray(64),
        lum: IntArray = IntArray(64),
        wave: IntArray = IntArray(48 * 96),
        levels: FloatArray = FloatArray(4),
        bins: Array<IntArray> = Array(4) { IntArray(256) },
        saturated: IntArray = IntArray(4),
        totals: IntArray = IntArray(4),
        cw: Float = Float.NaN,
        spot: Float = Float.NaN,
        guard: Float = Float.NaN,
        mask: BooleanArray = BooleanArray(96 * 24)
    ) = ScopeMeterNative.ScopeMeterFrame(
        histogram = RgbHistogram(hist, hist, hist, lum, false),
        waveform = RgbWaveform(96, 48, wave, wave, wave, false),
        ettr = EttrRawSample(EttrChannelLevels(levels[0], levels[1], levels[2], levels[3]),
            bins, saturated, totals, cw, spot, guard),
        focus = FocusPeakingFrame(96, 24, mask),
        focusEnergy = FloatArray(96 * 24),
        focusMean = FloatArray(96 * 24)
    )

    private fun reportFor(native: ScopeMeterNative.ScopeMeterFrame,
                          mutate: ScopeMeterNative.ScopeMeterFrame.() -> Unit = {}): ScopeMeterAB.ABReport {
        // Kotlin side mirrors the frame; the test mutates one side after.
        val k = frame(
            hist = native.histogram.red.copyOf(), lum = native.histogram.luminance.copyOf(),
            wave = native.waveform.red.copyOf(),
            levels = floatArrayOf(native.ettr.levels.r, native.ettr.levels.gr,
                native.ettr.levels.gb, native.ettr.levels.b),
            bins = Array(4) { native.ettr.bins[it].copyOf() },
            saturated = native.ettr.saturated.copyOf(), totals = native.ettr.totals.copyOf(),
            cw = native.ettr.centerWeightedGreen, spot = native.ettr.spotGreen,
            guard = native.ettr.guardHottest, mask = native.focus.mask.copyOf()
        )
        native.mutate()
        return ScopeMeterAB.compare(native, k.histogram, k.waveform, k.ettr, k.focus,
            nativeNs = 1_000_000, histNs = 1_000_000, waveNs = 1_000_000,
            ettrNs = 1_000_000, focusNs = 1_000_000)
    }

    @Test fun `identical frames pass with the speedup ratio`() {
        val report = reportFor(frame())
        assertTrue(report.passed)
        assertTrue(report.diffs.isEmpty())
        assertEquals(4_000_000L, report.kotlinNs)
        assertEquals(4.0, report.speedup, 0.0)
    }

    @Test fun `one int bin flip fails and names the cell`() {
        val report = reportFor(frame()) { histogram.red[5] = 7 }
        assertFalse(report.passed)
        assertTrue(report.diffs.any { it.startsWith("hist.r[5]") })
    }

    @Test fun `one ULP float drift fails bitwise`() {
        val g = frame(levels = floatArrayOf(1f, 0f, 0f, 0f))
        val k = frame(levels = floatArrayOf(Float.fromBits(0x3F800001), 0f, 0f, 0f))
        val report = ScopeMeterAB.compare(g, k.histogram, k.waveform, k.ettr, k.focus,
            1, 1, 1, 1, 1)
        assertFalse(report.passed)
        assertTrue(report.diffs.any { it.startsWith("ettr.levels[0]") })
    }

    @Test fun `identical NaN bits pass, differing NaN bits fail`() {
        val nanA = Float.fromBits(0x7FC00000)
        val nanB = Float.fromBits(0x7FC00001)
        val same = reportFor(frame(cw = nanA, spot = nanA, guard = nanA))
        assertTrue(same.passed)
        val g = frame(cw = nanA)
        val k = frame(cw = nanB)
        val report = ScopeMeterAB.compare(g, k.histogram, k.waveform, k.ettr, k.focus,
            1, 1, 1, 1, 1)
        assertFalse(report.passed)
        assertTrue(report.diffs.any { it.startsWith("ettr.means[0]") })
    }

    @Test fun `size and flag mismatches fail`() {
        val g = frame()
        val short = RgbHistogram(IntArray(63), IntArray(64), IntArray(64), IntArray(64), false)
        val r1 = ScopeMeterAB.compare(g, short, g.waveform, g.ettr, g.focus, 1, 1, 1, 1, 1)
        assertFalse(r1.passed)
        assertTrue(r1.diffs.any { it.contains("hist.r size") })
        val flagged = RgbHistogram(IntArray(64), IntArray(64), IntArray(64), IntArray(64), true)
        val r2 = ScopeMeterAB.compare(g, flagged, g.waveform, g.ettr, g.focus, 1, 1, 1, 1, 1)
        assertFalse(r2.passed)
        assertTrue(r2.diffs.any { it.startsWith("hist.agx") })
    }

    @Test fun `focus mask diff fails and grid mismatch is reported`() {
        val g = frame()
        g.focus.mask[100] = true
        val k = frame()
        val r1 = ScopeMeterAB.compare(g, k.histogram, k.waveform, k.ettr, k.focus,
            1, 1, 1, 1, 1)
        assertFalse(r1.passed)
        assertTrue(r1.diffs.any { it.startsWith("focus.mask[100]") })
        val narrow = FocusPeakingFrame(96, 23, BooleanArray(96 * 23))
        val r2 = ScopeMeterAB.compare(g, k.histogram, k.waveform, k.ettr, narrow,
            1, 1, 1, 1, 1)
        assertFalse(r2.passed)
        assertTrue(r2.diffs.any { it.startsWith("focus grid") })
    }
}
