// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class LkrStageBitsTest {
    @Test fun packsAtTheDepthTheWhiteLevelNeedsUpToTwelveBits() {
        assertEquals(10, LkrMerge.stageBits(1023f))
        assertEquals(11, LkrMerge.stageBits(1024f))   // one above 10 bits needs 11
        assertEquals(12, LkrMerge.stageBits(4095f))
        // Deeper sensors and unknown levels stay plain 16-bit.
        assertEquals(16, LkrMerge.stageBits(16383f))
        assertEquals(16, LkrMerge.stageBits(65535f))
        assertEquals(16, LkrMerge.stageBits(0f))
    }
}
