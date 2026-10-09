// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

/**
 * On-device BGU look: runs the Halide AOT look through real JNI and checks the
 * RAW-identity branch against an independent Kotlin Reinhard reference, plus a
 * JPEG-default smoke (finite, in range, non-trivial). No camera.
 */
@RunWith(AndroidJUnit4::class)
class BguLookInstrumentedTest {
    @Test fun rawIdentityMatchesReinhard() {
        assumeTrue("halide library unavailable", BguLook.available)
        val w = 12
        val h = 9
        val edges = byteArrayOf(0, 1, 127, 128.toByte(), 254.toByte(), 255.toByte())
        // Planar packing (channel outermost): quad[ch * w * h + y * w + x].
        val quad = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) for (ch in 0..3) {
            quad[(ch * h + y) * w + x] = edges[((y * w + x) * 4 + ch) % edges.size]
        }
        val out = BguLook.run(
            quad, w, h,
            lens = FloatArray(1 * 1 * 4) { 1f }, lensCols = 1, lensRows = 1,
            wb = floatArrayOf(1f, 1f, 1f, 1f),
            ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            aces = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            white = floatArrayOf(1f, 1f, 1f),
            fparams = FloatArray(BguLook.FP_COUNT),
            iparams = IntArray(BguLook.IP_COUNT)
        )
        assertNotNull("look returned null", out)
        for (y in 0 until h) for (x in 0 until w) {
            val q = DoubleArray(4) { ((quad[(it * h + y) * w + x].toInt() and 0xff) / 255.0) }
            val merged = doubleArrayOf(q[0], (q[1] + q[2]) * 0.5, q[3])
            for (ch in 0..2) {
                val guideGot = out!!.guide[(ch * h + y) * w + x].toDouble()
                assertEquals("guide ($x,$y,$ch)", merged[ch], guideGot, 1e-6)
                val lin = merged[ch] * 2.0
                val want = (lin / (1.0 + lin)).pow(1.0 / 2.2)
                val got = out.developed[(ch * h + y) * w + x].toDouble()
                assertEquals("developed ($x,$y,$ch)", want, got, 3e-3)
            }
        }
    }

    @Test fun jpegDefaultIsFiniteAndLively() {
        assumeTrue("halide library unavailable", BguLook.available)
        val w = 8
        val h = 8
        // Mid-gray ramp quads (planar packing).
        val quad = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) for (ch in 0..3) {
            quad[(ch * h + y) * w + x] = (((y * w + x) * 255 / (w * h)) and 0xff).toByte()
        }
        val fparams = FloatArray(BguLook.FP_COUNT)
        fparams[BguLook.FP_EV] = 0f
        fparams[BguLook.FP_CONTRAST] = 1f
        fparams[BguLook.FP_SATURATION] = 1f
        fparams[BguLook.FP_PURITY] = 1f
        fparams[BguLook.FP_HUE] = 0f
        fparams[BguLook.FP_SHADOW_EV] = 10f
        fparams[BguLook.FP_HIGHLIGHT_EV] = 6.5f
        fparams[BguLook.FP_GAMUT] = 0f
        fparams[BguLook.FP_SHOULDER] = 1f
        val iparams = IntArray(BguLook.IP_COUNT)
        iparams[BguLook.IP_JPEG] = 1
        val out = BguLook.run(
            quad, w, h,
            lens = FloatArray(1 * 1 * 4) { 1f }, lensCols = 1, lensRows = 1,
            wb = floatArrayOf(1f, 1f, 1f, 1f),
            ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            aces = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            white = floatArrayOf(1f, 1f, 1f),
            fparams, iparams
        )
        assertNotNull("look returned null", out)
        var sum = 0.0
        out!!.developed.forEach {
            assertTrue("non-finite", it.isFinite())
            assertTrue("out of range: $it", it >= 0f && it <= 1f)
            sum += it
        }
        // Mid-gray through AgX must not collapse to black or blow to white.
        val mean = sum / out.developed.size
        assertTrue("dead output (mean=$mean)", mean > 0.05 && mean < 0.95)
    }

    @Test fun jpegProductionLike128x96() {
        assumeTrue("halide library unavailable", BguLook.available)
        // Production-sized smooth fixture with the logged production matrix.
        // Formula-defined so the host check can replicate it exactly.
        val w = 128
        val h = 96
        val quad = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            // Dark like the failing production scene (means ~0.02-0.06).
            val v = ((x * 24 / (w - 1) + y * 24 / (h - 1)) / 2 + 2) % 26
            for (ch in 0..3) {
                quad[(ch * h + y) * w + x] = ((v + ch) % 26).toByte()
            }
        }
        val fparams = FloatArray(BguLook.FP_COUNT)
        fparams[BguLook.FP_EV] = 0.7f
        fparams[BguLook.FP_CONTRAST] = 1f
        fparams[BguLook.FP_SATURATION] = 1f
        fparams[BguLook.FP_PURITY] = 1f
        fparams[BguLook.FP_HUE] = 0f
        fparams[BguLook.FP_SHADOW_EV] = 10f
        fparams[BguLook.FP_HIGHLIGHT_EV] = 6.5f
        fparams[BguLook.FP_GAMUT] = 0f
        fparams[BguLook.FP_SHOULDER] = 1f
        val iparams = IntArray(BguLook.IP_COUNT)
        iparams[BguLook.IP_JPEG] = 1
        val out = BguLook.run(
            quad, w, h,
            lens = FloatArray(1 * 1 * 4) { 1f }, lensCols = 1, lensRows = 1,
            wb = floatArrayOf(1.883f, 1f, 1f, 1.725f),
            ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            aces = floatArrayOf(1.827f, -0.004f, 0.063f, 0.075f, 1.206f, -0.297f, -0.077f, -0.352f, 2.179f),
            white = floatArrayOf(0.531f, 1f, 0.58f),
            fparams, iparams
        )
        assertNotNull("look returned null", out)
        val n = w * h
        fun mean(a: FloatArray, ch: Int): Double {
            var s = 0.0
            for (i in 0 until n) s += a[ch * n + i]
            return s / n
        }
        val gm = DoubleArray(3) { mean(out!!.guide, it) }
        val dm = DoubleArray(3) { mean(out!!.developed, it) }
        android.util.Log.i(
            "BguLookTest",
            "prodlike guide=${gm.joinToString(",") { "%.4f".format(it) }} " +
                "dev=${dm.joinToString(",") { "%.4f".format(it) }}"
        )
        out!!.developed.forEach { assertTrue("non-finite", it.isFinite()) }
        // Balanced fixture through a sane matrix: green must track R/B.
        assertTrue("green crushed: ${dm.joinToString(",")}", dm[1] > dm[0] * 0.5 && dm[1] > dm[2] * 0.5)
    }

    @Test fun lensInterleavedStridesHonored() {
        assumeTrue("halide library unavailable", BguLook.available)
        // Uniform gray quads through the RAW branch (identity CCM/WB): with a
        // uniform planar map of [R=1, Ge=4, Go=4, B=1], developed R/B must be
        // bit-identical to the un-lensed run while developed G must jump. Any
        // layout/stride regression would leak the 4x gains into R/B.
        val w = 16
        val h = 12
        val quad = ByteArray(w * h * 4) { 128.toByte() }
        val cols = 4
        val rows = 3
        val cells = cols * rows
        // Planar packing (channel outermost), [R=1, Ge=4, Go=4, B=1].
        val lens = FloatArray(cells * 4)
        for (cell in 0 until cells) {
            lens[cell] = 1f
            lens[cells + cell] = 4f
            lens[2 * cells + cell] = 4f
            lens[3 * cells + cell] = 1f
        }
        val iparams = IntArray(BguLook.IP_COUNT)
        iparams[BguLook.IP_APPLY_LENS] = 1
        iparams[BguLook.IP_AL] = 0
        iparams[BguLook.IP_AT] = 0
        iparams[BguLook.IP_AR] = w
        iparams[BguLook.IP_AB] = h
        val lensed = BguLook.run(
            quad, w, h, lens, cols, rows,
            wb = floatArrayOf(1f, 1f, 1f, 1f),
            ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            aces = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            white = floatArrayOf(1f, 1f, 1f),
            fparams = FloatArray(BguLook.FP_COUNT), iparams
        )
        iparams[BguLook.IP_APPLY_LENS] = 0
        val plain = BguLook.run(
            quad, w, h, lens, cols, rows,
            wb = floatArrayOf(1f, 1f, 1f, 1f),
            ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            aces = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            white = floatArrayOf(1f, 1f, 1f),
            fparams = FloatArray(BguLook.FP_COUNT), iparams
        )
        assertNotNull(lensed)
        assertNotNull(plain)
        val n = w * h
        // Guide is unlensed by design: identical in both runs.
        assertArrayEquals(lensed!!.guide, plain!!.guide, 0f)
        for (i in 0 until n) {
            assertEquals("R leaked lens gain at $i", plain.developed[i], lensed.developed[i], 0f)
            assertEquals("B leaked lens gain at $i", plain.developed[2 * n + i], lensed.developed[2 * n + i], 0f)
            val dg = lensed.developed[n + i] - plain.developed[n + i]
            assertTrue("G ignored lens gain at $i (delta=$dg)", dg > 0.1f)
        }
    }

    @Test fun rawAsymmetricCcmMatchesReference() {
        assumeTrue("halide library unavailable", BguLook.available)
        // RAW branch with a production asymmetric CCM (column-major, Camera2
        // order) and production-like WB: every developed channel must match
        // an independent Kotlin reference. Identity-CCM tests are blind to a
        // matrix transpose (identity is symmetric); this one is not.
        val w = 12
        val h = 9
        val quad = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) for (ch in 0..3) {
            quad[(ch * h + y) * w + x] = (((y * w + x) * 255 / (w * h)) and 0xff).toByte()
        }
        val wb = floatArrayOf(1.34f, 1f, 1f, 2.676f)
        val ccm = floatArrayOf(
            1.730f, -0.225f, 0.043f, -0.607f, 1.145f, -0.822f, -0.123f, 0.080f, 1.779f
        )
        val iparams = IntArray(BguLook.IP_COUNT)
        iparams[BguLook.IP_APPLY_LENS] = 1
        iparams[BguLook.IP_QB_X] = 0
        iparams[BguLook.IP_QB_Y] = 0
        iparams[BguLook.IP_LOW_STEP] = 1
        iparams[BguLook.IP_AL] = 0
        iparams[BguLook.IP_AT] = 0
        iparams[BguLook.IP_AR] = w
        iparams[BguLook.IP_AB] = h
        val out = BguLook.run(
            quad, w, h,
            lens = FloatArray(1 * 1 * 4) { 1f }, lensCols = 1, lensRows = 1,
            wb, ccm,
            aces = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            white = floatArrayOf(1f, 1f, 1f),
            fparams = FloatArray(BguLook.FP_COUNT), iparams
        )
        assertNotNull("look returned null", out)
        for (y in 0 until h) for (x in 0 until w) {
            val q = DoubleArray(4) { ((quad[(it * h + y) * w + x].toInt() and 0xff) / 255.0) }
            // Lens is 1x1 ones: identity. WB, merged greens, column-major CCM
            // applied GLSL mat3-vec convention: out[r] = M[0][r]*R + M[1][r]*G
            // + M[2][r]*B with M[c][r] = ccm[c * 3 + r].
            val r = q[0] * wb[0]
            val g = (q[1] * wb[1] + q[2] * wb[2]) * 0.5
            val b = q[3] * wb[3]
            val v = doubleArrayOf(r, g, b)
            for (ch in 0..2) {
                var acc = 0.0
                for (c in 0..2) acc += ccm[c * 3 + ch] * v[c]
                val lin = maxOf(acc, 0.0) * 2.0
                val want = (lin / (1.0 + lin)).pow(1.0 / 2.2)
                val got = out!!.developed[(ch * h + y) * w + x].toDouble()
                assertEquals("developed ($x,$y,$ch)", want, got, 3e-3)
            }
        }
    }

    @Test fun runIntoMatchesRunAndRejectsShortBuffers() {
        assumeTrue("halide library unavailable", BguLook.available)
        val w = 12
        val h = 9
        val quad = ByteArray(w * h * 4) { ((it * 255 / (w * h * 4)) and 0xff).toByte() }
        val lens = FloatArray(1 * 1 * 4) { 1f }
        val wb = floatArrayOf(1f, 1f, 1f, 1f)
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val ref = BguLook.run(
            quad, w, h, lens, 1, 1, wb, id, id, floatArrayOf(1f, 1f, 1f),
            FloatArray(BguLook.FP_COUNT), IntArray(BguLook.IP_COUNT)
        )
        assertNotNull(ref)
        val guide = FloatArray(w * h * 3)
        val dev = FloatArray(w * h * 3)
        val rc = BguLook.runInto(
            quad, w, h, lens, 1, 1, wb, id, id, floatArrayOf(1f, 1f, 1f),
            FloatArray(BguLook.FP_COUNT), IntArray(BguLook.IP_COUNT), guide, dev
        )
        assertEquals(0, rc)
        assertArrayEquals(ref!!.guide, guide, 0f)
        assertArrayEquals(ref.developed, dev, 0f)
        assertEquals(-1, BguLook.runInto(
            quad, w, h, lens, 1, 1, wb, id, id, floatArrayOf(1f, 1f, 1f),
            FloatArray(BguLook.FP_COUNT), IntArray(BguLook.IP_COUNT),
            FloatArray(4), dev
        ))
    }

    @Test fun lookRejectsBadInput() {
        assumeTrue("halide library unavailable", BguLook.available)
        val ok = { w: Int, h: Int ->
            BguLook.run(
                ByteArray(w * h * 4), w, h, FloatArray(4) { 1f }, 1, 1,
                FloatArray(4) { 1f }, FloatArray(9), FloatArray(9), FloatArray(3),
                FloatArray(BguLook.FP_COUNT), IntArray(BguLook.IP_COUNT)
            )
        }
        assertNull(ok(0, 8))
        assertNull(
            BguLook.run(
                ByteArray(4), 8, 8, FloatArray(4) { 1f }, 1, 1,
                FloatArray(4) { 1f }, FloatArray(9), FloatArray(9), FloatArray(3),
                FloatArray(BguLook.FP_COUNT), IntArray(BguLook.IP_COUNT)
            )
        )
    }
}
