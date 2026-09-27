// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Measured noise LUT: generator, storage, sampler, and cache. No GLES required. */
class RawSrNoiseLutTest {
    @Test fun rgbgExpansionMapsRasterPhasesAndRgb() {
        // Six-coefficient RGB profile on RGGB: raster (0,0)=R (1,0)=G (0,1)=G (1,1)=B.
        val rgb = doubleArrayOf(0.001, 0.0001, 0.002, 0.0002, 0.004, 0.0004)
        val black = floatArrayOf(10f, 20f, 30f, 40f)
        val white = 1000f
        val (alpha, beta) = RawSrNoiseLut.rgbgNormalized(rgb, black, white, BayerPattern.RGGB)
        // R <- raster 0, G1 <- raster 1, B <- raster 3, G2 <- raster 2.
        // Six-coefficient DNG planes are already normalized: passthrough.
        assertEquals(0.001, alpha[0], 0.0)
        assertEquals(0.0002, beta[1], 0.0)
        assertEquals(0.004, alpha[2], 0.0)
        assertEquals(0.0002, beta[3], 0.0)
        assertEquals(0.002, alpha[1], 0.0)
        assertEquals(0.002, alpha[3], 0.0)
        // Eight-coefficient raster profile on BGGR: (0,0)=B (1,0)=G (0,1)=G (1,1)=R.
        val raster = doubleArrayOf(0.01, 0.001, 0.02, 0.002, 0.03, 0.003, 0.04, 0.004)
        val (alpha8, beta8) = RawSrNoiseLut.rgbgNormalized(raster, black, white, BayerPattern.BGGR)
        assertEquals(0.04 / 960.0, alpha8[0], 1e-15) // R <- raster 3
        assertEquals(0.02 / 980.0, alpha8[1], 1e-15) // G1 <- raster 1
        assertEquals(0.01 / 990.0, alpha8[2], 1e-15) // B <- raster 0
        assertEquals(0.03 / 970.0, alpha8[3], 1e-15) // G2 <- raster 2
        assertEquals((0.04 * 40 + 0.004) / (960.0 * 960.0), beta8[0], 1e-15)
    }

    @Test fun rgbgExpansionRejectsInvalidInputs() {
        val good6 = doubleArrayOf(0.001, 0.0001, 0.002, 0.0002, 0.004, 0.0004)
        val black = floatArrayOf(10f, 20f, 30f, 40f)
        assertThrows(IllegalArgumentException::class.java) {
            RawSrNoiseLut.rgbgNormalized(DoubleArray(7), black, 1000f, BayerPattern.RGGB)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawSrNoiseLut.rgbgNormalized(
                doubleArrayOf(-0.001, 0.0001, 0.002, 0.0002, 0.004, 0.0004),
                black, 1000f, BayerPattern.RGGB)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawSrNoiseLut.rgbgNormalized(
                good6.copyOf().also { it[0] = Double.NaN }, black, 1000f, BayerPattern.RGGB)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawSrNoiseLut.rgbgNormalized(good6, floatArrayOf(10f, 20f, 30f), 1000f, BayerPattern.RGGB)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawSrNoiseLut.rgbgNormalized(good6, black, 40f, BayerPattern.RGGB)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawSrNoiseLut.rgbgNormalized(good6, black, Float.NaN, BayerPattern.RGGB)
        }
    }

    @Test fun zeroNoiseGeneratesExactZeroCurves() {
        val zeros = DoubleArray(4)
        val lut = RawSrNoiseLut.generate(zeros, zeros, bins = 65, trials = 20_000, seed = 0)
        assertTrue(lut.sigmaSq.all { it == 0f })
        assertTrue(lut.dSq.all { it == 0f })
        assertTrue(lut.sigmaSem.all { it == 0f })
        assertTrue(lut.dSem.all { it == 0f })
        assertEquals(20_000L, lut.counts.sum())
        // Midpoint-stratified latent prior covers every bin.
        assertTrue("every bin populated, min=${lut.counts.min()}", lut.counts.all { it > 0 })
    }

    @Test fun generationIsDeterministicPerSeed() {
        val alpha = doubleArrayOf(1e-4, 1e-4, 1e-4, 1e-4)
        val beta = doubleArrayOf(1e-6, 1e-6, 1e-6, 1e-6)
        val first = RawSrNoiseLut.generate(alpha, beta, bins = 33, trials = 5_000, seed = 7)
        val second = RawSrNoiseLut.generate(alpha, beta, bins = 33, trials = 5_000, seed = 7)
        assertArrayEquals(first.sigmaSq, second.sigmaSq, 0f)
        assertArrayEquals(first.dSq, second.dSq, 0f)
        assertArrayEquals(first.counts, second.counts)
        val other = RawSrNoiseLut.generate(alpha, beta, bins = 33, trials = 5_000, seed = 8)
        assertFalse(first.sigmaSq.contentEquals(other.sigmaSq))
    }

    @Test fun curvesMatchAnalyticNoiseExpectation() {
        // Sqrt guide domain (reference `clip_raw_then_sqrt_bayer_quad_rgb_v1`).
        // Single-sample variance at latent 0.5 is 1e-4*0.5+1e-6 = 5.1e-5;
        // the delta method gives sqrt-domain var ~= 5.1e-5/(4*0.5) = 2.55e-5.
        // Patch-mean variances: R/B 2.55e-5/9, G half that (root of the
        // two-green mean); d² sums two independent means per channel:
        // 2*(2.83+1.42+2.83)e-6 ~= 1.42e-5. Patch variances carry the
        // (n-1)/n Welford factor: (2.55+1.275+2.55)e-5*8/9 ~= 5.67e-5.
        // Ranges catch a missing green-averaging factor (which would read
        // ~1.7e-5 / ~6.8e-5) with wide margin over Monte Carlo noise (~3%).
        val alpha = doubleArrayOf(1e-4, 1e-4, 1e-4, 1e-4)
        val beta = doubleArrayOf(1e-6, 1e-6, 1e-6, 1e-6)
        val lut = RawSrNoiseLut.generate(alpha, beta, bins = 65, trials = 200_000, seed = 1)
        assertTrue(lut.sigmaSq.all { it.isFinite() && it >= 0f })
        assertTrue(lut.dSq.all { it.isFinite() && it >= 0f })
        assertEquals(200_000L, lut.counts.sum())
        val mid = lut.bins / 2
        assertTrue("dSq[mid]=${lut.dSq[mid]}", lut.dSq[mid] in 1.15e-5f..1.65e-5f)
        assertTrue("sigmaSq[mid]=${lut.sigmaSq[mid]}", lut.sigmaSq[mid] in 4.8e-5f..6.4e-5f)
    }

    @Test fun storageRoundtripsAndRejectsCorruption() {
        val alpha = doubleArrayOf(1e-4, 2e-4, 3e-4, 4e-4)
        val beta = doubleArrayOf(1e-6, 2e-6, 3e-6, 4e-6)
        val lut = RawSrNoiseLut.generate(alpha, beta, bins = 33, trials = 5_000, seed = 3)
        val bytes = lut.toBytes()
        assertEquals(96 + 33 * 24, bytes.size)
        val decoded = RawSrNoiseLut.load(bytes, alpha, beta)
        assertEquals(lut.bins, decoded.bins)
        assertEquals(lut.trials, decoded.trials)
        assertEquals(lut.seed, decoded.seed)
        assertArrayEquals(lut.alpha, decoded.alpha, 0.0)
        assertArrayEquals(lut.beta, decoded.beta, 0.0)
        assertArrayEquals(lut.sigmaSq, decoded.sigmaSq, 0f)
        assertArrayEquals(lut.dSq, decoded.dSq, 0f)
        assertArrayEquals(lut.sigmaSem, decoded.sigmaSem, 0f)
        assertArrayEquals(lut.dSem, decoded.dSem, 0f)
        assertArrayEquals(lut.counts, decoded.counts)
        // Bad magic.
        assertThrows(IllegalArgumentException::class.java) {
            RawSrNoiseLut.load(bytes.copyOf().also { it[0] = 'X'.code.toByte() })
        }
        // Truncated.
        assertThrows(IllegalArgumentException::class.java) {
            RawSrNoiseLut.load(bytes.copyOf(bytes.size - 10))
        }
        // Bin count lies about the size.
        val badBins = bytes.copyOf()
        ByteBuffer.wrap(badBins).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 999)
        assertThrows(IllegalArgumentException::class.java) { RawSrNoiseLut.load(badBins) }
        // Counts no longer sum to trials (bump the first count by one).
        val badCounts = bytes.copyOf()
        val countOffset = 96 + 33 * 16
        val view = ByteBuffer.wrap(badCounts).order(ByteOrder.LITTLE_ENDIAN)
        view.putLong(countOffset, view.getLong(countOffset) + 1)
        assertThrows(IllegalArgumentException::class.java) { RawSrNoiseLut.load(badCounts) }
        // Profile mismatch.
        assertThrows(IllegalArgumentException::class.java) {
            RawSrNoiseLut.load(bytes, doubleArrayOf(9e-4, 2e-4, 3e-4, 4e-4), beta)
        }
        // Half a profile is not a profile.
        assertThrows(IllegalArgumentException::class.java) {
            RawSrNoiseLut.load(bytes, alpha, null)
        }
    }

    @Test fun sampleSelectsNearestBinAndClamps() {
        val lut = RawSrNoiseLut.Lut(
            bins = 5, trials = 100, seed = 0,
            alpha = DoubleArray(4), beta = DoubleArray(4),
            sigmaSq = floatArrayOf(0f, 1f, 2f, 3f, 4f),
            dSq = floatArrayOf(10f, 11f, 12f, 13f, 14f),
            sigmaSem = FloatArray(5), dSem = FloatArray(5),
            counts = longArrayOf(20, 20, 20, 20, 20)
        )
        assertEquals(RawSrNoiseLut.Sample(0f, 10f), lut.sample(-0.5f))
        assertEquals(RawSrNoiseLut.Sample(0f, 10f), lut.sample(0f))
        assertEquals(RawSrNoiseLut.Sample(1f, 11f), lut.sample(0.24f))
        assertEquals(RawSrNoiseLut.Sample(2f, 12f), lut.sample(0.4f))
        assertEquals(RawSrNoiseLut.Sample(4f, 14f), lut.sample(1f))
        assertEquals(RawSrNoiseLut.Sample(4f, 14f), lut.sample(2f))
    }

    @Test fun sparseSimulationInterpolatesEmptyBins() {
        val alpha = doubleArrayOf(1e-4, 1e-4, 1e-4, 1e-4)
        val beta = doubleArrayOf(1e-6, 1e-6, 1e-6, 1e-6)
        val lut = RawSrNoiseLut.generate(alpha, beta, bins = 101, trials = 10, seed = 0)
        assertTrue(lut.counts.count { it > 0 } <= 10)
        assertTrue(lut.sigmaSq.all { it.isFinite() && it >= 0f })
        assertTrue(lut.dSq.all { it.isFinite() && it >= 0f })
        assertEquals(10L, lut.counts.sum())
    }

    @Test fun cancellationAbortsGeneration() {
        val alpha = doubleArrayOf(1e-4, 1e-4, 1e-4, 1e-4)
        val beta = doubleArrayOf(1e-6, 1e-6, 1e-6, 1e-6)
        assertThrows(CancellationException::class.java) {
            RawSrNoiseLut.generate(alpha, beta, bins = 33, trials = 100_000,
                seed = 0, isCancelled = { true })
        }
    }

    @Test fun progressReportsMonotonicCompletion() {
        val alpha = doubleArrayOf(1e-4, 1e-4, 1e-4, 1e-4)
        val beta = doubleArrayOf(1e-6, 1e-6, 1e-6, 1e-6)
        val reports = ArrayList<Pair<Int, Int>>()
        RawSrNoiseLut.generate(alpha, beta, bins = 33, trials = 5_000, seed = 0,
            progress = { completed, total -> reports += completed to total })
        assertTrue(reports.isNotEmpty())
        assertTrue(reports.all { it.second == 5_000 })
        assertEquals(5_000, reports.last().first)
        assertTrue(reports.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test fun cachedRoundtripsAndHealsCorruption() {
        val dir = Files.createTempDirectory("rawsr-lut").toFile()
        try {
            val profile = ImmutableDoubleValues(
                doubleArrayOf(2.5e-4, 2.5e-6, 2.5e-4, 2.5e-6, 2.5e-4, 2.5e-6, 2.5e-4, 2.5e-6))
            val black = floatArrayOf(64f, 64f, 64f, 64f)
            val first = RawSrNoiseLut.cached(profile, black, 4000f, BayerPattern.RGGB, dir,
                bins = 33, trials = 5_000, seed = 0)!!
            assertEquals(1, dir.listFiles()!!.size)
            val second = RawSrNoiseLut.cached(profile, black, 4000f, BayerPattern.RGGB, dir,
                bins = 33, trials = 5_000, seed = 0)!!
            assertArrayEquals(first.sigmaSq, second.sigmaSq, 0f)
            assertArrayEquals(first.counts, second.counts)
            // Corrupt the cache entry: the next call regenerates instead of failing.
            dir.listFiles()!!.single().writeBytes(ByteArray(96 + 33 * 24))
            val healed = RawSrNoiseLut.cached(profile, black, 4000f, BayerPattern.RGGB, dir,
                bins = 33, trials = 5_000, seed = 0)!!
            assertEquals(5_000L, healed.counts.sum())
            assertArrayEquals(first.sigmaSq, healed.sigmaSq, 0f)
            // Null or invalid inputs keep the analytic path (null), never throw.
            assertNull(RawSrNoiseLut.cached(null, black, 4000f, BayerPattern.RGGB, dir))
            assertNull(RawSrNoiseLut.cached(profile, null, 4000f, BayerPattern.RGGB, dir))
            assertNull(RawSrNoiseLut.cached(
                ImmutableDoubleValues(DoubleArray(7)), black, 4000f, BayerPattern.RGGB, dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
