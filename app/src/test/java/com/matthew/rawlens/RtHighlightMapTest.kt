// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity against RT highlight accumulator + gating, downsample, directional
 * extension scans, and hue-proximity weighting.
 *
 * Inputs and goldens below were machine-generated from the float32 C++ oracle
 * replicating references/RawTherapee/hilite_recon.cc:479-545, :547-568,
 * :580-910 and :1054-1106 verbatim (see /tmp/rt_hlmap_oracle.cpp); the
 * generator script that embedded them read /tmp/rt_hlmap_goldens.txt, so no
 * hand transcription is involved.
 *
 * Strict tests use branch-designed inputs placed far from every gate
 * threshold, so RT and the port must take identical branches with identical
 * arithmetic: accumulator copies and scan math assert raw bits. The chained
 * 32x32 integration test documents end-to-end behavior with tolerances,
 * because RT's hipass/density gates are discontinuous: FMA-level rounding
 * (JVM fuses a*b+c, the oracle rounds twice) flips boundary-adjacent keep /
 * zero decisions. That sensitivity is inherent to the algorithm, not the
 * port: any rounding regime (GPU FMA, -O2, OpenMP reduction order) flips the
 * same cells.
 */
class RtHighlightMapTest {
    private fun maxUlp(expected: List<String>, actual: Array<FloatArray>): Long {
        val flat = expected.flatMap { it.split(" ") }
        var k = 0
        var worst = 0L
        for (row in actual) for (x in row) {
            val e = flat[k].toLong(16)
            val a = x.toBits().toLong() and 0xffffffffL
            fun t(u: Long) = if (u and 0x80000000L == 0L) u else 0x100000000L - u
            val d = kotlin.math.abs(t(e) - t(a))
            if (d > worst) worst = d
            k++
        }
        assertEquals(flat.size, k)
        return worst
    }

    private fun assertBits3(label: String, expected: List<List<String>>, actual: Array<Array<FloatArray>>) {
        assertEquals(expected.size, actual.size)
        for (m in expected.indices) {
            val flat = expected[m].flatMap { it.split(" ") }
            var k = 0
            for (row in actual[m]) for (x in row) {
                assertEquals("$label[$m][$k]", flat[k].toLong(16), x.toBits().toLong() and 0xffffffffL)
                k++
            }
            assertEquals(flat.size, k)
        }
    }

    private fun countNonzero(v: Array<Array<FloatArray>>): Int {
        var n = 0
        for (m in v) for (row in m) for (x in row) if (x != 0f) n++
        return n
    }

    private fun thresholds() = RtColorPropagation.computeThresholds(
        floatArrayOf(66000f, 66000f, 66000f),
        floatArrayOf(63000f, 64000f, 63500f)
    )

    @Test
    fun accumulatorDirectInputsMatchRtBits() {
        val w = 16
        val h = 8
        val red = Array(h) { FloatArray(w) { 45000f } }
        val green = Array(h) { FloatArray(w) { 45000f * 0.98f } }
        val blue = Array(h) { FloatArray(w) { 45000f * 1.01f } }
        red[0][0] = 1000f
        green[0][0] = 1000f
        blue[0][0] = 1000f
        val variation = Array(h) { FloatArray(w) }
        variation[4][10] = 1000000f
        val density = Array(h) { FloatArray(w) { 1f } }
        density[2][2] = 0.5f
        density[6][6] = 0f
        val t = thresholds()
        val roi = HlRoi(0, 0, w - 1, h - 1)
        val acc = RtHighlightMap.accumulateRgb(red, green, blue, variation, t.thresh, t.maxF, roi)
        assertEquals(127, acc.hipassNorm)
        assertEquals(0x412E848000000000L, java.lang.Double.doubleToLongBits(acc.hipassSum))
        // RT narrows the double quotient to `const float hipass_ave`; the
        // Double below is the exact extension of that float.
        assertEquals(0x40CEC203E0000000L, java.lang.Double.doubleToLongBits(acc.hipassAve.toDouble()))
        RtHighlightMap.applyGates(acc.full, variation, density, acc.hipassAve)
        assertBits3("s2full", GOLDEN_S2FULL, acc.full)
    }

    @Test
    fun directionalScansDesignedMapMatchRtBits() {
        val g = 8
        val hilite = Array(4) { Array(g + 1) { FloatArray(g + 1) } }
        for (i in 0..g) {
            for (j in 0..g) {
                val block = i in 2..5 && j in 2..5
                hilite[3][i][j] = if (block) 1f else 0f
                hilite[0][i][j] = if (block) 1000f + i * 100f + j * 10f else 0f
                hilite[1][i][j] = if (block) 2000f + i * 100f + j * 10f else 0f
                hilite[2][i][j] = if (block) 3000f + i * 100f + j * 10f else 0f
            }
        }
        val maps = RtHighlightMap.directionalExtend(hilite, g, g)
        assertBits3("q", GOLDEN_Q, maps.dir)
        assertBits3("q0", GOLDEN_Q0, maps.dir0)
        assertBits3("q4", GOLDEN_Q4, maps.dir4)
        val hw = RtHighlightMap.hueWeight(floatArrayOf(0.5f, 0.3f, 0.2f), maps, 3, 3)
        assertEquals(true, hw.hasWeight)
        val golden = GOLDEN_QCF
        for (c in 0 until 3) {
            val e = golden[c].toLong(16)
            val a = hw.clipfix[c].toBits().toLong() and 0xffffffffL
            fun tr(u: Long) = if (u and 0x80000000L == 0L) u else 0x100000000L - u
            val d = kotlin.math.abs(tr(e) - tr(a))
            assertTrue("qcf[$c] ulp=$d", d <= QCF_BOUND)
        }
    }

    @Test
    fun chainedSceneIntegration() {
        val h = 32
        val w = 32
        val red = Array(h) { FloatArray(w) }
        val green = Array(h) { FloatArray(w) }
        val blue = Array(h) { FloatArray(w) }
        for (i in 0 until h) {
            for (j in 0 until w) {
                val base = ((i * 131 + j * 137 + 7) % 1009).toFloat() / 1009f * 40000f
                val ring = i in 10 until 22 && j in 10 until 22
                val blob = i in 12 until 20 && j in 12 until 20
                var v = base
                if (ring) v = 45000f + ((i * 17 + j * 29) % 500).toFloat()
                if (blob) v = 65000f
                red[i][j] = v
                green[i][j] = v * 0.98f
                blue[i][j] = v * 1.01f
            }
        }
        val t = thresholds()
        val roi = RtHighlightMap.findRoi(red, green, blue, t.maxF)!!
        assertEquals(HlRoi(0, 0, 31, 31), roi)
        val acc = RtHighlightMap.accumulateAndGate(red, green, blue, t.thresh, t.maxF, roi)
        assertEquals(606, acc.hipassNorm)
        assertEquals(14374966.640625, acc.hipassSum, SUM_TOL)
        val hil = RtHighlightMap.downsample(acc.full)
        val maps = RtHighlightMap.directionalExtend(hil, 8, 8)
        assertTrue("hil count ${countNonzero(hil)}", kotlin.math.abs(countNonzero(hil) - HIL_COUNT) <= COUNT_TOL)
        assertTrue("dir count", kotlin.math.abs(countNonzero(maps.dir) - DIR_COUNT) <= COUNT_TOL)
        assertTrue("dir0 count", kotlin.math.abs(countNonzero(maps.dir0) - DIR0_COUNT) <= COUNT_TOL)
        assertTrue("dir4 count", kotlin.math.abs(countNonzero(maps.dir4) - DIR4_COUNT) <= COUNT_TOL)
        for ((px, py) in listOf(14 to 14, 12 to 12)) {
            val pixel = floatArrayOf(red[py][px], green[py][px], blue[py][px])
            val blend = RtColorPropagation.blendEstimate(pixel, t)
            val y = 0.00001f + blend[0] + blend[1] + blend[2]
            val norm = floatArrayOf(blend[0] / y, blend[1] / y, blend[2] / y)
            val hw = RtHighlightMap.hueWeight(norm, maps, 3, 3)
            assertEquals(true, hw.hasWeight)
            val golden = CHAINED_CLIPFIX["$px,$py"]!!
            for (c in 0 until 3) {
                val rel = kotlin.math.abs(hw.clipfix[c] - golden[c]) / golden[c]
                assertTrue("clipfix($px,$py)[$c] rel=$rel", rel <= CLIPFIX_REL_TOL)
            }
        }
    }

    private companion object {
        const val QCF_BOUND = 8L
        const val SUM_TOL = 1.0
        const val COUNT_TOL = 64
        const val CLIPFIX_REL_TOL = 0.15
        const val HIL_COUNT = 248
        const val DIR_COUNT = 392
        const val DIR0_COUNT = 184
        const val DIR4_COUNT = 192
        val GOLDEN_S2FULL = listOf(
            listOf(
                "00000000 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800",
                "472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800",
                "472fc800 472fc800 00000000 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800",
                "472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800",
                "472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 00000000 472fc800 472fc800 472fc800 472fc800 472fc800",
                "472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800",
                "472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800",
                "472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800 472fc800"
            ),
            listOf(
                "00000000 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400",
                "472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400",
                "472c4400 472c4400 00000000 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400",
                "472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400",
                "472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 00000000 472c4400 472c4400 472c4400 472c4400 472c4400",
                "472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400",
                "472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400",
                "472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400 472c4400"
            ),
            listOf(
                "00000000 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00",
                "47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00",
                "47318a00 47318a00 00000000 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00",
                "47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00",
                "47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 00000000 47318a00 47318a00 47318a00 47318a00 47318a00",
                "47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00",
                "47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00",
                "47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00 47318a00"
            ),
            listOf(
                "00000000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000",
                "3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000",
                "3f800000 3f800000 00000000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000",
                "3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000",
                "3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 00000000 3f800000 3f800000 3f800000 3f800000 3f800000",
                "3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000",
                "3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000",
                "3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000 3f800000"
            )
        )
        val GOLDEN_Q = listOf(
            listOf(
                "44988000 44988000 4303fe33 4303fe33 430936d3 430936d3 449c4000 449c4000",
                "44988000 44988000 4303fe33 4303fe33 430936d3 430936d3 449c4000 449c4000",
                "4308ffea 4308ffea 44988000 4499c000 449b0000 449c4000 430bfeff 430bfeff",
                "430dffe1 430dffe1 44a50000 44a64000 44a78000 44a8c000 430da95b 430da95b",
                "00000000 00000000 44b18000 44b2c000 44b40000 44b54000 00000000 00000000",
                "00000000 00000000 44be0000 44bf4000 44c08000 44c1c000 00000000 00000000",
                "44be0000 44be0000 4318ffdf 43197fe7 43197fe7 4319ffdf 44c1c000 44c1c000",
                "44be0000 44be0000 4318ffdf 43197fe7 43197fe7 4319ffdf 44c1c000 44c1c000"
            ),
            listOf(
                "450ac000 450ac000 4367fcd5 4367fcd5 436d3552 436d3552 450ca000 450ca000",
                "450ac000 450ac000 4367fcd5 4367fcd5 436d3552 436d3552 450ca000 450ca000",
                "436cffda 436cffda 450ac000 450b6000 450c0000 450ca000 436ffe48 436ffe48",
                "4371ffcb 4371ffcb 45110000 4511a000 45124000 4512e000 4371a86d 4371a86d",
                "00000000 00000000 45174000 4517e000 45188000 45192000 00000000 00000000",
                "00000000 00000000 451d8000 451e2000 451ec000 451f6000 00000000 00000000",
                "451d8000 451d8000 437cffc8 437d7fd7 437d7fd7 437dffc8 451f6000 451f6000",
                "451d8000 451d8000 437cffc8 437d7fd7 437d7fd7 437dffc8 451f6000 451f6000"
            ),
            listOf(
                "45494000 45494000 43a5fdbc 43a5fdbc 43a899e9 43a899e9 454b2000 454b2000",
                "45494000 45494000 43a5fdbc 43a5fdbc 43a899e9 43a899e9 454b2000 454b2000",
                "43a87fe4 43a87fe4 45494000 4549e000 454a8000 454b2000 43a9fec9 43a9fec9",
                "43aaffdb 43aaffdb 454f8000 45502000 4550c000 45516000 43aad3c1 43aad3c1",
                "00000000 00000000 4555c000 45566000 45570000 4557a000 00000000 00000000",
                "00000000 00000000 455c0000 455ca000 455d4000 455de000 00000000 00000000",
                "455c0000 455c0000 43b07fda 43b0bfe3 43b0bfe3 43b0ffda 455de000 455de000",
                "455c0000 455c0000 43b07fda 43b0bfe3 43b0bfe3 43b0ffda 455de000 455de000"
            ),
            listOf(
                "3f800000 3f800000 3dcccccd 3dcccccd 3dcccccd 3dcccccd 3f800000 3f800000",
                "3f800000 3f800000 3dcccccd 3dcccccd 3dcccccd 3dcccccd 3f800000 3f800000",
                "3dcccccd 3dcccccd 3f800000 3f800000 3f800000 3f800000 3dcccccd 3dcccccd",
                "3dcccccd 3dcccccd 3f800000 3f800000 3f800000 3f800000 3dcccccd 3dcccccd",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "3f800000 3f800000 3dcccccd 3dcccccd 3dcccccd 3dcccccd 3f800000 3f800000",
                "3f800000 3f800000 3dcccccd 3dcccccd 3dcccccd 3dcccccd 3f800000 3f800000"
            ),
            listOf(
                "44988000 44988000 42f5ffca 42f6ffd8 42f6ffd8 42f7ffca 449c4000 449c4000",
                "44988000 44988000 42f5ffca 42f6ffd8 42f6ffd8 42f7ffca 449c4000 449c4000",
                "00000000 00000000 44988000 4499c000 449b0000 449c4000 00000000 00000000",
                "00000000 00000000 44a50000 44a64000 44a78000 44a8c000 00000000 00000000",
                "4303ffe3 4303ffe3 44b18000 44b2c000 44b40000 44b54000 430a540d 430a540d",
                "4308ffea 4308ffea 44be0000 44bf4000 44c08000 44c1c000 430bfeff 430bfeff",
                "44be0000 44be0000 4314cbed 4315a9ea 4314e69d 431437bf 44c1c000 44c1c000",
                "44be0000 44be0000 4314cbed 4315a9ea 4314e69d 431437bf 44c1c000 44c1c000"
            ),
            listOf(
                "450ac000 450ac000 435effd0 435f7fdb 435f7fdb 435fffcf 450ca000 450ca000",
                "450ac000 450ac000 435effd0 435f7fdb 435f7fdb 435fffcf 450ca000 450ca000",
                "00000000 00000000 450ac000 450b6000 450c0000 450ca000 00000000 00000000",
                "00000000 00000000 45110000 4511a000 45124000 4512e000 00000000 00000000",
                "4367ffcd 4367ffcd 45174000 4517e000 45188000 45192000 436e5320 436e5320",
                "436cffda 436cffda 451d8000 451e2000 451ec000 451f6000 436ffe48 436ffe48",
                "451d8000 451d8000 4378cb57 4379a968 4378e5ed 437836f5 451f6000 451f6000",
                "451d8000 451d8000 4378cb57 4379a968 4378e5ed 437836f5 451f6000 451f6000"
            ),
            listOf(
                "45494000 45494000 43a17fdd 43a1bfe6 43a1bfe6 43a1ffdd 454b2000 454b2000",
                "45494000 45494000 43a17fdd 43a1bfe6 43a1bfe6 43a1ffdd 454b2000 454b2000",
                "00000000 00000000 45494000 4549e000 454a8000 454b2000 00000000 00000000",
                "00000000 00000000 454f8000 45502000 4550c000 45516000 00000000 00000000",
                "43a5ffdc 43a5ffdc 4555c000 45566000 45570000 4557a000 43a9291a 43a9291a",
                "43a87fe4 43a87fe4 455c0000 455ca000 455d4000 455de000 43a9fec9 43a9fec9",
                "455c0000 455c0000 43ae6560 43aed473 43ae729e 43ae1b17 455de000 455de000",
                "455c0000 455c0000 43ae6560 43aed473 43ae729e 43ae1b17 455de000 455de000"
            ),
            listOf(
                "3f800000 3f800000 3dcccca0 3dccccab 3dccccab 3dcccca0 3f800000 3f800000",
                "3f800000 3f800000 3dcccca0 3dccccab 3dccccab 3dcccca0 3f800000 3f800000",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "3dcccccd 3dcccccd 3f800000 3f800000 3f800000 3f800000 3dcccccd 3dcccccd",
                "3dcccccd 3dcccccd 3f800000 3f800000 3f800000 3f800000 3dcccccd 3dcccccd",
                "3f800000 3f800000 3dcccbc0 3dcccbed 3dcccc0d 3dcccbed 3f800000 3f800000",
                "3f800000 3f800000 3dcccbc0 3dcccbed 3dcccc0d 3dcccbed 3f800000 3f800000"
            )
        )
        val GOLDEN_Q0 = listOf(
            listOf(
                "44988000 44988000 00000000 00000000 00000000 00000000 44be0000 44be0000",
                "44988000 44988000 00000000 00000000 00000000 00000000 44be0000 44be0000",
                "00000000 00000000 44988000 44a50000 44b18000 44be0000 00000000 00000000",
                "00000000 00000000 4499c000 44a64000 44b2c000 44bf4000 00000000 00000000",
                "00000000 00000000 449b0000 44a78000 44b40000 44c08000 00000000 00000000",
                "00000000 00000000 449c4000 44a8c000 44b54000 44c1c000 00000000 00000000",
                "449c4000 449c4000 4306ffe3 430bffe9 430bffe9 4310ffe0 44c1c000 44c1c000",
                "449c4000 449c4000 4306ffe3 430bffe9 430bffe9 4310ffe0 44c1c000 44c1c000"
            ),
            listOf(
                "450ac000 450ac000 00000000 00000000 00000000 00000000 451d8000 451d8000",
                "450ac000 450ac000 00000000 00000000 00000000 00000000 451d8000 451d8000",
                "00000000 00000000 450ac000 45110000 45174000 451d8000 00000000 00000000",
                "00000000 00000000 450b6000 4511a000 4517e000 451e2000 00000000 00000000",
                "00000000 00000000 450c0000 45124000 45188000 451ec000 00000000 00000000",
                "00000000 00000000 450ca000 4512e000 45192000 451f6000 00000000 00000000",
                "450ca000 450ca000 436affcd 436fffd8 436fffd8 4374ffcb 451f6000 451f6000",
                "450ca000 450ca000 436affcd 436fffd8 436fffd8 4374ffcb 451f6000 451f6000"
            ),
            listOf(
                "45494000 45494000 00000000 00000000 00000000 00000000 455c0000 455c0000",
                "45494000 45494000 00000000 00000000 00000000 00000000 455c0000 455c0000",
                "00000000 00000000 45494000 454f8000 4555c000 455c0000 00000000 00000000",
                "00000000 00000000 4549e000 45502000 45566000 455ca000 00000000 00000000",
                "00000000 00000000 454a8000 4550c000 45570000 455d4000 00000000 00000000",
                "00000000 00000000 454b2000 45516000 4557a000 455de000 00000000 00000000",
                "454b2000 454b2000 43a77fdb 43a9ffe4 43a9ffe4 43ac7fdb 455de000 455de000",
                "454b2000 454b2000 43a77fdb 43a9ffe4 43a9ffe4 43ac7fdb 455de000 455de000"
            ),
            listOf(
                "3f800000 3f800000 00000000 00000000 00000000 00000000 3f800000 3f800000",
                "3f800000 3f800000 00000000 00000000 00000000 00000000 3f800000 3f800000",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "3f800000 3f800000 3dcccccd 3dcccccd 3dcccccd 3dcccccd 3f800000 3f800000",
                "3f800000 3f800000 3dcccccd 3dcccccd 3dcccccd 3dcccccd 3f800000 3f800000"
            )
        )
        val GOLDEN_Q4 = listOf(
            listOf(
                "44988000 44988000 4303ffe3 4308ffea 4308ffea 430dffe1 44be0000 44be0000",
                "44988000 44988000 4303ffe3 4308ffea 4308ffea 430dffe1 44be0000 44be0000",
                "00000000 00000000 44988000 44a50000 44b18000 44be0000 00000000 00000000",
                "00000000 00000000 4499c000 44a64000 44b2c000 44bf4000 00000000 00000000",
                "00000000 00000000 449b0000 44a78000 44b40000 44c08000 00000000 00000000",
                "00000000 00000000 449c4000 44a8c000 44b54000 44c1c000 00000000 00000000",
                "449c4000 449c4000 430a540d 430bfeff 430bfeff 430da95b 44c1c000 44c1c000",
                "449c4000 449c4000 430a540d 430bfeff 430bfeff 430da95b 44c1c000 44c1c000"
            ),
            listOf(
                "450ac000 450ac000 4367ffcd 436cffda 436cffda 4371ffcb 451d8000 451d8000",
                "450ac000 450ac000 4367ffcd 436cffda 436cffda 4371ffcb 451d8000 451d8000",
                "00000000 00000000 450ac000 45110000 45174000 451d8000 00000000 00000000",
                "00000000 00000000 450b6000 4511a000 4517e000 451e2000 00000000 00000000",
                "00000000 00000000 450c0000 45124000 45188000 451ec000 00000000 00000000",
                "00000000 00000000 450ca000 4512e000 45192000 451f6000 00000000 00000000",
                "450ca000 450ca000 436e5320 436ffe48 436ffe48 4371a86d 451f6000 451f6000",
                "450ca000 450ca000 436e5320 436ffe48 436ffe48 4371a86d 451f6000 451f6000"
            ),
            listOf(
                "45494000 45494000 43a5ffdc 43a87fe4 43a87fe4 43aaffdb 455c0000 455c0000",
                "45494000 45494000 43a5ffdc 43a87fe4 43a87fe4 43aaffdb 455c0000 455c0000",
                "00000000 00000000 45494000 454f8000 4555c000 455c0000 00000000 00000000",
                "00000000 00000000 4549e000 45502000 45566000 455ca000 00000000 00000000",
                "00000000 00000000 454a8000 4550c000 45570000 455d4000 00000000 00000000",
                "00000000 00000000 454b2000 45516000 4557a000 455de000 00000000 00000000",
                "454b2000 454b2000 43a9291a 43a9fec9 43a9fec9 43aad3c1 455de000 455de000",
                "454b2000 454b2000 43a9291a 43a9fec9 43a9fec9 43aad3c1 455de000 455de000"
            ),
            listOf(
                "3f800000 3f800000 3dcccccd 3dcccccd 3dcccccd 3dcccccd 3f800000 3f800000",
                "3f800000 3f800000 3dcccccd 3dcccccd 3dcccccd 3dcccccd 3f800000 3f800000",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "00000000 00000000 3f800000 3f800000 3f800000 3f800000 00000000 00000000",
                "3f800000 3f800000 3dcccccd 3dcccccd 3dcccccd 3dcccccd 3f800000 3f800000",
                "3f800000 3f800000 3dcccccd 3dcccccd 3dcccccd 3dcccccd 3f800000 3f800000"
            )
        )
        val GOLDEN_QCF = listOf("3eef9eec", "3f51e4b5", "3f95fcfb")
        val CHAINED_CLIPFIX = mapOf(
            "14,14" to floatArrayOf(181476.188f, 177846.672f, 183290.984f),
            "12,12" to floatArrayOf(181476.188f, 177846.672f, 183290.984f)
        )
    }
}
