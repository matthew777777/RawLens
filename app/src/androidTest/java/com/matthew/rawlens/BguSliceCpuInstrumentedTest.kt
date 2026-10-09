// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * Step-7 CPU-slice check on device: the native slice must match the JVM
 * scalar reference within 1 LSB on a textured guide + non-trivial grid, and
 * report its wall time (fallback budget).
 */
@RunWith(AndroidJUnit4::class)
class BguSliceCpuInstrumentedTest {
    @Test fun nativeMatchesScalarWithinOneLsb() {
        assumeTrue("slice cpu unavailable", BguSliceCpu.available)
        val w = 160
        val h = 120
        val gw = 5
        val gh = 4
        val gz = 9
        // Textured guide (gradient + checker), deterministic.
        val guide = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            guide[i] = (((x * 255) / (w - 1)) and 0xff).toByte()
            guide[i + 1] = (((y * 255) / (h - 1)) and 0xff).toByte()
            guide[i + 2] = ((((x + y) % 2) * 255) and 0xff).toByte()
            guide[i + 3] = ((((x * 7 + y * 13) % 256) + 256) % 256).toByte()
        }
        // Non-trivial grid: per-cell gain ramp + bias (planar layout).
        val coeffs = FloatArray(gw * gh * gz * 12)
        for (z in 0 until gz) for (y in 0 until gh) for (x in 0 until gw) {
            val t = (x + y + z).toFloat() / (gw + gh + gz)
            for (row in 0..2) for (col in 0..3) {
                val v = if (row == col) 0.8f + 0.4f * t else 0.01f * (t - 0.5f)
                coeffs[x + gw * (y + gh * (z + gz * (row * 4 + col)))] = v
            }
        }
        val grid = BguGrid(gw, gh, gz, coeffs)
        val sx = 8f
        val sy = 6f
        val sz = 8f
        val want = BguSliceCpu.sliceScalar(guide, w, h, grid, sx, sy, sz, true)
        val guideBuf = ByteBuffer.allocateDirect(guide.size)
        guideBuf.put(guide)
        guideBuf.flip()
        val outBuf = ByteBuffer.allocateDirect(w * h * 4)
        val t0 = System.nanoTime()
        val rc = BguSliceCpu.slice(guideBuf, w, h, coeffs, gw, gh, gz, sx, sy, sz, true, outBuf)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(0, rc)
        val got = ByteArray(w * h * 4)
        outBuf.get(got)
        var maxDiff = 0
        for (i in want.indices) {
            val d = kotlin.math.abs((want[i].toInt() and 0xff) - (got[i].toInt() and 0xff))
            if (d > maxDiff) maxDiff = d
        }
        Log.i(TAG, "slicecpu ${w}x${h} maxDiff=${maxDiff}LSB wallMs=%.3f".format(ms))
        assertTrue("maxDiff=$maxDiff", maxDiff <= 1)

        // Step-2 parity on the same fixture.
        val wantHalf = BguSliceCpu.sliceScalar(guide, w, h, grid, sx, sy, sz, true, step = 2)
        val outHalfBuf = ByteBuffer.allocateDirect((w / 2) * (h / 2) * 4)
        guideBuf.rewind()
        val rcHalf = BguSliceCpu.slice(
            guideBuf, w, h, coeffs, gw, gh, gz, sx, sy, sz, true, outHalfBuf, step = 2
        )
        assertEquals(0, rcHalf)
        val gotHalf = ByteArray((w / 2) * (h / 2) * 4)
        outHalfBuf.get(gotHalf)
        var maxHalf = 0
        for (i in wantHalf.indices) {
            val d = kotlin.math.abs((wantHalf[i].toInt() and 0xff) - (gotHalf[i].toInt() and 0xff))
            if (d > maxHalf) maxHalf = d
        }
        Log.i(TAG, "slicecpu half maxDiff=${maxHalf}LSB")
        assertTrue("maxHalf=$maxHalf", maxHalf <= 1)

        // Production-size budget leg (timing only): full guide + grid.
        val fw = 1020
        val fh = 764
        val fguide = ByteArray(fw * fh * 4) { ((it * 1103515245 + 12345) ushr 16).toByte() }
        val fgw = 8
        val fgh = 6
        val fgz = 9
        val fcoeffs = FloatArray(fgw * fgh * fgz * 12) { i -> ((i % 12) % 5) * 0.25f }
        val fguideBuf = ByteBuffer.allocateDirect(fguide.size)
        fguideBuf.put(fguide)
        fguideBuf.flip()
        val foutBuf = ByteBuffer.allocateDirect(fw * fh * 4)
        // Warmup, then 5 timed runs.
        BguSliceCpu.slice(fguideBuf, fw, fh, fcoeffs, fgw, fgh, fgz, 8f, 6f, 8f, true, foutBuf)
        fguideBuf.rewind()
        foutBuf.clear()
        var best = Long.MAX_VALUE
        repeat(5) {
            fguideBuf.rewind()
            foutBuf.clear()
            val t = System.nanoTime()
            val r = BguSliceCpu.slice(
                fguideBuf, fw, fh, fcoeffs, fgw, fgh, fgz, 8f, 6f, 8f, true, foutBuf
            )
            assertEquals(0, r)
            val dt = System.nanoTime() - t
            if (dt < best) best = dt
        }
        Log.i(TAG, "slicecpu budget ${fw}x${fh} bestMs=%.3f".format(best / 1e6))

        // Half-res slice budget (the fallback operating point).
        val houtBuf = ByteBuffer.allocateDirect((fw / 2) * (fh / 2) * 4)
        fguideBuf.rewind()
        BguSliceCpu.slice(fguideBuf, fw, fh, fcoeffs, fgw, fgh, fgz, 8f, 6f, 8f, true, houtBuf, 2)
        var hbest = Long.MAX_VALUE
        repeat(5) {
            fguideBuf.rewind()
            houtBuf.clear()
            val t = System.nanoTime()
            val r = BguSliceCpu.slice(
                fguideBuf, fw, fh, fcoeffs, fgw, fgh, fgz, 8f, 6f, 8f, true, houtBuf, 2
            )
            assertEquals(0, r)
            val dt = System.nanoTime() - t
            if (dt < hbest) hbest = dt
        }
        Log.i(TAG, "slicecpu budget ${fw / 2}x${fh / 2} bestMs=%.3f".format(hbest / 1e6))

        // CPU guide budget: synthetic 4080x3060 RGGB -> 1020x764 quad bytes.
        val sw = 4080
        val sh = 3060
        val src = ByteBuffer.allocateDirect(sw * sh * 2)
        val sv = src.asShortBuffer()
        for (i in 0 until sw * sh) sv.put(i, ((i * 1103515245 + 12345) ushr 16).toShort())
        val gdst = ByteBuffer.allocateDirect(fw * fh * 4)
        val channels = intArrayOf(0, 1, 2, 3)
        val black = floatArrayOf(64f, 64f, 64f, 64f)
        VfCpuNeon.copy(src, sw * 2, 2, 0, 0, fw, fh, 4, channels, black, 1023f, gdst)
        var gbest = Long.MAX_VALUE
        repeat(5) {
            src.rewind()
            gdst.clear()
            val t = System.nanoTime()
            VfCpuNeon.copy(src, sw * 2, 2, 0, 0, fw, fh, 4, channels, black, 1023f, gdst)
            val dt = System.nanoTime() - t
            if (dt < gbest) gbest = dt
        }
        Log.i(TAG, "slicecpu budget guide ${fw}x${fh} bestMs=%.3f".format(gbest / 1e6))
    }

    companion object {
        private const val TAG = "BguSliceCpuTest"
    }
}
