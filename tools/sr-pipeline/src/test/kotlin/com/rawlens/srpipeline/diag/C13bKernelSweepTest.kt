package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.ColourDiff
import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.KernelNet
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Checklist M / C13b + Z1: kernel-width sweep, Wronski and KernelNet, on the
 * real burst IMG_20260929_170823_957 (8 frames, 512 crops).
 *
 * KernelNet is ported 1:1 from kernelnet.comp (PREP luma = sqrt(quad mean),
 * sensor orientation; 5 conv3x3 relu (first stride 2) + conv1x1 head
 * (2 sig, 2 sig, 2 sig - 1); COV shaping: s *= raw/map ratio * 0.25,
 * clamp [0.25, 2.0], elongation <= 7, rho clamp +-0.8, det >= 1e-2,
 * Omega = 0.5 [[s2^2, rho s1 s2], [rho s1 s2, s1^2]]). The net runs on a
 * sensor-aligned crop with a 64 px margin so crop borders do not matter. fp16
 * head rounding is skipped (~1e-3).
 *
 * Variants: base; "all>=s": every channel's kernel minor-axis std floored at
 * s raw px; "RB>=s": floor only for R and B (G keeps the base kernel;
 * emulated with two merges).
 *
 * Metrics (interior, R/B channels unless noted):
 *  zip%   share of pixels with n_eff < 1.5 (one effective sample)
 *  edgeRatio  site HP-RMS max/min on the 5 % strongest-gradient pixels (1.0 = no zipper)
 *  sharpY   mean |grad Y| on those edge pixels, relative to base (Y=(R+2G+B)/4)
 *  noiseY   HP-RMS of Y on the 20 % flattest pixels, relative to base
 *
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*C13bKernelSweepTest*' -PsrpDiag=1 -i
 */
class C13bKernelSweepTest {
    private val h = C13aMergeDiagTest()
    private val crop = 512

    // ---------- KernelNet port ----------
    private val layers = listOf(
        intArrayOf(2, 16, 3, 2, 1), intArrayOf(16, 16, 3, 1, 1), intArrayOf(16, 16, 3, 1, 1),
        intArrayOf(16, 16, 3, 1, 1), intArrayOf(16, 16, 3, 1, 1), intArrayOf(16, 3, 1, 1, 0),
    )

    internal fun knMap(luma: Array<FloatArray>, sigma: Float): Array<Array<FloatArray>> {
        val w = KernelNet.bundledWeights()
        var x = arrayOf(luma, Array(luma.size) { FloatArray(luma[0].size) { sigma } })
        var o = 0
        for ((li, l) in layers.withIndex()) {
            val (cin, cout, k, st, relu) = l.toList()
            val inH = x[0].size; val inW = x[0][0].size
            val r = k / 2
            val outH = (inH + 2 * r - k) / st + 1; val outW = (inW + 2 * r - k) / st + 1
            val wOff = o; o += cout * cin * k * k
            val bOff = o; o += cout
            val src = x
            x = Array(cout) { oc ->
                Array(outH) { oy ->
                    FloatArray(outW) { ox ->
                        var acc = w[bOff + oc]
                        for (ic in 0 until cin) for (ky in 0 until k) {
                            val iy = oy * st + ky - r
                            if (iy < 0 || iy >= inH) continue
                            for (kx in 0 until k) {
                                val ix = ox * st + kx - r
                                if (ix < 0 || ix >= inW) continue
                                acc += w[wOff + oc * cin * k * k + (ic * k + ky) * k + kx] * src[ic][iy][ix]
                            }
                        }
                        if (li < layers.size - 1) { if (relu == 1) maxOf(acc, 0f) else acc }
                        else { val sg = 1f / (1f + exp(-acc)); 2f * sg - (if (oc == 2) 1f else 0f) }
                    }
                }
            }
        }
        return x
    }

    /** KernelNet covs on the grey grid of the pipeline crop at (ox, oy) (ox odd). */
    private fun knCovs(ox: Int, oy: Int, sigma: Float, scale: Float = 0.25f, cap: Float = 2f): Array<Array<Array<FloatArray>>> {
        val m = 64
        val sx0 = ox - 1 - m; val sy0 = oy - m        // sensor-aligned (even x)
        val n = crop + 2 * m
        // readCrop requires odd x for RGGB; read one wider at odd x-1? Read raw directly via odd start then shift.
        val raw = h.readCrop(0, sx0 + 1, sy0, n, n)   // raw[y][x] = sensor (sx0+1+x)
        val lumaW = n / 2
        // sensor quads start at even sensor x = sx0 + 2q -> crop index 2q - 1
        val luma = Array(lumaW) { ly -> FloatArray(lumaW) { lx ->
            val x = 2 * lx - 1; val y = 2 * ly
            val a = if (x >= 0) raw[y][x] + raw[y + 1][x] else raw[y][x + 1] + raw[y + 1][x + 1]
            sqrt(((a + raw[y][x + 1] + raw[y + 1][x + 1]) / 4f).coerceIn(0f, 1f))
        } }
        val map = knMap(luma, sigma)
        val mapH = map[0].size; val mapW = map[0][0].size
        val g = crop / 2
        return Array(g) { gy -> Array(g) { gx ->
            // pipeline cell centre (crop coords) -> sensor coords in the big crop
            val X = (2f * gx + 1f) + 1f + m; val Y = (2f * gy + 1f) + m
            val u = X / n * mapW - 0.5f; val v = Y / n * mapH - 0.5f
            val u0 = kotlin.math.floor(u).toInt().coerceIn(0, mapW - 1); val v0 = kotlin.math.floor(v).toInt().coerceIn(0, mapH - 1)
            val u1 = minOf(u0 + 1, mapW - 1); val v1 = minOf(v0 + 1, mapH - 1)
            val fu = (u - u0).coerceIn(0f, 1f); val fv = (v - v0).coerceIn(0f, 1f)
            val s = FloatArray(3) { c ->
                val t0 = map[c][v0][u0] + fu * (map[c][v0][u1] - map[c][v0][u0])
                val t1 = map[c][v1][u0] + fu * (map[c][v1][u1] - map[c][v1][u0])
                t0 + fv * (t1 - t0)
            }
            var s1 = maxOf(s[0], 1e-4f); var s2 = maxOf(s[1], 1e-4f)
            val rho = s[2].coerceIn(-0.8f, 0.8f)
            s1 = (s1 * (n / mapW.toFloat()) * scale).coerceIn(0.25f, cap)
            s2 = (s2 * (n / mapH.toFloat()) * scale).coerceIn(0.25f, cap)
            if (s1 > s2 * 7f) s1 = s2 * 7f else if (s2 > s1 * 7f) s2 = s1 * 7f
            val det = maxOf(1f - rho * rho, 1e-2f)
            val re = sign(rho) * sqrt(maxOf(1f - det, 0f))
            arrayOf(floatArrayOf(0.5f * s2 * s2, 0.5f * re * s1 * s2), floatArrayOf(0.5f * re * s1 * s2, 0.5f * s1 * s1))
        } }
    }

    // ---------- floor ----------
    private fun floorCovs(c: Array<Array<Array<FloatArray>>>, sd: Float): Array<Array<Array<FloatArray>>> {
        val v = sd * sd
        return Array(c.size) { y -> Array(c[0].size) { x ->
            val a = c[y][x][0][0].toDouble(); val b = c[y][x][0][1].toDouble(); val d = c[y][x][1][1].toDouble()
            val tr = (a + d) / 2; val q = sqrt((a - d) * (a - d) / 4 + b * b)
            var l1 = tr + q; var l2 = tr - q
            // eigenvector for l1
            var ex = b; var ey = l1 - a
            if (abs(ex) + abs(ey) < 1e-20) { ex = if (a >= d) 1.0 else 0.0; ey = if (a >= d) 0.0 else 1.0 }
            val nn = sqrt(ex * ex + ey * ey); ex /= nn; ey /= nn
            l1 = maxOf(l1, v.toDouble()); l2 = maxOf(l2, v.toDouble())
            // Omega = l1 e e^T + l2 e_perp e_perp^T
            arrayOf(
                floatArrayOf((l1 * ex * ex + l2 * ey * ey).toFloat(), ((l1 - l2) * ex * ey).toFloat()),
                floatArrayOf(((l1 - l2) * ex * ey).toFloat(), (l1 * ey * ey + l2 * ex * ex).toFloat()),
            )
        } }
    }

    private class M(val zip: Double, val edgeRatio: Double, val sharp: Double, val noise: Double)

    private fun metrics(img: Array<Array<FloatArray>>, acc: C13aMergeDiagTest.Acc, edgeMask: Array<BooleanArray>, flatMask: Array<BooleanArray>): M {
        var zip = 0; var tot = 0
        val ss = DoubleArray(4); val cnt = IntArray(4)
        var sh = 0.0; var shn = 0; var nz = 0.0; var nzn = 0
        fun Y(y: Int, x: Int) = (img[y][x][0] + 2 * img[y][x][1] + img[y][x][2]) / 4.0
        for (y in 8 until crop - 8) for (x in 8 until crop - 8) {
            for (c in listOf(0, 2)) {
                val d = acc.den[y][x][c].toDouble(); if (d * d / acc.den2[y][x][c] < 1.5) zip++; tot++
            }
            var mean = 0.0
            for (dy in -1..1) for (dx in -1..1) mean += Y(y + dy, x + dx)
            val hp = Y(y, x) - mean / 9
            if (edgeMask[y][x]) {
                for (c in listOf(0, 2)) {
                    var mc = 0.0
                    for (dy in -1..1) for (dx in -1..1) mc += img[y + dy][x + dx][c]
                    val hc = img[y][x][c] - mc / 9
                    val ph = (y % 2) * 2 + (x % 2); ss[ph] += hc * hc; cnt[ph]++
                }
                sh += abs(Y(y, x + 1) - Y(y, x - 1)) + abs(Y(y + 1, x) - Y(y - 1, x)); shn++
            }
            if (flatMask[y][x]) { nz += hp * hp; nzn++ }
        }
        val r = DoubleArray(4) { sqrt(ss[it] / cnt[it]) }
        return M(100.0 * zip / tot, r.max() / r.min(), sh / shn, sqrt(nz / nzn))
    }

    @Test
    fun sweep() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val cfg = h.config()
        val alpha = cfg.kernelAlpha; val beta = cfg.kernelBeta
        val sigma = KernelNet.sigma(alpha, beta)
        for ((ox, oy) in listOf(1785 to 1274, 801 to 600)) {
            val frames = (0..7).map { h.readCrop(it, ox, oy, crop, crop) }
            val kn = knCovs(ox, oy, sigma)
            run {
                val sd = ArrayList<Double>()
                for (row in kn) for (m in row) {
                    val a = m[0][0].toDouble(); val b = m[0][1].toDouble(); val c = m[1][1].toDouble()
                    sd += sqrt((a + c) / 2 - sqrt((a - c) * (a - c) / 4 + b * b))
                }
                sd.sort()
                println("C13bK c${ox}_$oy KernelNet minor std p5 %.3f p25 %.3f med %.3f p75 %.3f".format(
                    sd[sd.size / 20], sd[sd.size / 4], sd[sd.size / 2], sd[sd.size * 3 / 4]))
            }
            // masks from the reference bilinear-ish G (stable across variants)
            val ref = frames[0]
            val gmag = Array(crop) { y -> DoubleArray(crop) { x ->
                if (y < 2 || x < 2 || y > crop - 3 || x > crop - 3) 0.0
                else abs(ref[y][x + 2] - ref[y][x - 2]).toDouble() + abs(ref[y + 2][x] - ref[y - 2][x]) } }
            val all = gmag.flatMap { it.toList() }.sorted()
            val eThr = all[(all.size * 0.95).toInt()]; val fThr = all[(all.size * 0.20).toInt()]
            val edge = Array(crop) { y -> BooleanArray(crop) { x -> gmag[y][x] >= eThr } }
            val flat = Array(crop) { y -> BooleanArray(crop) { x -> gmag[y][x] in 1e-12..fThr } }

            for (kernel in listOf("wronski", "kernelnet")) {
                val baseFn: (Int, Array<FloatArray>) -> Array<Array<Array<FloatArray>>> =
                    if (kernel == "wronski") { _, fr -> EndToEnd.frameKernels(fr, cfg) } else { _, _ -> kn }
                var base: M? = null
                val variants = listOf("base" to 0f, "all>=0.5" to 0.5f, "all>=0.7" to 0.7f, "RB>=0.5" to -0.5f, "RB>=0.7" to -0.7f, "RB>=1.0" to -1.0f)
                val baseAcc = h.run(frames, cfg, baseFn)
                val baseImg = h.divided(baseAcc)
                for ((name, s) in variants) {
                    val (img, acc) = when {
                        s == 0f -> baseImg to baseAcc
                        s > 0f -> { val a = h.run(frames, cfg, covFn = { k, fr -> floorCovs(baseFn(k, fr), s) }); h.divided(a) to a }
                        else -> {
                            val a = h.run(frames, cfg, covFn = { k, fr -> floorCovs(baseFn(k, fr), -s) })
                            val im = h.divided(a)
                            // G from base, R/B from floored; den/den2 of R/B from floored
                            val mix = Array(crop) { y -> Array(crop) { x -> floatArrayOf(im[y][x][0], baseImg[y][x][1], im[y][x][2]) } }
                            mix to a
                        }
                    }
                    val m = metrics(img, acc, edge, flat)
                    if (base == null) base = m
                    println("C13bS c${ox}_$oy %-9s %-9s zip(n_eff<1.5) %5.2f%%  edgeRatio %.3f  sharpY %.3f  noiseY %.3f".format(
                        kernel, name, m.zip, m.edgeRatio, m.sharp / base.sharp, m.noise / base.noise))
                }
            }
        }
    }

    /** Per-frame green estimate at every pixel: G sites keep G; R/B sites use Hamilton-Adams-style
     *  directional G (gradient-corrected, pick the smoother direction). */
    private fun greenHat(raw: Array<FloatArray>): Array<FloatArray> {
        val hh = raw.size; val ww = raw[0].size
        fun at(y: Int, x: Int) = raw[y.coerceIn(0, hh - 1)][x.coerceIn(0, ww - 1)]
        return Array(hh) { y -> FloatArray(ww) { x ->
            if ((y + x) % 2 == 1) raw[y][x] else {
                val gh = (at(y, x - 1) + at(y, x + 1)) / 2 + (2 * raw[y][x] - at(y, x - 2) - at(y, x + 2)) / 4
                val gv = (at(y - 1, x) + at(y + 1, x)) / 2 + (2 * raw[y][x] - at(y - 2, x) - at(y + 2, x)) / 4
                val dh = abs(at(y, x - 1) - at(y, x + 1)) + abs(2 * raw[y][x] - at(y, x - 2) - at(y, x + 2))
                val dv = abs(at(y - 1, x) - at(y + 1, x)) + abs(2 * raw[y][x] - at(y - 2, x) - at(y + 2, x))
                when { dh < dv -> gh; dv < dh -> gv; else -> (gh + gv) / 2 }
            }
        } }
    }

    /** Colour-difference merge input: R/B sites carry C - Ghat, G sites unchanged. */
    private fun colourDiff(raw: Array<FloatArray>): Array<FloatArray> {
        val g = greenHat(raw)
        return Array(raw.size) { y -> FloatArray(raw[0].size) { x -> if ((y + x) % 2 == 1) raw[y][x] else raw[y][x] - g[y][x] } }
    }

    @Test
    fun colourDifferenceMerge() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val cfg = h.config()
        val sigma = KernelNet.sigma(cfg.kernelAlpha, cfg.kernelBeta)
        for ((ox, oy) in listOf(1785 to 1274, 801 to 600)) {
            val frames = (0..7).map { h.readCrop(it, ox, oy, crop, crop) }
            val kn = knCovs(ox, oy, sigma)
            val ref = frames[0]
            val gmag = Array(crop) { y -> DoubleArray(crop) { x ->
                if (y < 2 || x < 2 || y > crop - 3 || x > crop - 3) 0.0
                else abs(ref[y][x + 2] - ref[y][x - 2]).toDouble() + abs(ref[y + 2][x] - ref[y - 2][x]) } }
            val all = gmag.flatMap { it.toList() }.sorted()
            val eThr = all[(all.size * 0.95).toInt()]; val fThr = all[(all.size * 0.20).toInt()]
            val edge = Array(crop) { y -> BooleanArray(crop) { x -> gmag[y][x] >= eThr } }
            val flat = Array(crop) { y -> BooleanArray(crop) { x -> gmag[y][x] in 1e-12..fThr } }
            for (kernel in listOf("wronski", "kernelnet")) {
                val fn: (Int, Array<FloatArray>) -> Array<Array<Array<FloatArray>>> =
                    if (kernel == "wronski") { _, fr -> EndToEnd.frameKernels(fr, cfg) } else { _, _ -> kn }
                val baseAcc = h.run(frames, cfg, fn)
                val baseImg = h.divided(baseAcc)
                val cdAcc = h.run(frames, cfg, fn, ::colourDiff)
                val cd = h.divided(cdAcc)
                val cdImg = Array(crop) { y -> Array(crop) { x -> floatArrayOf(cd[y][x][1] + cd[y][x][0], cd[y][x][1], cd[y][x][1] + cd[y][x][2]) } }
                val mb = metrics(baseImg, baseAcc, edge, flat)
                val mc = metrics(cdImg, cdAcc, edge, flat)
                // G-channel edge ratio as the no-zipper yardstick
                fun gRatio(img: Array<Array<FloatArray>>): Double {
                    val ss = DoubleArray(4); val cnt = IntArray(4)
                    for (y in 8 until crop - 8) for (x in 8 until crop - 8) if (edge[y][x]) {
                        var mc2 = 0.0; for (dy in -1..1) for (dx in -1..1) mc2 += img[y + dy][x + dx][1]
                        val hc = img[y][x][1] - mc2 / 9; val ph = (y % 2) * 2 + (x % 2); ss[ph] += hc * hc; cnt[ph]++
                    }
                    val r = DoubleArray(4) { sqrt(ss[it] / cnt[it]) }; return r.max() / r.min()
                }
                println("C13bC c${ox}_$oy %-9s base     : R/B edgeRatio %.3f (G yardstick %.3f) sharpY 1.000 noiseY 1.000".format(kernel, mb.edgeRatio, gRatio(baseImg)))
                println("C13bC c${ox}_$oy %-9s colourDiff: R/B edgeRatio %.3f sharpY %.3f noiseY %.3f".format(kernel, mc.edgeRatio, mc.sharp / mb.sharp, mc.noise / mb.noise))
                if (ox == 801) {
                    val tag = "c${ox}_${oy}_$kernel"
                    h.javaClass.getDeclaredMethod("png", String::class.java, Array<Array<FloatArray>>::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java)
                        .also { it.isAccessible = true }.let { m ->
                            m.invoke(h, "${tag}_base", baseImg, 300, 300, 128, 4)
                            m.invoke(h, "${tag}_colourdiff", cdImg, 300, 300, 128, 4)
                        }
                }
            }
        }
    }

    /** KernelNet covs for an RGGB frame that IS sensor orientation (synthetic), on its grey grid. */
    internal fun knCovsRggb(raw: Array<FloatArray>, sigma: Float, scale: Float = 0.25f, cap: Float = 2f): Array<Array<Array<FloatArray>>> {
        val hh = raw.size; val ww = raw[0].size
        val luma = Array(hh / 2) { ly -> FloatArray(ww / 2) { lx ->
            sqrt(((raw[2 * ly][2 * lx] + raw[2 * ly][2 * lx + 1] + raw[2 * ly + 1][2 * lx] + raw[2 * ly + 1][2 * lx + 1]) / 4f).coerceIn(0f, 1f)) } }
        val map = knMap(luma, sigma)
        val mapH = map[0].size; val mapW = map[0][0].size
        return Array(hh / 2) { gy -> Array(ww / 2) { gx ->
            val X = 2f * gx + 1f; val Y = 2f * gy + 1f
            val u = X / ww * mapW - 0.5f; val v = Y / hh * mapH - 0.5f
            val u0 = kotlin.math.floor(u).toInt().coerceIn(0, mapW - 1); val v0 = kotlin.math.floor(v).toInt().coerceIn(0, mapH - 1)
            val u1 = minOf(u0 + 1, mapW - 1); val v1 = minOf(v0 + 1, mapH - 1)
            val fu = (u - u0).coerceIn(0f, 1f); val fv = (v - v0).coerceIn(0f, 1f)
            val t = FloatArray(3) { c ->
                val t0 = map[c][v0][u0] + fu * (map[c][v0][u1] - map[c][v0][u0])
                val t1 = map[c][v1][u0] + fu * (map[c][v1][u1] - map[c][v1][u0])
                t0 + fv * (t1 - t0) }
            var s1 = maxOf(t[0], 1e-4f); var s2 = maxOf(t[1], 1e-4f)
            val rho = t[2].coerceIn(-0.8f, 0.8f)
            s1 = (s1 * (ww / mapW.toFloat()) * scale).coerceIn(0.25f, cap)
            s2 = (s2 * (hh / mapH.toFloat()) * scale).coerceIn(0.25f, cap)
            if (s1 > s2 * 7f) s1 = s2 * 7f else if (s2 > s1 * 7f) s2 = s1 * 7f
            val det = maxOf(1f - rho * rho, 1e-2f)
            val re = sign(rho) * sqrt(maxOf(1f - det, 0f))
            arrayOf(floatArrayOf(0.5f * s2 * s2, 0.5f * re * s1 * s2), floatArrayOf(0.5f * re * s1 * s2, 0.5f * s1 * s1))
        } }
    }

    /** Z1 variant A/B: G-hat (Hamilton-Adams vs 4-neighbour mean) x (raw vs white-balanced difference). */
    @Test
    fun colourDiffVariants() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val cfg = h.config()
        val wbR = 962f / 512f; val wbB = 763f / 512f
        fun gHat(raw: Array<FloatArray>, ha: Boolean): Array<FloatArray> {
            val hh = raw.size; val ww = raw[0].size
            fun at(y: Int, x: Int) = raw[y.coerceIn(0, hh - 1)][x.coerceIn(0, ww - 1)]
            return Array(hh) { y -> FloatArray(ww) { x ->
                if ((y + x) % 2 == 1) raw[y][x] else if (!ha) (at(y - 1, x) + at(y + 1, x) + at(y, x - 1) + at(y, x + 1)) / 4 else {
                    val gh = (at(y, x - 1) + at(y, x + 1)) / 2 + (2 * raw[y][x] - at(y, x - 2) - at(y, x + 2)) / 4
                    val gv = (at(y - 1, x) + at(y + 1, x)) / 2 + (2 * raw[y][x] - at(y - 2, x) - at(y + 2, x)) / 4
                    val dh = abs(at(y, x - 1) - at(y, x + 1)) + abs(2 * raw[y][x] - at(y, x - 2) - at(y, x + 2))
                    val dv = abs(at(y - 1, x) - at(y + 1, x)) + abs(2 * raw[y][x] - at(y - 2, x) - at(y + 2, x))
                    when { dh < dv -> gh; dv < dh -> gv; else -> (gh + gv) / 2 }
                } } }
        }
        for ((ox, oy) in listOf(1785 to 1274, 801 to 600)) {
            val frames = (0..7).map { h.readCrop(it, ox, oy, crop, crop) }
            val ref = frames[0]
            val gmag = Array(crop) { y -> DoubleArray(crop) { x ->
                if (y < 2 || x < 2 || y > crop - 3 || x > crop - 3) 0.0
                else abs(ref[y][x + 2] - ref[y][x - 2]).toDouble() + abs(ref[y + 2][x] - ref[y - 2][x]) } }
            val all = gmag.flatMap { it.toList() }.sorted()
            val eThr = all[(all.size * 0.95).toInt()]; val fThr = all[(all.size * 0.20).toInt()]
            val edge = Array(crop) { y -> BooleanArray(crop) { x -> gmag[y][x] >= eThr } }
            val flat = Array(crop) { y -> BooleanArray(crop) { x -> gmag[y][x] in 1e-12..fThr } }
            val baseAcc = h.run(frames, cfg); val baseImg = h.divided(baseAcc)
            val mb = metrics(baseImg, baseAcc, edge, flat)
            println("Z1V c${ox}_$oy base: edgeRatio %.3f".format(mb.edgeRatio))
            for (ha in listOf(true, false)) for (wbOn in listOf(false, true)) {
                val gr = if (wbOn) wbR else 1f; val gb = if (wbOn) wbB else 1f
                val acc = h.run(frames, cfg, mergeIn = { raw ->
                    val g = gHat(raw, ha)
                    Array(raw.size) { y -> FloatArray(raw[0].size) { x -> when {
                        (y + x) % 2 == 1 -> raw[y][x]
                        y % 2 == 0 -> gr * raw[y][x] - g[y][x]
                        else -> gb * raw[y][x] - g[y][x] } } }
                })
                val d = h.divided(acc)
                val img = Array(crop) { y -> Array(crop) { x -> floatArrayOf((d[y][x][1] + d[y][x][0]) / gr, d[y][x][1], (d[y][x][1] + d[y][x][2]) / gb) } }
                val m = metrics(img, acc, edge, flat)
                println("Z1V c${ox}_$oy G-hat %-3s diff %-3s: edgeRatio %.3f sharpY %.3f noiseY %.3f".format(
                    if (ha) "HA" else "avg", if (wbOn) "WB" else "raw", m.edgeRatio, m.sharp / mb.sharp, m.noise / mb.noise))
            }
        }
    }

    /** Z1 production check: EndToEnd(colourDiff) for Wronski; harness + shipped ColourDiff for KernelNet. */
    @Test
    fun zipperFixProduction() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val cfg = h.config()
        val wb = floatArrayOf(962f / 512f, 763f / 512f)
        val sigma = KernelNet.sigma(cfg.kernelAlpha, cfg.kernelBeta)
        for ((ox, oy) in listOf(1785 to 1274, 801 to 600)) {
            val frames = (0..7).map { h.readCrop(it, ox, oy, crop, crop) }
            val ref = frames[0]
            val gmag = Array(crop) { y -> DoubleArray(crop) { x ->
                if (y < 2 || x < 2 || y > crop - 3 || x > crop - 3) 0.0
                else abs(ref[y][x + 2] - ref[y][x - 2]).toDouble() + abs(ref[y + 2][x] - ref[y - 2][x]) } }
            val all = gmag.flatMap { it.toList() }.sorted()
            val eThr = all[(all.size * 0.95).toInt()]; val fThr = all[(all.size * 0.20).toInt()]
            val edge = Array(crop) { y -> BooleanArray(crop) { x -> gmag[y][x] >= eThr } }
            val flat = Array(crop) { y -> BooleanArray(crop) { x -> gmag[y][x] in 1e-12..fThr } }
            val baseAcc = h.run(frames, cfg)
            val baseImg = h.divided(baseAcc)
            val mb = metrics(baseImg, baseAcc, edge, flat)
            val off = EndToEnd.run(frames[0], frames.drop(1).map { f -> { f } }, cfg)
            val on = EndToEnd.run(frames[0], frames.drop(1).map { f -> { f } }, cfg.copy(colourDiff = wb))
            val mOff = metrics(off, baseAcc, edge, flat); val mOn = metrics(on, baseAcc, edge, flat)
            println("Z1P c${ox}_$oy Wronski EndToEnd: zipper off %.3f on %.3f | sharpY %.3f noiseY %.3f (on vs off)".format(
                mOff.edgeRatio, mOn.edgeRatio, mOn.sharp / mOff.sharp, mOn.noise / mOff.noise))
            val kn = knCovs(ox, oy, sigma)
            val knOffAcc = h.run(frames, cfg, covFn = { _, _ -> kn }); val knOff = h.divided(knOffAcc)
            val knOnAcc = h.run(frames, cfg, covFn = { _, _ -> kn }, mergeIn = { f -> ColourDiff.toRows(f, wb[0], wb[1]) })
            val knOn = h.divided(knOnAcc).also { ColourDiff.reconstruct(it, wb[0], wb[1]) }
            val kOff = metrics(knOff, knOffAcc, edge, flat); val kOn = metrics(knOn, knOnAcc, edge, flat)
            println("Z1P c${ox}_$oy KernelNet       : zipper off %.3f on %.3f | sharpY %.3f noiseY %.3f (on vs off)".format(
                kOff.edgeRatio, kOn.edgeRatio, kOn.sharp / kOff.sharp, kOn.noise / kOff.noise))
            println("Z1P c${ox}_$oy (base harness Wronski zipper %.3f; G yardstick from earlier run 1.09 / 1.41)".format(mb.edgeRatio))
            for ((name, img) in listOf("wronski_off" to off, "wronski_on" to on, "kernelnet_off" to knOff, "kernelnet_on" to knOn)) {
                h.javaClass.getDeclaredMethod("png", String::class.java, Array<Array<FloatArray>>::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java)
                    .also { it.isAccessible = true }.invoke(h, "z1_c${ox}_${oy}_$name", img, 300, 300, 128, 4)
            }
        }
    }
}
