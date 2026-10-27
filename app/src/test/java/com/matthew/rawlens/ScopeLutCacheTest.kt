// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    @Test
    fun exposureIsAppliedBeforeTheLutDomainClamp() {
        val settings = JpegOutputSettings(highlightShoulder = 0f)
        val cache = ScopeLutCache()
        val exposed = cache.get(settings, 4.0)
        // Both inputs exceed sensor white after +4 EV. They must retain
        // distinct output levels instead of both stopping at curve(1).
        val low = AgxDisplayTransform.scopeLutLookup(exposed, 0.125)
        val high = AgxDisplayTransform.scopeLutLookup(exposed, 0.5)
        assertTrue(high > low)
        assertEquals(AgxDisplayTransform.scopeCurve(8f, settings), high, 0.002f)
        assertSame(exposed, cache.get(settings, 4.0))
        val unexposed = cache.get(settings, 0.0)
        assertTrue(AgxDisplayTransform.scopeLutLookup(unexposed, 0.5) < high)
    }

}
