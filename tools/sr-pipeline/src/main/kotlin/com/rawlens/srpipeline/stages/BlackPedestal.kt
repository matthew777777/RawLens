package com.rawlens.srpipeline.stages

import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Per-burst black pedestal. Some sensors (hdrplus 133623: DNG black 63, darkest
 * floor 65-66 DN in every channel) sit a little above the metadata black. The
 * excess is equal in R, G and B before white balance, so white balance
 * (R x2.2, B x1.6) turns it magenta in lifted shadows.
 *
 * [estimate] looks at the darkest 8x8 CFA blocks of the reference frame. Each
 * block's 16 RGGB quads are split in two: the first 8 rank the blocks, the
 * other 8 measure them, so selecting "dark" does not bias the measured means
 * downwards through noise (that bias would hit G hardest and fake a tint).
 * A pedestal is reported only when the three channel means agree within three
 * standard errors of the mean (a truly unlit floor is neutral in raw; a dimly
 * lit dark object carries the scene's colour), as their minimum, if positive.
 * The merge is linear with normalised weights, so subtracting it from the
 * merged camera-RGB output equals subtracting it from every frame.
 */
object BlackPedestal {
    data class Estimate(val pedestal: Float, val r: Double, val g: Double, val b: Double,
                        val sigma: Double, val blocks: Int, val neutral: Boolean)

    /** Fraction of blocks treated as the dark floor (0.5 %, at least [MIN_BLOCKS]). */
    private const val DARK_FRACTION = 0.005
    private const val MIN_BLOCKS = 200

    /** RGGB-normalised CFA rows (0 = metadata black, 1 = white); a, b = noise, [R, G1, B, G2]. */
    fun estimate(f: Array<FloatArray>, a: FloatArray, b: FloatArray): Estimate {
        val bh = f.size / 8; val bw = f[0].size / 8
        val nb = bh * bw
        if (nb < MIN_BLOCKS * 4) return Estimate(0f, 0.0, 0.0, 0.0, 0.0, 0, false)
        val rank = DoubleArray(nb)
        val meas = Array(3) { DoubleArray(nb) }
        java.util.stream.IntStream.range(0, bh).parallel().forEach { by -> for (bx in 0 until bw) {
            var rk = 0.0; var mr = 0.0; var mg = 0.0; var mb = 0.0
            for (q in 0 until 16) {
                val y = by * 8 + (q / 4) * 2; val x = bx * 8 + (q % 4) * 2
                val r0 = f[y]; val r1 = f[y + 1]
                val R = r0[x]; val G = 0.5f * (r0[x + 1] + r1[x]); val B = r1[x + 1]
                if (q < 8) rk += (R + G + B).toDouble() else { mr += R; mg += G; mb += B }
            }
            val i = by * bw + bx
            rank[i] = rk; meas[0][i] = mr / 8; meas[1][i] = mg / 8; meas[2][i] = mb / 8
        } }
        val k = max(MIN_BLOCKS, (nb * DARK_FRACTION).toInt())
        val cut = rank.copyOf().also { it.sort() }[k - 1]
        val order = rank.indices.filter { rank[it] <= cut }.sortedBy { rank[it] }.take(k)
        val m = DoubleArray(3) { c -> order.sumOf { meas[c][it] } / k }
        // Standard error of each channel mean: per-pixel variance a x + b over
        // the measured pixels (8 per block; G is a pair average -> half).
        val se = DoubleArray(3) { c ->
            // Noise arrays are [R, G1, B, G2] (Gat.kt); G uses the two greens' mean.
            val v = if (c == 1) (a[1] + a[3]) / 2.0 * max(0.0, m[c]) + (b[1] + b[3]) / 2.0
                else a[if (c == 0) 0 else 2] * max(0.0, m[c]) + b[if (c == 0) 0 else 2]
            sqrt((if (c == 1) v / 2 else v) / (8.0 * k))
        }
        val sigma = sqrt(se.sumOf { it * it } / 3)
        val lo = m.min(); val hi = m.max()
        val neutral = hi - lo <= 3.0 * sigma * sqrt(2.0)
        val ped = if (neutral && lo > 0) lo.toFloat() else 0f
        return Estimate(ped, m[0], m[1], m[2], sigma, k, neutral)
    }

    /** rgb: interleaved camera RGB (black 0, white 1), in place: (v - p) / (1 - p). */
    fun apply(rgb: FloatBuffer, count: Int, p: Float) {
        if (p <= 0f) return
        val s = 1f / (1f - p)
        val chunk = 1 shl 16
        java.util.stream.IntStream.range(0, (count + chunk - 1) / chunk).parallel().forEach { c ->
            for (i in c * chunk until minOf(count, (c + 1) * chunk)) rgb.put(i, (rgb.get(i) - p) * s)
        }
    }
}
