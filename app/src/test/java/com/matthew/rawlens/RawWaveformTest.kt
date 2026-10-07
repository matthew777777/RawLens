// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawWaveformTest {
    @Test
    fun columnMappingSpansFrameAndClamps() {
        val width = 4000
        assertEquals(0, RawWaveformSampler.columnOf(0, width))
        assertEquals(
            RawWaveformSampler.COLUMNS - 1,
            RawWaveformSampler.columnOf(width - 1, width)
        )
        assertEquals(
            RawWaveformSampler.COLUMNS / 2,
            RawWaveformSampler.columnOf(width / 2, width)
        )
        assertEquals(0, RawWaveformSampler.columnOf(-50, width))
        assertEquals(
            RawWaveformSampler.COLUMNS - 1,
            RawWaveformSampler.columnOf(width + 50, width)
        )
    }

    @Test
    fun levelMappingSpansBlackToWhiteAndClamps() {
        assertEquals(0, RawWaveformSampler.levelOf(0.0))
        assertEquals(RawWaveformSampler.LEVELS - 1, RawWaveformSampler.levelOf(1.0))
        assertEquals(0, RawWaveformSampler.levelOf(-0.5))
        assertEquals(RawWaveformSampler.LEVELS - 1, RawWaveformSampler.levelOf(1.5))
        var previous = -1
        var value = 0.0
        while (value <= 1.0) {
            val level = RawWaveformSampler.levelOf(value)
            assertTrue(level >= previous)
            previous = level
            value += 0.01
        }
    }

    @Test
    fun scopeLutLookupClampsToEnds() {
        val lut = AgxDisplayTransform.buildScopeLut(JpegOutputSettings())
        assertEquals(lut.first(), AgxDisplayTransform.scopeLutLookup(lut, -1.0), 0f)
        assertEquals(lut.first(), AgxDisplayTransform.scopeLutLookup(lut, 0.0), 0f)
        assertEquals(lut.last(), AgxDisplayTransform.scopeLutLookup(lut, 1.0), 0f)
        assertEquals(lut.last(), AgxDisplayTransform.scopeLutLookup(lut, 2.0), 0f)
    }

    @Test
    fun scopeModeCyclesAndRoundTripsPreference() {
        assertEquals(ScopeMode.WAVEFORM, ScopeMode.HISTOGRAM.next())
        assertEquals(ScopeMode.HISTOGRAM, ScopeMode.WAVEFORM.next())
        assertEquals(ScopeMode.WAVEFORM, ScopeMode.fromPreference("WAVEFORM"))
        assertEquals(ScopeMode.HISTOGRAM, ScopeMode.fromPreference(null))
        assertEquals(ScopeMode.HISTOGRAM, ScopeMode.fromPreference("bogus"))
    }
}
