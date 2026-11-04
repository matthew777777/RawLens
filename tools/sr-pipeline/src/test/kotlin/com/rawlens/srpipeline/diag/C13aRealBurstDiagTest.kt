package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.AlignLoopConfig
import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.Grey
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.alignLevels
import com.rawlens.srpipeline.stages.initAlignment
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Checklist M / C13a on the real 8-frame burst IMG_20260929_170823_957
 * (REDMI Note 15 Pro, 4080x3060, GRBG, black 64, white 1023, uncompressed
 * single strip). Crops only; measurement only, no production code.
 *
 * Crops start one column right of an even x, turning GRBG into RGGB (same
 * effect on alignment as the app's flip).
 *
 * A) Known integer shift, real content: ref = F00 crop, moving = F00 crop
 *    displaced by an even (sx, sy) => moving(x) = ref(x - s), flow must be s.
 *    (Same noise realisation in both: optimistic for noise.)
 * B) Real motion F01..F07 vs F00: our flow vs an independent oracle,
 *    exhaustive sub-pixel SSD (Keys bicubic, 32x32 grey window centred on
 *    the tile, 0.1 then 0.01 px grid within +-0.6 px of our flow).
 * C) Phase coverage: per tile, the 8 fractional offsets (ref = 0,0) on the
 *    unit torus; mean nearest-neighbour distance vs i.i.d. uniform.
 *
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*C13aRealBurstDiagTest*' -PsrpDiag=1 -i
 */
class C13aRealBurstDiagTest {

    private val dir = "/Users/monikamalinowska/Downloads/My/Photos/Photos/IMG_20260929_170823_957"
    private val stem = "IMG_20260929_170823_957"
    private val rawW = 4080
    private val rawH = 3060
    private val stripOffset = 6644L
    private val black = 64f
    private val white = 1023f
    private val crop = 512
    private val loop: AlignLoopConfig = EndToEnd.loopConfig(
        PipelineConfig(factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8)),
    )

    private fun readCrop(frame: Int, x0: Int, y0: Int, w: Int = crop, h: Int = crop): Array<FloatArray> {
        require(x0 % 2 == 1 && y0 % 2 == 0) { "x0 odd (GRBG->RGGB), y0 even" }
        RandomAccessFile("$dir/${stem}_F%02d.dng".format(frame), "r").use { f ->
            val row = ByteArray(w * 2)
            return Array(h) { y ->
                f.seek(stripOffset + ((y0 + y).toLong() * rawW + x0) * 2)
                f.readFully(row)
                val bb = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(w) { ((bb.getShort(it * 2).toInt() and 0xFFFF) - black) / (white - black) }
            }
        }
    }

    private fun keys(t: Double): Double {
        val a = abs(t)
        return when {
            a <= 1 -> (1.5 * a - 2.5) * a * a + 1
            a < 2 -> ((-0.5 * a + 2.5) * a - 4) * a + 2
            else -> 0.0
        }
    }

    private fun bicubic(img: Array<FloatArray>, x: Double, y: Double): Double {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        var s = 0.0
        for (j in -1..2) {
            val wy = keys(y - (iy + j))
            val r = img[(iy + j).coerceIn(0, img.size - 1)]
            for (i in -1..2) s += wy * keys(x - (ix + i)) * r[(ix + i).coerceIn(0, r.size - 1)]
        }
        return s
    }

    /** SSD between ref window and moving sampled at +flow (moving(x+f) ~ ref(x)). */
    private fun ssd(ref: Array<FloatArray>, mov: Array<FloatArray>, cx: Int, cy: Int, fx: Double, fy: Double): Double {
        var s = 0.0
        for (y in cy - 16 until cy + 16) for (x in cx - 16 until cx + 16) {
            val d = bicubic(mov, x + fx, y + fy) - ref[y][x]
            s += d * d
        }
        return s
    }

    private fun oracle(ref: Array<FloatArray>, mov: Array<FloatArray>, cx: Int, cy: Int, gx: Double, gy: Double): Pair<Double, Double> {
        var best = Triple(Double.MAX_VALUE, gx, gy)
        for ((step, span) in listOf(0.1 to 6, 0.01 to 10)) {
            val (_, bx, by) = best
            for (j in -span..span) for (i in -span..span) {
                val fx = bx + i * step
                val fy = by + j * step
                val v = ssd(ref, mov, cx, cy, fx, fy)
                if (v < best.first) best = Triple(v, fx, fy)
            }
        }
        return best.second to best.third
    }

    private fun pct(sorted: List<Double>, q: Double) = sorted[(q * (sorted.size - 1)).toInt()]

    // Crop origins (x odd, y even): centre, upper-left texture, lower-right.
    private val origins = listOf(1785 to 1274, 801 to 600, 3001 to 2200)

    @Test
    fun knownIntegerShift() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        for ((ox, oy) in origins) {
            val ref = readCrop(0, ox, oy)
            val init = initAlignment(Grey.computeGreyImages(ref, "FFT"), loop)
            for ((sx, sy) in listOf(0 to 0, 2 to 0, 0 to 2, 2 to 2, 4 to -2, 8 to 6, 16 to -10, 24 to 20)) {
                // moving(x) = ref(x - s)  =>  crop origin o - s
                val mov = readCrop(0, ox - sx, oy - sy)
                val flow = alignLevels(init, Grey.computeGreyImages(mov, "FFT"), loop)
                val mx = (abs(sx) + 15) / 16 + 1
                val my = (abs(sy) + 15) / 16 + 1
                val errs = ArrayList<Double>()
                var bad = 0
                for (ty in my until flow.size - my) for (tx in mx until flow[0].size - mx) {
                    val dx = flow[ty][tx][0].toDouble() - sx
                    val dy = flow[ty][tx][1].toDouble() - sy
                    val e = sqrt(dx * dx + dy * dy)
                    errs += e
                    if (e > 0.5) bad++
                }
                errs.sort()
                println("C13aA crop(%d,%d) s=(%3d,%3d) tiles=%d rms=%.4f med=%.4f p95=%.4f max=%.3f  >0.5px: %d (%.1f%%)".format(
                    ox, oy, sx, sy, errs.size, sqrt(errs.sumOf { it * it } / errs.size), pct(errs, .5), pct(errs, .95), errs.last(),
                    bad, 100.0 * bad / errs.size))
            }
        }
    }

    /** Validates the SSD oracle on known shifts, and re-runs big shifts on a 2048 crop. */
    @Test
    fun oracleAndCropSizeChecks() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        for ((ox, oy) in listOf(origins[0], origins[2])) {
            val refGrey = Grey.computeGreyImages(readCrop(0, ox, oy), "FFT")
            for ((sx, sy) in listOf(2 to 0, 8 to 6)) {
                val movGrey = Grey.computeGreyImages(readCrop(0, ox - sx, oy - sy), "FFT")
                val errs = ArrayList<Double>()
                for (ty in 4 until 28 step 3) for (tx in 4 until 28 step 3) {
                    // start the oracle 0.3 px off the truth
                    val (fx, fy) = oracle(refGrey, movGrey, tx * 16 + 8, ty * 16 + 8, sx + 0.3, sy - 0.3)
                    errs += sqrt((fx - sx) * (fx - sx) + (fy - sy) * (fy - sy))
                }
                val s = errs.sorted()
                println("C13aO oracle crop(%d,%d) s=(%d,%d) err med %.4f p95 %.4f max %.4f (n=%d)".format(
                    ox, oy, sx, sy, pct(s, .5), pct(s, .95), s.last(), s.size))
            }
        }
        val big = 2048
        val (bx, by) = 1785 to 506
        val ref = readCrop(0, bx, by, big, big)
        val init = initAlignment(Grey.computeGreyImages(ref, "FFT"), loop)
        for ((sx, sy) in listOf(8 to 6, 16 to -10, 24 to 20, 40 to -30)) {
            val flow = alignLevels(init, Grey.computeGreyImages(readCrop(0, bx - sx, by - sy, big, big), "FFT"), loop)
            val mx = (abs(sx) + 15) / 16 + 1
            val my = (abs(sy) + 15) / 16 + 1
            val errs = ArrayList<Double>()
            for (ty in my until flow.size - my) for (tx in mx until flow[0].size - mx) {
                val dx = flow[ty][tx][0].toDouble() - sx
                val dy = flow[ty][tx][1].toDouble() - sy
                errs += sqrt(dx * dx + dy * dy)
            }
            val s = errs.sorted()
            println("C13aA2048 s=(%3d,%3d) tiles=%d rms=%.4f med=%.4f p95=%.4f max=%.3f >0.5px %.1f%%".format(
                sx, sy, s.size, sqrt(s.sumOf { it * it } / s.size), pct(s, .5), pct(s, .95), s.last(),
                100.0 * s.count { it > 0.5 } / s.size))
        }
    }

    /** Bad-tile location for big shifts; frac histograms ours vs oracle; closure 0->2 vs 0->1 + 1->2. */
    @Test
    fun lockingAndClosure() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val big = 2048
        val (bx, by) = 1785 to 506
        val init = initAlignment(Grey.computeGreyImages(readCrop(0, bx, by, big, big), "FFT"), loop)
        for ((sx, sy) in listOf(16 to -10, 24 to 20)) {
            val flow = alignLevels(init, Grey.computeGreyImages(readCrop(0, bx - sx, by - sy, big, big), "FFT"), loop)
            val n = flow.size
            val badByDist = IntArray(9)
            val allByDist = IntArray(9)
            for (ty in 0 until n) for (tx in 0 until flow[0].size) {
                val d = minOf(minOf(ty, n - 1 - ty), minOf(tx, flow[0].size - 1 - tx))
                val bin = when { d < 2 -> 0; d < 4 -> 1; d < 8 -> 2; d < 16 -> 3; d < 24 -> 4; d < 32 -> 5; d < 48 -> 6; d < 64 -> 7; else -> 8 }
                allByDist[bin]++
                val dx = flow[ty][tx][0] - sx
                val dy = flow[ty][tx][1] - sy
                if (dx * dx + dy * dy > 0.25f) badByDist[bin]++
            }
            val labels = listOf("<2", "2-4", "4-8", "8-16", "16-24", "24-32", "32-48", "48-64", ">=64")
            println("C13aL s=($sx,$sy) bad share by tile distance to crop border: " +
                labels.indices.joinToString("  ") { "${labels[it]}:%.1f%%".format(100.0 * badByDist[it] / maxOf(1, allByDist[it])) })
        }

        for ((ox, oy) in listOf(origins[0], origins[2])) {
            val g = (0..2).map { Grey.computeGreyImages(readCrop(it, ox, oy), "FFT") }
            val i0 = initAlignment(g[0], loop)
            val i1 = initAlignment(g[1], loop)
            val f01 = alignLevels(i0, g[1], loop)
            val f02 = alignLevels(i0, g[2], loop)
            val f12 = alignLevels(i1, g[2], loop)
            val hOurs = IntArray(10)
            val hOr = IntArray(10)
            val cOurs = ArrayList<Double>()
            val cOr = ArrayList<Double>()
            for (ty in 3 until f01.size - 3 step 2) for (tx in 3 until f01[0].size - 3 step 2) {
                val cx = tx * 16 + 8
                val cy = ty * 16 + 8
                val a = f01[ty][tx]
                val b = f02[ty][tx]
                // 1->2 evaluated where tile (ty,tx) of frame 0 lands in frame 1
                val t1y = ((cy + a[1]) / 16).toInt().coerceIn(0, f12.size - 1)
                val t1x = ((cx + a[0]) / 16).toInt().coerceIn(0, f12[0].size - 1)
                val c = f12[t1y][t1x]
                val cdx = (a[0] + c[0] - b[0]).toDouble()
                val cdy = (a[1] + c[1] - b[1]).toDouble()
                cOurs += sqrt(cdx * cdx + cdy * cdy)
                val o01 = oracle(g[0], g[1], cx, cy, a[0].toDouble(), a[1].toDouble())
                val o02 = oracle(g[0], g[2], cx, cy, b[0].toDouble(), b[1].toDouble())
                val c1x = (cx + o01.first).toInt()
                val c1y = (cy + o01.second).toInt()
                if (c1x < 24 || c1y < 24 || c1x > crop - 24 || c1y > crop - 24) continue
                val o12 = oracle(g[1], g[2], c1x, c1y, c[0].toDouble(), c[1].toDouble())
                // o12 measured at integer c1; correct for the sub-pixel landing offset is second order (flow ~ smooth)
                val odx = o01.first + o12.first - o02.first
                val ody = o01.second + o12.second - o02.second
                cOr += sqrt(odx * odx + ody * ody)
                hOurs[((a[0] - floor(a[0])) * 10).toInt().coerceIn(0, 9)]++
                hOr[((o01.first - floor(o01.first)) * 10).toInt().coerceIn(0, 9)]++
            }
            fun h(x: IntArray) = x.joinToString(" ") { "%.3f".format(it.toDouble() / x.sum()) }
            val so = cOurs.sorted()
            val sr = cOr.sorted()
            println("C13aK crop($ox,$oy) frac x F01 ours  : ${h(hOurs)}")
            println("C13aK crop($ox,$oy) frac x F01 oracle: ${h(hOr)}")
            println("C13aK crop(%d,%d) closure |0->1 + 1->2 - 0->2|  ours med %.4f p95 %.4f | oracle med %.4f p95 %.4f (n=%d/%d)".format(
                ox, oy, pct(so, .5), pct(so, .95), pct(sr, .5), pct(sr, .95), so.size, sr.size))
        }
    }

    /** Real grey content, known sub-pixel shift (bicubic), optional noise; bypasses the raw/grey step. */
    @Test
    fun greyDomainSubpixel() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        for ((ox, oy) in listOf(origins[0], origins[2])) {
            val g0 = Grey.computeGreyImages(readCrop(0, ox, oy), "FFT")
            val g1 = Grey.computeGreyImages(readCrop(1, ox, oy), "FFT")
            // noise of one grey frame: half the variance of the F00-F01 difference on flat-ish... use residual after our alignment is too indirect;
            // estimate from Laplacian MAD instead.
            val lap = ArrayList<Double>()
            for (y in 1 until crop - 1) for (x in 1 until crop - 1)
                lap += abs(4.0 * g0[y][x] - g0[y - 1][x] - g0[y + 1][x] - g0[y][x - 1] - g0[y][x + 1])
            lap.sort()
            val sigma = lap[lap.size / 2] / 0.6745 / sqrt(20.0)
            println("C13aG crop($ox,$oy) grey noise sigma estimate %.5f".format(sigma))
            val init = initAlignment(g0, loop)
            for (noise in listOf(0.0, sigma)) for (s in listOf(0.25, 0.5, 0.75, 1.5)) {
                val rng = java.util.Random(7)
                // moving(x) = ref(x - s) on both axes (s, s/2)
                val mov = Array(crop) { y -> FloatArray(crop) { x ->
                    (bicubic(g0, x - s, y - s / 2) + noise * rng.nextGaussian()).toFloat() } }
                val f = alignLevels(init, mov, loop)
                val ex = ArrayList<Double>()
                val hist = IntArray(10)
                for (ty in 2 until f.size - 2) for (tx in 2 until f[0].size - 2) {
                    val dx = f[ty][tx][0] - s
                    val dy = f[ty][tx][1] - s / 2
                    ex += sqrt(dx * dx + dy * dy)
                    val fx = f[ty][tx][0].toDouble()
                    hist[((fx - floor(fx)) * 10).toInt().coerceIn(0, 9)]++
                }
                val so = ex.sorted()
                println("C13aG crop(%d,%d) noise %.4f s=(%.2f,%.3f) err med %.4f p95 %.4f | frac x hist %s".format(
                    ox, oy, noise, s, s / 2, pct(so, .5), pct(so, .95), hist.joinToString(" ") { "%.2f".format(it.toDouble() / hist.sum()) }))
            }
            // real F01 alignment in grey domain from g1 for comparison was C13aB.
            check(g1.isNotEmpty())
        }
    }

    /** Per-tile residual RMS at our flow vs oracle flow vs the noise floor (sigma*sqrt2). */
    @Test
    fun residualVsNoiseFloor() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        for ((ox, oy) in listOf(origins[0], origins[2])) {
            val g0 = Grey.computeGreyImages(readCrop(0, ox, oy), "FFT")
            val lap = ArrayList<Double>()
            for (y in 1 until crop - 1) for (x in 1 until crop - 1)
                lap += abs(4.0 * g0[y][x] - g0[y - 1][x] - g0[y + 1][x] - g0[y][x - 1] - g0[y][x + 1])
            lap.sort()
            val sigma = lap[lap.size / 2] / 0.6745 / sqrt(20.0)
            val init = initAlignment(g0, loop)
            for (k in listOf(1, 4, 7)) {
                val gk = Grey.computeGreyImages(readCrop(k, ox, oy), "FFT")
                val f = alignLevels(init, gk, loop)
                val rOurs = ArrayList<Double>()
                val rOr = ArrayList<Double>()
                val rZeroFrac = ArrayList<Double>()
                for (ty in 3 until f.size - 3 step 3) for (tx in 3 until f[0].size - 3 step 3) {
                    val cx = tx * 16 + 8
                    val cy = ty * 16 + 8
                    val fx = f[ty][tx][0].toDouble()
                    val fy = f[ty][tx][1].toDouble()
                    val (qx, qy) = oracle(g0, gk, cx, cy, fx, fy)
                    rOurs += sqrt(ssd(g0, gk, cx, cy, fx, fy) / 1024)
                    rOr += sqrt(ssd(g0, gk, cx, cy, qx, qy) / 1024)
                    rZeroFrac += sqrt(ssd(g0, gk, cx, cy, Math.round(qx).toDouble(), Math.round(qy).toDouble()) / 1024)
                }
                println("C13aR crop(%d,%d) F%02d residual RMS median: ours %.5f  oracle %.5f  oracle-rounded-to-int %.5f  noise floor %.5f".format(
                    ox, oy, k, rOurs.sorted()[rOurs.size / 2], rOr.sorted()[rOr.size / 2], rZeroFrac.sorted()[rZeroFrac.size / 2], sigma * sqrt(2.0)))
            }
        }
    }

    /** Ours vs oracle restricted to well-textured tiles (structure-tensor min eigenvalue, top quartile). */
    @Test
    fun texturedTilesVsOracle() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        for ((ox, oy) in origins) {
            val g0 = Grey.computeGreyImages(readCrop(0, ox, oy), "FFT")
            val init = initAlignment(g0, loop)
            val nT = crop / 16
            val lam = Array(nT) { DoubleArray(nT) }
            for (ty in 0 until nT) for (tx in 0 until nT) {
                var a = 0.0; var b = 0.0; var c = 0.0
                for (y in maxOf(1, ty * 16 - 8) until minOf(crop - 1, ty * 16 + 24)) for (x in maxOf(1, tx * 16 - 8) until minOf(crop - 1, tx * 16 + 24)) {
                    val gx = (g0[y][x + 1] - g0[y][x - 1]) / 2.0
                    val gy = (g0[y + 1][x] - g0[y - 1][x]) / 2.0
                    a += gx * gx; b += gx * gy; c += gy * gy
                }
                lam[ty][tx] = (a + c) / 2 - sqrt(((a - c) / 2) * ((a - c) / 2) + b * b)
            }
            val cand = ArrayList<Pair<Int, Int>>()
            for (ty in 3 until nT - 3) for (tx in 3 until nT - 3) cand += ty to tx
            val thr = cand.map { lam[it.first][it.second] }.sorted()[(cand.size * 3) / 4]
            val sel = cand.filter { lam[it.first][it.second] >= thr }
            val errs = ArrayList<Double>()
            for (k in 1..7) {
                val gk = Grey.computeGreyImages(readCrop(k, ox, oy), "FFT")
                val f = alignLevels(init, gk, loop)
                for ((ty, tx) in sel) {
                    val fx = f[ty][tx][0].toDouble()
                    val fy = f[ty][tx][1].toDouble()
                    val (qx, qy) = oracle(g0, gk, tx * 16 + 8, ty * 16 + 8, fx, fy)
                    errs += sqrt((fx - qx) * (fx - qx) + (fy - qy) * (fy - qy))
                }
            }
            val so = errs.sorted()
            println("C13aT crop(%d,%d) textured tiles=%d x7 frames: ours-oracle rms %.4f med %.4f p90 %.4f p95 %.4f  >0.1px %.1f%%  >0.25px %.1f%%".format(
                ox, oy, sel.size, sqrt(so.sumOf { it * it } / so.size), pct(so, .5), pct(so, .9), pct(so, .95),
                100.0 * so.count { it > 0.1 } / so.size, 100.0 * so.count { it > 0.25 } / so.size))
        }
    }

    /** Which pyramid level breaks the border band: per-level bad share by tile distance to border. */
    @Test
    fun borderBandPerLevel() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val big = 2048
        val (bx, by) = 1785 to 506
        val init = initAlignment(Grey.computeGreyImages(readCrop(0, bx, by, big, big), "FFT"), loop)
        for ((sx, sy) in listOf(16 to -10, 24 to 20)) {
            val lv = com.rawlens.srpipeline.stages.alignLevelsPerLevel(init, Grey.computeGreyImages(readCrop(0, bx - sx, by - sy, big, big), "FFT"), loop)
            var scale = loop.factors.fold(1) { a, f -> a * f }
            lv.forEachIndexed { p, flow ->
                val tilePx = loop.tileSizes[loop.factors.size - 1 - p] * scale
                val n = flow.size; val m = flow[0].size
                val bad = IntArray(6); val all = IntArray(6)
                for (ty in 0 until n) for (tx in 0 until m) {
                    val dPx = minOf(minOf(ty, n - 1 - ty), minOf(tx, m - 1 - tx)) * tilePx
                    val b = when { dPx < 64 -> 0; dPx < 128 -> 1; dPx < 256 -> 2; dPx < 384 -> 3; dPx < 512 -> 4; else -> 5 }
                    all[b]++
                    val ex = flow[ty][tx][0] * scale - sx; val ey = flow[ty][tx][1] * scale - sy
                    // a level is "bad" if off by more than one of its own pixels
                    if (sqrt((ex * ex + ey * ey).toDouble()) > scale) bad[b]++
                }
                val lab = listOf("<64", "64-128", "128-256", "256-384", "384-512", ">=512")
                println("C13aBB s=($sx,$sy) level $p (x$scale, tile ${tilePx}px, ${n}x$m): " +
                    lab.indices.joinToString("  ") { "${lab[it]}:" + if (all[it] == 0) "-" else "%.0f%%".format(100.0 * bad[it] / all[it]) })
                var tlB = 0; var tlA = 0; var brB = 0; var brA = 0
                for (ty in 0 until n) for (tx in 0 until m) {
                    val ex = flow[ty][tx][0] * scale - sx; val ey = flow[ty][tx][1] * scale - sy
                    val isBad = sqrt((ex * ex + ey * ey).toDouble()) > scale
                    if (minOf(ty, tx) * tilePx < 128) { tlA++; if (isBad) tlB++ }
                    if (maxOf(ty - (n - 1), tx - (m - 1)) > -128 / tilePx - 1 && (n - 1 - ty) * tilePx < 128 || (m - 1 - tx) * tilePx < 128) { brA++; if (isBad) brB++ }
                }
                println("C13aBB   level $p within 128px: top/left bad %.0f%%  bottom/right bad %.0f%%".format(100.0 * tlB / maxOf(1, tlA), 100.0 * brB / maxOf(1, brA)))
                scale /= loop.factors[loop.factors.size - 1 - p]
            }
        }
    }

    @Test
    fun realMotionVsOracle() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        for ((ox, oy) in origins) {
            val refRaw = readCrop(0, ox, oy)
            val refGrey = Grey.computeGreyImages(refRaw, "FFT")
            val init = initAlignment(refGrey, loop)
            val flows = ArrayList<Array<Array<FloatArray>>>()
            val allErr = ArrayList<Double>()
            for (k in 1..7) {
                val movGrey = Grey.computeGreyImages(readCrop(k, ox, oy), "FFT")
                val flow = alignLevels(init, movGrey, loop)
                flows += flow
                val errs = ArrayList<Double>()
                var bx = 0.0
                var by = 0.0
                val fxs = ArrayList<Double>()
                val fys = ArrayList<Double>()
                // every 2nd interior tile (cost), window must stay inside after shift
                for (ty in 2 until flow.size - 2 step 2) for (tx in 2 until flow[0].size - 2 step 2) {
                    val fx = flow[ty][tx][0].toDouble()
                    val fy = flow[ty][tx][1].toDouble()
                    val cx = tx * 16 + 8
                    val cy = ty * 16 + 8
                    if (cx + fx - 20 < 0 || cy + fy - 20 < 0 || cx + fx + 20 >= crop || cy + fy + 20 >= crop) continue
                    val (ox2, oy2) = oracle(refGrey, movGrey, cx, cy, fx, fy)
                    val dx = fx - ox2
                    val dy = fy - oy2
                    bx += dx; by += dy
                    errs += sqrt(dx * dx + dy * dy)
                    fxs += fx; fys += fy
                }
                val n = errs.size
                val s = errs.sorted()
                println("C13aB crop(%d,%d) F%02d flow med (%.2f,%.2f) | ours-oracle bias (%+.4f,%+.4f) rms %.4f med %.4f p95 %.4f max %.3f  >0.1px %.1f%%  >0.5px %.1f%% (n=%d)".format(
                    ox, oy, k, fxs.sorted()[n / 2], fys.sorted()[n / 2], bx / n, by / n,
                    sqrt(s.sumOf { it * it } / n), pct(s, .5), pct(s, .95), s.last(),
                    100.0 * s.count { it > 0.1 } / n, 100.0 * s.count { it > 0.5 } / n, n))
                allErr += errs
            }
            val s = allErr.sorted()
            println("C13aB crop(%d,%d) ALL rms %.4f med %.4f p95 %.4f  >0.1px %.1f%%".format(ox, oy,
                sqrt(s.sumOf { it * it } / s.size), pct(s, .5), pct(s, .95), 100.0 * s.count { it > 0.1 } / s.size))

            // C) phase coverage + frac histogram
            val hist = IntArray(10)
            var nnSum = 0.0
            var nnCnt = 0
            val ty0 = 2
            for (ty in ty0 until flows[0].size - 2) for (tx in 2 until flows[0][0].size - 2) {
                val ph = ArrayList<DoubleArray>()
                ph += doubleArrayOf(0.0, 0.0)
                for (f in flows) {
                    val fx = f[ty][tx][0].toDouble()
                    val fy = f[ty][tx][1].toDouble()
                    ph += doubleArrayOf(fx - floor(fx), fy - floor(fy))
                    hist[((fx - floor(fx)) * 10).toInt().coerceIn(0, 9)]++
                }
                for (i in ph.indices) {
                    var best = 9.0
                    for (j in ph.indices) if (i != j) {
                        var dx = abs(ph[i][0] - ph[j][0]); dx = minOf(dx, 1 - dx)
                        var dy = abs(ph[i][1] - ph[j][1]); dy = minOf(dy, 1 - dy)
                        best = minOf(best, sqrt(dx * dx + dy * dy))
                    }
                    nnSum += best; nnCnt++
                }
            }
            // i.i.d. uniform on the unit torus, 8 points: E[nn] ~= 0.5/sqrt(7) (Poisson approx) ~= 0.189
            val tot = hist.sum().toDouble()
            println("C13aC crop(%d,%d) frac(flow_x) hist: %s".format(ox, oy, hist.joinToString(" ") { "%.3f".format(it / tot) }))
            println("C13aC crop(%d,%d) mean NN phase distance %.4f (uniform-random 8 pts ~0.189; 0 = all phases coincide)".format(ox, oy, nnSum / nnCnt))
        }
    }
}
