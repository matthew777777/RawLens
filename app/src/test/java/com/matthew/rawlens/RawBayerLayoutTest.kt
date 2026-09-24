// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class RawBayerLayoutTest {
    @Test fun `vendor flag on regular Bayer cameras does not reject development`() {
        assertFalse(RawBayerLayout.requiresRemosaic(true, false, false))
        assertFalse(RawBayerLayout.requiresRemosaic(true, true, false))
        assertFalse(RawBayerLayout.requiresRemosaic(true, false, true))
    }
    @Test fun `explicit grouped dimensions take precedence over incomplete capabilities`() {
        assertTrue(RawBayerLayout.requiresRemosaic(true, false, false, 2, 2))
        assertFalse(RawBayerLayout.requiresRemosaic(false, true, true, 2, 2))
    }
    @Test fun `grouped CFA remains protected from regular Bayer demosaic`() {
        assertTrue(RawBayerLayout.requiresRemosaic(true, true, true))
        assertFalse(RawBayerLayout.requiresRemosaic(false, true, true))
        assertFalse(RawBayerLayout.requiresRemosaic(null, true, true))
    }
}
