// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Real-capture check of [RawWhiteBalanceFallback] on dumped X14U tele raws
 * (16-bit LE, 4096x3072). Runs only with env WB_REAL_DIR=<dir with cam4.bin cam5.bin>.
 * Labels: cam4 = raw grey of unclipped snow; cam5 = cam4's converged HAL AWB
 * moved to cam5 through both sensors' D65 matrices (same overcast light).
 */
class RawWhiteBalanceRealDngTest {
    private fun d(s: String) = ImmutableDoubleValues(s.split(',').map { it.trim().toDouble() }.toDoubleArray())
    private fun diag(a: Double, b: Double, c: Double) = d("$a,0,0,0,$b,0,0,0,$c")

    internal class Case(val file: String, val cfa: Int, val locus: RawWhiteBalanceFallback.Locus, val truth: DoubleArray,
               val ev100: Double?)

    private fun angular(a: DoubleArray?, b: DoubleArray): Double {
        if (a == null) return 45.0
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        return Math.toDegrees(acos((dot / (sqrt(a.sumOf { it * it }) * sqrt(b.sumOf { it * it }))).coerceIn(-1.0, 1.0)))
    }

    @Test fun realTeleRaws() {
        val dir = System.getenv("WB_REAL_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "cam4.bin").exists())
        val cam4 = RawWhiteBalanceFallback.Locus.from(
            d("0.7813,-0.5078,-0.1797,-0.2031,1.3516,0.3750,-0.1016,0.1250,0.4375"),
            d("1.4219,-0.7188,-0.1094,-0.7422,1.8203,0.2813,-0.1797,-0.0781,0.6484"),
            diag(1.0078, 1.0, 1.0), diag(1.0078, 1.0, 1.0), 21, 17)!!
        val cam5 = RawWhiteBalanceFallback.Locus.from(
            d("0.8203,-0.5156,-0.1563,-0.2422,1.3672,0.3594,-0.1016,0.1094,0.4453"),
            d("1.4844,-0.9375,-0.2188,-0.5938,2.2578,0.4922,-0.7344,-0.6406,0.3906"),
            diag(1.0, 1.0, 0.9922), diag(1.0, 1.0, 0.9922), 21, 17)!!
        val cases = listOf(
            Case("cam4.bin", 3, cam4, doubleArrayOf(0.48, 1.0, 0.656),
                RawWhiteBalanceFallback.exposureEv100(1_733_346L, 50, 1.8f)),
            Case("cam5.bin", 2, cam5, doubleArrayOf(0.4903, 1.0, 0.6767),
                RawWhiteBalanceFallback.exposureEv100(10_000_000L, 95, 2.5f)),
        )
        for (c in cases) {
            val bytes = File(dir, c.file).readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            buf.rewind()
            for ((label, ev) in listOf("ev" to c.ev100, "noEv" to null)) {
                val e = RawWhiteBalanceFallback.estimateDetailed(buf, 8192, 2, 4096, 3072, c.cfa,
                    FloatArray(4) { 64f }, 1023f, c.locus, ev)
                println("WBREAL ${c.file} $label est=${e?.neutral?.joinToString { "%.4f".format(it) }} " +
                    "conf=${e?.confidence?.let { "%.2f".format(it) }} truth=${c.truth.joinToString()} " +
                    "err=%.2f deg".format(angular(e?.neutral, c.truth)))
            }
            val gw = RawWhiteBalanceFallback.estimateDetailed(buf, 8192, 2, 4096, 3072, c.cfa,
                FloatArray(4) { 64f }, 1023f, null, null)
            println("WBREAL ${c.file} grayWorld est=${gw?.neutral?.joinToString { "%.4f".format(it) }} err=%.2f deg"
                .format(angular(gw?.neutral, c.truth)))
        }
    }
}
