// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** RawSrFft tests against an independent naive-DFT oracle. */
class RawSrFftTest {
    private fun naiveDft(re: DoubleArray, im: DoubleArray, inverse: Boolean): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
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
            outRe[k] = sumRe
            outIm[k] = sumIm
        }
        if (inverse) {
            for (k in 0 until n) {
                outRe[k] /= n
                outIm[k] /= n
            }
        }
        return outRe to outIm
    }

    private fun checkVsNaive(n: Int, seed: Long) {
        val random = java.util.Random(seed)
        val re = DoubleArray(n) { random.nextGaussian() }
        val im = DoubleArray(n) { random.nextGaussian() }
        for (inverse in listOf(false, true)) {
            val gotRe = re.copyOf()
            val gotIm = im.copyOf()
            RawSrFft.fft1d(gotRe, gotIm, n, inverse)
            val (expRe, expIm) = naiveDft(re, im, inverse)
            var worst = 0.0
            for (k in 0 until n) {
                worst = maxOf(worst, kotlin.math.abs(gotRe[k] - expRe[k]))
                worst = maxOf(worst, kotlin.math.abs(gotIm[k] - expIm[k]))
            }
            assertTrue("n=$n inverse=$inverse worst=$worst", worst < 1e-9 * maxOf(n / 64.0, 1.0))
        }
    }

    @Test fun matchesNaiveDftAcrossFactors() {
        for (n in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 16, 17, 19, 25, 27, 32, 49, 64, 100, 128, 200, 256)) {
            checkVsNaive(n, 1234L + n)
        }
    }

    @Test fun matchesNaiveDftAtCameraSizes() {
        for (n in listOf(3000, 3060, 3072, 3648, 4000, 4032, 4080, 4096, 5472, 6000, 6144, 8000, 8192)) {
            checkVsNaive(n, 77L)
        }
    }

    @Test fun roundtripIsIdentity() {
        val random = java.util.Random(42L)
        for ((w, h) in listOf(64 to 48, 125 to 65, 400 to 300)) {
            val re = DoubleArray(w * h) { random.nextGaussian() }
            val im = DoubleArray(w * h) { random.nextGaussian() }
            val origRe = re.copyOf()
            val origIm = im.copyOf()
            RawSrFft.fft2d(re, im, w, h, false)
            RawSrFft.fft2d(re, im, w, h, true)
            var worst = 0.0
            for (i in re.indices) {
                worst = maxOf(worst, kotlin.math.abs(re[i] - origRe[i]))
                worst = maxOf(worst, kotlin.math.abs(im[i] - origIm[i]))
            }
            assertTrue("${w}x$h roundtrip worst=$worst", worst < 1e-9)
        }
    }

    @Test fun dcAndImpulseBehave() {
        val n = 120
        val re = DoubleArray(n) { 1.0 }
        val im = DoubleArray(n)
        RawSrFft.fft1d(re, im, n, false)
        assertEquals(n.toDouble(), re[0], 1e-9)
        assertEquals(0.0, im[0], 1e-9)
        for (k in 1 until n) {
            assertEquals(0.0, re[k], 1e-9)
            assertEquals(0.0, im[k], 1e-9)
        }
        val impulseRe = DoubleArray(n)
        val impulseIm = DoubleArray(n)
        impulseRe[0] = 1.0
        RawSrFft.fft1d(impulseRe, impulseIm, n, false)
        for (k in 0 until n) {
            assertEquals(1.0, impulseRe[k], 1e-9)
            assertEquals(0.0, impulseIm[k], 1e-9)
        }
    }

    @Test fun repeatedRunsAreBitwiseIdentical() {
        val random = java.util.Random(9L)
        val re = DoubleArray(4032) { random.nextGaussian() }
        val im = DoubleArray(4032) { random.nextGaussian() }
        val aRe = re.copyOf()
        val aIm = im.copyOf()
        RawSrFft.fft1d(aRe, aIm, 4032, false)
        val bRe = re.copyOf()
        val bIm = im.copyOf()
        RawSrFft.fft1d(bRe, bIm, 4032, false)
        assertTrue(aRe.contentEquals(bRe))
        assertTrue(aIm.contentEquals(bIm))
    }

    @Test fun bufferPlanesMatchArrayPlanesBitwise() {
        // The off-heap FFT path (RawSrAlignment.fftGrey at 12MP) must run
        // the identical transform: same values, same visit order.
        val random = java.util.Random(20261004L)
        val w = 400
        val h = 300
        val re = DoubleArray(w * h) { random.nextGaussian() }
        val im = DoubleArray(w * h) { random.nextGaussian() }
        val arrRe = re.copyOf()
        val arrIm = im.copyOf()
        RawSrFft.fft2d(arrRe, arrIm, w, h, false)
        RawSrFft.FftPlanePair.allocate(w * h).use { planes ->
            val bufRe = planes.re
            val bufIm = planes.im
            for (i in re.indices) {
                bufRe.put(i, re[i])
                bufIm.put(i, im[i])
            }
            RawSrFft.fft2d(bufRe, bufIm, w, h, false)
            for (i in re.indices) {
                assertEquals(arrRe[i], bufRe.get(i), 0.0)
                assertEquals(arrIm[i], bufIm.get(i), 0.0)
            }
            RawSrFft.fft2d(arrRe, arrIm, w, h, true)
            RawSrFft.fft2d(bufRe, bufIm, w, h, true)
            for (i in re.indices) {
                assertEquals(arrRe[i], bufRe.get(i), 0.0)
                assertEquals(arrIm[i], bufIm.get(i), 0.0)
            }
        }
    }

    @Test fun bufferShiftMatchesArrayShiftBitwise() {
        val random = java.util.Random(7L)
        val w = 125
        val h = 65
        val re = DoubleArray(w * h) { random.nextGaussian() }
        val im = DoubleArray(w * h) { random.nextGaussian() }
        for (forward in listOf(true, false)) {
            val arrRe = re.copyOf()
            val arrIm = im.copyOf()
            val bufRe = java.nio.DoubleBuffer.wrap(re.copyOf())
            val bufIm = java.nio.DoubleBuffer.wrap(im.copyOf())
            RawSrAlignment.fftShift(arrRe, arrIm, w, h, forward)
            RawSrAlignment.fftShift(bufRe, bufIm, w, h, forward)
            for (i in re.indices) {
                assertEquals(arrRe[i], bufRe.get(i), 0.0)
                assertEquals(arrIm[i], bufIm.get(i), 0.0)
            }
        }
    }

    @Test fun fftPlanePairHoldsTwelveMegapixelsAndClosesCleanly() {
        // 12MP regression: each FFT plane is ~100MB; ART heap-backs
        // ByteBuffer.allocateDirect (the second logcat OOM died inside
        // it), so planes live in ashmem regions with deterministic
        // cleanup. (On this JVM the android.jar stub is unavailable so
        // the direct-buffer fallback runs; the ashmem path itself is
        // proven on-device.)
        RawSrFft.FftPlanePair.allocate(4080 * 3060).use { planes ->
            assertEquals(4080 * 3060, planes.words)
            assertEquals(4080 * 3060, planes.re.capacity())
            assertEquals(4080 * 3060, planes.im.capacity())
            planes.re.put(0, 1.0)
            planes.im.put(4080 * 3060 - 1, -1.0)
            assertEquals(1.0, planes.re.get(0), 0.0)
            assertEquals(-1.0, planes.im.get(4080 * 3060 - 1), 0.0)
        }
        // Double close is a safe no-op (no double-munmap).
        val planes = RawSrFft.FftPlanePair.allocate(16)
        planes.close()
        planes.close()
    }

    @Test fun largePrimeFactorRejected() {
        try {
            RawSrFft.fft1d(DoubleArray(23), DoubleArray(23), 23, false)
            assertTrue("expected rejection of prime 23", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("prime factor"))
        }
    }
}
