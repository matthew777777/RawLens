// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/** Temporary diagnostic probe (deleted after 4C diagnosis). */
class RobustnessProbeTest {
    private fun packed(codes: (Int, Int) -> Int): RawSrPackedFrame {
        val layoutW = 66
        val layoutH = 50
        val rowStride = layoutW * 2
        val plane = ByteBuffer.allocateDirect(rowStride * layoutH).order(ByteOrder.nativeOrder())
        for (sy in 0 until layoutH) for (sx in 0 until layoutW)
            plane.putShort(sy * rowStride + sx * 2, codes(sx, sy).toShort())
        return RawSrPackedFrame(
            plane, RawPlaneLayout(layoutW, layoutH, rowStride, 2, 0, 0),
            RawCrop(1, 1, 64, 48), RawNormalization(BayerPattern.RGGB,
                listOf(64f, 64f, 64f, 64f), 4000f), null,
            ImmutableDoubleValues(doubleArrayOf(0.02, 1.0, 0.02, 1.0, 0.02, 1.0, 0.02, 1.0)))
    }

    private fun textured(seed: Int): (Int, Int) -> Int {
        val sigma = kotlin.math.sqrt(0.02 * 1500 + 1.0)
        return { sx, sy ->
            val hash = (((sx * 73856093) xor (sy * 19349663) xor seed) and 0x7fffffff) % 1001 / 1000.0 * 2.0 - 1.0
            1500 + (hash * 1.73 * sigma).toInt() + ((sx * 79 + sy * 43 + seed * 131) % 101)
        }
    }

    @Test fun probeQuadZero() {
        val ref = RawSrRobustness.linearGuide(packed(textured(7)))
        val mov = RawSrRobustness.linearGuide(packed(textured(99)))
        println("PROBE guideSize=${ref.width}x${ref.height} model=${ref.model}/${mov.model}")
        println("PROBE alpha=${ref.alpha.toList()} beta=${ref.beta.toList()}")
        for (c in 0..2) {
            // 3x3 clamp stats at quad 0 by hand.
            var sum = 0.0
            var sq = 0.0
            var msum = 0.0
            for (i in -1..1) for (j in -1..1) {
                val v = ref.channel(c)[(0 + i).coerceIn(0, 11) * 32 + (0 + j).coerceIn(0, 31)].toDouble()
                val m = mov.channel(c)[(0 + i).coerceIn(0, 11) * 32 + (0 + j).coerceIn(0, 31)].toDouble()
                sum += v
                sq += v * v
                msum += m
            }
            val mean = sum / 9.0
            val vari = sq / 9.0 - mean * mean
            val mmean = msum / 9.0
            val err = mean - mmean
            val expected = ref.alpha[c] * mmean + ref.beta[c]
            println("PROBE c=$c mean=$mean mmean=$mmean err=$err var=$vari exp=$expected")
        }
        println("PROBE ref00 r=${ref.red[0]} g=${ref.green[0]} b=${ref.blue[0]}")
        println("PROBE mov00 r=${mov.red[0]} g=${mov.green[0]} b=${mov.blue[0]}")
        val flow = RawSrAlignmentField(32, 24, 8, 4, 3, List(12) {
            RawSrTileFlow(4f, 4f, 0f, 0f, 0f, false)
        })
        val tuning = RawSrTuning.fromReference(packed(textured(7))).tuning
        println("PROBE tuning snr=${tuning.snr} t=${tuning.t} s1=${tuning.s1} s2=${tuning.s2}")
        val out = RawSrRobustness.evaluate(ref, mov, flow, tuning, RawSrAlignmentConfig())
        println("PROBE eval r00=${out.r[0]} flags00=${out.flags[0]}")
    }
}
