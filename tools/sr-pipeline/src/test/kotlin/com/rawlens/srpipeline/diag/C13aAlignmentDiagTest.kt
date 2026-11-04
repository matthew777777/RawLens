package com.rawlens.srpipeline.diag

import com.rawlens.srpipeline.stages.AlignLoopConfig
import com.rawlens.srpipeline.stages.EndToEnd
import com.rawlens.srpipeline.stages.Grey
import com.rawlens.srpipeline.stages.PipelineConfig
import com.rawlens.srpipeline.stages.alignLevelsPerLevel
import com.rawlens.srpipeline.stages.initAlignment
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Checklist M / C13a: alignment precision on synthetic bursts with known
 * shifts. No production code is touched; this only measures.
 *
 * Scene: band-limited sinusoids plus soft edges, evaluated analytically at
 * shifted positions with 4x4 box integration per raw pixel (sensor pixel
 * aperture), RGGB gains, optional Poisson-Gaussian noise (var = a*v + b).
 * The moving frame is `ref(x - s)`, so the expected flow is `+s` (sign pinned
 * by AlignLoopTest: roll(+2x) -> flow (2,0)); flow is in grey (half-raw)
 * pixels for bayer, so the expected grey flow is `s_raw / 2`.
 *
 * Alignment config = CLI/app: factors [1,2,4,4], tiles [16,16,16,8]
 * (SNR > 22 dB), radii [1,4,4,4], 3 ICA iterations, FFT grey.
 *
 * Run: ./gradlew :tools:sr-pipeline:test --tests '*C13aAlignmentDiagTest*' -PsrpDiag=1 -i
 */
class C13aAlignmentDiagTest {

    private val size = 512 // raw px; grey 256
    private val loop: AlignLoopConfig = EndToEnd.loopConfig(
        PipelineConfig(factors = listOf(1, 2, 4, 4), tileSizes = listOf(16, 16, 16, 8)),
    )

    private data class Wave(val fx: Double, val fy: Double, val ph: Double, val a: Double)

    private val waves: List<Wave> = run {
        val rng = java.util.Random(13)
        // 1/f amplitude spectrum (natural-image statistics), log-uniform
        // frequency 0.002..0.25 cycles / raw px, so every pyramid level
        // sees texture.
        List(96) {
            val f = 0.002 * Math.pow(0.25 / 0.002, rng.nextDouble())
            val th = rng.nextDouble() * PI
            Wave(f * cos(th), f * kotlin.math.sin(th), rng.nextDouble() * 2 * PI, 0.0012 / f / 12.0)
        }
    }

    private fun scene(u: Double, v: Double): Double {
        var s = 0.4
        for (w in waves) s += w.a * cos(2 * PI * (w.fx * u + w.fy * v) + w.ph)
        s += 0.12 * tanh((u * 0.8 + v * 0.6 - 260.0) / 1.5)
        s += 0.08 * tanh((u * -0.3 + v * 0.95 - 120.0) / 0.8)
        return s
    }

    private val gains = doubleArrayOf(0.7, 1.0, 1.0, 0.55) // R G1 G2 B

    private fun frame(sx: Double, sy: Double, a: Double, b: Double, seed: Long): Array<FloatArray> {
        val rng = java.util.Random(seed)
        val n = 4
        return Array(size) { y ->
            FloatArray(size) { x ->
                var acc = 0.0
                for (j in 0 until n) for (i in 0 until n) {
                    acc += scene(x + (i + 0.5) / n - 0.5 - sx, y + (j + 0.5) / n - 0.5 - sy)
                }
                val g = gains[(y and 1) * 2 + (x and 1)]
                val v = (acc / (n * n) * g).coerceIn(0.0, 1.0)
                val noisy = if (a > 0 || b > 0) v + rng.nextGaussian() * sqrt(a * v + b) else v
                noisy.toFloat()
            }
        }
    }

    private class Stats(val biasX: Double, val biasY: Double, val rms: Double, val p95: Double, val max: Double)

    private fun measure(flow: Array<Array<FloatArray>>, ex: Double, ey: Double): Stats {
        val errs = ArrayList<Double>()
        var bx = 0.0
        var by = 0.0
        for (ty in 1 until flow.size - 1) for (tx in 1 until flow[0].size - 1) {
            val dx = flow[ty][tx][0] - ex
            val dy = flow[ty][tx][1] - ey
            bx += dx; by += dy
            errs += sqrt(dx * dx + dy * dy)
        }
        val n = errs.size
        errs.sort()
        val rms = sqrt(errs.sumOf { it * it } / n)
        return Stats(bx / n, by / n, rms, errs[(0.95 * (n - 1)).toInt()], errs.last())
    }

    @Test
    fun c13aSweep() {
        assumeTrue(System.getProperty("srpipeline.diag", "").isNotEmpty())
        val noiseLevels = listOf("clean" to (0.0 to 0.0), "iso100" to (1e-4 to 1e-6), "dim" to (8e-4 to 2e-5))
        for ((label, ab) in noiseLevels) {
            val (a, b) = ab
            val ref = frame(0.0, 0.0, a, b, 1)
            val refGrey = Grey.computeGreyImages(ref, "FFT")
            val init = initAlignment(refGrey, loop)
            println("C13a [$label] a=$a b=$b grey=${refGrey.size}x${refGrey[0].size}")
            println("C13a [$label]   sx_raw   sy_raw  exp_gx  exp_gy | biasX   biasY    rms     p95     max   | mean est fx")
            val fracHist = IntArray(10)
            val fracErr = ArrayList<Double>()
            var seed = 100L
            // 0.05 grey-px steps across one grey px (0.1 raw), plus an
            // integer offset (3 raw = 1.5 grey) so the coarse levels act.
            val steps = (0..10).map { it * 0.1 }
            for (base in listOf(0.0, 3.0, 8.0, 15.0)) for (s in steps) {
                val sx = base + s
                val sy = base + 0.37 + 0.5 * s
                val mov = frame(sx, sy, a, b, seed++)
                val levels = alignLevelsPerLevel(init, Grey.computeGreyImages(mov, "FFT"), loop)
                val flow = levels.last()
                if (s == 0.0) {
                    var scale = loop.factors.fold(1) { acc, f -> acc * f }
                    levels.forEachIndexed { p, lv ->
                        val xs = lv.flatMap { r -> r.map { it[0] * scale } }.sorted()
                        val ys = lv.flatMap { r -> r.map { it[1] * scale } }.sorted()
                        println("C13a [$label]   level $p (x$scale, ${lv.size}x${lv[0].size} tiles) median raw flow (%.3f, %.3f) min/max x %.2f/%.2f".format(
                            xs[xs.size / 2], ys[ys.size / 2], xs.first(), xs.last()))
                        scale /= loop.factors[loop.factors.size - 1 - p]
                    }
                }
                val ex = sx // FFT grey is full raw resolution
                val ey = sy
                val st = measure(flow, ex, ey)
                var mfx = 0.0
                var cnt = 0
                for (ty in 1 until flow.size - 1) for (tx in 1 until flow[0].size - 1) {
                    val fx = flow[ty][tx][0].toDouble()
                    mfx += fx; cnt++
                    val fr = fx - floor(fx)
                    fracHist[(fr * 10).toInt().coerceIn(0, 9)]++
                    val efr = ex - floor(ex)
                    var d = fr - efr
                    if (d > 0.5) d -= 1.0
                    if (d < -0.5) d += 1.0
                    fracErr += d
                }
                println(
                    "C13a [$label] %7.3f %7.3f %7.3f %7.3f | %+.4f %+.4f %.4f %.4f %.4f | %.4f".format(
                        sx, sy, ex, ey, st.biasX, st.biasY, st.rms, st.p95, st.max, mfx / cnt,
                    ),
                )
            }
            val tot = fracHist.sum().toDouble()
            println("C13a [$label] frac(flow_x) histogram (10 bins, expected uniform-ish): " +
                fracHist.joinToString(" ") { "%.3f".format(it / tot) })
            val fe = fracErr.map { abs(it) }.sorted()
            println("C13a [$label] |frac error| median %.4f p95 %.4f".format(fe[fe.size / 2], fe[(0.95 * (fe.size - 1)).toInt()]))
        }
    }
}
