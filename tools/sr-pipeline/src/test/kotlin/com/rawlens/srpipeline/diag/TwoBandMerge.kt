package com.rawlens.srpipeline.diag

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * CPU prototype of the two-band MFSR reconstruction (anti oil-painting), for
 * the known-truth bench only: alignment is the oracle global shift per frame
 * (the bench's static scene), no robustness. Works at any output scale.
 *
 * Green (white-balanced CFA samples placed at their scene positions):
 *   low  = wide isotropic normalised Gaussian splat (sigmaLow sensor px)
 *   high = every sample's residual vs low, splatted with a narrow isotropic
 *          Gaussian (sigmaHigh sensor px), normalised; confidence-faded where
 *          the narrow kernel sees few samples (falls back to the low band:
 *          blurry, never zippered)
 *   G = low + high. Transfer = N + L (1 - N) with N, L the two kernels' MTFs,
 *   so the merge blur is only the narrow kernel's.
 *   Optional: overlapped 8x8 DCT Wiener shrink of the high band with the
 *   exact per-pixel noise variance (sum w^2 var / (sum w)^2), keeping at
 *   least `grainFloor` of each coefficient.
 * Chroma: R-G and B-G at the R / B sample positions, wide splat (low band
 * only), added back to G.
 * Output: camera RGB in the bench's convention (channel gains reapplied).
 */
object TwoBandMerge {
    data class Params(
        val sigmaLow: Double = 1.0,
        val sigmaHigh: Double = 0.35,
        val confK: Double = 0.5,
        val denoise: Boolean = false,
        val grainFloor: Double = 0.0,
        val noiseA: Double = 0.0,
        val noiseB: Double = 0.0,
    )

    private class Grid(val n: Int, val sg: Double) {
        val num = DoubleArray(n * n); val den = DoubleArray(n * n); val den2 = DoubleArray(n * n)
        val varAcc = DoubleArray(n * n)
        // scene coordinate u -> grid index (u + 0.5) * sg - 0.5
        fun splat(u: Double, v: Double, value: Double, sigma: Double, variance: Double = 0.0) {
            val gx = (u + 0.5) * sg - 0.5; val gy = (v + 0.5) * sg - 0.5
            val r = 3.0 * sigma * sg
            val x0 = max(0, kotlin.math.ceil(gx - r).toInt()); val x1 = minOf(n - 1, floor(gx + r).toInt())
            val y0 = max(0, kotlin.math.ceil(gy - r).toInt()); val y1 = minOf(n - 1, floor(gy + r).toInt())
            val inv = 1.0 / (2 * sigma * sigma * sg * sg)
            for (y in y0..y1) { val dy = y - gy
                for (x in x0..x1) { val dx = x - gx
                    val w = exp(-(dx * dx + dy * dy) * inv)
                    val i = y * n + x
                    num[i] += w * value; den[i] += w; den2[i] += w * w; varAcc[i] += w * w * variance
                } }
        }
        fun value(i: Int) = if (den[i] > 1e-12) num[i] / den[i] else 0.0
        fun at(field: DoubleArray, u: Double, v: Double): Double {
            val gx = ((u + 0.5) * sg - 0.5).coerceIn(0.0, n - 1.0001); val gy = ((v + 0.5) * sg - 0.5).coerceIn(0.0, n - 1.0001)
            val x = gx.toInt(); val y = gy.toInt(); val fx = gx - x; val fy = gy - y
            val i = y * n + x
            return (field[i] * (1 - fx) + field[i + 1] * fx) * (1 - fy) + (field[i + n] * (1 - fx) + field[i + n + 1] * fx) * fy
        }
    }

    /** frames: CFA RGGB [n][n]; shifts: frame px (x, y) shows scene (x - sx, y - sy). */
    fun merge(frames: List<Array<FloatArray>>, shifts: List<Pair<Double, Double>>, gains: DoubleArray,
              scale: Int, p: Params): Array<Array<FloatArray>> {
        val n = frames[0].size
        val no = n * scale
        fun variance(raw: Double, g: Double) = (p.noiseA * raw.coerceAtLeast(0.0) + p.noiseB) / (g * g)
        // 1. Green low band on a 2x grid (smooth, bilinear-accurate).
        val low = Grid(2 * n, 2.0)
        for ((k, f) in frames.withIndex()) { val (sx, sy) = shifts[k]
            for (y in 0 until n) for (x in 0 until n) if (((y and 1) xor (x and 1)) == 1)
                low.splat(x - sx, y - sy, f[y][x] / gains[(y and 1) * 2 + (x and 1)], p.sigmaLow) }
        val lowF = DoubleArray(low.n * low.n) { low.value(it) }
        // 2. Residuals, narrow splat on the output grid.
        val high = Grid(no, scale.toDouble())
        for ((k, f) in frames.withIndex()) { val (sx, sy) = shifts[k]
            for (y in 0 until n) for (x in 0 until n) if (((y and 1) xor (x and 1)) == 1) {
                val g = gains[(y and 1) * 2 + (x and 1)]
                val u = x - sx; val v = y - sy
                high.splat(u, v, f[y][x] / g - low.at(lowF, u, v), p.sigmaHigh, variance(f[y][x].toDouble(), g))
            } }
        val conf = DoubleArray(no * no) { i -> high.den[i] / (high.den[i] + p.confK) }
        var hf = DoubleArray(no * no) { i -> high.value(i) * conf[i] }
        if (p.denoise) {
            val nv = DoubleArray(no * no) { i -> if (high.den[i] > 1e-12) high.varAcc[i] / (high.den[i] * high.den[i]) * conf[i] * conf[i] else 0.0 }
            hf = dctWiener(hf, nv, no, p.grainFloor)
        }
        val g = DoubleArray(no * no) { i ->
            val y = i / no; val x = i % no
            low.at(lowF, (x + 0.5) / scale - 0.5, (y + 0.5) / scale - 0.5) + hf[i]
        }
        // 3. Chroma: colour differences at R/B positions, wide splat.
        val gGrid = Grid(no, scale.toDouble()).also { System.arraycopy(g, 0, it.num, 0, g.size) }
        val dr = Grid(no, scale.toDouble()); val db = Grid(no, scale.toDouble())
        for ((k, f) in frames.withIndex()) { val (sx, sy) = shifts[k]
            for (y in 0 until n) for (x in 0 until n) {
                val c = (y and 1) * 2 + (x and 1)
                if (c != 0 && c != 3) continue
                val u = x - sx; val v = y - sy
                val d = f[y][x] / gains[c] - gGrid.at(gGrid.num, u, v)
                (if (c == 0) dr else db).splat(u, v, d, p.sigmaLow)
            } }
        return Array(no) { y -> Array(no) { x -> val i = y * no + x
            floatArrayOf(((g[i] + dr.value(i)) * gains[0]).toFloat(), g[i].toFloat(), ((g[i] + db.value(i)) * gains[3]).toFloat())
        } }
    }

    /** Overlapped (step 4) 8x8 DCT empirical Wiener, per-block noise variance from nv. */
    private fun dctWiener(src: DoubleArray, nv: DoubleArray, n: Int, floorFrac: Double): DoubleArray {
        val b = 8
        val c = Array(b) { k -> DoubleArray(b) { x -> (if (k == 0) sqrt(1.0 / b) else sqrt(2.0 / b)) * cos(PI * (2 * x + 1) * k / (2 * b)) } }
        val out = DoubleArray(n * n); val wsum = DoubleArray(n * n)
        val blk = Array(b) { DoubleArray(b) }; val tmp = Array(b) { DoubleArray(b) }
        val win = DoubleArray(b) { 0.5 - 0.5 * cos(2 * PI * (it + 0.5) / b) }
        var by = 0
        while (by + b <= n) { var bx = 0
            while (bx + b <= n) {
                var sv = 0.0
                for (y in 0 until b) for (x in 0 until b) { blk[y][x] = src[(by + y) * n + bx + x]; sv += nv[(by + y) * n + bx + x] }
                sv /= b * b
                // forward 2D DCT
                for (y in 0 until b) for (k in 0 until b) { var s = 0.0; for (x in 0 until b) s += c[k][x] * blk[y][x]; tmp[y][k] = s }
                for (k in 0 until b) for (l in 0 until b) { var s = 0.0; for (y in 0 until b) s += c[l][y] * tmp[y][k]; blk[l][k] = s }
                for (l in 0 until b) for (k in 0 until b) {
                    val e = blk[l][k] * blk[l][k]
                    val gain = if (e > 1e-30) max(floorFrac, max(0.0, e - sv) / e) else 1.0
                    blk[l][k] *= gain
                }
                for (y in 0 until b) for (k in 0 until b) { var s = 0.0; for (l in 0 until b) s += c[l][y] * blk[l][k]; tmp[y][k] = s }
                for (y in 0 until b) for (x in 0 until b) { var s = 0.0; for (k in 0 until b) s += c[k][x] * tmp[y][k]
                    val w = win[y] * win[x]; out[(by + y) * n + bx + x] += w * s; wsum[(by + y) * n + bx + x] += w }
                bx += 4 }
            by += 4 }
        return DoubleArray(n * n) { if (wsum[it] > 1e-9) out[it] / wsum[it] else src[it] }
    }
}
