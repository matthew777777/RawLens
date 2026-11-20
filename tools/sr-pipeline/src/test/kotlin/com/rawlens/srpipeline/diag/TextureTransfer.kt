package com.rawlens.srpipeline.diag

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/**
 * Oil-painting metric: per radial frequency band, how much of the true
 * low-contrast texture survives a reconstruction, measured against known truth
 * in a texture-only region (no edges, no chart, no moving object).
 *
 * With X = result spectrum and T = truth spectrum (same Hann-windowed region,
 * green channel):
 *   transfer = Re sum(X T*) / sum(|T|^2)   1 = all detail kept, < 1 smeared
 *                                           (noise is uncorrelated with T, so
 *                                           it does not inflate transfer)
 *   errDb    = 10 log10(sum|X - transfer T|^2 / sum|T|^2)
 *              what is left that is not scaled truth (noise, aliasing,
 *              distortion), relative to the band's signal; lower is better.
 * Bands are in cycles per SENSOR pixel, so 1x and 2x are comparable; bands
 * above 0.5 exist only at 2x (beyond the sensor's Nyquist: super-resolution).
 */
object TextureTransfer {
    data class Band(val lo: Double, val hi: Double, val transfer: Double, val errDb: Double)

    val BANDS = listOf(0.05 to 0.1, 0.1 to 0.2, 0.2 to 0.3, 0.3 to 0.4, 0.4 to 0.5, 0.5 to 0.7)

    /**
     * `est` / `truth`: [H][W][3] at `scale` output px per sensor px. Region is
     * given in sensor (scene) px: [u0, u0 + size) x [v0, v0 + size), size a power of two.
     */
    fun measure(est: Array<Array<FloatArray>>, truth: Array<Array<FloatArray>>, scale: Int,
                u0: Int, v0: Int, size: Int): List<Band> {
        val n = size * scale
        require(n and (n - 1) == 0) { "region must be a power of two" }
        fun spectrum(img: Array<Array<FloatArray>>): Pair<Array<DoubleArray>, Array<DoubleArray>> {
            val re = Array(n) { DoubleArray(n) }; val im = Array(n) { DoubleArray(n) }
            var mean = 0.0
            for (y in 0 until n) for (x in 0 until n) mean += img[v0 * scale + y][u0 * scale + x][1]
            mean /= n * n
            for (y in 0 until n) for (x in 0 until n) {
                val w = (0.5 - 0.5 * cos(2 * PI * (x + 0.5) / n)) * (0.5 - 0.5 * cos(2 * PI * (y + 0.5) / n))
                re[y][x] = (img[v0 * scale + y][u0 * scale + x][1] - mean) * w
            }
            for (y in 0 until n) fft(re[y], im[y])
            val cr = DoubleArray(n); val ci = DoubleArray(n)
            for (x in 0 until n) {
                for (y in 0 until n) { cr[y] = re[y][x]; ci[y] = im[y][x] }
                fft(cr, ci)
                for (y in 0 until n) { re[y][x] = cr[y]; im[y][x] = ci[y] }
            }
            return re to im
        }
        val (xr, xi) = spectrum(est)
        val (tr, ti) = spectrum(truth)
        return BANDS.filter { it.second <= 0.5 * scale + 1e-9 }.map { (lo, hi) ->
            var cross = 0.0; var tt = 0.0; var xx = 0.0
            for (ky in 0 until n) for (kx in 0 until n) {
                val fy = (if (ky > n / 2) ky - n else ky).toDouble() / n * scale
                val fx = (if (kx > n / 2) kx - n else kx).toDouble() / n * scale
                val f = hypot(fx, fy)
                if (f < lo || f >= hi) continue
                cross += xr[ky][kx] * tr[ky][kx] + xi[ky][kx] * ti[ky][kx]
                tt += tr[ky][kx] * tr[ky][kx] + ti[ky][kx] * ti[ky][kx]
                xx += xr[ky][kx] * xr[ky][kx] + xi[ky][kx] * xi[ky][kx]
            }
            val t = if (tt > 0) cross / tt else 0.0
            // |X - tT|^2 summed = xx - 2 t cross + t^2 tt
            val err = (xx - 2 * t * cross + t * t * tt).coerceAtLeast(1e-30)
            Band(lo, hi, t, 10 * log10(err / tt.coerceAtLeast(1e-30)))
        }
    }

    fun format(bands: List<Band>): String = bands.joinToString("  ") {
        "%.2f-%.2f T %.2f E %+.1f".format(it.lo, it.hi, it.transfer, it.errDb)
    }

    /** In-place radix-2 complex FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = cos(ang * k); val wi = sin(ang * k)
                val ar = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                val ai = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                re[i + k] += ar; im[i + k] += ai
            }
            len = len shl 1
        }
    }
}
