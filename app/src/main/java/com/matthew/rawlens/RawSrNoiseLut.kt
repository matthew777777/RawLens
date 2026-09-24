// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Measured noise-correction LUT for motion robustness (Jamy-L `noise_lut.py` /
 * `monte_carlo.py` methodology, reimplemented for RawLens's linear guide domain).
 *
 * The reference simulates clipped noisy 3x3 patch statistics over a stratified
 * latent-brightness prior and bins the expected reference variance (`sigma_sq`)
 * and reference-moving distance (`d_sq`) by measured brightness. The robustness
 * stage then floors sigma² at the LUT value and shrinks d² by
 * `(d²/(d²+d²_LUT))²`, so pure noise disagreements stop rejecting static
 * texture. No upstream code is copied; the Monte Carlo loop, Welford patch
 * statistics, file validation, and profile-match rules are reimplemented here.
 *
 * Deliberate domain difference: the reference LUT is generated for the
 * sqrt-domain guide (`clip_raw_then_sqrt_bayer_quad_rgb_v1`), which RawLens
 * does not use — our robustness statistics live in the linear normalized
 * domain (docs/raw-sr-robustness.md §1). This LUT is therefore generated for
 * [TRANSFORM] (`clip_raw_then_linear_bayer_quad_rgb_v1`): identical procedure,
 * no square root. Reference `.npz` files are NOT loadable here, and ours are
 * not loadable there; the transform name is part of the cache key so the two
 * can never be confused.
 *
 * Coefficients are normalized-domain `(alpha, beta)` in EXIF RGBG plane order
 * (R, G1, B, G2), i.e. `var(v) = alpha*v + beta` for `v` in [0, 1]. Sensor
 * code-domain profiles are converted by [rgbgNormalized], which mirrors
 * [RawSrCovarianceGuide]'s exact per-phase normalization.
 *
 * Pure Kotlin/JVM with no Android dependencies: the generator, storage, and
 * sampler all run in unit tests. Generation is single-threaded and
 * deterministic per seed (`java.util.Random` has a specified algorithm);
 * per-profile results are cached on disk by [cached] so the ~seconds of
 * first-save Monte Carlo are paid once per sensor profile.
 */
object RawSrNoiseLut {
    /** Simulated transform; part of the on-disk identity, never reused across domains. */
    const val TRANSFORM = "clip_raw_then_linear_bayer_quad_rgb_v1"
    const val FORMAT_VERSION = 1
    const val DEFAULT_BINS = 1001
    const val DEFAULT_TRIALS = 500_000
    const val DEFAULT_SEED = 0L

    private const val MAGIC = "RLNLUT01"
    private const val CHUNK_TRIALS = 50_000
    private const val PROFILE_RTOL = 1e-6
    private const val PROFILE_ATOL = 1e-12

    /** One LUT lookup result: expected noise variance and noise distance. */
    data class Sample(val sigmaSq: Float, val dSq: Float)

    /**
     * Measured noise curves over a uniform measured-brightness grid [0, 1].
     * Brightness grid points are implicit (`i/(bins-1)`), exactly like the
     * reference's validated uniform grid.
     */
    data class Lut(
        val bins: Int,
        val trials: Long,
        val seed: Long,
        /** Normalized-domain RGBG slopes (R, G1, B, G2). */
        val alpha: DoubleArray,
        /** Normalized-domain RGBG offsets (R, G1, B, G2). */
        val beta: DoubleArray,
        val sigmaSq: FloatArray,
        val dSq: FloatArray,
        val sigmaSem: FloatArray,
        val dSem: FloatArray,
        val counts: LongArray
    ) {
        init {
            require(bins >= 2)
            require(trials >= 1 && seed >= 0)
            require(alpha.size == 4 && beta.size == 4)
            require(sigmaSq.size == bins && dSq.size == bins)
            require(sigmaSem.size == bins && dSem.size == bins && counts.size == bins)
        }

        /**
         * Nearest-bin lookup at measured reference brightness. Bin selection
         * is `floor(b*(bins-1) + 0.5)` — identical to the generator binning
         * and to the GLSL twin (`round` is floor(x+0.5) there).
         */
        fun sample(brightness: Float): Sample {
            val b = brightness.coerceIn(0f, 1f)
            val idx = floor(b * (bins - 1) + 0.5f).toInt().coerceIn(0, bins - 1)
            return Sample(sigmaSq[idx], dSq[idx])
        }

        /** Little-endian versioned encoding; see [load] for the layout. */
        fun toBytes(): ByteArray {
            val buffer = ByteBuffer.allocate(96 + bins * 24).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(MAGIC.toByteArray(Charsets.US_ASCII))
            buffer.putInt(FORMAT_VERSION)
            buffer.putInt(bins)
            buffer.putLong(trials)
            buffer.putLong(seed)
            alpha.forEach(buffer::putDouble)
            beta.forEach(buffer::putDouble)
            sigmaSq.forEach(buffer::putFloat)
            dSq.forEach(buffer::putFloat)
            sigmaSem.forEach(buffer::putFloat)
            dSem.forEach(buffer::putFloat)
            counts.forEach(buffer::putLong)
            return buffer.array()
        }
    }

    /**
     * Normalized-domain RGBG coefficients from a code-domain sensor profile.
     * [profile] holds S/O pairs: 8 values in sensor raster-phase order or 6 in
     * RGB color order (mirrors [RawSrTuning.estimate]). [blackLevels] are the
     * four sensor-raster black levels, [whiteLevel] the scalar white level,
     * [sensorPattern] the unshifted sensor CFA pattern (raster phases are
     * sensor-relative, so the crop-shifted pattern is wrong here).
     */
    fun rgbgNormalized(
        profile: DoubleArray,
        blackLevels: FloatArray,
        whiteLevel: Float,
        sensorPattern: BayerPattern
    ): Pair<DoubleArray, DoubleArray> {
        require(profile.size == 6 || profile.size == 8) {
            "Noise profile must hold 6 (RGB) or 8 (raster-phase) S/O values, got ${profile.size}"
        }
        require(profile.all { it.isFinite() && it >= 0.0 }) { "Noise profile must be finite and nonnegative" }
        require(blackLevels.size == 4 && blackLevels.all { it.isFinite() }) {
            "Black levels must be four finite values"
        }
        require(whiteLevel.isFinite() && blackLevels.all { whiteLevel > it }) {
            "White level must be finite and above every black level"
        }
        val alpha = DoubleArray(4)
        val beta = DoubleArray(4)
        var greens = 0
        for (raster in 0..3) {
            val slope: Double
            val offset: Double
            if (profile.size == 8) {
                slope = profile[raster * 2]
                offset = profile[raster * 2 + 1]
            } else {
                val channel = when (sensorPattern.colorAt(raster and 1, (raster shr 1) and 1)) {
                    CfaColor.RED -> 0
                    CfaColor.GREEN -> 1
                    CfaColor.BLUE -> 2
                }
                slope = profile[channel * 2]
                offset = profile[channel * 2 + 1]
            }
            val black = blackLevels[raster].toDouble()
            val white = whiteLevel.toDouble()
            // Exact per-phase normalization, mirroring RawSrCovarianceGuide.
            val a = slope / (white - black)
            val b = (slope * black + offset) / ((white - black) * (white - black))
            when (sensorPattern.colorAt(raster and 1, (raster shr 1) and 1)) {
                CfaColor.RED -> { alpha[0] = a; beta[0] = b }
                CfaColor.BLUE -> { alpha[2] = a; beta[2] = b }
                CfaColor.GREEN -> {
                    // Raster order decides G1/G2, matching EXIF R,G1,B,G2 planes.
                    val slot = if (greens == 0) 1 else 3
                    alpha[slot] = a; beta[slot] = b
                    greens++
                }
            }
        }
        require(greens == 2) { "Bayer pattern must hold exactly two green phases" }
        return alpha to beta
    }

    /**
     * Monte Carlo simulation of clipped-noise 3x3 patch statistics in the
     * linear guide domain. The latent prior is exactly uniform by midpoint
     * stratification (`(trial+0.5)/trials`, mirroring the reference); R/B
     * channels simulate single-sample patches, G simulates mean-of-two-greens
     * patches, and the reference means/variances plus moving means bin into
     * sigma²/d² curves by measured reference brightness. Deterministic per
     * [seed]. Throws [CancellationException] when [isCancelled] trips.
     */
    fun generate(
        alpha: DoubleArray,
        beta: DoubleArray,
        bins: Int = DEFAULT_BINS,
        trials: Int = DEFAULT_TRIALS,
        seed: Long = DEFAULT_SEED,
        isCancelled: (() -> Boolean)? = null,
        progress: ((completed: Int, total: Int) -> Unit)? = null
    ): Lut {
        require(alpha.size == 4 && beta.size == 4) { "RGBG alpha/beta must hold four values each" }
        require(alpha.all { it.isFinite() && it >= 0.0 } && beta.all { it.isFinite() && it >= 0.0 }) {
            "RGBG alpha/beta must be finite and nonnegative"
        }
        require(bins >= 2) { "bins must be at least 2" }
        require(trials >= 1) { "trials must be positive" }
        require(seed >= 0) { "seed must be nonnegative" }
        val random = Random(seed)
        val counts = LongArray(bins)
        val sigmaSum = DoubleArray(bins)
        val sigmaSumSq = DoubleArray(bins)
        val dSum = DoubleArray(bins)
        val dSumSq = DoubleArray(bins)
        // Scratch pair (mean, variance); the trial loop allocates nothing.
        val stats = DoubleArray(2)
        var completed = 0
        while (completed < trials) {
            if (isCancelled?.invoke() == true) throw CancellationException("Noise LUT generation cancelled")
            val stop = minOf(completed + CHUNK_TRIALS, trials)
            for (trial in completed until stop) {
                val latent = (trial + 0.5) / trials
                patchStats(random, latent, alpha[0], beta[0], stats)
                val refR = stats[0]; val varR = stats[1]
                patchStatsGreen(random, latent, alpha[1], beta[1], alpha[3], beta[3], stats)
                val refG = stats[0]; val varG = stats[1]
                patchStats(random, latent, alpha[2], beta[2], stats)
                val refB = stats[0]; val varB = stats[1]
                val movR = patchMean(random, latent, alpha[0], beta[0])
                val movG = patchMeanGreen(random, latent, alpha[1], beta[1], alpha[3], beta[3])
                val movB = patchMean(random, latent, alpha[2], beta[2])
                val measured = (refR + refG + refB) / 3.0
                val idx = floor(measured * (bins - 1) + 0.5).toInt().coerceIn(0, bins - 1)
                val sigma = varR + varG + varB
                val dr = refR - movR; val dg = refG - movG; val db = refB - movB
                val d = dr * dr + dg * dg + db * db
                counts[idx]++
                sigmaSum[idx] += sigma
                sigmaSumSq[idx] += sigma * sigma
                dSum[idx] += d
                dSumSq[idx] += d * d
            }
            completed = stop
            progress?.invoke(completed, trials)
        }
        val (sigmaCurve, sigmaSem) = finalizeCurve(counts, sigmaSum, sigmaSumSq)
        val (dCurve, dSem) = finalizeCurve(counts, dSum, dSumSq)
        return Lut(bins, trials.toLong(), seed, alpha.copyOf(), beta.copyOf(),
            sigmaCurve, dCurve, sigmaSem, dSem, counts)
    }

    private fun noisy(random: Random, brightness: Double, slope: Double, offset: Double): Double {
        // All terms are nonnegative by validation, so no variance floor is needed.
        val value = brightness + sqrt(slope * brightness + offset) * random.nextGaussian()
        return value.coerceIn(0.0, 1.0)
    }

    /** Welford mean/variance of a single-sample 3x3 patch into [out] (mean, variance). */
    private fun patchStats(
        random: Random, brightness: Double, slope: Double, offset: Double, out: DoubleArray
    ) {
        var mean = 0.0
        var moment2 = 0.0
        for (sample in 0..8) {
            val value = noisy(random, brightness, slope, offset)
            val delta = value - mean
            mean += delta / (sample + 1)
            moment2 += delta * (value - mean)
        }
        out[0] = mean
        out[1] = maxOf(moment2 / 9.0, 0.0)
    }

    /** Welford mean/variance of a mean-of-two-greens 3x3 patch (the guide's G). */
    private fun patchStatsGreen(
        random: Random, brightness: Double,
        slopeA: Double, offsetA: Double, slopeB: Double, offsetB: Double,
        out: DoubleArray
    ) {
        var mean = 0.0
        var moment2 = 0.0
        for (sample in 0..8) {
            val value = 0.5 * (noisy(random, brightness, slopeA, offsetA) +
                noisy(random, brightness, slopeB, offsetB))
            val delta = value - mean
            mean += delta / (sample + 1)
            moment2 += delta * (value - mean)
        }
        out[0] = mean
        out[1] = maxOf(moment2 / 9.0, 0.0)
    }

    private fun patchMean(random: Random, brightness: Double, slope: Double, offset: Double): Double {
        var mean = 0.0
        for (sample in 0..8) {
            mean += (noisy(random, brightness, slope, offset) - mean) / (sample + 1)
        }
        return mean
    }

    private fun patchMeanGreen(
        random: Random, brightness: Double,
        slopeA: Double, offsetA: Double, slopeB: Double, offsetB: Double
    ): Double {
        var mean = 0.0
        for (sample in 0..8) {
            val value = 0.5 * (noisy(random, brightness, slopeA, offsetA) +
                noisy(random, brightness, slopeB, offsetB))
            mean += (value - mean) / (sample + 1)
        }
        return mean
    }

    private fun finalizeCurve(counts: LongArray, sums: DoubleArray, sumsSq: DoubleArray): Pair<FloatArray, FloatArray> {
        val populated = BooleanArray(counts.size) { counts[it] > 0 }
        require(populated.any { it }) { "Monte Carlo simulation populated no measured-brightness bins" }
        val means = DoubleArray(counts.size)
        val sem = DoubleArray(counts.size)
        for (i in counts.indices) {
            if (!populated[i]) continue
            val mean = sums[i] / counts[i]
            means[i] = mean
            val variance = maxOf(sumsSq[i] / counts[i] - mean * mean, 0.0)
            sem[i] = sqrt(variance / counts[i])
        }
        // Linear interpolation over unpopulated bins with clamped ends,
        // mirroring np.interp on the populated grid points.
        return interpolateMissing(means, populated) to interpolateMissing(sem, populated)
    }

    private fun interpolateMissing(values: DoubleArray, populated: BooleanArray): FloatArray {
        val out = FloatArray(values.size)
        // Nearest populated index at or below / above each position.
        var below = -1
        val lower = IntArray(values.size)
        for (i in values.indices) {
            if (populated[i]) below = i
            lower[i] = below
        }
        var above = -1
        val upper = IntArray(values.size)
        for (i in values.indices.reversed()) {
            if (populated[i]) above = i
            upper[i] = above
        }
        for (i in values.indices) {
            if (populated[i]) {
                out[i] = values[i].toFloat()
                continue
            }
            val lo = lower[i]
            val hi = upper[i]
            out[i] = when {
                lo < 0 -> values[hi].toFloat()
                hi < 0 -> values[lo].toFloat()
                hi == lo -> values[lo].toFloat()
                else -> {
                    val t = (i - lo).toDouble() / (hi - lo)
                    (values[lo] + t * (values[hi] - values[lo])).toFloat()
                }
            }
        }
        return out
    }

    /**
     * Decodes [Lut.toBytes]. Rejects truncated input, stale versions, shape
     * mismatches, non-finite or negative curves, and bin counts that do not
     * sum to the trial count — mirroring the reference loader's strictness.
     * When [expectedAlpha]/[expectedBeta] are provided (together), the stored
     * profile must match within 1e-6 relative / 1e-12 absolute tolerance.
     */
    fun load(
        bytes: ByteArray,
        expectedAlpha: DoubleArray? = null,
        expectedBeta: DoubleArray? = null
    ): Lut {
        fun fail(reason: String): Nothing = throw IllegalArgumentException("Invalid noise LUT: $reason")
        if ((expectedAlpha == null) != (expectedBeta == null))
            fail("expected alpha/beta must be provided together")
        if (bytes.size < 96) fail("truncated header (${bytes.size} bytes)")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) fail("bad magic")
        if (buffer.int != FORMAT_VERSION) fail("unsupported format version")
        val bins = buffer.int
        if (bins < 2) fail("bins must be at least 2")
        val trials = buffer.long
        if (trials < 1) fail("trials must be positive")
        val seed = buffer.long
        if (seed < 0) fail("seed must be nonnegative")
        if (bytes.size != 96 + bins * 24) fail("size ${bytes.size} mismatches $bins bins")
        val alpha = DoubleArray(4) { buffer.double }
        val beta = DoubleArray(4) { buffer.double }
        val sigmaSq = FloatArray(bins) { buffer.float }
        val dSq = FloatArray(bins) { buffer.float }
        val sigmaSem = FloatArray(bins) { buffer.float }
        val dSem = FloatArray(bins) { buffer.float }
        val counts = LongArray(bins) { buffer.long }
        if (!(alpha + beta).all { it.isFinite() && it >= 0.0 }) fail("alpha/beta must be finite and nonnegative")
        if (!sigmaSq.all { it.isFinite() && it >= 0f } || !dSq.all { it.isFinite() && it >= 0f })
            fail("curves must be finite and nonnegative")
        if (!sigmaSem.all { it.isFinite() && it >= 0f } || !dSem.all { it.isFinite() && it >= 0f })
            fail("standard errors must be finite and nonnegative")
        if (counts.any { it < 0 || it > trials }) fail("bin counts must lie in 0..trials")
        var total = 0L
        for (count in counts) {
            total += count
            if (total > trials) fail("bin counts sum past $trials trials")
        }
        if (total != trials) fail("bin counts sum to $total, expected $trials")
        if (expectedAlpha != null && expectedBeta != null) {
            if (!matchProfile(alpha, expectedAlpha) || !matchProfile(beta, expectedBeta))
                fail("stored profile does not match the burst profile")
        }
        return Lut(bins, trials, seed, alpha, beta, sigmaSq, dSq, sigmaSem, dSem, counts)
    }

    private fun matchProfile(stored: DoubleArray, expected: DoubleArray): Boolean {
        if (expected.size != 4) return false
        return stored.indices.all { i ->
            kotlin.math.abs(stored[i] - expected[i]) <= PROFILE_ATOL + PROFILE_RTOL * kotlin.math.abs(expected[i])
        }
    }

    /**
     * Load-or-generate LUT for one sensor profile, cached in [cacheDir] under
     * a SHA-1 filename over the transform, parameters, and profile. Null or
     * invalid inputs yield null (the caller keeps the analytic path); corrupt
     * cache files regenerate instead of failing. Cache writes are best-effort:
     * a generated LUT is returned even when the write fails.
     */
    fun cached(
        profile: ImmutableDoubleValues?,
        blackLevels: FloatArray?,
        whiteLevel: Float,
        sensorPattern: BayerPattern,
        cacheDir: File,
        bins: Int = DEFAULT_BINS,
        trials: Int = DEFAULT_TRIALS,
        seed: Long = DEFAULT_SEED
    ): Lut? {
        if (profile == null || blackLevels == null) return null
        val coefficients = try {
            profile.toDoubleArray()
        } catch (_: Exception) {
            return null
        }
        val (alpha, beta) = try {
            rgbgNormalized(coefficients, blackLevels, whiteLevel, sensorPattern)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bins < 2 || trials < 1 || seed < 0) return null
        val digest = MessageDigest.getInstance("SHA-1")
        fun feed(text: String) = digest.update(text.toByteArray(Charsets.UTF_8))
        fun feedLong(value: Long) = digest.update(ByteBuffer.allocate(8).putLong(value).array())
        feed("$TRANSFORM/$FORMAT_VERSION/$bins/$trials/$seed/${sensorPattern.name}/")
        val doubles = ByteBuffer.allocate(8 * (coefficients.size + 8)).order(ByteOrder.LITTLE_ENDIAN)
        coefficients.forEach(doubles::putDouble)
        blackLevels.forEach { doubles.putDouble(it.toDouble()) }
        doubles.putDouble(whiteLevel.toDouble())
        digest.update(doubles.array())
        val key = digest.digest().joinToString("") { "%02x".format(it) }
        val file = File(cacheDir, "rawsr-noiselut-$key.bin")
        if (file.isFile) {
            try {
                return load(file.readBytes(), alpha, beta)
            } catch (_: Exception) {
                // Corrupt or stale cache entry: regenerate below.
            }
        }
        val lut = try {
            generate(alpha, beta, bins, trials, seed)
        } catch (_: IllegalArgumentException) {
            return null
        }
        runCatching {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "rawsr-noiselut-$key.tmp")
            tmp.writeBytes(lut.toBytes())
            if (!tmp.renameTo(file)) {
                tmp.delete()
                file.writeBytes(lut.toBytes())
            }
        }
        return lut
    }
}
