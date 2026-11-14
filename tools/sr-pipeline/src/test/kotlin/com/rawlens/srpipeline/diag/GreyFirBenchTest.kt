package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.Grey
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.alignLevels
import com.rawlens.srpipeline.stages.initAlignment
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Mobile cut 1: the IPOL FFT grey (two full-frame 2-D FFTs per frame, 7.7 s of
 * 27.7 s on the Mali-G615) vs separable half-band FIR greys ([Grey.firLowpass]).
 * The grey only feeds alignment, so the gate is flow accuracy against KNOWN
 * shifts and the merged result against truth, not grey-image equality.
 * Real burst: FIR vs FFT merge difference on the C9 night crop (if exported).
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*GreyFirBenchTest*' -PsrpDiag=1 -PsrpHeap=8g -i
 */
class GreyFirBenchTest {
    private val methods = listOf("FFT", "FIR7", "FIR11", "FIR15")

    private fun cfg(grey: String, fin: BurstConfig.Final, a: Double, b: Double, scale: Float) = PipelineConfig(
        scale = scale, bayer = true, greyMethod = grey, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
        kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
        kernelPreprocessing = "gat",
        kernelAlpha = FloatArray(4) { maxOf(a, 1e-6).toFloat() }, kernelBeta = FloatArray(4) { maxOf(b, 1e-8).toFloat() },
        robustnessParams = RobustnessParams(noiseCorrection = false, s3 = false), robustnessEnabled = true, edgeExtendFlow = false,
    )

    @Test
    fun knownShifts() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val t = Sr2xTruthTest(); val h = C13aMergeDiagTest(); val truth = t.truth2x(); val n = t.n
        for ((label, ab) in listOf("clean" to (0.0 to 0.0), "iso100" to (1e-4 to 1e-6), "dim" to (8e-4 to 2e-5))) {
            val (a, b) = ab
            val rng = java.util.Random(5)
            val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
            val frames = shifts.mapIndexed { k, (sx, sy) -> t.frame(sx, sy, a, b, 100L + k) }
            val fin = BurstConfig.finalize(BurstConfig.estimateSnr(frames[0], DoubleArray(4) { maxOf(a, 1e-6) }, DoubleArray(4) { maxOf(b, 1e-8) }))
            for (m in methods) {
                val c2 = cfg(m, fin, a, b, 2f)
                val loop = EndToEnd.loopConfig(c2)
                val init = initAlignment(Grey.computeGreyImages(frames[0], m), loop)
                var e = 0.0; var cnt = 0; var worst = 0.0
                for (k in 1 until frames.size) {
                    val fl = alignLevels(init, Grey.computeGreyImages(frames[k], m), loop)
                    for (r in 2 until fl.size - 2) for (q in 2 until fl[0].size - 2) {
                        val dx = fl[r][q][0] - shifts[k].first; val dy = fl[r][q][1] - shifts[k].second
                        val d2 = dx * dx + dy * dy; e += d2; cnt++; if (d2 > worst) worst = d2
                    }
                }
                val (p2, d2) = t.score(h.divided(h.run(frames, c2)), truth)
                println("GREYFIR [%-6s] %-5s flow RMS err %.4f px  worst %.3f px | 2x merge PSNR %.2f dB detail %.2f dB"
                    .format(label, m, sqrt(e / cnt), sqrt(worst), p2, d2))
            }
        }
        println("GREYFIR n=$n")
    }

    /** Uses the C9 export dir (tools/sr-pipeline/parity/c9): -PsrpBurstDir=<dir with burst.f32 + cfg.json>. */
    @Test
    fun realNightCrop() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val dir = File(System.getProperty("srpipeline.burst"))
        assumeTrue(File(dir, "burst.f32").exists())
        val txt = File(dir, "cfg.json").readText()
        fun num(k: String) = Regex("\"$k\":\\s*([-0-9.eE]+)").find(txt)!!.groupValues[1].toDouble()
        val nF = num("n").toInt(); val hh = num("h").toInt(); val ww = num("w").toInt()
        val fb = ByteBuffer.wrap(File(dir, "burst.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val frames = List(nF) { Array(hh) { FloatArray(ww).also { r -> fb.get(r) } } }
        val alpha = Regex("\"alpha\":\\s*\\[([^]]*)]").find(txt)!!.groupValues[1].split(',').map { it.trim().toDouble() }
        val beta = Regex("\"beta\":\\s*\\[([^]]*)]").find(txt)!!.groupValues[1].split(',').map { it.trim().toDouble() }
        val fin = BurstConfig.finalize(num("snr"))
        fun run(m: String) = cfg(m, fin, alpha[1], beta[1], 1f).copy(
            kernelAlpha = FloatArray(4) { alpha[it].toFloat() }, kernelBeta = FloatArray(4) { beta[it].toFloat() },
        ).let { EndToEnd.run(frames[0], frames.drop(1).map { f -> { f } }, it) }
        val ref = run("FFT")
        for (m in methods.drop(1)) {
            val o = run(m)
            var se = 0.0; var c = 0; var big = 0
            for (y in 64 until hh - 64) for (x in 64 until ww - 64) for (ch in 0..2) {
                val d = (o[y][x][ch] - ref[y][x][ch]).toDouble()
                if (d.isNaN()) continue
                se += d * d; c++; if (kotlin.math.abs(d) > 1.0 / 255) big++
            }
            println("GREYFIR real crop %-5s vs FFT: PSNR %.1f dB, px>1/255 %.4f%%".format(m, 10 * log10(1 / (se / c)), 100.0 * big / c))
        }
    }
}
