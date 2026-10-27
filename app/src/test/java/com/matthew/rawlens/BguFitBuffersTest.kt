// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Fit-thread-local frame buffer reuse and resize rules. */
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

}
