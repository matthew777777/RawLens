// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class ForwardRawSelectionTest {
    @Test fun `forward topup excludes pre-shutter results and duplicates and reserves exact count`() {
        val selection = ForwardRawSelection(7, 1000L, 2)
        assertFalse(selection.accept(900))
        assertFalse(selection.accept(1000))
        assertTrue(selection.accept(1100))
        assertFalse(selection.accept(1100))
        assertFalse(selection.accept(1050))
        assertTrue(selection.accept(1200))
        assertFalse(selection.accept(1300))
        assertEquals(7, selection.captureId)
    }
}
