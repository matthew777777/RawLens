// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Halide spike: the AOT box-downsample filter must link, run, and
 * match an independent scalar box mean on a 16x16x3 u16 ramp (same fixture as
 * tools/halide/host_check.cpp). No camera. This is the toolchain gate for the
 * whole BGU viewfinder rewrite: generator -> AOT .a per ABI -> JNI -> device.
 */
@RunWith(AndroidJUnit4::class)
class BguSpikeInstrumentedTest {
    @Test fun downsampleMatchesScalarBoxMean() {
        assumeTrue("halide spike library unavailable", BguSpike.available)
        val w = 16
        val h = 16
        val c = 3
        val factor = 8
        val input = ShortArray(w * h * c) { i ->
            val x = i % w
            val y = (i / w) % h
            val ch = i / (w * h)
            (x + y * w + ch * 1000).toShort()
        }
        val out = BguSpike.downsample(input, w, h, c, factor)
        assertNotNull("filter returned null", out)
        val ow = (w + factor - 1) / factor
        val oh = (h + factor - 1) / factor
        assertEquals(ow * oh * c, out!!.size)
        for (oy in 0 until oh) for (ox in 0 until ow) for (ch in 0 until c) {
            var sum = 0
            for (ry in 0 until factor) for (rx in 0 until factor) {
                // Fixture divides evenly; mirror repeat_edge anyway.
                val sx = (ox * factor + rx).coerceIn(0, w - 1)
                val sy = (oy * factor + ry).coerceIn(0, h - 1)
                sum += input[(ch * h + sy) * w + sx].toInt() and 0xffff
            }
            val expected = (sum + factor * factor / 2) / (factor * factor)
            val got = out[(ch * oh + oy) * ow + ox].toInt() and 0xffff
            assertEquals("mismatch at ($ox,$oy,$ch)", expected, got)
        }
    }

    @Test fun downsampleRejectsBadInput() {
        assumeTrue("halide spike library unavailable", BguSpike.available)
        assertNull(BguSpike.downsample(ShortArray(4), 16, 16, 3, 8))
        assertNull(BguSpike.downsample(ShortArray(16 * 16 * 3), 16, 16, 3, 1))
        assertNull(BguSpike.downsample(ShortArray(16 * 16 * 3), 16, 16, 3, 17))
    }
}
