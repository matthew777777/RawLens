// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScopeLutCacheTest {
    @Test
    fun rebuildsOnlyWhenSettingsChange() {
        var builds = 0
        val cache = ScopeLutCache { builds++; AgxDisplayTransform.buildScopeLut(it) }
        val base = JpegOutputSettings()
        val first = cache.get(base)
        assertSame(first, cache.get(base.copy()))
        assertEquals(1, builds)
        cache.get(base.copy(agxContrast = base.agxContrast + 0.1f))
        assertEquals(2, builds)
    }

    @Test
    fun cachedLutIsBitIdenticalToAFreshBuild() {
        val settings = JpegOutputSettings()
        val cache = ScopeLutCache()
        cache.get(settings)
        assertArrayEquals(AgxDisplayTransform.buildScopeLut(settings), cache.get(settings), 0f)
    }
}
