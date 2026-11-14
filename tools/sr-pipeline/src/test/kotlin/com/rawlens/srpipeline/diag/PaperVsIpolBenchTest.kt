package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.SelectionLaw
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Benchmark of the four places where the IPOL reference (and RawLens) differs
 * from Wronski et al. 2019 + supplement (docs/refactor/mfsr-c9-reference-parity.md,
 * Leg 3), on the known-truth burst of [Sr2xTruthTest]:
 *  1. anisotropy A: IPOL 1+sqrt((l1-l2)/(l1+l2)) vs paper 1+sqrt(l1/l2);
 *  2. width law: IPOL linear interpolation vs paper kDetail*kStretch*A / kDetail/(kShrink*A);
 *  3. Dth/Dtr: IPOL [0.81..0.71]/[1.24..1.0] on GAT gradients vs paper
 *     [0.010..0.001]/[0.020..0.006] on [0,1] intensities (no GAT). The paper
 *     lists ranges only; low SNR takes the larger end, as IPOL does;
 *  4. s1/s2: IPOL 2/12 vs paper 12/2.
 * Everything else Python-exact (kMin 0, S.3 off, noise correction off), plus one
 * row with the RawLens Wronski extras (kMin 0.64, S.3) for context.
 * Metrics vs truth (interior): PSNR and detailSNR (high-pass band); motion scene:
 * PSNR inside the moving disc (ghosting) and outside (static detail).
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*PaperVsIpolBenchTest*' -PsrpDiag=1 -PsrpHeap=8g -i
 */
class PaperVsIpolBenchTest {
    private data class Variant(
        val name: String,
        val law: SelectionLaw = SelectionLaw.LINEAR,
        val paperA: Boolean = false,
        val paperD: Boolean = false,
        val paperS: Boolean = false,
        val extras: Boolean = false,
    )

    private val variants = listOf(
        Variant("IPOL (current, parity)"),
        Variant("1+2 paper A + paper law", law = SelectionLaw.PAPER, paperA = true),
        Variant("2   paper law, IPOL A", law = SelectionLaw.PAPER),
        Variant("3   paper Dth/Dtr, no GAT", paperD = true),
        Variant("4   paper s1=12 s2=2", paperS = true),
        Variant("all paper (1+2+3+4)", law = SelectionLaw.PAPER, paperA = true, paperD = true, paperS = true),
        Variant("IPOL + RawLens kMin/S.3", extras = true),
    )

    private fun lerp(snr: Double, lo: Double, hi: Double): Double {
        val c = snr.coerceIn(6.0, 30.0); return lo + (c - 6.0) / 24.0 * (hi - lo)
    }

    private fun cfg(v: Variant, snr: Double, a: Double, b: Double, scale: Float): PipelineConfig {
        val fin = BurstConfig.finalize(snr)
        return PipelineConfig(
            scale = scale, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
            kernelParams = KernelParams(
                kDetail = fin.kDetail.toFloat(), kDenoise = fin.kDenoise.toFloat(),
                dTh = (if (v.paperD) lerp(snr, 0.010, 0.001) else fin.dTh).toFloat(),
                dTr = (if (v.paperD) lerp(snr, 0.020, 0.006) else fin.dTr).toFloat(),
                selection = v.law, paperAnisotropy = v.paperA, kMin = if (v.extras) 0.64f else 0f,
            ),
            kernelPreprocessing = if (v.paperD) null else "gat",
            kernelAlpha = FloatArray(4) { maxOf(a, 1e-6).toFloat() }, kernelBeta = FloatArray(4) { maxOf(b, 1e-8).toFloat() },
            robustnessParams = RobustnessParams(
                s1 = if (v.paperS) 12f else 2f, s2 = if (v.paperS) 2f else 12f,
                noiseCorrection = false, s3 = v.extras,
            ),
            robustnessEnabled = true, edgeExtendFlow = false,
        )
    }

    @Test
    fun staticScene() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val t = Sr2xTruthTest(); val h = C13aMergeDiagTest(); val truth = t.truth2x()
        for ((label, ab) in listOf("clean" to (0.0 to 0.0), "iso100" to (1e-4 to 1e-6), "dim" to (8e-4 to 2e-5))) {
            val (a, b) = ab
            val rng = java.util.Random(5)
            val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
            val frames = shifts.mapIndexed { k, (sx, sy) -> t.frame(sx, sy, a, b, 100L + k) }
            val snr = BurstConfig.estimateSnr(frames[0], DoubleArray(4) { maxOf(a, 1e-6) }, DoubleArray(4) { maxOf(b, 1e-8) })
            for (v in variants) {
                val m2 = h.divided(h.run(frames, cfg(v, snr, a, b, 2f)))
                val m1 = t.bicubic2x(h.divided(h.run(frames, cfg(v, snr, a, b, 1f))))
                val (p2, d2) = t.score(m2, truth); val (p1, d1) = t.score(m1, truth)
                val nan = m2.sumOf { r -> r.count { px -> px.any { it.isNaN() } } }
                println("BENCH static [%-6s snr %4.1f] %-26s | 2x PSNR %6.2f detail %6.2f | 1x PSNR %6.2f detail %6.2f | NaN px %d"
                    .format(label, snr, v.name, p2, d2, p1, d1, nan))
            }
        }
    }

    @Test
    fun movingDisc() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val t = Sr2xTruthTest(); val h = C13aMergeDiagTest(); val truth = t.truth2x(); val n = t.n
        val rng = java.util.Random(5)
        val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
        val (cx0, cy0, rad) = Triple(300.0, 330.0, 45.0)
        val a = 1e-4; val b = 1e-6
        val frames = shifts.mapIndexed { k, (sx, sy) ->
            val r2 = java.util.Random(100L + k)
            Array(n) { y -> FloatArray(n) { x ->
                val inDisc = (x - sx - cx0) * (x - sx - cx0) + (y - sy - cy0) * (y - sy - cy0) < rad * rad
                val ex = if (inDisc) 1.5 * k else 0.0; val ey = if (inDisc) 0.7 * k else 0.0
                val v = (t.box(x - sx - ex, y - sy - ey) * t.gains[(y and 1) * 2 + (x and 1)]).coerceIn(0.0, 1.0)
                (v + r2.nextGaussian() * sqrt(a * v + b)).toFloat()
            } }
        }
        val snr = BurstConfig.estimateSnr(frames[0], DoubleArray(4) { a }, DoubleArray(4) { b })
        fun region(img: Array<Array<FloatArray>>, inside: Boolean): Double {
            var se = 0.0; var c = 0
            for (y in 24 until 2 * n - 24) for (x in 24 until 2 * n - 24) {
                val u = (x + 0.5) / 2 - 0.5; val w = (y + 0.5) / 2 - 0.5
                val r = sqrt((u - cx0) * (u - cx0) + (w - cy0) * (w - cy0))
                if (if (inside) r < rad - 4 else r > rad + 12) for (ch in 0..2) {
                    val d = (img[y][x][ch] - truth[y][x][ch]).toDouble(); se += d * d; c++ }
            }
            return 10 * log10(0.36 / (se / c))
        }
        for (v in variants) {
            val m = h.divided(h.run(frames, cfg(v, snr, a, b, 2f)))
            println("BENCH motion [snr %4.1f] %-26s | disc PSNR %6.2f | static PSNR %6.2f".format(snr, v.name, region(m, true), region(m, false)))
        }
    }
}
