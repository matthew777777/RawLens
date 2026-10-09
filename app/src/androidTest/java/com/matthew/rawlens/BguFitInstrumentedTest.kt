// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device BGU fit: runs the Halide AOT fit through real JNI, then verifies
 * the fit's defining property with an independent Kotlin trilinear slice —
 * slicing the grid at the training guide must reproduce the developed output.
 * Logs fit time (benchmark signal; a generous hang tripwire only).
 */
@RunWith(AndroidJUnit4::class)
class BguFitInstrumentedTest {
    @Test fun fitReconstructsAffineTarget() {
        assumeTrue("halide library unavailable", BguFit.available)
        val w = 34
        val h = 25
        val s = 16
        val r = 1f / 8
        var seed = 0xB6F17
        fun nextUnit(): Float {
            seed = seed * 1664525 + 1013904223
            return ((seed ushr 8) and 0xffffff) / 16777216f
        }
        val guide = FloatArray(w * h * 3)
        val dev = FloatArray(w * h * 3)
        val gains = floatArrayOf(1.1f, 1.0f, 0.9f)
        for (y in 0 until h) for (x in 0 until w) {
            val base = floatArrayOf(
                (x + 0.5f) / w + (nextUnit() - 0.5f) * 0.02f,
                (y + 0.5f) / h + (nextUnit() - 0.5f) * 0.02f,
                0.5f + 0.5f * kotlin.math.sin((x * 0.7f + y * 1.3f) * 0.1f) +
                    (nextUnit() - 0.5f) * 0.02f
            )
            for (ch in 0..2) {
                guide[(ch * h + y) * w + x] = base[ch]
                dev[(ch * h + y) * w + x] = base[ch] * gains[ch] + 0.02f * (ch + 1)
            }
        }

        val startNs = System.nanoTime()
        val grid = BguFit.fit(guide, dev, w, h, s, r)
        val elapsedMs = (System.nanoTime() - startNs) / 1e6
        Log.i(TAG, "bgu fit ${w}x$h s=$s bins=${(1 / r).toInt()} took ${"%.2f".format(elapsedMs)} ms")
        assertNotNull("fit returned null", grid)
        assertTrue("fit too slow (>${HANG_MS}ms): $elapsedMs", elapsedMs < HANG_MS)
        grid!!.coeffs.forEach { assertTrue("non-finite coeff", it.isFinite()) }

        // Independent trilinear slice at every training pixel.
        var sumAbs = 0.0
        var maxAbs = 0.0
        var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            val rgb = FloatArray(3) { guide[(it * h + y) * w + x] }
            val luma = (0.25f * rgb[0] + 0.5f * rgb[1] + 0.25f * rgb[2]).coerceIn(0f, 1f)
            val gx = x.toFloat() / s
            val gy = y.toFloat() / s
            val gz = luma / r
            for (row in 0..2) {
                val m = FloatArray(4) { k -> trilerp(grid, gx, gy, gz, row * 4 + k) }
                val got = m[0] * rgb[0] + m[1] * rgb[1] + m[2] * rgb[2] + m[3]
                val err = kotlin.math.abs(got - dev[(row * h + y) * w + x]).toDouble()
                sumAbs += err
                maxAbs = maxOf(maxAbs, err)
                n++
            }
        }
        Log.i(TAG, "reconstruction mean=${sumAbs / n} max=$maxAbs")
        assertTrue("mean ${sumAbs / n} above tripwire", sumAbs / n < 2e-4)
        assertTrue("max $maxAbs above tripwire", maxAbs < 2e-2)
    }

    @Test fun fitRejectsBadInput() {
        assumeTrue("halide library unavailable", BguFit.available)
        assertNull(BguFit.fit(FloatArray(4), FloatArray(34 * 25 * 3), 34, 25))
        assertNull(BguFit.fit(FloatArray(34 * 25 * 3), FloatArray(4), 34, 25))
    }

    @Test fun fitIntoMatchesFitAndRejectsShortBuffer() {
        assumeTrue("halide library unavailable", BguFit.available)
        val w = 34
        val h = 25
        val guide = FloatArray(w * h * 3) { (it % 251) / 251f }
        val dev = FloatArray(w * h * 3) { (it % 251) / 251f * 1.1f + 0.02f }
        val ref = BguFit.fit(guide, dev, w, h)
        assertNotNull(ref)
        val dims = BguFit.gridDims(w, h)
        val coeffs = FloatArray(dims[0] * dims[1] * dims[2] * 12)
        assertEquals(0, BguFit.fitInto(guide, dev, w, h, coeffs))
        assertArrayEquals(ref!!.coeffs, coeffs, 0f)
        assertEquals(-1, BguFit.fitInto(guide, dev, w, h, FloatArray(8)))
    }

    private fun trilerp(grid: BguGrid, gx: Float, gy: Float, gz: Float, ch: Int): Float {
        val x0 = gx.toInt().coerceIn(0, grid.gw - 1)
        val y0 = gy.toInt().coerceIn(0, grid.gh - 1)
        val z0 = gz.toInt().coerceIn(0, grid.gz - 1)
        val x1 = (x0 + 1).coerceIn(0, grid.gw - 1)
        val y1 = (y0 + 1).coerceIn(0, grid.gh - 1)
        val z1 = (z0 + 1).coerceIn(0, grid.gz - 1)
        val fx = (gx - gx.toInt()).coerceIn(0f, 1f)
        val fy = (gy - gy.toInt()).coerceIn(0f, 1f)
        val fz = (gz - gz.toInt()).coerceIn(0f, 1f)
        fun c(x: Int, y: Int, z: Int): Float {
            val row = ch / 4
            val col = ch % 4
            return grid.at(x, y, z, row, col)
        }
        val c00 = c(x0, y0, z0) + (c(x1, y0, z0) - c(x0, y0, z0)) * fx
        val c10 = c(x0, y1, z0) + (c(x1, y1, z0) - c(x0, y1, z0)) * fx
        val c01 = c(x0, y0, z1) + (c(x1, y0, z1) - c(x0, y0, z1)) * fx
        val c11 = c(x0, y1, z1) + (c(x1, y1, z1) - c(x0, y1, z1)) * fx
        val c0 = c00 + (c10 - c00) * fy
        val c1 = c01 + (c11 - c01) * fy
        return c0 + (c1 - c0) * fz
    }

    companion object {
        private const val TAG = "BguFitTest"
        private const val HANG_MS = 2000.0
    }
}
