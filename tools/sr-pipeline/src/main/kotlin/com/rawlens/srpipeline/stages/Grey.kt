package com.rawlens.srpipeline.stages

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Shared input validation for 2D raw/grey stage inputs. */
internal fun requireRectImage(image: Array<FloatArray>, name: String) {
    require(image.isNotEmpty() && image[0].isNotEmpty()) { "$name must be a non-empty 2D image" }
    val w = image[0].size
    require(image.all { it.size == w }) { "$name must be rectangular, got ragged rows" }
}

/**
 * Grey conversion + FFT low-pass stage.
 *
 * Mirrors `compute_grey_images` in
 * `handheld_super_resolution/utils_image.py`: the same input (2D raw `[H, W]`
 * float image), the same method names (`FFT`, `decimating`, `demosaicing`),
 * the same output shapes (`[H, W]` for `FFT`/`demosaicing`, `[H/2, W/2]`
 * for `decimating`), and the same unknown-method error. The `decimating` and
 * `demosaicing` methods delegate to [Decimate] and [Demosaic] respectively.
 *
 * The FFT path mirrors the torch path (`fft2 -> fftshift -> zero outer
 * quarters -> ifftshift -> ifft2 -> real`, unnormalized forward transform)
 * with a pure-Kotlin separable FFT in double precision (radix-2
 * Cooley-Tukey, naive DFT fallback for non-power-of-two sizes), so no extra
 * dependency is needed. Float-tolerant: the recorded envelope is ~2.2e-8
 * maxabs.
 */
object Grey {

    fun computeGreyImages(image: Array<FloatArray>, method: String): Array<FloatArray> {
        requireRectImage(image, "image")
        return when (method) {
            "FFT" -> fftLowpass(image)
            "decimating" -> Decimate.decimateToGrey(image)
            "demosaicing" -> Demosaic.demosaicToGrey(image)
            else -> throw IllegalArgumentException("Unknown grayscale method: $method")
        }
    }

    /**
     * Low-pass filter: full-resolution grey output, high frequencies removed
     * by zeroing the outer quarters of the shifted spectrum
     * (`[:H/4, :]`, `[:, :W/4]`, `[-H/4:, :]`, `[:, -W/4:]`).
     *
     * Degenerate sizes (`H < 4` or `W < 4`) mirror the Python `-0 == 0`
     * slicing quirk: the corresponding axis is zeroed completely.
     */
    fun fftLowpass(grey: Array<FloatArray>): Array<FloatArray> {
        requireRectImage(grey, "grey")
        val h = grey.size
        val w = grey[0].size
        val re = DoubleArray(h * w) { grey[it / w][it % w].toDouble() }
        val im = DoubleArray(h * w)

        fft2(re, im, h, w, inverse = false)
        shift2(re, h, w, forward = true)
        shift2(im, h, w, forward = true)
        zeroOuterQuarters(re, h, w)
        zeroOuterQuarters(im, h, w)
        shift2(re, h, w, forward = false)
        shift2(im, h, w, forward = false)
        fft2(re, im, h, w, inverse = true)

        return Array(h) { y -> FloatArray(w) { x -> re[y * w + x].toFloat() } }
    }

    private fun zeroOuterQuarters(a: DoubleArray, h: Int, w: Int) {
        val qy = h / 4
        if (qy == 0) {
            a.fill(0.0)
        } else {
            for (y in 0 until qy) for (x in 0 until w) a[y * w + x] = 0.0
            for (y in h - qy until h) for (x in 0 until w) a[y * w + x] = 0.0
        }
        val qx = w / 4
        if (qx == 0) {
            a.fill(0.0)
        } else {
            for (y in 0 until h) for (x in 0 until qx) a[y * w + x] = 0.0
            for (y in 0 until h) for (x in w - qx until w) a[y * w + x] = 0.0
        }
    }

    private fun fft2(re: DoubleArray, im: DoubleArray, h: Int, w: Int, inverse: Boolean) {
        val tmpRe = DoubleArray(maxOf(h, w))
        val tmpIm = DoubleArray(maxOf(h, w))
        for (y in 0 until h) {
            for (x in 0 until w) {
                tmpRe[x] = re[y * w + x]
                tmpIm[x] = im[y * w + x]
            }
            fft1d(tmpRe, tmpIm, w, inverse)
            for (x in 0 until w) {
                re[y * w + x] = tmpRe[x]
                im[y * w + x] = tmpIm[x]
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                tmpRe[y] = re[y * w + x]
                tmpIm[y] = im[y * w + x]
            }
            fft1d(tmpRe, tmpIm, h, inverse)
            for (y in 0 until h) {
                re[y * w + x] = tmpRe[y]
                im[y * w + x] = tmpIm[y]
            }
        }
    }

    /**
     * `fftshift` (forward) / `ifftshift` (!forward) along each axis:
     * split point is `(n + 1) / 2` for fftshift, `n / 2` for ifftshift
     * (identical for even sizes, as in numpy/torch).
     */
    private fun shift2(a: DoubleArray, h: Int, w: Int, forward: Boolean) {
        val tmp = DoubleArray(maxOf(h, w))
        val pw = if (forward) (w + 1) / 2 else w / 2
        for (y in 0 until h) {
            for (x in 0 until w) tmp[x] = a[y * w + (x + pw) % w]
            for (x in 0 until w) a[y * w + x] = tmp[x]
        }
        val ph = if (forward) (h + 1) / 2 else h / 2
        for (x in 0 until w) {
            for (y in 0 until h) tmp[y] = a[((y + ph) % h) * w + x]
            for (y in 0 until h) a[y * w + x] = tmp[y]
        }
    }

    private fun fft1d(re: DoubleArray, im: DoubleArray, n: Int, inverse: Boolean) {
        if (n == 1) return
        if (n and (n - 1) != 0) {
            dft1d(re, im, n, inverse)
            return
        }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * if (inverse) 1.0 else -1.0
            val wre = cos(ang)
            val wim = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nwr = wr * wre - wi * wim
                    wi = wr * wim + wi * wre
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }

    private fun dft1d(re: DoubleArray, im: DoubleArray, n: Int, inverse: Boolean) {
        val srcRe = re.copyOfRange(0, n)
        val srcIm = im.copyOfRange(0, n)
        val sign = if (inverse) 1.0 else -1.0
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val ang = sign * 2.0 * PI * k * t / n
                val c = cos(ang)
                val s = sin(ang)
                sr += srcRe[t] * c - srcIm[t] * s
                si += srcRe[t] * s + srcIm[t] * c
            }
            if (inverse) {
                sr /= n
                si /= n
            }
            re[k] = sr
            im[k] = si
        }
    }
}
