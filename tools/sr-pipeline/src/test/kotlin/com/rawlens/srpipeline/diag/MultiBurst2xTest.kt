package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.BurstConfig
import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.Grey
import com.rawlens.srpipeline.stages.KernelNet
import com.rawlens.srpipeline.stages.KernelParams
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.RobustnessParams
import com.rawlens.srpipeline.stages.alignLevels
import com.rawlens.srpipeline.stages.computeGuideImage
import com.rawlens.srpipeline.stages.computeLocalStats
import com.rawlens.srpipeline.stages.computeRobustness
import com.rawlens.srpipeline.stages.initAlignment
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * MFSR 2.0 stage 2 on several real 8-frame bursts (REDMI Note 15 Pro DNGs,
 * 4080x3060 uncompressed GRBG), 512 crops at three places per burst.
 * Per burst, read from the tags: strip offset, black/white, NoiseProfile (3 pairs
 * -> R,G1,B,G2), AsShotNeutral (WB for the chroma prior).
 *
 * Methods: A single frame bilinear + bicubic 2x; C stage 1 merge @2x (app
 * engine); R stage 2 with an isotropic Charbonnier-BTV prior; K stage 2 with
 * a KernelNet-steered prior (KernelNet run in sensor orientation like the app).
 * No truth on real data, so per crop: edge |grad Y| (top 5 %) and flat noise
 * (bottom 20 % HP RMS) relative to A, and WB greenness on the darkest 10 %.
 * PNGs to build/c13a/mb_<burst>_<crop>_<method>.png.
 *
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*MultiBurst2xTest*' -PsrpDiag=1 -i
 */
class MultiBurst2xTest {
    private val root = "/Users/monikamalinowska/Downloads/My/Photos/Photos"
    private val bursts = listOf(
        "IMG_20260929_170823_957", "IMG_20260917_141353_404", "IMG_20260919_184641_979",
        "IMG_20260920_133434_814", "IMG_20260921_194618_520",
    )
    private val n = 512
    private val rawW = 4080
    private val rawH = 3060
    // crop origins: x odd (GRBG -> RGGB), y even; top-left, centre, bottom-right
    private val defaultPlaces = listOf("tl" to (401 to 300), "c" to (1785 to 1274), "br" to (3201 to 2300))
    // SRP_PLACES env override: "name:ox:oy;name:ox:oy" (ox odd, oy even)
    private val places = (System.getenv("SRP_PLACES") ?: "").split(';').filter { it.isNotBlank() }
        .map { it.split(':').let { (n, x, y) -> n to (x.toInt() to y.toInt()) } }.ifEmpty { defaultPlaces }

    /** Raw float32 LE dump, [y][x][c] interleaved, for the python/lkr side-by-side. */
    private fun dumpF32(name: String, img: Array<Array<FloatArray>>) {
        val bb = ByteBuffer.allocate(img.size * img[0].size * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (row in img) for (px in row) for (c in 0 until 3) bb.putFloat(px[c])
        File(File("build/c13a").apply { mkdirs() }, "$name.f32").writeBytes(bb.array())
    }

    private class Tags(val strip: Long, val black: Float, val white: Float, val noise: DoubleArray, val neutral: DoubleArray, val iso: Int)

    private fun tags(path: String): Tags {
        val b = File(path).readBytes()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val off = bb.getInt(4)
        val cnt = bb.getShort(off).toInt() and 0xffff
        var strip = 0L; var black = 0f; var white = 0f; var iso = 0
        var noise = DoubleArray(0); var neutral = doubleArrayOf(1.0, 1.0, 1.0)
        for (i in 0 until cnt) {
            val e = off + 2 + 12 * i
            val tag = bb.getShort(e).toInt() and 0xffff
            val type = bb.getShort(e + 2).toInt() and 0xffff
            val c = bb.getInt(e + 4)
            val vOff = e + 8
            fun ptr() = bb.getInt(vOff)
            when (tag) {
                273 -> strip = (if (type == 3) bb.getShort(vOff).toInt() and 0xffff else bb.getInt(vOff)).toLong()
                34855 -> iso = bb.getShort(vOff).toInt() and 0xffff
                50714 -> { val p = ptr(); black = bb.getInt(p).toFloat() / bb.getInt(p + 4) }
                50717 -> white = (if (type == 3) bb.getShort(vOff).toInt() and 0xffff else bb.getInt(vOff)).toFloat()
                50728 -> { val p = ptr(); neutral = DoubleArray(c) { bb.getInt(p + 8 * it).toDouble() / bb.getInt(p + 8 * it + 4) } }
                51041 -> { val p = ptr(); noise = DoubleArray(c) { bb.getDouble(p + 8 * it) } }
            }
        }
        return Tags(strip, black, white, noise, neutral, iso)
    }

    private fun read(path: String, t: Tags, x0: Int, y0: Int, w: Int, h: Int): Array<FloatArray> =
        RandomAccessFile(path, "r").use { f ->
            val row = ByteArray(w * 2)
            Array(h) { y ->
                f.seek(t.strip + ((y0 + y).toLong() * rawW + x0) * 2)
                f.readFully(row)
                val bb = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(w) { ((bb.getShort(it * 2).toInt() and 0xFFFF) - t.black) / (t.white - t.black) }
            }
        }

    /** KernelNet covs for a pipeline crop at (ox odd, oy), net in sensor orientation with a 64 px margin. */
    private fun knCovs(path: String, t: Tags, ox: Int, oy: Int, sigma: Float, kn: C13bKernelSweepTest): Array<Array<Array<FloatArray>>> {
        val m = 64
        val sx0 = (ox - 1 - m).coerceAtLeast(0); val sy0 = (oy - m).coerceAtLeast(0)
        val big = minOf(n + 2 * m, rawW - sx0, rawH - sy0) and 3.inv()
        val raw = read(path, t, sx0, sy0, big, big)              // sensor-aligned (even x)
        val luma = Array(big / 2) { ly -> FloatArray(big / 2) { lx ->
            sqrt(((raw[2 * ly][2 * lx] + raw[2 * ly][2 * lx + 1] + raw[2 * ly + 1][2 * lx] + raw[2 * ly + 1][2 * lx + 1]) / 4f).coerceIn(0f, 1f)) } }
        val map = kn.knMap(luma, sigma)
        val mapH = map[0].size; val mapW = map[0][0].size
        return Array(n / 2) { gy -> Array(n / 2) { gx ->
            val X = (ox - sx0) + 2f * gx + 1f; val Y = (oy - sy0) + 2f * gy + 1f
            val u = X / big * mapW - 0.5f; val v = Y / big * mapH - 0.5f
            val u0 = floor(u).toInt().coerceIn(0, mapW - 1); val v0 = floor(v).toInt().coerceIn(0, mapH - 1)
            val u1 = minOf(u0 + 1, mapW - 1); val v1 = minOf(v0 + 1, mapH - 1)
            val fu = (u - u0).coerceIn(0f, 1f); val fv = (v - v0).coerceIn(0f, 1f)
            val s = FloatArray(3) { c ->
                val a0 = map[c][v0][u0] + fu * (map[c][v0][u1] - map[c][v0][u0])
                val a1 = map[c][v1][u0] + fu * (map[c][v1][u1] - map[c][v1][u0])
                a0 + fv * (a1 - a0) }
            var s1 = maxOf(s[0], 1e-4f); var s2 = maxOf(s[1], 1e-4f)
            val rho = s[2].coerceIn(-0.8f, 0.8f)
            s1 = (s1 * (big / mapW.toFloat()) * 0.25f).coerceIn(0.25f, 2f)
            s2 = (s2 * (big / mapH.toFloat()) * 0.25f).coerceIn(0.25f, 2f)
            if (s1 > s2 * 7f) s1 = s2 * 7f else if (s2 > s1 * 7f) s2 = s1 * 7f
            val det = maxOf(1f - rho * rho, 1e-2f)
            val re = sign(rho) * sqrt(maxOf(1f - det, 0f))
            arrayOf(floatArrayOf(0.5f * s2 * s2, 0.5f * re * s1 * s2), floatArrayOf(0.5f * re * s1 * s2, 0.5f * s1 * s1))
        } }
    }

    private fun stats(img: Array<Array<FloatArray>>, ref: Array<Array<FloatArray>>, wbR: Float, wbB: Float): Triple<Double, Double, Double> {
        val H = img.size
        fun Y(a: Array<Array<FloatArray>>, y: Int, x: Int) = (a[y][x][0] + 2 * a[y][x][1] + a[y][x][2]) / 4.0
        val g = ArrayList<Double>(); val f = ArrayList<Double>(); val lum = ArrayList<Double>(); val gr = ArrayList<Double>()
        for (y in 32 until H - 32) for (x in 32 until H - 32) {
            g += abs(Y(img, y, x + 1) - Y(img, y, x - 1)) + abs(Y(img, y + 1, x) - Y(img, y - 1, x))
            var mm = 0.0; for (dy in -1..1) for (dx in -1..1) mm += Y(img, y + dy, x + dx); f += Y(img, y, x) - mm / 9
            lum += Y(ref, y, x)
            val p = img[y][x]; gr += p[1] - (p[0] * wbR + p[2] * wbB) / 2.0
        }
        // masks from the reference image A so all methods are measured on the same pixels
        val gRef = ArrayList<Double>()
        for (y in 32 until H - 32) for (x in 32 until H - 32) gRef += abs(Y(ref, y, x + 1) - Y(ref, y, x - 1)) + abs(Y(ref, y + 1, x) - Y(ref, y - 1, x))
        val order = gRef.indices.sortedBy { gRef[it] }
        val edge = order.takeLast(order.size / 20).map { g[it] }.average()
        val flat = sqrt(order.take(order.size / 5).map { f[it] * f[it] }.average())
        val dark = lum.indices.sortedBy { lum[it] }.take(lum.size / 10).map { gr[it] }.average()
        return Triple(edge, flat, dark)
    }

    @Test
    fun allBursts() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val h = C13aMergeDiagTest()
        val tr = Sr2xTruthTest()
        val kn = C13bKernelSweepTest()
        val png = h.javaClass.getDeclaredMethod("png", String::class.java, Array<Array<FloatArray>>::class.java,
            Int::class.java, Int::class.java, Int::class.java, Int::class.java).also { it.isAccessible = true }
        val only = (System.getenv("SRP_BURSTS") ?: System.getProperty("srpipeline.bursts", "")).split(',').filter { it.isNotBlank() }
        for (burst in bursts.filter { only.isEmpty() || it in only }) {
            val files = (0..7).map { "$root/$burst/${burst}_F%02d.dng".format(it) }
            val t = tags(files[0])
            val alpha = doubleArrayOf(t.noise[0], t.noise[2], t.noise[4], t.noise[2])
            val beta = doubleArrayOf(t.noise[1], t.noise[3], t.noise[5], t.noise[3])
            val wbR = (1 / t.neutral[0]).toFloat(); val wbB = (1 / t.neutral[2]).toFloat()
            val full = read(files[0], t, 1, 0, rawW - 2, rawH)
            val fin = BurstConfig.finalize(BurstConfig.estimateSnr(full, alpha, beta))
            println("MB2X $burst ISO ${t.iso} strip ${t.strip} snr %.1f tile ${fin.tileSize} wb R %.3f B %.3f".format(fin.snr, wbR, wbB))
            // 512 crops cannot hold 32/64 tiles through the 4-level pyramid: tile 16 for all (noted deviation)
            val cfg = PipelineConfig(scale = 2f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
                kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
                kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { alpha[it].toFloat() }, kernelBeta = FloatArray(4) { beta[it].toFloat() },
                robustnessParams = RobustnessParams(), robustnessEnabled = true)
            val nA = alpha[1].toFloat(); val nB = beta[1].toFloat()
            for ((place, xy) in places) {
                val (ox, oy) = xy
                val frames = files.map { read(it, t, ox, oy, n, n) }
                val loop = EndToEnd.loopConfig(cfg)
                val init = initAlignment(Grey.computeGreyImages(frames[0], "FFT"), loop)
                val flows = frames.mapIndexed { k, fr ->
                    if (k == 0) Array(n / 16) { Array(n / 16) { floatArrayOf(0f, 0f) } }
                    else alignLevels(init, Grey.computeGreyImages(fr, "FFT"), loop)
                }
                val (means, vars) = computeLocalStats(computeGuideImage(frames[0]))
                val rw = frames.mapIndexed { k, fr ->
                    if (k == 0) Array(n / 2) { FloatArray(n / 2) { 1f } }
                    else computeRobustness(fr, means, vars, flows[k], cfg.sigmaSqCurve, cfg.dSqCurve, 16, true, cfg.robustnessParams)
                }
                val rMean = rw.drop(1).map { r -> r.sumOf { row -> row.sumOf { it.toDouble() } } / (r.size * r[0].size) }.average()
                val A = tr.javaClass.getDeclaredMethod("bicubic2x", Array<Array<FloatArray>>::class.java).also { it.isAccessible = true }
                    .invoke(tr, tr.javaClass.getDeclaredMethod("bilinear", Array<FloatArray>::class.java).also { it.isAccessible = true }
                        .invoke(tr, frames[0])) as Array<Array<FloatArray>>
                val C = h.divided(h.run(frames, cfg))
                val wb = floatArrayOf(wbR, wbB)
                val R = tr.backProject(C, frames, flows, 16, iters = 15, mu = 0.2f, chromaSigma = 1.0, wb = wb, weights = rw,
                    btvKappa = 0.1f, noiseA = nA, noiseB = nB, noiseFloor = 0.002f, charbK = 1f)
                val kc = knCovs(files[0], t, ox, oy, KernelNet.sigma(FloatArray(4) { alpha[it].toFloat() }, FloatArray(4) { beta[it].toFloat() }), kn)
                val K = tr.backProject(C, frames, flows, 16, iters = 15, mu = 0.2f, chromaSigma = 1.0, wb = wb, weights = rw,
                    btvKappa = 0.1f, noiseA = nA, noiseB = nB, noiseFloor = 0.002f, charbK = 1f, steerCovs = kc, steerScale = 1f)
                val (ea, fa, _) = stats(A, A, wbR, wbB)
                val line = StringBuilder()
                for ((name, img) in listOf("A" to A, "C" to C, "R" to R, "K" to K)) {
                    val (e, fl, gd) = stats(img, A, wbR, wbB)
                    line.append(" | %s edge %.2f noise %.2f green %+.5f".format(name, e / ea, fl / fa, gd))
                    png.invoke(h, "mb_${burst}_${place}_$name", img, 384, 384, 256, 2)
                    if (System.getenv("SRP_DUMP") != null) dumpF32("mb_${burst}_${place}_$name", img)
                }
                println("MB2X $burst $place(%d,%d) r %.2f%s".format(ox, oy, rMean, line))
            }
        }
    }

    /** C18: static hot/dead pixel detector on full frames (ref candidates, confirmed on 2 more frames). */
    @Test
    fun hotPixelCensus() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        for (burst in bursts) {
            val files = (0..7).map { "$root/$burst/${burst}_F%02d.dng".format(it) }
            val t = tags(files[0])
            val fr = (0..2).map { read(files[it], t, 1, 0, rawW - 2, rawH) }
            val t0 = System.nanoTime()
            val cand = com.rawlens.srpipeline.stages.HotPixels.candidates(fr[0])
            val t1 = System.nanoTime()
            val conf = com.rawlens.srpipeline.stages.HotPixels.confirm(cand, rawH, rawW - 2,
                listOf({ y: Int, x: Int -> fr[1][y][x] }, { y: Int, x: Int -> fr[2][y][x] }))
            val t2 = System.nanoTime()
            println("C18HP $burst ISO ${t.iso}: candidates ${cand.size} confirmed ${conf.size} (candidates %.0f ms, confirm %.0f ms on desktop)".format((t1 - t0) / 1e6, (t2 - t1) / 1e6))
        }
    }

    /** C18 A/B: night burst merge (scale 1, app config) with and without static hot-pixel suppression. */
    @Test
    fun hotPixelAb() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val h = C13aMergeDiagTest()
        val png = h.javaClass.getDeclaredMethod("png", String::class.java, Array<Array<FloatArray>>::class.java,
            Int::class.java, Int::class.java, Int::class.java, Int::class.java).also { it.isAccessible = true }
        val burst = "IMG_20260921_194618_520"
        val files = (0..7).map { "$root/$burst/${burst}_F%02d.dng".format(it) }
        val t = tags(files[0])
        val alpha = doubleArrayOf(t.noise[0], t.noise[2], t.noise[4], t.noise[2])
        val beta = doubleArrayOf(t.noise[1], t.noise[3], t.noise[5], t.noise[3])
        val fin = BurstConfig.finalize(BurstConfig.estimateSnr(read(files[0], t, 1, 0, rawW - 2, rawH), alpha, beta))
        val cfg = PipelineConfig(scale = 1f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
            kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
            kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { alpha[it].toFloat() }, kernelBeta = FloatArray(4) { beta[it].toFloat() },
            robustnessParams = RobustnessParams(), robustnessEnabled = true)
        for ((place, xy) in places) {
            val (ox, oy) = xy
            val raw = files.map { read(it, t, ox, oy, n, n) }
            val d = com.rawlens.srpipeline.stages.HotPixels.confirm(com.rawlens.srpipeline.stages.HotPixels.candidates(raw[0]), n, n,
                listOf({ y: Int, x: Int -> raw[1][y][x] }, { y: Int, x: Int -> raw[2][y][x] }))
            val off = h.divided(h.run(raw, cfg))
            val fixed = raw.map { f -> Array(n) { y -> f[y].copyOf() }.also { com.rawlens.srpipeline.stages.HotPixels.correct(it, d) } }
            val on = h.divided(h.run(fixed, cfg))
            // isolated colour outliers in the output: |C - median3x3(C)| > 0.05 on any channel
            fun speckles(img: Array<Array<FloatArray>>): Int {
                var k = 0
                for (y in 2 until n - 2) for (x in 2 until n - 2) for (c in 0..2) {
                    val v = FloatArray(9); var i = 0
                    for (dy in -1..1) for (dx in -1..1) v[i++] = img[y + dy][x + dx][c]
                    v.sort(); if (abs(img[y][x][c] - v[4]) > 0.05f) { k++; break }
                }
                return k
            }
            println("C18AB $burst $place defects ${d.size} | output speckles off ${speckles(off)} on ${speckles(on)}")
            png.invoke(h, "c18_${place}_off", off, 128, 128, 256, 2)
            png.invoke(h, "c18_${place}_on", on, 128, 128, 256, 2)
        }
    }

    /** Trace the black holes in blown lamps (194618 centre): which stage first gives NaN / zero weight. */
    @Test
    fun blackHoleTrace() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val burst = "IMG_20260921_194618_520"
        val files = (0..7).map { "$root/$burst/${burst}_F%02d.dng".format(it) }
        val t = tags(files[0])
        val alpha = doubleArrayOf(t.noise[0], t.noise[2], t.noise[4], t.noise[2])
        val beta = doubleArrayOf(t.noise[1], t.noise[3], t.noise[5], t.noise[3])
        val fin = BurstConfig.finalize(BurstConfig.estimateSnr(read(files[0], t, 1, 0, rawW - 2, rawH), alpha, beta))
        val cfg = PipelineConfig(scale = 1f, bayer = true, factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8),
            kernelParams = KernelParams(fin.kDetail.toFloat(), fin.kDenoise.toFloat(), fin.dTh.toFloat(), fin.dTr.toFloat()),
            kernelPreprocessing = "gat", kernelAlpha = FloatArray(4) { alpha[it].toFloat() }, kernelBeta = FloatArray(4) { beta[it].toFloat() },
            robustnessParams = RobustnessParams(), robustnessEnabled = true)
        val (ox, oy) = 1785 to 1274
        val raw = files.map { read(it, t, ox, oy, n, n) }
        var sat = 0; var over = 0
        for (row in raw[0]) for (v in row) { if (v >= 0.999f) sat++; if (v > 1.0001f) over++ }
        println("BH ref: pixels >= 0.999: $sat, > 1.0001: $over, max %.4f".format(raw[0].maxOf { r -> r.max() }))
        val covs = EndToEnd.frameKernels(raw[0], cfg)
        var nanCov = 0; var badCov = 0
        for (r in covs) for (m in r) { val det = m[0][0] * m[1][1] - m[0][1] * m[0][1]
            if (m.any { row -> row.any { it.isNaN() } }) nanCov++ else if (!(det > 0f)) badCov++ }
        println("BH ref kernels: NaN covs $nanCov, det<=0 covs $badCov of ${covs.size * covs[0].size}")
        val pre = com.rawlens.srpipeline.stages.Gat.gat(raw[0], cfg.kernelAlpha, cfg.kernelBeta)
        var nanGat = 0; for (r in pre) for (v in r) if (v.isNaN()) nanGat++
        println("BH GAT NaN: $nanGat")
        val h = C13aMergeDiagTest()
        val acc = h.run(raw, cfg)
        var zeroDen = 0; var nanNum = 0
        for (y in 0 until n) for (x in 0 until n) for (c in 0..2) { if (!(acc.den[y][x][c] > 0f)) zeroDen++; if (acc.num[y][x][c].isNaN()) nanNum++ }
        println("BH accumulate: den<=0 or NaN $zeroDen, num NaN $nanNum")
        val img = h.divided(acc)
        for (y in 234..246 step 2) {
            val sb = StringBuilder("BH out y=$y:")
            for (x in 130..146 step 2) { val p = img[y][x]; sb.append(" (%.2f,%.2f,%.2f)".format(p[0], p[1], p[2])) }
            println(sb)
        }
        var nan = 0; var inf = 0; var neg = 0; var gt1 = 0; var mx = -9f
        val badAt = ArrayList<String>()
        for (y in 0 until n) for (x in 0 until n) for (c in 0..2) { val v = img[y][x][c]
            if (v.isNaN()) { nan++; if (badAt.size < 5) badAt += "($x,$y,c$c NaN)" } else if (v.isInfinite()) inf++
            else { if (v < 0f) neg++; if (v > 1f) { gt1++; if (badAt.size < 5) badAt += "($x,$y,c$c=%.4f)".format(v) }; mx = maxOf(mx, v) } }
        println("BH divided: NaN $nan Inf $inf neg $neg >1 $gt1 max %.5f first: $badAt".format(mx))
        val sb = StringBuilder("BH raw ref y=240:")
        for (x in 130..146) sb.append(" %.2f".format(raw[0][240][x]))
        println(sb)
    }
}
