// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * RawSrFftF32 tests: the float transcription agrees with an independent
 * naive-DFT oracle and with the double [RawSrFft] (precision step), and
 * the fused flip matches a host pre-flip bitwise.
 */
class RawSrFftF32Test {
    private fun naiveDft(re: FloatArray, im: FloatArray, inverse: Boolean): Pair<FloatArray, FloatArray> {
        val n = re.size
        val outRe = FloatArray(n)
        val outIm = FloatArray(n)
        val sign = if (inverse) 1.0 else -1.0
        for (k in 0 until n) {
            var sumRe = 0.0
            var sumIm = 0.0
            for (t in 0 until n) {
                val angle = sign * 2.0 * PI * t * k / n
                val w = cos(angle)
                val wim = sin(angle)
                sumRe += re[t] * w - im[t] * wim
                sumIm += re[t] * wim + im[t] * w
            }
            outRe[k] = sumRe.toFloat()
            outIm[k] = sumIm.toFloat()
        }
        if (inverse) {
            for (k in 0 until n) {
                outRe[k] /= n
                outIm[k] /= n
            }
        }
        return outRe to outIm
    }

    @Test fun matchesNaiveDftAcrossFactors() {
        for (n in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 16, 17, 19, 25, 27, 32, 49, 64, 100, 128)) {
            val random = java.util.Random(1234L + n)
            val re = FloatArray(n) { random.nextGaussian().toFloat() }
            val im = FloatArray(n) { random.nextGaussian().toFloat() }
            for (inverse in listOf(false, true)) {
                val gotRe = re.copyOf()
                val gotIm = im.copyOf()
                RawSrFftF32.fft1d(gotRe, gotIm, n, inverse)
                val (expRe, expIm) = naiveDft(re, im, inverse)
                var worst = 0f
                for (k in 0 until n) {
                    worst = maxOf(worst, kotlin.math.abs(gotRe[k] - expRe[k]))
                    worst = maxOf(worst, kotlin.math.abs(gotIm[k] - expIm[k]))
                }
                assertTrue("n=$n inverse=$inverse worst=$worst", worst < 1e-3f)
            }
        }
    }

    @Test fun matchesDoubleFft1dWithinFloatTolerance() {
        for (n in listOf(3000, 4000, 4080)) {
            val random = java.util.Random(77L)
            val dre = DoubleArray(n) { random.nextGaussian() }
            val dim = DoubleArray(n) { random.nextGaussian() }
            val fre = FloatArray(n) { dre[it].toFloat() }
            val fim = FloatArray(n) { dim[it].toFloat() }
            for (inverse in listOf(false, true)) {
                val gotRe = fre.copyOf()
                val gotIm = fim.copyOf()
                RawSrFftF32.fft1d(gotRe, gotIm, n, inverse)
                val wantRe = dre.copyOf()
                val wantIm = dim.copyOf()
                RawSrFft.fft1d(wantRe, wantIm, n, inverse)
                var worst = 0.0
                for (k in 0 until n) {
                    worst = maxOf(worst, kotlin.math.abs(gotRe[k] - wantRe[k]))
                    worst = maxOf(worst, kotlin.math.abs(gotIm[k] - wantIm[k]))
                }
                // Spectrum peaks scale with n; the bound is relative-ish.
                assertTrue("n=$n inverse=$inverse worst=$worst", worst < 0.05 * n / 64.0)
            }
        }
    }

    @Test fun roundtripIsIdentity() {
        val random = java.util.Random(42L)
        for ((w, h) in listOf(64 to 48, 125 to 65)) {
            val re = FloatArray(w * h) { random.nextGaussian().toFloat() }
            val im = FloatArray(w * h) { random.nextGaussian().toFloat() }
            val origRe = re.copyOf()
            val origIm = im.copyOf()
            RawSrFftF32.fft2d(re, im, w, h, false)
            RawSrFftF32.fft2d(re, im, w, h, true)
            var worst = 0f
            for (i in re.indices) {
                worst = maxOf(worst, kotlin.math.abs(re[i] - origRe[i]))
                worst = maxOf(worst, kotlin.math.abs(im[i] - origIm[i]))
            }
            assertTrue("${w}x$h roundtrip worst=$worst", worst < 2e-3f)
        }
    }

    @Test fun greyMatchesDoubleGrey() {
        // Textured mosaic (a flat fixture would hide filter bugs in ties).
        val random = java.util.Random(20261005L)
        val w = 160
        val h = 120
        val mosaic = FloatArray(w * h) {
            (0.2 + 0.6 * random.nextDouble() + 0.05 * sin(it * 0.37)).toFloat()
        }
        val want = RawSrAlignment.fftGrey(mosaic, w, h)
        val got = RawSrFftF32.fftGrey(mosaic, w, h)
        var worst = 0f
        var sum = 0.0
        for (i in mosaic.indices) {
            val d = kotlin.math.abs(got.values[i] - want.values[i])
            worst = maxOf(worst, d)
            sum += d
        }
        assertTrue("grey worst=$worst mean=${sum / mosaic.size}", worst < 2e-3f)
    }

    @Test fun fusedFlipMatchesHostPreflipBitwise() {
        val random = java.util.Random(9L)
        val w = 96
        val h = 64
        val mosaic = FloatArray(w * h) { random.nextGaussian().toFloat() }
        for (flip in 0..3) {
            val got = RawSrFftF32.fftGrey(mosaic, w, h, flip)
            val pre = FloatArray(w * h)
            for (y in 0 until h) {
                val sy = if (flip == 2 || flip == 3) h - 1 - y else y
                for (x in 0 until w) {
                    val sx = if (flip == 1 || flip == 3) w - 1 - x else x
                    pre[y * w + x] = mosaic[sy * w + sx]
                }
            }
            val want = RawSrFftF32.fftGrey(pre, w, h, 0)
            assertTrue("flip=$flip", got.values.contentEquals(want.values))
        }
    }

    @Test fun repeatedRunsAreBitwiseIdentical() {
        val random = java.util.Random(11L)
        val re = FloatArray(4032) { random.nextGaussian().toFloat() }
        val im = FloatArray(4032) { random.nextGaussian().toFloat() }
        val aRe = re.copyOf()
        val aIm = im.copyOf()
        RawSrFftF32.fft1d(aRe, aIm, 4032, false)
        val bRe = re.copyOf()
        val bIm = im.copyOf()
        RawSrFftF32.fft1d(bRe, bIm, 4032, false)
        assertTrue(aRe.contentEquals(bRe))
        assertTrue(aIm.contentEquals(bIm))
    }
}
