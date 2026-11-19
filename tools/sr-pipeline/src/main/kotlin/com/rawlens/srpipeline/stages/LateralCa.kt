package com.rawlens.srpipeline.stages

import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Lateral chromatic aberration: the lens images R and B at slightly different
 * magnifications than G, so colour fringes grow towards the corners. Model:
 * radial scale about the image centre c, observed R(p) = true R(c + (p-c)/(1+kR)),
 * i.e. true R(p) = observed R(c + (p-c)(1+kR)); likewise B.
 *
 * [estimate] runs on the MERGED colour-difference output (ch1 = G,
 * ch0 = wbR - G, ch2 = wbB - G), where all three planes are co-sited. A fit
 * on the raw half-res CFA planes was tried first and rejected: on hdrplus
 * 133623 it reported kR = +2.3e-4 (and step-dependent values of either sign
 * in a numpy check) while AHD and the merge both measure <= 2.5e-5, so
 * correcting by it over-corrected. At strong radial-edge points away from the
 * centre it compares the radial derivative of G at p with that of R (B) at
 * c + (p-c)(1+k) and keeps the k with the highest normalized correlation
 * (coarse/fine grid + parabolic refinement). Normalized correlation drops the
 * hue and intensity differences between channels. The warp is ca_warp.comp.
 */
object LateralCa {
    data class Fit(val kR: Float, val kB: Float, val nccR: Float, val nccB: Float, val points: Int) {
        /** Largest R/B shift at the frame corner, output px. */
        fun cornerShift(w: Int, h: Int): Float =
            maxOf(abs(kR), abs(kB)) * 0.5f * sqrt(w.toFloat() * w + h.toFloat() * h)
    }

    const val MAX_K = 0.003f
    private const val STEP = 0.0000125f
    private const val MIN_POINTS = 2000
    private const val MIN_NCC = 0.5f

    /**
     * Correct only when the fitted corner shift is at least this many output
     * px: below it the warp's bilinear resample costs more chroma sharpness
     * than the misregistration (a tenth of a pixel is not visible as a fringe).
     */
    const val MIN_CORNER_SHIFT_PX = 0.1f

    /** `rgb`: w*h*3 interleaved colour-difference floats (absolute indexing). */
    fun estimate(rgb: FloatBuffer, w: Int, h: Int, scale: Float, maxPoints: Int = 40_000): Fit {
        val cx = w / 2.0; val cy = h / 2.0
        val rMax = sqrt(cx * cx + cy * cy)
        val step = 2.0 * maxOf(1f, scale)
        val m = (step + 3).toInt()
        fun g(x: Int, y: Int) = rgb.get(3 * (y * w + x) + 1).toDouble()
        fun bil(ch: Int, x: Double, y: Double): Double {
            if (x < 0 || y < 0 || x > w - 1.001 || y > h - 1.001) return Double.NaN
            val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
            val fx = x - x0; val fy = y - y0
            fun v(xx: Int, yy: Int) = if (ch == 1) g(xx, yy) else rgb.get(3 * (yy * w + xx) + ch) + g(xx, yy)
            val a = v(x0, y0) * (1 - fx) + v(x0 + 1, y0) * fx
            val b = v(x0, y0 + 1) * (1 - fx) + v(x0 + 1, y0 + 1) * fx
            val r = a * (1 - fy) + b * fy
            return if (r.isNaN() || r.isInfinite()) Double.NaN else r
        }
        // Candidates: pixels beyond 0.3 rMax on a grid; (x, y) is an index,
        // its centre sits at (x + .5, y + .5).
        val gridStride = maxOf(1, sqrt((w.toDouble() * h) / (maxPoints * 8.0)).toInt())
        val cand = ArrayList<DoubleArray>()
        for (y in m until h - m step gridStride) for (x in m until w - m step gridStride) {
            val dx = x + 0.5 - cx; val dy = y + 0.5 - cy; val rr = sqrt(dx * dx + dy * dy)
            if (rr < 0.3 * rMax) continue
            val ux = dx / rr; val uy = dy / rr
            val d = bil(1, x + step * ux, y + step * uy) - bil(1, x - step * ux, y - step * uy)
            if (!d.isNaN()) cand.add(doubleArrayOf(x.toDouble(), y.toDouble(), ux, uy, d))
        }
        if (cand.size < MIN_POINTS) return Fit(0f, 0f, 0f, 0f, cand.size)
        val thr = cand.map { abs(it[4]) }.sorted().let { it[(it.size * 0.8).toInt()] }
        val strong = cand.filter { abs(it[4]) >= thr && thr > 0 }
        val pts = if (strong.size <= maxPoints) strong
            else List(maxPoints) { strong[(it.toLong() * strong.size / maxPoints).toInt()] }
        if (pts.size < MIN_POINTS) return Fit(0f, 0f, 0f, 0f, pts.size)
        fun fitPlane(ch: Int): Pair<Float, Float> {
            fun ncc(k: Double): Double {
                var sgg = 0.0; var spp = 0.0; var sgp = 0.0; var sg = 0.0; var sp = 0.0; var n = 0
                for (p in pts) {
                    val qx = cx + (p[0] + 0.5 - cx) * (1 + k) - 0.5
                    val qy = cy + (p[1] + 0.5 - cy) * (1 + k) - 0.5
                    val d = bil(ch, qx + step * p[2], qy + step * p[3]) - bil(ch, qx - step * p[2], qy - step * p[3])
                    if (d.isNaN()) continue
                    val gd = p[4]
                    sg += gd; sp += d; sgg += gd * gd; spp += d * d; sgp += gd * d; n++
                }
                return if (n < MIN_POINTS) 0.0 else {
                    val cov = sgp / n - sg / n * sp / n
                    val vg = sgg / n - (sg / n) * (sg / n); val vp = spp / n - (sp / n) * (sp / n)
                    if (vg <= 0 || vp <= 0) 0.0 else cov / sqrt(vg * vp)
                }
            }
            fun scan(ks: List<Double>): DoubleArray = DoubleArray(ks.size).also { out ->
                java.util.stream.IntStream.range(0, ks.size).parallel().forEach { out[it] = ncc(ks[it]) }
            }
            val coarseStep = 8.0 * STEP
            val coarseN = (MAX_K / coarseStep).toInt()
            val coarse = (-coarseN..coarseN).map { it * coarseStep }
            val cn = scan(coarse)
            val c0 = coarse[cn.indices.maxByOrNull { cn[it] }!!]
            val ks = (-8..8).map { c0 + it * STEP.toDouble() }.filter { abs(it) <= MAX_K + 1e-9 }
            val nccs = scan(ks)
            val i = nccs.indices.maxByOrNull { nccs[it] }!!
            var k = ks[i]
            if (i in 1 until ks.size - 1) {
                val a = nccs[i - 1]; val b = nccs[i]; val cc = nccs[i + 1]
                val den = a - 2 * b + cc
                if (den < 0) k += STEP * (0.5 * (a - cc) / den).coerceIn(-0.5, 0.5)
            }
            return k.toFloat() to nccs[i].toFloat()
        }
        val (kR, nR) = fitPlane(0)
        val (kB, nB) = fitPlane(2)
        fun ok(k: Float, n: Float) = if (n >= MIN_NCC && abs(k) < MAX_K - STEP) k else 0f
        return Fit(ok(kR, nR), ok(kB, nB), nR, nB, pts.size)
    }
}
