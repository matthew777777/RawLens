// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class VfRenderSanitizeTest {
    @Test
    fun wbGainKeepsSaneValues() {
        assertEquals(1f, RawViewfinder.sanitizeWbGain(1f), 0f)
        assertEquals(0.5f, RawViewfinder.sanitizeWbGain(0.5f), 0f)
        assertEquals(2.3125f, RawViewfinder.sanitizeWbGain(2.3125f), 0f)
        assertEquals(0f, RawViewfinder.sanitizeWbGain(0f), 0f)
    }

    @Test
    fun wbGainRejectsNonFiniteAndNegative() {
        // A bogus HAL gain would poison every presented frame (NaN renders
        // black on Adreno): unity fallback.
        assertEquals(1f, RawViewfinder.sanitizeWbGain(Float.NaN), 0f)
        assertEquals(1f, RawViewfinder.sanitizeWbGain(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, RawViewfinder.sanitizeWbGain(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(1f, RawViewfinder.sanitizeWbGain(-0.5f), 0f)
    }

    @Test
    fun ccmKeepsNegatives() {
        // Off-diagonal CCM elements are legitimately negative.
        assertEquals(-0.2f, RawViewfinder.sanitizeCcmElement(-0.2f, 0f), 0f)
        assertEquals(1.7f, RawViewfinder.sanitizeCcmElement(1.7f, 1f), 0f)
    }

    @Test
    fun ccmRejectsNonFinite() {
        // Zero-denominator Rationals convert to NaN/Inf: identity fallback.
        assertEquals(1f, RawViewfinder.sanitizeCcmElement(Float.NaN, 1f), 0f)
        assertEquals(0f, RawViewfinder.sanitizeCcmElement(Float.NaN, 0f), 0f)
        assertEquals(1f, RawViewfinder.sanitizeCcmElement(Float.POSITIVE_INFINITY, 1f), 0f)
    }
}
