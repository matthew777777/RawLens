package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.KernelNet
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * MFSR 2.0 goal (owner 2026-11-03): proper 2x super-resolution. The only place
 * "added detail" is provable is a known-truth burst, so this builds one:
 * analytic scene (1/f texture to 0.45 cycles/raw px, i.e. above the R/B Bayer
 * Nyquist 0.25, plus sharp edges), 8 frames at random +-3 px shifts, 4x4
 * pixel-aperture integration, RGGB gains, Poisson-Gaussian noise.
 *
 * Truth at 2x: the same pixel-aperture-integrated scene sampled at the output
 * pixel centres (merge convention: raw pixel j's centre is lr coordinate
 * j + 0.5; output pixel i maps to lr (i + 0.5) / 2).
 *
 * Methods: A single frame bilinear demosaic + bicubic 2x; B 8-frame merge at 1x
 * + bicubic 2x; C 8-frame merge at 2x; D = C with the colour-difference merge
 * (C15).
 * Metrics (interior, all channels): PSNR; detailSNR = SNR of the high-pass
 * band (x - gauss(x, 1 output px)) vs the truth's high-pass, where SR shows.
 *
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*Sr2xTruthTest*' -PsrpDiag=1 -i
 */
class Sr2xTruthTest {
    private val n = 512
    private val gains = doubleArrayOf(0.7, 1.0, 1.0, 0.55)
    private val chGain = doubleArrayOf(0.7, 1.0, 0.55)

    private data class Wave(val fx: Double, val fy: Double, val ph: Double, val a: Double)
    private val waves = run {
        val rng = java.util.Random(21)
        List(160) {
            val f = 0.003 * Math.pow(0.45 / 0.003, rng.nextDouble())
            val th = rng.nextDouble() * PI
            Wave(f * cos(th), f * sin(th), rng.nextDouble() * 2 * PI, 0.0009 / f / 10.0)
        }
    }

    private fun scene(u: Double, v: Double): Double {
        var s = 0.42
        for (w in waves) s += w.a * cos(2 * PI * (w.fx * u + w.fy * v) + w.ph)
        s += 0.12 * tanh((u * 0.8 + v * 0.6 - 150.0) / 0.4)
        s += 0.10 * tanh((u * -0.31 + v * 0.95 - 70.0) / 0.3)
        // fine line pairs (resolution chart-like), 0.4 cycles/px
        if (u in 180.0..240.0 && v in 20.0..80.0) s += 0.08 * cos(2 * PI * 0.4 * (u + 0.2 * v))
        return s
    }

    private fun box(u: Double, v: Double): Double {
        var acc = 0.0
        for (j in 0 until 4) for (i in 0 until 4) acc += scene(u + (i + 0.5) / 4 - 0.5, v + (j + 0.5) / 4 - 0.5)
        return acc / 16
    }

    private fun frame(sx: Double, sy: Double, a: Double, b: Double, seed: Long): Array<FloatArray> {
        val rng = java.util.Random(seed)
        return Array(n) { y -> FloatArray(n) { x ->
            val v = (box(x - sx, y - sy) * gains[(y and 1) * 2 + (x and 1)]).coerceIn(0.0, 1.0)
            (v + if (a > 0) rng.nextGaussian() * sqrt(a * v + b) else 0.0).toFloat()
        } }
    }

    private fun truth2x(): Array<Array<FloatArray>> = Array(2 * n) { i -> Array(2 * n) { j ->
        val v = box((j + 0.5) / 2 - 0.5, (i + 0.5) / 2 - 0.5)
        FloatArray(3) { c -> (v * chGain[c]).toFloat() }
    } }

    private fun bilinear(raw: Array<FloatArray>): Array<Array<FloatArray>> {
        val h = raw.size; val w = raw[0].size
        return Array(h) { y -> Array(w) { x -> FloatArray(3) { c ->
            var s = 0f; var k = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy; val xx = x + dx
                if (yy !in 0 until h || xx !in 0 until w) continue
                if (yy % 2 + xx % 2 != c) continue
                if (c == 1 && dx != 0 && dy != 0) continue
                s += raw[yy][xx]; k++
            }
            s / k
        } } }
    }

    private fun keys(t: Double): Double { val a = abs(t); return when { a <= 1 -> (1.5 * a - 2.5) * a * a + 1; a < 2 -> ((-0.5 * a + 2.5) * a - 4) * a + 2; else -> 0.0 } }

    /** 1x RGB (pixel centre = scene coord x) -> 2x at scene coords ((j+.5)/2-.5). */
    private fun bicubic2x(img: Array<Array<FloatArray>>): Array<Array<FloatArray>> {
        val h = img.size; val w = img[0].size
        return Array(2 * h) { i -> Array(2 * w) { j -> FloatArray(3) { c ->
            val x = (j + 0.5) / 2 - 0.5; val y = (i + 0.5) / 2 - 0.5
            val ix = floor(x).toInt(); val iy = floor(y).toInt(); var s = 0.0
            for (q in -1..2) { val wy = keys(y - (iy + q)); val r = img[(iy + q).coerceIn(0, h - 1)]
                for (p in -1..2) s += wy * keys(x - (ix + p)) * r[(ix + p).coerceIn(0, w - 1)][c] }
            s.toFloat()
        } } }
    }

    private fun hp(img: Array<Array<FloatArray>>, c: Int): Array<DoubleArray> {
        val h = img.size; val w = img[0].size
        val k = DoubleArray(7) { exp(-0.5 * (it - 3) * (it - 3)) }.let { a -> val s = a.sum(); DoubleArray(7) { a[it] / s } }
        val t = Array(h) { y -> DoubleArray(w) { x -> var s = 0.0; for (q in -3..3) s += k[q + 3] * img[y][(x + q).coerceIn(0, w - 1)][c]; s } }
        return Array(h) { y -> DoubleArray(w) { x -> var s = 0.0; for (q in -3..3) s += k[q + 3] * t[(y + q).coerceIn(0, h - 1)][x]; img[y][x][c] - s } }
    }

    private fun score(est: Array<Array<FloatArray>>, truth: Array<Array<FloatArray>>): Pair<Double, Double> {
        val m = 24
        var se = 0.0; var cnt = 0; var hs = 0.0; var he = 0.0
        for (c in 0..2) {
            val ht = hp(truth, c); val hx = hp(est, c)
            for (y in m until 2 * n - m) for (x in m until 2 * n - m) {
                val d = (est[y][x][c] - truth[y][x][c]).toDouble(); se += d * d; cnt++
                hs += ht[y][x] * ht[y][x]; val e = hx[y][x] - ht[y][x]; he += e * e
            }
        }
        val psnr = 10 * log10(0.6 * 0.6 / (se / cnt))
        return psnr to 10 * log10(hs / he)
    }

    private fun colourDiff(raw: Array<FloatArray>): Array<FloatArray> {
        val hh = raw.size; val ww = raw[0].size
        fun at(y: Int, x: Int) = raw[y.coerceIn(0, hh - 1)][x.coerceIn(0, ww - 1)]
        return Array(hh) { y -> FloatArray(ww) { x ->
            if ((y + x) % 2 == 1) raw[y][x] else {
                val gh = (at(y, x - 1) + at(y, x + 1)) / 2 + (2 * raw[y][x] - at(y, x - 2) - at(y, x + 2)) / 4
                val gv = (at(y - 1, x) + at(y + 1, x)) / 2 + (2 * raw[y][x] - at(y - 2, x) - at(y + 2, x)) / 4
                val dh = abs(at(y, x - 1) - at(y, x + 1)) + abs(2 * raw[y][x] - at(y, x - 2) - at(y, x + 2))
                val dv = abs(at(y - 1, x) - at(y + 1, x)) + abs(2 * raw[y][x] - at(y - 2, x) - at(y + 2, x))
                raw[y][x] - when { dh < dv -> gh; dv < dh -> gv; else -> (gh + gv) / 2 }
            }
        } }
    }

    @Test
    fun twoXAddsDetail() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val h = C13aMergeDiagTest()
        val truth = truth2x()
        for ((label, ab) in listOf("clean" to (0.0 to 0.0), "iso100" to (1e-4 to 1e-6), "dim" to (8e-4 to 2e-5))) {
            val (a, b) = ab
            val rng = java.util.Random(5)
            val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
            val frames = shifts.mapIndexed { k, (sx, sy) -> frame(sx, sy, a, b, 100L + k) }
            val al = DoubleArray(4) { maxOf(a, 1e-6) }; val be = DoubleArray(4) { maxOf(b, 1e-8) }
            val fin = BurstConfig.finalize(BurstConfig.estimateSnr(frames[0], al, be))
            fun cfg(scale: Float) = PipelineConfig(
                scale = scale, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8), // fixed: smaller truth too small for 32/64 tiles
                kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
                kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { al[it].toFloat() }, kernelBeta = FloatArray(4) { be[it].toFloat() },
                robustnessParams = RobustnessParams(), robustnessEnabled = true,
            )
            val A = bicubic2x(bilinear(frames[0]))
            val B = bicubic2x(h.divided(h.run(frames, cfg(1f))))
            val C = h.divided(h.run(frames, cfg(2f)))
            val dRaw = h.divided(h.run(frames, cfg(2f), mergeIn = ::colourDiff))
            val D = Array(2 * n) { y -> Array(2 * n) { x -> floatArrayOf(dRaw[y][x][1] + dRaw[y][x][0], dRaw[y][x][1], dRaw[y][x][1] + dRaw[y][x][2]) } }
            val one = h.divided(h.run(frames.take(1), cfg(2f)))
            val loop = com.rawlens.srpipeline.stages.EndToEnd.loopConfig(cfg(2f))
            val init = com.rawlens.srpipeline.stages.initAlignment(com.rawlens.srpipeline.stages.Grey.computeGreyImages(frames[0], "FFT"), loop)
            val flows = frames.mapIndexed { k, fr ->
                if (k == 0) Array(n / 16) { Array(n / 16) { floatArrayOf(0f, 0f) } }
                else com.rawlens.srpipeline.stages.alignLevels(init, com.rawlens.srpipeline.stages.Grey.computeGreyImages(fr, "FFT"), loop)
            }
            run {
                var e = 0.0; var c = 0
                for ((k, fl) in flows.withIndex()) if (k > 0) for (r in 2 until fl.size - 2) for (q in 2 until fl[0].size - 2) {
                    val dx = fl[r][q][0] - shifts[k].first; val dy = fl[r][q][1] - shifts[k].second; e += dx * dx + dy * dy; c++ }
                println("SR2X [%-6s] flow RMS error vs truth %.4f px".format(label, sqrt(e / c)))
            }
            val E1 = backProject(C, frames, flows, 16, iters = 30, chromaSigma = 1.0)
            val E2 = backProject(C, frames, flows, 16, iters = 30, chromaSigma = 1.0, lumaLambda = 0.02f)
            for ((name, img) in listOf(
                "A 1-frame demosaic+bicubic2x" to A, "  1-frame merge @2x" to one, "B 8-frame merge@1x+bicubic2x" to B,
                "C 8-frame merge @2x" to C, "D 8-frame merge @2x colourDiff" to D,
                "E1 back-projection x30 (chroma prior)" to E1, "E2 E1 + luma Laplacian 0.02" to E2,
            )) {
                val (p, d) = score(img, truth)
                println("SR2X [%-6s] snr %.1f dB tile %d | %-32s PSNR %.2f dB  detailSNR %.2f dB".format(label, fin.snr, fin.tileSize, name, p, d))
            }
        }
    }

    @Test
    fun forwardModelAndTruthFlowBp() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val h = C13aMergeDiagTest()
        val truth = truth2x()
        val rng = java.util.Random(5)
        val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
        val tflows = shifts.map { (sx, sy) -> Array(n / 16) { Array(n / 16) { floatArrayOf(sx.toFloat(), sy.toFloat()) } } }
        // forward-model check on noise-free frames: residual after ONE zero-step BP pass = A X - y
        val clean = shifts.mapIndexed { k, (sx, sy) -> frame(sx, sy, 0.0, 0.0, 1L + k) }
        val r0 = backProject(truth, clean, tflows, 16, iters = 1, mu = 0f, chromaSigma = 0.0, probe = true)
        check(r0 === r0)
        for ((label, ab) in listOf("clean" to (0.0 to 0.0), "iso100" to (1e-4 to 1e-6), "dim" to (8e-4 to 2e-5))) {
            val (a, b) = ab
            val frames = shifts.mapIndexed { k, (sx, sy) -> frame(sx, sy, a, b, 100L + k) }
            val al = DoubleArray(4) { maxOf(a, 1e-6) }; val be = DoubleArray(4) { maxOf(b, 1e-8) }
            val fin = BurstConfig.finalize(BurstConfig.estimateSnr(frames[0], al, be))
            val cfg = PipelineConfig(scale = 2f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
                kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
                kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { al[it].toFloat() }, kernelBeta = FloatArray(4) { be[it].toFloat() },
                robustnessParams = RobustnessParams(), robustnessEnabled = true)
            val C = h.divided(h.run(frames, cfg))
            for ((mu, ll) in listOf(0.2f to 0f, 0.2f to 0.05f, 0.2f to 0.1f)) {
                var E = C
                val traj = StringBuilder()
                for (step in 1..4) {
                    E = backProject(E, frames, tflows, 16, iters = 5, mu = mu, chromaSigma = 1.0, lumaLambda = ll)
                    val (p, d) = score(E, truth)
                    traj.append(" it%d: %.2f/%.2f".format(step * 5, p, d))
                }
                println("SR2XT [%-6s] TRUE flows BP mu %.1f lap %.2f PSNR/detailSNR%s".format(label, mu, ll, traj))
            }
            val (pc, dc) = score(C, truth)
            println("SR2XT [%-6s] C merge@2x (init): PSNR %.2f detailSNR %.2f".format(label, pc, dc))
        }
    }

    @Test
    fun estimatedFlowBp() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val h = C13aMergeDiagTest()
        val truth = truth2x()
        val rng = java.util.Random(5)
        val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
        for ((label, ab, lam) in listOf(Triple("clean", 0.0 to 0.0, 0.05f), Triple("iso100", 1e-4 to 1e-6, 0.1f), Triple("dim", 8e-4 to 2e-5, 0.2f))) {
            val (a, b) = ab
            val frames = shifts.mapIndexed { k, (sx, sy) -> frame(sx, sy, a, b, 100L + k) }
            val al = DoubleArray(4) { maxOf(a, 1e-6) }; val be = DoubleArray(4) { maxOf(b, 1e-8) }
            val fin = BurstConfig.finalize(BurstConfig.estimateSnr(frames[0], al, be))
            val cfg = PipelineConfig(scale = 2f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
                kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
                kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { al[it].toFloat() }, kernelBeta = FloatArray(4) { be[it].toFloat() },
                robustnessParams = RobustnessParams(), robustnessEnabled = true)
            val loop = com.rawlens.srpipeline.stages.EndToEnd.loopConfig(cfg)
            val init = com.rawlens.srpipeline.stages.initAlignment(com.rawlens.srpipeline.stages.Grey.computeGreyImages(frames[0], "FFT"), loop)
            val flows = frames.mapIndexed { k, fr ->
                if (k == 0) Array(n / 16) { Array(n / 16) { floatArrayOf(0f, 0f) } }
                else com.rawlens.srpipeline.stages.alignLevels(init, com.rawlens.srpipeline.stages.Grey.computeGreyImages(fr, "FFT"), loop)
            }
            val errs = ArrayList<Double>()
            for ((k, fl) in flows.withIndex()) if (k > 0) for (r in 1 until fl.size - 1) for (q in 1 until fl[0].size - 1) {
                val dx = fl[r][q][0] - shifts[k].first; val dy = fl[r][q][1] - shifts[k].second; errs += sqrt(dx * dx + dy * dy) }
            errs.sort()
            println("SR2XE [%-6s] flow error vs truth: med %.4f p90 %.4f p99 %.3f max %.2f  >0.5px %.2f%%".format(label,
                errs[errs.size / 2], errs[(errs.size * 0.9).toInt()], errs[(errs.size * 0.99).toInt()], errs.last(), 100.0 * errs.count { it > 0.5 } / errs.size))
            val C = h.divided(h.run(frames, cfg))
            // robustness weights from the pipeline (per frame, raw/2 grid), reference = 1
            val (means, vars) = com.rawlens.srpipeline.stages.computeLocalStats(com.rawlens.srpipeline.stages.computeGuideImage(frames[0]))
            val rw = frames.mapIndexed { k, fr ->
                if (k == 0) Array(n / 2) { FloatArray(n / 2) { 1f } }
                else { val r = com.rawlens.srpipeline.stages.computeRobustness(fr, means, vars, flows[k], cfg.sigmaSqCurve, cfg.dSqCurve, 16, true, cfg.robustnessParams)
                    Array(n / 2) { y -> FloatArray(n / 2) { x -> r[y][x] } } }
            }
            for ((name, w) in listOf("no robustness" to null, "pipeline robustness" to rw)) {
                var E = C; val traj = StringBuilder()
                for (step in 1..3) { E = backProject(E, frames, flows, 16, iters = 5, mu = 0.2f, chromaSigma = 1.0, lumaLambda = lam, weights = w)
                    val (p, d) = score(E, truth); traj.append(" it%d: %.2f/%.2f".format(step * 5, p, d)) }
                println("SR2XE [%-6s] EST flows BP lap %.2f %-20s PSNR/detailSNR%s".format(label, lam, name, traj))
            }
            val (pc, dc) = score(C, truth)
            println("SR2XE [%-6s] C merge@2x: PSNR %.2f detailSNR %.2f".format(label, pc, dc))
        }
    }

    @Test
    fun realBurst2x() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val h = C13aMergeDiagTest()
        val base = h.config()
        val cfg = base.copy(scale = 2f)
        val (ox, oy) = 801 to 600
        val frames = (0..7).map { h.readCrop(it, ox, oy, n, n) }
        val loop = com.rawlens.srpipeline.stages.EndToEnd.loopConfig(cfg)
        val init = com.rawlens.srpipeline.stages.initAlignment(com.rawlens.srpipeline.stages.Grey.computeGreyImages(frames[0], "FFT"), loop)
        val tile = cfg.tileSizes.first()
        val flows = frames.mapIndexed { k, fr ->
            if (k == 0) Array(n / tile) { Array(n / tile) { floatArrayOf(0f, 0f) } }
            else com.rawlens.srpipeline.stages.alignLevels(init, com.rawlens.srpipeline.stages.Grey.computeGreyImages(fr, "FFT"), loop)
        }
        val (means, vars) = com.rawlens.srpipeline.stages.computeLocalStats(com.rawlens.srpipeline.stages.computeGuideImage(frames[0]))
        val rw = frames.mapIndexed { k, fr ->
            if (k == 0) Array(n / 2) { FloatArray(n / 2) { 1f } }
            else com.rawlens.srpipeline.stages.computeRobustness(fr, means, vars, flows[k], cfg.sigmaSqCurve, cfg.dSqCurve, tile, true, cfg.robustnessParams)
        }
        val A = bicubic2x(bilinear(frames[0]))
        val C = h.divided(h.run(frames, cfg))
        val E = backProject(C, frames, flows, tile, iters = 15, mu = 0.2f, chromaSigma = 1.0, lumaLambda = 0.05f, weights = rw)
        val F = backProject(C, frames, flows, tile, iters = 15, mu = 0.2f, chromaSigma = 1.0, weights = rw, btvLambda = 3e-5f, wb = floatArrayOf(1.879f, 1.490f))
        val G = backProject(C, frames, flows, tile, iters = 15, mu = 0.2f, chromaSigma = 1.0, weights = rw, btvLambda = 1e-4f)
        val png = h.javaClass.getDeclaredMethod("png", String::class.java, Array<Array<FloatArray>>::class.java,
            Int::class.java, Int::class.java, Int::class.java, Int::class.java).also { it.isAccessible = true }
        // same scene region in all three: 2x pixels (600..856) -> raw 300..428
        for ((name, img) in listOf("A_single_bicubic2x" to A, "C_merge2x" to C, "E_backproject2x" to E, "F_bp_btv3e-5_wbchroma" to F, "G_bp_btv1e-4" to G)) {
            png.invoke(h, "real2x_c801_600_$name", img, 600, 600, 256, 2)
        }
        // sharpness/noise on the real crop (no truth): |grad Y| on top-5% edges, flat HP RMS
        fun stats(img: Array<Array<FloatArray>>): Pair<Double, Double> {
            val H = img.size; val g = ArrayList<Double>(); val f = ArrayList<Double>()
            fun Y(y: Int, x: Int) = (img[y][x][0] + 2 * img[y][x][1] + img[y][x][2]) / 4.0
            for (y in 32 until H - 32) for (x in 32 until H - 32) {
                val gr = abs(Y(y, x + 1) - Y(y, x - 1)) + abs(Y(y + 1, x) - Y(y - 1, x)); g += gr
                var m = 0.0; for (dy in -1..1) for (dx in -1..1) m += Y(y + dy, x + dx); f += Y(y, x) - m / 9 }
            val idx = g.indices.sortedBy { g[it] }
            val edge = idx.takeLast(g.size / 20).map { g[it] }.average()
            val flat = sqrt(idx.take(g.size / 5).map { f[it] * f[it] }.average())
            return edge to flat
        }
        val (ea, fa) = stats(A); for ((name, img) in listOf("A" to A, "C" to C, "E" to E, "F BP+BTV 3e-5 WB chroma" to F, "G BP+BTV 1e-4" to G)) {
            val (e, fl) = stats(img)
            println("SR2XR real c801_600 %s: edge |grad Y| x%.3f of A, flat noise x%.3f of A".format(name, e / ea, fl / fa))
        }
        println("SR2XR PNGs in ${java.io.File("build/c13a").absolutePath}")
    }

    @Test
    fun perChannelColourCheck() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val h = C13aMergeDiagTest()
        val truth = truth2x()
        val rng = java.util.Random(5)
        val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
        val tflows = shifts.map { (sx, sy) -> Array(n / 16) { Array(n / 16) { floatArrayOf(sx.toFloat(), sy.toFloat()) } } }
        val frames = shifts.mapIndexed { k, (sx, sy) -> frame(sx, sy, 0.0, 0.0, 100L + k) }
        val fin = BurstConfig.finalize(30.0)
        val cfg = PipelineConfig(scale = 2f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
            kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
            kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { 1e-6f }, kernelBeta = FloatArray(4) { 1e-8f },
            robustnessParams = RobustnessParams(), robustnessEnabled = true)
        val C = h.divided(h.run(frames, cfg))
        val E = backProject(C, frames, tflows, 16, iters = 15, mu = 0.2f, chromaSigma = 1.0, btvLambda = 3e-5f)
        val E0 = backProject(C, frames, tflows, 16, iters = 15, mu = 0.2f, chromaSigma = 0.0, btvLambda = 3e-5f)
        val Ew = backProject(C, frames, tflows, 16, iters = 15, mu = 0.2f, chromaSigma = 1.0, btvLambda = 3e-5f, wb = floatArrayOf((1 / 0.7).toFloat(), (1 / 0.55).toFloat()))
        for ((name, img) in listOf("C merge@2x" to C, "E BP chroma1 BTV" to E, "E0 BP no chroma prior" to E0, "Ew BP WB chroma prior" to Ew)) {
            val m = 24
            val se = DoubleArray(3); val bias = DoubleArray(3); var cnt = 0
            // signed chroma error on the 5% strongest truth edges
            val gl = ArrayList<Pair<Double, IntArray>>()
            for (y in m until 2 * n - m) for (x in m until 2 * n - m) {
                for (c in 0..2) { val d = (img[y][x][c] - truth[y][x][c]).toDouble(); se[c] += d * d }
                cnt++
                val g = abs(truth[y][x + 1][1] - truth[y][x - 1][1]) + abs(truth[y + 1][x][1] - truth[y - 1][x][1])
                gl += g.toDouble() to intArrayOf(y, x)
            }
            gl.sortBy { -it.first }
            val top = gl.take(gl.size / 20)
            for ((_, yx) in top) for (c in 0..2) {
                val t = truth[yx[0]][yx[1]]; val e = img[yx[0]][yx[1]]
                // chroma error: (C - G) error, normalised by channel gain
                bias[c] += ((e[c] - e[1]) - (t[c] - t[1])).toDouble()
            }
            println("SR2XC %-22s PSNR R %.2f G %.2f B %.2f | mean (C-G) error on edges: R %+.5f B %+.5f".format(name,
                10 * log10(0.36 / (se[0] / cnt)), 10 * log10(0.36 / (se[1] / cnt)), 10 * log10(0.36 / (se[2] / cnt)),
                bias[0] / top.size, bias[2] / top.size))
        }
    }

    /** Green-cast diagnosis: greenness on dark pixels, real crop variants. */
    @Test
    fun greenCastDiag() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val h = C13aMergeDiagTest()
        val cfg = h.config().copy(scale = 2f)
        val (ox, oy) = 801 to 600
        val frames = (0..7).map { h.readCrop(it, ox, oy, n, n) }
        val loop = com.rawlens.srpipeline.stages.EndToEnd.loopConfig(cfg)
        val init = com.rawlens.srpipeline.stages.initAlignment(com.rawlens.srpipeline.stages.Grey.computeGreyImages(frames[0], "FFT"), loop)
        val tile = cfg.tileSizes.first()
        val flows = frames.mapIndexed { k, fr ->
            if (k == 0) Array(n / tile) { Array(n / tile) { floatArrayOf(0f, 0f) } }
            else com.rawlens.srpipeline.stages.alignLevels(init, com.rawlens.srpipeline.stages.Grey.computeGreyImages(fr, "FFT"), loop)
        }
        val (means, vars) = com.rawlens.srpipeline.stages.computeLocalStats(com.rawlens.srpipeline.stages.computeGuideImage(frames[0]))
        val rw = frames.mapIndexed { k, fr ->
            if (k == 0) Array(n / 2) { FloatArray(n / 2) { 1f } }
            else com.rawlens.srpipeline.stages.computeRobustness(fr, means, vars, flows[k], cfg.sigmaSqCurve, cfg.dSqCurve, tile, true, cfg.robustnessParams)
        }
        val A = bicubic2x(bilinear(frames[0]))
        val C = h.divided(h.run(frames, cfg))
        // dark mask from A's luma (girders), interior
        val ys = ArrayList<Float>()
        for (y in 32 until 2 * n - 32) for (x in 32 until 2 * n - 32) ys += A[y][x][0] + A[y][x][1] + A[y][x][2]
        val thr = ys.sorted()[ys.size / 10]
        fun green(img: Array<Array<FloatArray>>): String {
            var gd = 0.0; var cnt = 0; var neg = 0
            for (y in 32 until 2 * n - 32) for (x in 32 until 2 * n - 32) {
                val a = A[y][x]; if (a[0] + a[1] + a[2] > thr) continue
                val p = img[y][x]
                // white-balanced greenness (gains 962/512, 763/512 from AsShotNeutral)
                gd += p[1] - (p[0] * 1.879 + p[2] * 1.490) / 2; cnt++
                if (p[0] < 0 || p[2] < 0) neg++
            }
            return "WB greenness on darkest 10%% %+.5f, R or B < 0 on %.1f%% of them".format(gd / cnt, 100.0 * neg / cnt)
        }
        println("SR2XG A single+bicubic  : ${green(A)}")
        println("SR2XG C merge@2x        : ${green(C)}")
        val variants = listOf(
            "F (chroma1, BTV, weights)" to backProject(C, frames, flows, tile, iters = 15, mu = 0.2f, chromaSigma = 1.0, weights = rw, btvLambda = 3e-5f),
            "F no weights" to backProject(C, frames, flows, tile, iters = 15, mu = 0.2f, chromaSigma = 1.0, btvLambda = 3e-5f),
            "F no chroma prior" to backProject(C, frames, flows, tile, iters = 15, mu = 0.2f, chromaSigma = 0.0, weights = rw, btvLambda = 3e-5f),
            "F zero flows (ref only)" to backProject(C, frames.take(1), flows.take(1), tile, iters = 15, mu = 0.2f, chromaSigma = 1.0, btvLambda = 3e-5f),
            "F WB chroma prior" to backProject(C, frames, flows, tile, iters = 15, mu = 0.2f, chromaSigma = 1.0, weights = rw, btvLambda = 3e-5f, wb = floatArrayOf(1.879f, 1.490f)),
            "F 3 iters" to backProject(C, frames, flows, tile, iters = 3, mu = 0.2f, chromaSigma = 1.0, weights = rw, btvLambda = 3e-5f),
        )
        for ((name, img) in variants) println("SR2XG " + name.padEnd(24) + ": " + green(img))
    }

    /** Noise-adaptive BTV + Huber: one kappa across noise levels? (estimated flows) */
    @Test
    fun noiseAdaptiveSweep() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val h = C13aMergeDiagTest()
        val truth = truth2x()
        val rng = java.util.Random(5)
        val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
        val wb = floatArrayOf((1 / 0.7).toFloat(), (1 / 0.55).toFloat())
        for ((label, ab) in listOf("clean" to (1e-6 to 1e-8), "iso100" to (1e-4 to 1e-6), "dim" to (8e-4 to 2e-5))) {
            val (a, b) = ab
            val frames = shifts.mapIndexed { k, (sx, sy) -> frame(sx, sy, if (label == "clean") 0.0 else a, if (label == "clean") 0.0 else b, 100L + k) }
            val al = DoubleArray(4) { a }; val be = DoubleArray(4) { b }
            val fin = BurstConfig.finalize(BurstConfig.estimateSnr(frames[0], al, be))
            val cfg = PipelineConfig(scale = 2f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
                kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
                kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { a.toFloat() }, kernelBeta = FloatArray(4) { b.toFloat() },
                robustnessParams = RobustnessParams(), robustnessEnabled = true)
            val loop = com.rawlens.srpipeline.stages.EndToEnd.loopConfig(cfg)
            val init = com.rawlens.srpipeline.stages.initAlignment(com.rawlens.srpipeline.stages.Grey.computeGreyImages(frames[0], "FFT"), loop)
            val flows = frames.mapIndexed { k, fr ->
                if (k == 0) Array(n / 16) { Array(n / 16) { floatArrayOf(0f, 0f) } }
                else com.rawlens.srpipeline.stages.alignLevels(init, com.rawlens.srpipeline.stages.Grey.computeGreyImages(fr, "FFT"), loop)
            }
            val C = h.divided(h.run(frames, cfg))
            val (pc, dc) = score(C, truth)
            val line = StringBuilder("C %.2f/%.2f".format(pc, dc))
            for (kappa in listOf(0.02f, 0.05f, 0.1f, 0.2f)) for (ck in listOf(1f, 2f)) {
                val hk = 0f
                val E = backProject(C, frames, flows, 16, iters = 15, mu = 0.2f, chromaSigma = 1.0, wb = wb,
                    btvKappa = kappa, noiseA = a.toFloat(), noiseB = b.toFloat(), huberK = hk, noiseFloor = 0.002f, charbK = ck)
                val (p, d) = score(E, truth)
                line.append(" | k%.2f c%.0f %.2f/%.2f".format(kappa, ck, p, d))
            }
            println("SR2XN [%-6s] PSNR/detailSNR: %s".format(label, line))
        }
    }

    /** Motion: a disc moving 1.5 px/frame (independent of the camera shift). Ghosting in the disc vs truth (= reference). */
    @Test
    fun motionRobustness() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val h = C13aMergeDiagTest()
        val truth = truth2x()
        val rng = java.util.Random(5)
        val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
        val (cx0, cy0, rad) = Triple(300.0, 330.0, 45.0)
        val a = 1e-4; val b = 1e-6
        val frames = shifts.mapIndexed { k, (sx, sy) ->
            val r2 = java.util.Random(100L + k)
            Array(n) { y -> FloatArray(n) { x ->
                // the disc's content is displaced by an extra (1.5k, 0.7k) in frame k; it sits at the same place
                val inDisc = (x - sx - cx0) * (x - sx - cx0) + (y - sy - cy0) * (y - sy - cy0) < rad * rad
                val ex = if (inDisc) 1.5 * k else 0.0; val ey = if (inDisc) 0.7 * k else 0.0
                val v = (box(x - sx - ex, y - sy - ey) * gains[(y and 1) * 2 + (x and 1)]).coerceIn(0.0, 1.0)
                (v + r2.nextGaussian() * sqrt(a * v + b)).toFloat()
            } }
        }
        val fin = BurstConfig.finalize(BurstConfig.estimateSnr(frames[0], DoubleArray(4) { a }, DoubleArray(4) { b }))
        val cfg = PipelineConfig(scale = 2f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
            kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
            kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { a.toFloat() }, kernelBeta = FloatArray(4) { b.toFloat() },
            robustnessParams = RobustnessParams(), robustnessEnabled = true)
        val loop = com.rawlens.srpipeline.stages.EndToEnd.loopConfig(cfg)
        val init = com.rawlens.srpipeline.stages.initAlignment(com.rawlens.srpipeline.stages.Grey.computeGreyImages(frames[0], "FFT"), loop)
        val flows = frames.mapIndexed { k, fr ->
            if (k == 0) Array(n / 16) { Array(n / 16) { floatArrayOf(0f, 0f) } }
            else com.rawlens.srpipeline.stages.alignLevels(init, com.rawlens.srpipeline.stages.Grey.computeGreyImages(fr, "FFT"), loop)
        }
        val (means, vars) = com.rawlens.srpipeline.stages.computeLocalStats(com.rawlens.srpipeline.stages.computeGuideImage(frames[0]))
        val rw = frames.mapIndexed { k, fr ->
            if (k == 0) Array(n / 2) { FloatArray(n / 2) { 1f } }
            else com.rawlens.srpipeline.stages.computeRobustness(fr, means, vars, flows[k], cfg.sigmaSqCurve, cfg.dSqCurve, 16, true, cfg.robustnessParams)
        }
        val C = h.divided(h.run(frames, cfg))
        val wb = floatArrayOf((1 / 0.7).toFloat(), (1 / 0.55).toFloat())
        fun bp(w: List<Array<FloatArray>>?, hk: Float) = backProject(C, frames, flows, 16, iters = 15, mu = 0.2f, chromaSigma = 1.0, wb = wb,
            btvKappa = 0.1f, noiseA = a.toFloat(), noiseB = b.toFloat(), noiseFloor = 0.002f, charbK = 1f, weights = w, huberK = hk)
        // reference = frame 0 (no shift, disc content undisplaced) => truth2x is correct everywhere
        fun regionPsnr(img: Array<Array<FloatArray>>, inside: Boolean): Double {
            var se = 0.0; var c = 0
            for (y in 24 until 2 * n - 24) for (x in 24 until 2 * n - 24) {
                val u = (x + 0.5) / 2 - 0.5; val v = (y + 0.5) / 2 - 0.5
                val d2 = (u - cx0) * (u - cx0) + (v - cy0) * (v - cy0)
                if ((d2 < (rad - 4) * (rad - 4)) != inside) continue
                if (!inside && d2 < (rad + 8) * (rad + 8)) continue
                for (ch in 0..2) { val e = (img[y][x][ch] - truth[y][x][ch]).toDouble(); se += e * e }; c += 3
            }
            return 10 * log10(0.36 / (se / c))
        }
        val meanR = rw.drop(1).map { r -> var s2 = 0.0; var c = 0
            for (y in 0 until n / 2) for (x in 0 until n / 2) { val u = 2.0 * x; val v = 2.0 * y
                if ((u - cx0) * (u - cx0) + (v - cy0) * (v - cy0) < (rad - 6) * (rad - 6)) { s2 += r[y][x]; c++ } }; s2 / c }.average()
        println("SR2XM mean robustness inside the moving disc (frames 1-7): %.3f".format(meanR))
        for ((name, img) in listOf("A single frame" to bicubic2x(bilinear(frames[0])), "C merge@2x" to C,
            "BP no rejection" to bp(null, 0f), "BP + pipeline robustness" to bp(rw, 0f), "BP + robustness + Huber3" to bp(rw, 3f))) {
            println("SR2XM %-26s PSNR moving disc %.2f dB | static background %.2f dB".format(name, regionPsnr(img, true), regionPsnr(img, false)))
        }
    }

    /** Step 3: isotropic vs Wronski-steered vs KernelNet-steered prior, known truth, estimated flows. */
    @Test
    fun steeringPriorSweep() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val h = C13aMergeDiagTest()
        val kn = C13bKernelSweepTest()
        val truth = truth2x()
        val rng = java.util.Random(5)
        val shifts = listOf(0.0 to 0.0) + List(7) { (rng.nextDouble() * 6 - 3) to (rng.nextDouble() * 6 - 3) }
        val wb = floatArrayOf((1 / 0.7).toFloat(), (1 / 0.55).toFloat())
        for ((label, ab) in listOf("clean" to (1e-6 to 1e-8), "iso100" to (1e-4 to 1e-6), "dim" to (8e-4 to 2e-5))) {
            val (a, b) = ab
            val frames = shifts.mapIndexed { k, (sx, sy) -> frame(sx, sy, if (label == "clean") 0.0 else a, if (label == "clean") 0.0 else b, 100L + k) }
            val fin = BurstConfig.finalize(BurstConfig.estimateSnr(frames[0], DoubleArray(4) { a }, DoubleArray(4) { b }))
            val cfg = PipelineConfig(scale = 2f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
                kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
                kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { a.toFloat() }, kernelBeta = FloatArray(4) { b.toFloat() },
                robustnessParams = RobustnessParams(), robustnessEnabled = true)
            val loop = com.rawlens.srpipeline.stages.EndToEnd.loopConfig(cfg)
            val init = com.rawlens.srpipeline.stages.initAlignment(com.rawlens.srpipeline.stages.Grey.computeGreyImages(frames[0], "FFT"), loop)
            val flows = frames.mapIndexed { k, fr ->
                if (k == 0) Array(n / 16) { Array(n / 16) { floatArrayOf(0f, 0f) } }
                else com.rawlens.srpipeline.stages.alignLevels(init, com.rawlens.srpipeline.stages.Grey.computeGreyImages(fr, "FFT"), loop)
            }
            val (means, vars) = com.rawlens.srpipeline.stages.computeLocalStats(com.rawlens.srpipeline.stages.computeGuideImage(frames[0]))
            val rw = frames.mapIndexed { k, fr ->
                if (k == 0) Array(n / 2) { FloatArray(n / 2) { 1f } }
                else com.rawlens.srpipeline.stages.computeRobustness(fr, means, vars, flows[k], cfg.sigmaSqCurve, cfg.dSqCurve, 16, true, cfg.robustnessParams)
            }
            val C = h.divided(h.run(frames, cfg))
            val wCovs = com.rawlens.srpipeline.stages.EndToEnd.frameKernels(frames[0], cfg)
            val kCovs = kn.knCovsRggb(frames[0], KernelNet.sigma(FloatArray(4) { a.toFloat() }, FloatArray(4) { b.toFloat() }))
            val line = StringBuilder()
            val (pc, dc) = score(C, truth); line.append("merge %.2f/%.2f".format(pc, dc))
            for ((name, covs) in listOf("iso" to null, "wronski" to wCovs, "kernelnet" to kCovs)) for (kappa in listOf(0.1f, 0.2f)) {
                for (ss in if (covs == null) listOf(1f) else listOf(1f, 2f)) {
                    val E = backProject(C, frames, flows, 16, iters = 15, mu = 0.2f, chromaSigma = 1.0, wb = wb, weights = rw,
                        btvKappa = kappa, noiseA = a.toFloat(), noiseB = b.toFloat(), noiseFloor = 0.002f, charbK = 1f,
                        steerCovs = covs, steerScale = ss)
                    val (p, d) = score(E, truth)
                    line.append(" | %s k%.1f s%.0f %.2f/%.2f".format(name, kappa, ss, p, d))
                }
            }
            println("SR2XS [%-6s] %s".format(label, line))
        }
    }

    /**
     * Forward-model reconstruction (Irani-Peleg iterative back-projection;
     * Farsiu, Robinson, Elad, Milanfar 2004 robust SR / 2006 demosaic-SR):
     * X (2x RGB) is refined so that A_k X reproduces each frame's Bayer
     * samples. A_k: raw pixel box (1 raw px) = mean of X bilinearly sampled at
     * the 4 points +-0.25 raw px around the pixel's position in the reference
     * frame (pixel centre lr = m - flow + 0.5, our flow convention
     * moving(x + f) = ref(x)). Update: X_c -= mu * splat(r*e) / splat(r)
     * (diagonal preconditioning). Prior: chroma (R-G, B-G) smoothed each
     * iteration with a gaussian (sigma [chromaSigma] HR px); luma left free
     * apart from an optional small Laplacian ([lumaLambda]).
     */
    internal fun backProject(
        x0: Array<Array<FloatArray>>, frames: List<Array<FloatArray>>, flows: List<Array<Array<FloatArray>>>,
        tile: Int, iters: Int = 30, mu: Float = 1f, chromaSigma: Double = 1.0, lumaLambda: Float = 0f,
        weights: List<Array<FloatArray>>? = null,
        probe: Boolean = false,
        chromaLock: Boolean = false,
        btvLambda: Float = 0f,
        /** White-balance gains (R, B) for the chroma prior; neutral => wb*C - G = 0. */
        wb: FloatArray = floatArrayOf(1f, 1f),
        /** Noise-adaptive BTV: per-pixel weight btvKappa * sqrt(noiseA*Y + noiseB) (MAP, lambda ~ sigma). */
        btvKappa: Float = 0f,
        noiseA: Float = 0f,
        noiseB: Float = 0f,
        /** Huber data term: residual clipped at huberK * sigma(sample); 0 = plain L2. */
        huberK: Float = 0f,
        /** Model/alignment error floor added to the noise variance: sigma^2 = aY + b + floor^2. */
        noiseFloor: Float = 0f,
        /** Charbonnier-smoothed BTV: d / sqrt(d^2 + (charbK*sigma)^2); 0 = plain sign(). */
        charbK: Float = 0f,
        /** Steering prior: per-pixel kernel covariances on the grey (raw/2) grid; null = isotropic BTV weights. */
        steerCovs: Array<Array<Array<FloatArray>>>? = null,
        /** Covariance scale for the steering weights (Omega * steerScale^2). */
        steerScale: Float = 1f,
    ): Array<Array<FloatArray>> {
        val H = x0.size; val W = x0[0].size
        val X = Array(H) { y -> Array(W) { x -> FloatArray(3) { c -> x0[y][x][c].let { if (it.isNaN()) 0f else it } } } }
        fun sample(c: Int, hx: Double, hy: Double): Double {
            val ix = floor(hx).toInt(); val iy = floor(hy).toInt(); val fx = hx - ix; val fy = hy - iy
            fun v(yy: Int, xx: Int) = X[yy.coerceIn(0, H - 1)][xx.coerceIn(0, W - 1)][c].toDouble()
            return (v(iy, ix) * (1 - fx) + v(iy, ix + 1) * fx) * (1 - fy) + (v(iy + 1, ix) * (1 - fx) + v(iy + 1, ix + 1) * fx) * fy
        }
        val offs = doubleArrayOf(-0.25, 0.25)
        var probeSq = 0.0; var probeN = 0
        repeat(iters) {
            val g = Array(H) { Array(W) { FloatArray(3) } }; val wsum = Array(H) { Array(W) { FloatArray(3) } }
            fun splat(c: Int, hx: Double, hy: Double, v: Double, w: Double) {
                val ix = floor(hx).toInt(); val iy = floor(hy).toInt(); val fx = hx - ix; val fy = hy - iy
                for ((dy, wy) in listOf(0 to 1 - fy, 1 to fy)) for ((dx, wx) in listOf(0 to 1 - fx, 1 to fx)) {
                    val yy = (iy + dy).coerceIn(0, H - 1); val xx = (ix + dx).coerceIn(0, W - 1)
                    val ww = wy * wx * w
                    g[yy][xx][c] += (ww * v).toFloat(); wsum[yy][xx][c] += ww.toFloat()
                }
            }
            for ((k, fr) in frames.withIndex()) {
                val fl = flows[k]; val h = fr.size; val w = fr[0].size
                for (my in 0 until h) for (mx in 0 until w) {
                    val t = fl[(my / tile).coerceIn(0, fl.size - 1)][(mx / tile).coerceIn(0, fl[0].size - 1)]
                    val cx = mx - t[0] + 0.5; val cy = my - t[1] + 0.5      // ref lr coords of the pixel centre
                    if (cx < 1 || cy < 1 || cx > w - 1 || cy > h - 1) continue
                    val c = my % 2 + mx % 2
                    var pred = 0.0
                    for (oy in offs) for (ox in offs) pred += sample(c, 2 * (cx + ox) - 0.5, 2 * (cy + oy) - 0.5)
                    pred /= 4
                    val r = weights?.get(k)?.get((my / 2).coerceAtMost(h / 2 - 1))?.get((mx / 2).coerceAtMost(w / 2 - 1))?.toDouble() ?: 1.0
                    var e = pred - fr[my][mx]
                    if (huberK > 0f) { val sg = huberK * sqrt(noiseA * maxOf(fr[my][mx], 0f) + noiseB + noiseFloor * noiseFloor); e = e.coerceIn(-sg.toDouble(), sg.toDouble()) }
                    if (probe) { probeSq += e * e; probeN++ }
                    for (oy in offs) for (ox in offs) splat(c, 2 * (cx + ox) - 0.5, 2 * (cy + oy) - 0.5, e, r / 4)
                }
            }
            if (probe) println("SR2XT forward-model residual RMS (truth X vs noise-free frames) %.6f over %d samples".format(sqrt(probeSq / probeN), probeN))
            for (y in 0 until H) for (x in 0 until W) for (c in 0..2) {
                if (wsum[y][x][c] > 1e-6f) X[y][x][c] -= mu * g[y][x][c] / wsum[y][x][c]
            }
            if (btvLambda > 0f || btvKappa > 0f) {
                // Bilateral TV (Farsiu, Robinson, Elad, Milanfar 2004, Eq. 22-24), on luma,
                // P = 2, alpha = 0.7: grad = sum_{l,m} a^{|l|+|m|} (I - S^-l S^-m) sign(X - S^l S^m X)
                val Y = Array(H) { y -> FloatArray(W) { x -> (X[y][x][0] + 2 * X[y][x][1] + X[y][x][2]) / 4 } }
                val gr = Array(H) { FloatArray(W) }
                for (l in -2..2) for (m in 0..2) {
                    if (m == 0 && l <= 0) continue
                    val wIso = Math.pow(0.7, (abs(l) + m).toDouble()).toFloat()
                    for (y in 0 until H) for (x in 0 until W) {
                        val yy = y + m; val xx = x + l
                        if (yy !in 0 until H || xx !in 0 until W) continue
                        val wgt = if (steerCovs == null) wIso else {
                            // HR px -> raw px: d = (l, m) / 2; grey cell = 4 HR px
                            val cv = steerCovs[(y / 4).coerceIn(0, steerCovs.size - 1)][(x / 4).coerceIn(0, steerCovs[0].size - 1)]
                            val s2 = steerScale * steerScale
                            val a = cv[0][0] * s2; val bq = cv[0][1] * s2; val c = cv[1][1] * s2
                            val det = maxOf(a * c - bq * bq, 1e-8f)
                            val dx = l / 2f; val dy = m / 2f
                            val q = (c * dx * dx - 2 * bq * dx * dy + a * dy * dy) / det
                            // same total mass as the isotropic BTV row at this |shift| (normalises scale)
                            wIso * 2f * kotlin.math.exp(-0.5f * q)
                        }
                        val dd = Y[y][x] - Y[yy][xx]
                        val sg = if (charbK > 0f) {
                            val e2 = charbK * charbK * (noiseA * maxOf(Y[y][x], 0f) + noiseB + noiseFloor * noiseFloor)
                            dd / sqrt(dd * dd + e2)
                        } else Math.signum(dd)
                        gr[y][x] += wgt * sg; gr[yy][xx] -= wgt * sg
                    }
                }
                for (y in 0 until H) for (x in 0 until W) {
                    val lam = btvLambda + btvKappa * sqrt(noiseA * maxOf(Y[y][x], 0f) + noiseB + noiseFloor * noiseFloor)
                    for (c in 0..2) X[y][x][c] -= lam * gr[y][x]
                }
            }
            if (lumaLambda > 0f) {
                val Y = Array(H) { y -> FloatArray(W) { x -> X[y][x][1] } }
                for (y in 1 until H - 1) for (x in 1 until W - 1) {
                    val lap = 4 * Y[y][x] - Y[y - 1][x] - Y[y + 1][x] - Y[y][x - 1] - Y[y][x + 1]
                    for (c in 0..2) X[y][x][c] -= lumaLambda * lap
                }
            }
            if (chromaLock) {
                // luminance-only SR (Farsiu et al. 2006): chroma = the merge's chroma
                for (y in 0 until H) for (x in 0 until W) {
                    val y0 = x0[y][x]; if (y0[0].isNaN() || y0[1].isNaN() || y0[2].isNaN()) continue
                    val lumNew = (X[y][x][0] + 2 * X[y][x][1] + X[y][x][2]) / 4
                    val lumOld = (y0[0] + 2 * y0[1] + y0[2]) / 4
                    for (c in 0..2) X[y][x][c] = y0[c] - lumOld + lumNew
                }
            } else if (chromaSigma > 0) {
                val rad = (3 * chromaSigma).toInt().coerceAtLeast(1)
                val k = DoubleArray(2 * rad + 1) { exp(-0.5 * (it - rad) * (it - rad) / (chromaSigma * chromaSigma)) }.let { a -> val s = a.sum(); DoubleArray(a.size) { a[it] / s } }
                for (c in listOf(0, 2)) {
                    val g = wb[if (c == 0) 0 else 1]
                    val d = Array(H) { y -> DoubleArray(W) { x -> (g * X[y][x][c] - X[y][x][1]).toDouble() } }
                    val t = Array(H) { y -> DoubleArray(W) { x -> var s = 0.0; for (q in -rad..rad) s += k[q + rad] * d[y][(x + q).coerceIn(0, W - 1)]; s } }
                    for (y in 0 until H) for (x in 0 until W) { var s = 0.0; for (q in -rad..rad) s += k[q + rad] * t[(y + q).coerceIn(0, H - 1)][x]; X[y][x][c] = ((X[y][x][1] + s) / g).toFloat() }
                }
            }
        }
        return X
    }
}
