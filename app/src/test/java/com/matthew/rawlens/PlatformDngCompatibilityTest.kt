// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class PlatformDngCompatibilityTest {
    @Test fun `cropped Xiaomi RAW cannot enter platform writer`() {
        assertFalse(PlatformDngCompatibility.matches(4096, 2304, 4096, 3072, 4096, 3072))
    }
    @Test fun `either exact sensor extent is accepted`() {
        assertTrue(PlatformDngCompatibility.matches(4096, 3072, 4096, 3072, 4128, 3096))
        assertTrue(PlatformDngCompatibility.matches(4128, 3096, 4096, 3072, 4128, 3096))
        assertFalse(PlatformDngCompatibility.matches(4096, 3096, 4096, 3072, 4128, 3096))
    }
    @Test fun `missing dimensions cannot prove platform compatibility`() {
        assertFalse(PlatformDngCompatibility.matches(4096, 2304, null, null, null, null))
    }
}
