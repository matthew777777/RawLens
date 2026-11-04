package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.Grey
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.alignLevels
import com.rawlens.srpipeline.stages.computeGuideImage
import com.rawlens.srpipeline.stages.computeLocalStats
import com.rawlens.srpipeline.stages.computeRobustness
import com.rawlens.srpipeline.stages.initAlignment
import com.rawlens.srpipeline.stages.merge
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Checklist M / C13a merge leg + Z1 zipper, real burst IMG_20260929_170823_957,
 * 512 crops, CPU Kotlin pipeline with the CLI config (Wronski S.1 kernels,
 * GAT kernel preprocessing, robustness without noise LUT, scale 1).
 *
 * Per output pixel and channel: W = sum w (den) and n_eff = W^2 / sum w^2.
 * sum w^2 is exact: w = exp(-z/2) with z from inv(cov), so the same merge with
 * cov/2 and r^2 accumulates w^2 r^2.
 *
 * Zipper fingerprint: per output channel, high-pass h = out - 3x3 mean;
 * RMS(h) split by output Bayer phase (RGGB site: R, G1, G2, B), and the
 * Nyquist checker amplitudes (-1)^x, (-1)^y, (-1)^(x+y) of h. Baseline:
 * bilinear demosaic of the reference frame.
 *
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*C13aMergeDiagTest*' -PsrpDiag=1 -i
 * PNGs: build/c13a/ (4x nearest, WB + gamma 2.2).
 */
class C13aMergeDiagTest {

    private val dir = "/Users/monikamalinowska/Downloads/My/Photos/Photos/IMG_20260929_170823_957"
    private val stem = "IMG_20260929_170823_957"
    private val rawW = 4080
    private val rawH = 3060
    private val crop = 512
    private val out = File("build/c13a").apply { mkdirs() }

    // NoiseProfile tag (R,G,B pairs) expanded to R,G1,B,G2.
    private val alpha = doubleArrayOf(3.3967603e-4, 1.7186193e-4, 3.3983541e-4, 1.7186193e-4)
    private val beta = doubleArrayOf(3.8097883e-7, 3.9033430e-7, 3.7863041e-7, 3.9033430e-7)
    private val wb = floatArrayOf(962f / 512f, 1f, 763f / 512f) // 1/AsShotNeutral

    internal fun readCrop(frame: Int, x0: Int, y0: Int, w: Int, h: Int): Array<FloatArray> {
        RandomAccessFile("$dir/${stem}_F%02d.dng".format(frame), "r").use { f ->
            val row = ByteArray(w * 2)
            return Array(h) { y ->
                f.seek(6644L + ((y0 + y).toLong() * rawW + x0) * 2)
                f.readFully(row)
                val bb = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(w) { ((bb.getShort(it * 2).toInt() and 0xFFFF) - 64f) / (1023f - 64f) }
            }
        }
    }

    internal fun config(): PipelineConfig {
        val full = readCrop(0, 1, 0, rawW - 2, rawH)
        val fin = BurstConfig.finalize(BurstConfig.estimateSnr(full, alpha, beta))
        println("C13aM snr %.2f dB tile %d kDetail %.3f kDenoise %.3f".format(fin.snr, fin.tileSize, fin.kDetail, fin.kDenoise))
        return PipelineConfig(
            scale = 1f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = fin.tileSizes,
            kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
            kernelPreprocessing = "gat",
            kernelAlpha = FloatArray(4) { alpha[it].toFloat() }, kernelBeta = FloatArray(4) { beta[it].toFloat() },
            robustnessParams = RobustnessParams(), robustnessEnabled = true,
        )
    }

    internal class Acc(val num: Array<Array<FloatArray>>, val den: Array<Array<FloatArray>>, val den2: Array<Array<FloatArray>>)

    internal fun run(
        frames: List<Array<FloatArray>>,
        cfg: PipelineConfig,
        covFn: ((Int, Array<FloatArray>) -> Array<Array<Array<FloatArray>>>)? = null,
        mergeIn: ((Array<FloatArray>) -> Array<FloatArray>)? = null,
    ): Acc {
        val ref = frames[0]
        val h = ref.size
        val w = ref[0].size
        val tile = cfg.tileSizes.first()
        val loop = EndToEnd.loopConfig(cfg)
        val init = initAlignment(Grey.computeGreyImages(ref, cfg.greyMethod), loop)
        val (means, vars) = computeLocalStats(computeGuideImage(ref))
        val oh = (h * cfg.scale).toInt(); val ow = (w * cfg.scale).toInt()
        fun z3() = Array(oh) { Array(ow) { FloatArray(3) } }
        val num = z3(); val den = z3(); val num2 = z3(); val den2 = z3()
        frames.forEachIndexed { k, fr ->
            val flow = if (k == 0) {
                val nT = init.hessians.last()
                Array(nT.size) { Array(nT[0].size) { floatArrayOf(0f, 0f) } }
            } else alignLevels(init, Grey.computeGreyImages(fr, cfg.greyMethod), loop)
            val r = if (k == 0 || !cfg.robustnessEnabled) Array(h) { FloatArray(w) { 1f } } else
                computeRobustness(fr, means, vars, flow, cfg.sigmaSqCurve, cfg.dSqCurve, tile, true, cfg.robustnessParams)
            val covs = covFn?.invoke(k, fr) ?: EndToEnd.frameKernels(fr, cfg)
            if (k > 0) {
                var rs = 0.0; var rmin = 9f
                for (row in r) for (v in row) { rs += v; rmin = minOf(rmin, v) }
                // kernel std (sqrt of smaller cov eigenvalue) distribution, raw px
                val sd = ArrayList<Double>()
                for (row in covs) for (m in row) {
                    val a = m[0][0].toDouble(); val b = m[0][1].toDouble(); val c = m[1][1].toDouble()
                    sd += sqrt((a + c) / 2 - sqrt((a - c) * (a - c) / 4 + b * b))
                }
                sd.sort()
                if (k == 1 && System.getProperty("srpipeline.diag") == "v") println("C13aK frame1 kernel minor-axis std: p5 %.3f p25 %.3f med %.3f p75 %.3f".format(
                    sd[sd.size / 20], sd[sd.size / 4], sd[sd.size / 2], sd[sd.size * 3 / 4]))
                if (System.getProperty("srpipeline.diag") == "v") println("C13aR frame $k robustness mean %.4f min %.4f enabled=${cfg.robustnessEnabled}".format(rs / (r.size * r[0].size), rmin))
            }
            val mIn = mergeIn?.invoke(fr) ?: fr
            merge(mIn, flow, covs, r, num, den, true, cfg.scale, tile)
            val halfCov = Array(covs.size) { y -> Array(covs[0].size) { x -> Array(2) { i -> FloatArray(2) { j -> covs[y][x][i][j] / 2f } } } }
            val r2 = Array(r.size) { y -> FloatArray(r[0].size) { x -> r[y][x] * r[y][x] } }
            merge(mIn, flow, halfCov, r2, num2, den2, true, cfg.scale, tile)
        }
        return Acc(num, den, den2)
    }

    private fun bilinear(raw: Array<FloatArray>): Array<Array<FloatArray>> {
        val h = raw.size; val w = raw[0].size
        return Array(h) { y -> Array(w) { x ->
            FloatArray(3) { c ->
                var s = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = y + dy; val xx = x + dx
                    if (yy !in 0 until h || xx !in 0 until w) continue
                    val ch = yy % 2 + xx % 2
                    if (ch != c) continue
                    // bilinear: green uses the 4-neighbour cross only
                    if (c == 1 && dx != 0 && dy != 0) continue
                    s += raw[yy][xx]; n++
                }
                s / n
            }
        } }
    }

    private val phaseNames = listOf("R", "G1", "G2", "B")

    private fun zipper(label: String, img: Array<Array<FloatArray>>) {
        val h = img.size; val w = img[0].size
        val m = 8
        for (c in 0..2) {
            val ss = DoubleArray(4); val cnt = IntArray(4)
            var nx = 0.0; var ny = 0.0; var nxy = 0.0; var hs = 0.0; var n = 0
            for (y in m until h - m) for (x in m until w - m) {
                var mean = 0.0
                for (dy in -1..1) for (dx in -1..1) mean += img[y + dy][x + dx][c]
                val hp = img[y][x][c] - mean / 9
                val ph = (y % 2) * 2 + (x % 2)
                ss[ph] += hp * hp; cnt[ph]++
                val sx = if (x % 2 == 0) 1 else -1
                val sy = if (y % 2 == 0) 1 else -1
                nx += sx * hp; ny += sy * hp; nxy += sx * sy * hp; hs += hp * hp; n++
            }
            val rms = DoubleArray(4) { sqrt(ss[it] / cnt[it]) }
            val ratio = rms.max() / rms.min()
            val hr = sqrt(hs / n)
            println("C13aZ %-14s ch %s  HP rms by site %s  max/min %.3f | Nyquist/HP: x %.3f y %.3f xy %.3f".format(
                label, "RGB"[c], phaseNames.indices.joinToString(" ") { "${phaseNames[it]}=%.5f".format(rms[it]) }, ratio,
                abs(nx / n) / hr, abs(ny / n) / hr, abs(nxy / n) / hr))
        }
    }

    private fun density(label: String, acc: Acc) {
        val h = acc.den.size; val w = acc.den[0].size; val m = 8
        for (c in 0..2) {
            val wS = DoubleArray(4); val nS = DoubleArray(4); val nMin = DoubleArray(4) { 1e9 }; val cnt = IntArray(4)
            for (y in m until h - m) for (x in m until w - m) {
                val ph = (y % 2) * 2 + (x % 2)
                val d = acc.den[y][x][c].toDouble()
                val neff = d * d / acc.den2[y][x][c]
                wS[ph] += d; nS[ph] += neff; nMin[ph] = minOf(nMin[ph], neff); cnt[ph]++
            }
            println("C13aD %-14s ch %s  W by site %s | n_eff mean by site %s | n_eff min %s".format(label, "RGB"[c],
                (0..3).joinToString(" ") { "${phaseNames[it]}=%.3f".format(wS[it] / cnt[it]) },
                (0..3).joinToString(" ") { "${phaseNames[it]}=%.2f".format(nS[it] / cnt[it]) },
                (0..3).joinToString(" ") { "%.2f".format(nMin[it]) }))
        }
    }

    private fun png(name: String, img: Array<Array<FloatArray>>, x0: Int = 192, y0: Int = 192, n: Int = 128, z: Int = 4) {
        val bi = BufferedImage(n * z, n * z, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until n) for (x in 0 until n) {
            val p = img[y0 + y][x0 + x]
            val v = IntArray(3) { c -> ((p[c] * wb[c] * 1.6f).coerceIn(0f, 1f).toDouble().pow(1 / 2.2) * 255).toInt() }
            val rgb = (v[0] shl 16) or (v[1] shl 8) or v[2]
            for (j in 0 until z) for (i in 0 until z) bi.setRGB(x * z + i, y * z + j, rgb)
        }
        ImageIO.write(bi, "png", File(out, "$name.png"))
    }

    internal fun divided(acc: Acc) = Array(acc.num.size) { y -> Array(acc.num[0].size) { x ->
        FloatArray(3) { c -> acc.num[y][x][c] / acc.den[y][x][c] } } }

    @Test
    fun mergeDensityAndZipper() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val cfg = config()
        for ((ox, oy) in listOf(1785 to 1274, 801 to 600)) {
            val frames = (0..7).map { readCrop(it, ox, oy, crop, crop) }
            val tag = "c${ox}_$oy"
            val bil = bilinear(frames[0])
            zipper("$tag bilinear", bil)
            png("${tag}_bilinear", bil)
            for (nf in listOf(1, 8)) {
                val acc = run(frames.take(nf), cfg)
                density("$tag ${nf}f", acc)
                val img = divided(acc)
                zipper("$tag ${nf}f", img)
                png("${tag}_merge${nf}f", img)
            }
        }
        println("C13aM PNGs in ${out.absolutePath}")
    }

    /** Zipper strength bucketed by R/B n_eff, with and without robustness. */
    @Test
    fun zipperVsDensity() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val base = config()
        for ((ox, oy) in listOf(1785 to 1274, 801 to 600)) {
            val frames = (0..7).map { readCrop(it, ox, oy, crop, crop) }
            for ((lab, cfg) in listOf("robust" to base, "noRobust" to base.copy(robustnessEnabled = false))) {
                val acc = run(frames, cfg)
                val img = divided(acc)
                val edges = listOf(0.0, 1.5, 3.0, 6.0, 1e9)
                for (c in listOf(0, 2)) {
                    val ss = Array(4) { DoubleArray(4) }; val cnt = Array(4) { IntArray(4) }; val grad = DoubleArray(4); var tot = 0
                    for (y in 8 until crop - 8) for (x in 8 until crop - 8) {
                        val d = acc.den[y][x][c].toDouble()
                        val ne = d * d / acc.den2[y][x][c]
                        val b = (0 until 4).first { ne < edges[it + 1] }
                        var mean = 0.0
                        for (dy in -1..1) for (dx in -1..1) mean += img[y + dy][x + dx][c]
                        val hp = img[y][x][c] - mean / 9
                        val ph = (y % 2) * 2 + (x % 2)
                        ss[b][ph] += hp * hp; cnt[b][ph]++
                        grad[b] += abs(img[y][x + 1][1] - img[y][x - 1][1]) + abs(img[y + 1][x][1] - img[y - 1][x][1]); tot++
                    }
                    val line = (0 until 4).joinToString(" | ") { b ->
                        val n = cnt[b].sum()
                        if (n < 50) "n_eff[${edges[b]},${edges[b + 1]}): -" else {
                            val r = DoubleArray(4) { sqrt(ss[b][it] / maxOf(1, cnt[b][it])) }
                            "n_eff<%s: %.1f%% px, site ratio %.2f, |gradG| %.4f".format(
                                if (edges[b + 1] > 1e8) "inf" else "%.1f".format(edges[b + 1]), 100.0 * n / tot,
                                r.max() / r.min(), grad[b] / n)
                        }
                    }
                    println("C13aN c${ox}_$oy $lab ch ${"RGB"[c]}: $line")
                }
            }
        }
    }
}
