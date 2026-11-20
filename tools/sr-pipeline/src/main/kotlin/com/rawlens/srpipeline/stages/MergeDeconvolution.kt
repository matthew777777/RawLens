package com.rawlens.srpipeline.stages

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp

/**
 * Sharpening matched to the merge's own blur (replaces the RawTherapee
 * capture-sharpening port). The merge's luma transfer, measured on the
 * known-truth bench (TextureTransfer), is the continuous Keys cubic of the
 * per-frame green interpolation (a = -1.5) times a Gaussian of
 * [SIGMA_SENSOR_PX] sensor px (the two-neighbour fill of the missing greens,
 * averaged over the frames' sub-pixel phases, plus residual alignment):
 * 0.83 / 0.57 / 0.30 predicted vs 0.87 / 0.60 / 0.30 measured in the
 * 0.2-0.3 / 0.3-0.4 / 0.4-0.5 cycles/px bands. The inverse is a regularised
 * (Wiener-like) filter per axis, w = h / (h^2 + lambda), written as a short
 * separable FIR, so one 2-pass convolution undoes exactly that blur and no
 * more: no unsharp-mask halo, because nothing beyond the modelled transfer is
 * boosted. Applied to green only (colour-difference domain), so R and B take
 * the same detail.
 */
object MergeDeconvolution {
    const val SIGMA_SENSOR_PX = 0.52
    private const val KEYS_A = -1.5
    const val RADIUS = 4

    private fun keys(x: Double): Double {
        val t = abs(x)
        return when {
            t < 1 -> ((KEYS_A + 2) * t - (KEYS_A + 3)) * t * t + 1
            t < 2 -> ((KEYS_A * t - 5 * KEYS_A) * t + 8 * KEYS_A) * t - 4 * KEYS_A
            else -> 0.0
        }
    }

    /** Continuous Fourier transform of the Keys kernel at f cycles/px (even, real). */
    private fun keysMtf(f: Double): Double {
        val n = 800
        var s = 0.0
        for (i in 0..n) {
            val x = -2.0 + 4.0 * i / n
            val w = if (i == 0 || i == n) 0.5 else 1.0
            s += w * keys(x) * cos(2 * PI * f * x)
        }
        return s * 4.0 / n
    }

    /** Modelled per-axis merge transfer at f cycles per SENSOR px. */
    fun mtf(f: Double, sigma: Double = SIGMA_SENSOR_PX): Double =
        keysMtf(f) * exp(-2 * PI * PI * sigma * sigma * f * f)

    /**
     * Symmetric FIR taps (centre first, RADIUS + 1 values) of the per-axis
     * regularised inverse at `scale` output px per sensor px. `lambda` is the
     * Tikhonov term relative to the transfer^2 (0 = plain inverse).
     */
    fun taps(scale: Float, lambda: Double, sigma: Double = SIGMA_SENSOR_PX): FloatArray {
        val n = 256
        // Desired response on f_out in [0, 0.5]: w(f_out * scale cycles/sensor px).
        val resp = DoubleArray(n / 2 + 1) { k ->
            val fo = k.toDouble() / n
            val h = mtf(fo * scale, sigma)
            h / (h * h + lambda)
        }
        // Inverse cosine transform, Hann-windowed to RADIUS, renormalised to DC 1.
        val t = DoubleArray(RADIUS + 1) { m ->
            var s = 0.0
            for (k in 0..n / 2) {
                val w = if (k == 0 || k == n / 2) 1.0 else 2.0
                s += w * resp[k] * cos(2 * PI * k * m / n)
            }
            s / n * (0.5 + 0.5 * cos(PI * m / (RADIUS + 1)))
        }
        val dc = t[0] + 2 * (1..RADIUS).sumOf { t[it] }
        return FloatArray(RADIUS + 1) { (t[it] / dc).toFloat() }
    }

    /**
     * CPU reference: deconvolve green of an interleaved RGB image [H][W][3]
     * and add the same change to R and B (colour differences kept). `gain`
     * blends 0 (off) .. 1 (full).
     */
    fun applyRgb(img: Array<Array<FloatArray>>, taps: FloatArray, gain: Float = 1f): Array<Array<FloatArray>> {
        val h = img.size; val w = img[0].size; val r = taps.size - 1
        val tmp = Array(h) { FloatArray(w) }
        for (y in 0 until h) for (x in 0 until w) {
            var s = taps[0] * img[y][x][1]
            for (m in 1..r) s += taps[m] * (img[y][(x - m).coerceIn(0, w - 1)][1] + img[y][(x + m).coerceIn(0, w - 1)][1])
            tmp[y][x] = s
        }
        return Array(h) { y -> Array(w) { x ->
            var s = taps[0] * tmp[y][x]
            for (m in 1..r) s += taps[m] * (tmp[(y - m).coerceIn(0, h - 1)][x] + tmp[(y + m).coerceIn(0, h - 1)][x])
            val d = gain * (s - img[y][x][1])
            floatArrayOf(img[y][x][0] + d, img[y][x][1] + d, img[y][x][2] + d)
        } }
    }
}
