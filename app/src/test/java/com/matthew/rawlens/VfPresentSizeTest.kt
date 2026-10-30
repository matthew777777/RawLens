// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class VfPresentSizeTest {
    @Test fun nativePreviewBoundsReplaceFullScreenShading() {
        assertEquals(VfPresentSize.Size(723, 1080), VfPresentSize.forLayout(1080, 1613, 1080))
        assertEquals(VfPresentSize.Size(643, 960), VfPresentSize.forLayout(1080, 1613, 960))
        assertEquals(VfPresentSize.Size(1080, 723), VfPresentSize.forLayout(1613, 1080, 1080))
        assertEquals(VfPresentSize.Size(480, 640), VfPresentSize.forLayout(480, 640, 1080))
    }

    @Test fun preservesAspectAndNeverUpscalesAnyLayout() {
        val random = java.util.Random(11213)
        repeat(10000) {
            val w = 1 + random.nextInt(5000)
            val h = 1 + random.nextInt(5000)
            val edge = intArrayOf(480, 640, 960, 1080)[random.nextInt(4)]
            val s = VfPresentSize.forLayout(w, h, edge)
            assertTrue(s.width in 1..w && s.height in 1..h)
            assertEquals(minOf(maxOf(w, h), edge), maxOf(s.width, s.height))
            val idealW = w * minOf(1.0, edge.toDouble() / maxOf(w, h))
            val idealH = h * minOf(1.0, edge.toDouble() / maxOf(w, h))
            assertTrue(kotlin.math.abs(s.width - idealW) <= 1)
            assertTrue(kotlin.math.abs(s.height - idealH) <= 1)
        }
    }
}
