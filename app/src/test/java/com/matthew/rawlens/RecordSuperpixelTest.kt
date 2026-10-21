// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordSuperpixelTest {
    // 16x16 GBRG mosaic whose every site holds a linear ramp in sensor
    // position (code = black + (x + 2y) * k): a correctly centred
    // superpixel reads every channel at the quad centre, so R, G and B
    // must agree (no half-pixel colour misregistration).
    private val w = 16
    private val h = 16
    private val channels = intArrayOf(2, 0, 3, 1)  // GBRG
    private val levels = floatArrayOf(64f, 64f, 64f, 64f)
    private val ramp = IntArray(w * h) { 64 + ((it % w) + 2 * (it / w)) * 10 }

    @Test fun centreAlignedStep2PutsAllChannelsAtTheQuadCentre() {
        val out = RecordSuperpixel.develop(ramp, w, channels, 0, 0, 8, 8, 2, levels, 1023f)
        // Interior texels (edges replicate, so skip them).
        for (j in 1 until 7) for (i in 1 until 7) {
            val o = (j * 8 + i) * 4
            val g = (out[o + 1] + out[o + 2]) / 2f
            assertEquals("R at ($i,$j)", g, out[o], 1e-4f)
            assertEquals("B at ($i,$j)", g, out[o + 3], 1e-4f)
            // Quad centre (2i + .5, 2j + .5) on the ramp.
            assertEquals(((2 * i + 0.5f) + 2 * (2 * j + 0.5f)) * 10f / 959f, g, 1e-4f)
        }
    }

    @Test fun step4IsTheBlockMeanOfEveryQuad() {
        val codes = IntArray(w * h) { if ((it % w) % 4 == 0) 1023 else 64 }  // sparse bright columns
        val out = RecordSuperpixel.develop(codes, w, channels, 0, 0, 4, 4, 4, levels, 1023f)
        // Every 4-wide block holds one bright column of 4: G sites there are
        // 1 of 4 Gr/Gb samples in the block -> mean 0.5 for the site that
        // lands on it, never the all-or-nothing of a point sample.
        for (t in 0 until 16) {
            val o = t * 4
            for (s in 0..3) assertEquals(true, out[o + s] == 0f || out[o + s] == 0.5f)
        }
        assertEquals(0.5f, (0 until 16).map { out[it * 4] + out[it * 4 + 1] + out[it * 4 + 2] + out[it * 4 + 3] }.average().toFloat() / 2f, 1e-6f)
    }
}
