// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class CameraIdCacheTest {
    @Test fun `each id resolves once no matter how often it is read`() {
        var resolutions = 0
        val cache = CameraIdCache { id: String -> resolutions++.let { "route-$id" } }
        repeat(5) { assertEquals("route-0", cache.get("0")) }
        assertEquals(1, resolutions)
    }

    @Test fun `rejected ids resolve again so transient vendor responses never poison`() {
        // Xiaomi returns bogus characteristics while the camera service is
        // still initializing: a null cached at startup would wedge every
        // later open of that lens. Nulls re-hit the resolver; only successes
        // are memoized.
        var resolutions = 0
        val cache = CameraIdCache<String> { resolutions++.let { null } }
        repeat(3) { assertNull(cache.get("1")) }
        assertEquals(3, resolutions)
    }

    @Test fun `distinct ids resolve independently`() {
        val cache = CameraIdCache { id: String -> "route-$id" }
        assertEquals("route-0", cache.get("0"))
        assertEquals("route-2", cache.get("2"))
        assertEquals("route-0", cache.get("0"))
    }

    @Test fun `clear forces the next read to resolve again`() {
        var resolutions = 0
        val cache = CameraIdCache { id: String -> resolutions++.let { "route-$id" } }
        assertEquals("route-0", cache.get("0"))
        cache.clear()
        assertEquals("route-0", cache.get("0"))
        assertEquals(2, resolutions)
    }
}
