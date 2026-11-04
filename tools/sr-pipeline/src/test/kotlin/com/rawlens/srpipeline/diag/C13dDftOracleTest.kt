package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.Grey
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.alignLevels
import com.rawlens.srpipeline.stages.initAlignment
import org.jtransforms.fft.DoubleFFT_2D
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Checklist M / C13d: second, independent alignment oracle.
 * Guizar-Sicairos, Thurman, Fienup, "Efficient subpixel image registration
 * algorithms", Opt. Lett. 33(2), 2008 (the method behind
 * skimage.registration.phase_cross_correlation, BSD-3): cross-power spectrum,
 * integer peak, then a matrix-multiply DFT upsampled x100 over a 1.5 px
 * neighbourhood. Plain cross-correlation (paper default), Hann window, 64x64.
 * Residual measured after pre-shifting the moving window by round(our flow).
 *
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*C13dDftOracleTest*' -PsrpDiag=1 -i
 */
class C13dDftOracleTest {
    private val dir = "/Users/monikamalinowska/Downloads/My/Photos/Photos/IMG_20260929_170823_957"
    private val crop = 512
    private val win = 64
    private val up = 100
    private val loop = EndToEnd.loopConfig(PipelineConfig(factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8)))

    private fun readCrop(frame: Int, x0: Int, y0: Int): Array<FloatArray> {
        RandomAccessFile("$dir/IMG_20260929_170823_957_F%02d.dng".format(frame), "r").use { f ->
            val row = ByteArray(crop * 2)
            return Array(crop) { y ->
                f.seek(6644L + ((y0 + y).toLong() * 4080 + x0) * 2)
                f.readFully(row)
                val bb = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(crop) { ((bb.getShort(it * 2).toInt() and 0xFFFF) - 64f) / 959f }
            }
        }
    }

    private val hann = DoubleArray(win) { 0.5 - 0.5 * cos(2 * PI * (it + 0.5) / win) }
    private val fft = DoubleFFT_2D(win.toLong(), win.toLong())

    /** Windowed, mean-removed patch as interleaved complex [win][2*win]. */
    private fun patch(img: Array<FloatArray>, cx: Int, cy: Int): Array<DoubleArray> {
        var mean = 0.0
        for (y in 0 until win) for (x in 0 until win) mean += img[cy - win / 2 + y][cx - win / 2 + x]
        mean /= win * win
        return Array(win) { y -> DoubleArray(2 * win).also { r ->
            for (x in 0 until win) r[2 * x] = (img[cy - win / 2 + y][cx - win / 2 + x] - mean) * hann[y] * hann[x]
        } }
    }

    /**
     * Shift s such that mov(x + s) ~ ref(x) (our flow convention), relative to the
     * window centres. Returns (sx, sy).
     */
    fun register(ref: Array<FloatArray>, mov: Array<FloatArray>, cx: Int, cy: Int, mx: Int, my: Int): Pair<Double, Double> {
        val a = patch(ref, cx, cy); val b = patch(mov, mx, my)
        fft.complexForward(a); fft.complexForward(b)
        // R = conj(A) * B : its inverse peaks at +s when b(x) = a(x - s) ... i.e. mov(x+s)=ref(x)
        val re = Array(win) { DoubleArray(win) }; val im = Array(win) { DoubleArray(win) }
        val cc = Array(win) { DoubleArray(2 * win) }
        for (y in 0 until win) for (x in 0 until win) {
            val ar = a[y][2 * x]; val ai = a[y][2 * x + 1]; val br = b[y][2 * x]; val bi = b[y][2 * x + 1]
            re[y][x] = ar * br + ai * bi; im[y][x] = ar * bi - ai * br
            cc[y][2 * x] = re[y][x]; cc[y][2 * x + 1] = im[y][x]
        }
        fft.complexInverse(cc, false)
        var best = -1.0; var py = 0; var px = 0
        for (y in 0 until win) for (x in 0 until win) {
            val v = cc[y][2 * x]
            if (v > best) { best = v; py = y; px = x }
        }
        val s0y = (if (py > win / 2) py - win else py).toDouble()
        val s0x = (if (px > win / 2) px - win else px).toDouble()
        // Upsampled DFT around (s0y, s0x): 1.5 px span at 1/up resolution.
        val n = (1.5 * up).toInt() + 1
        val off = (n - 1) / 2
        // cc(t) = sum_k R(k) exp(+2 pi i k t / N), t = s0 + (j - off)/up
        val freq = IntArray(win) { if (it < win / 2) it else it - win }
        // first along x: T[y][jx] = sum_kx R[y][kx] e^{i 2pi fx t_x / N}
        val tRe = Array(win) { DoubleArray(n) }; val tIm = Array(win) { DoubleArray(n) }
        for (jx in 0 until n) {
            val t = s0x + (jx - off).toDouble() / up
            for (kx in 0 until win) {
                val ph = 2 * PI * freq[kx] * t / win
                val c = cos(ph); val s = sin(ph)
                for (y in 0 until win) {
                    tRe[y][jx] += re[y][kx] * c - im[y][kx] * s
                    tIm[y][jx] += re[y][kx] * s + im[y][kx] * c
                }
            }
        }
        var bv = -Double.MAX_VALUE; var by = 0.0; var bx = 0.0
        for (jy in 0 until n) {
            val t = s0y + (jy - off).toDouble() / up
            val cs = DoubleArray(win); val sn = DoubleArray(win)
            for (ky in 0 until win) { val ph = 2 * PI * freq[ky] * t / win; cs[ky] = cos(ph); sn[ky] = sin(ph) }
            for (jx in 0 until n) {
                var v = 0.0
                for (ky in 0 until win) v += tRe[ky][jx] * cs[ky] - tIm[ky][jx] * sn[ky]
                if (v > bv) { bv = v; by = t; bx = s0x + (jx - off).toDouble() / up }
            }
        }
        return bx to by
    }

    private fun keys(t: Double): Double { val a = abs(t); return when { a <= 1 -> (1.5 * a - 2.5) * a * a + 1; a < 2 -> ((-0.5 * a + 2.5) * a - 4) * a + 2; else -> 0.0 } }
    private fun bicubic(img: Array<FloatArray>, x: Double, y: Double): Double {
        val ix = floor(x).toInt(); val iy = floor(y).toInt(); var s = 0.0
        for (j in -1..2) { val wy = keys(y - (iy + j)); val r = img[(iy + j).coerceIn(0, img.size - 1)]
            for (i in -1..2) s += wy * keys(x - (ix + i)) * r[(ix + i).coerceIn(0, r.size - 1)] }
        return s
    }
    private fun pct(s: List<Double>, q: Double) = s[(q * (s.size - 1)).toInt()]
    private val origins = listOf(1785 to 1274, 801 to 600)

    @Test
    fun validateOnKnownShifts() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        for ((ox, oy) in origins) {
            val g0 = Grey.computeGreyImages(readCrop(0, ox, oy), "FFT")
            val rng = java.util.Random(3)
            for ((sx, sy) in listOf(0.0 to 0.0, 0.25 to -0.1, 0.5 to 0.25, 0.75 to 0.4, 1.3 to -0.6)) {
                // moving(x) = ref(x - s), plus noise at the measured grey level (~4.6e-4)
                val mov = Array(crop) { y -> FloatArray(crop) { x -> (bicubic(g0, x - sx, y - sy) + 4.6e-4 * rng.nextGaussian()).toFloat() } }
                val errs = ArrayList<Double>()
                for (ty in 3 until 29 step 3) for (tx in 3 until 29 step 3) {
                    val cx = tx * 16 + 8; val cy = ty * 16 + 8
                    val rx = Math.round(sx).toInt(); val ry = Math.round(sy).toInt()
                    val (dx, dy) = register(g0, mov, cx, cy, cx + rx, cy + ry)
                    val ex = rx + dx - sx; val ey = ry + dy - sy
                    errs += sqrt(ex * ex + ey * ey)
                }
                val s = errs.sorted()
                println("C13dV c${ox}_$oy s=(%.2f,%.2f) DFT error med %.4f p95 %.4f max %.4f".format(sx, sy, pct(s, .5), pct(s, .95), s.last()))
            }
        }
    }

    @Test
    fun oursVsDftOnRealMotion() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        for ((ox, oy) in origins) {
            val g0 = Grey.computeGreyImages(readCrop(0, ox, oy), "FFT")
            val init = initAlignment(g0, loop)
            // texture gate: structure-tensor min eigenvalue over the 64 window, top quartile
            val nT = crop / 16
            val cand = ArrayList<Triple<Int, Int, Double>>()
            for (ty in 3 until nT - 3) for (tx in 3 until nT - 3) {
                var a = 0.0; var b = 0.0; var c = 0.0
                for (y in ty * 16 - 24 until ty * 16 + 40) for (x in tx * 16 - 24 until tx * 16 + 40) {
                    val gx = (g0[y][x + 1] - g0[y][x - 1]) / 2.0; val gy = (g0[y + 1][x] - g0[y - 1][x]) / 2.0
                    a += gx * gx; b += gx * gy; c += gy * gy
                }
                cand += Triple(ty, tx, (a + c) / 2 - sqrt((a - c) * (a - c) / 4 + b * b))
            }
            val thr = cand.map { it.third }.sorted()[cand.size * 3 / 4]
            val sel = cand.filter { it.third >= thr }
            val errs = ArrayList<Double>(); var bx = 0.0; var by = 0.0
            val fracOurs = IntArray(10); val fracDft = IntArray(10)
            for (k in 1..7) {
                val gk = Grey.computeGreyImages(readCrop(k, ox, oy), "FFT")
                val f = alignLevels(init, gk, loop)
                for ((ty, tx, _) in sel) {
                    val fx = f[ty][tx][0].toDouble(); val fy = f[ty][tx][1].toDouble()
                    val cx = tx * 16 + 8; val cy = ty * 16 + 8
                    val rx = Math.round(fx).toInt(); val ry = Math.round(fy).toInt()
                    if (cx + rx - 33 < 0 || cy + ry - 33 < 0 || cx + rx + 33 > crop || cy + ry + 33 > crop) continue
                    val (dx, dy) = register(g0, gk, cx, cy, cx + rx, cy + ry)
                    val qx = rx + dx; val qy = ry + dy
                    errs += sqrt((fx - qx) * (fx - qx) + (fy - qy) * (fy - qy)); bx += fx - qx; by += fy - qy
                    fracOurs[((fx - floor(fx)) * 10).toInt().coerceIn(0, 9)]++
                    fracDft[((qx - floor(qx)) * 10).toInt().coerceIn(0, 9)]++
                }
            }
            val s = errs.sorted(); val n = s.size
            fun h(x: IntArray) = x.joinToString(" ") { "%.2f".format(it.toDouble() / x.sum()) }
            println("C13dR c${ox}_$oy textured n=$n ours-DFT bias (%+.4f,%+.4f) rms %.4f med %.4f p90 %.4f p95 %.4f >0.1px %.1f%% >0.25px %.1f%%".format(
                bx / n, by / n, sqrt(s.sumOf { it * it } / n), pct(s, .5), pct(s, .9), pct(s, .95),
                100.0 * s.count { it > 0.1 } / n, 100.0 * s.count { it > 0.25 } / n))
            println("C13dR c${ox}_$oy frac x ours: ${h(fracOurs)}")
            println("C13dR c${ox}_$oy frac x DFT : ${h(fracDft)}")
        }
    }
}
