// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Fit buffer-pool pins: reuse identity, realloc rules, ping-pong order. */
class BguFitBuffersTest {
    @Test fun frameReusesWhileDimsMatch() {
        val b = BguFitBuffers()
        val a = b.frame(128, 96)
        val again = b.frame(128, 96)
        assertSame(a.guide, again.guide)
        assertSame(a.developed, again.developed)
        assertEquals(128 * 96 * 3, a.guide.size)
    }

    @Test fun frameReallocatesOnDimChange() {
        val b = BguFitBuffers()
        val a = b.frame(128, 96)
        val resized = b.frame(64, 48)
        assertEquals(64 * 48 * 3, resized.guide.size)
        assertEquals(64 * 48 * 3, resized.developed.size)
        // Old arrays still intact for any in-flight consumer.
        assertEquals(128 * 96 * 3, a.guide.size)
    }

    @Test fun backGridPingPongsAndResizes() {
        val b = BguFitBuffers()
        val g0 = b.backGrid(8, 6, 9)
        assertEquals(8 * 6 * 9 * 12, g0.size)
        b.flip()
        val g1 = b.backGrid(8, 6, 9)
        assertEquals(8 * 6 * 9 * 12, g1.size)
        b.flip()
        assertSame(g0, b.backGrid(8, 6, 9))
        assertSame(g1, run { b.flip(); b.backGrid(8, 6, 9) })
        // Dim change reallocates both sides to the new size.
        val r0 = b.backGrid(4, 3, 9)
        assertEquals(4 * 3 * 9 * 12, r0.size)
        b.flip()
        assertEquals(4 * 3 * 9 * 12, b.backGrid(4, 3, 9).size)
    }
}
