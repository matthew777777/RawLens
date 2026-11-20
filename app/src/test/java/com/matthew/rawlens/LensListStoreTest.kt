// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class LensListStoreTest {
    @Test fun savedOrderWinsAndNewLensesKeepWidestFirst() {
        val widest = listOf("4", "2", "3", "1")
        assertEquals(listOf("3", "2", "4", "1"), LensListStore.applyOrder(widest, listOf("3", "2")))
        // Stale ids in the saved order are ignored.
        assertEquals(widest, LensListStore.applyOrder(widest, listOf("9")))
    }

    @Test fun moveStaysInBounds() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), LensListStore.move(ids, "b", -1))
        assertEquals(ids, LensListStore.move(ids, "a", -1))
        assertEquals(ids, LensListStore.move(ids, "c", +1))
        assertEquals(ids, LensListStore.move(ids, "x", +1))
    }

    @Test fun namesAreTrimmedCappedAndBlankMeansAutomatic() {
        assertEquals("UW", LensListStore.cleanName("  UW "))
        assertEquals("TELEP", LensListStore.cleanName("TELEPHOTO"))
        assertNull(LensListStore.cleanName("   "))
        assertEquals(listOf("2", "4"), LensListStore.parseOrder(" 2, ,4,2"))
    }
}
