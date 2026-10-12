// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDngWriterTest {
    @Test fun `dense unpacked plane takes the zero-copy path`() {
        // Reference HAL layout: no offset, no row padding, 2-byte pixels.
        assertTrue(isContiguousUnpackedRaw(0, 4080 * 2, 2, 4080))
        assertTrue(isContiguousUnpackedRaw(0, 2592 * 2, 2, 2592))
    }

    @Test fun `offset padding or packed pixels need the staging repack`() {
        // Buffer offset.
        assertFalse(isContiguousUnpackedRaw(16, 4080 * 2, 2, 4080))
        // Padded rows.
        assertFalse(isContiguousUnpackedRaw(0, 4080 * 2 + 32, 2, 4080))
        // Packed 10/12-bit pixels.
        assertFalse(isContiguousUnpackedRaw(0, 4080 * 2, 1, 4080))
        // Short rows can never hold the raster.
        assertFalse(isContiguousUnpackedRaw(0, 4080 * 2 - 2, 2, 4080))
    }
}
