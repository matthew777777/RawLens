package com.rawlens.srpipeline.stages

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Noise-LUT generator (`generate_noise_lut` in
 * `handheld_super_resolution/monte_carlo.py`), the robustness noise
 * correction of Wronski et al. 2019 Sec. 5.2.1: for a uniform latent
 * brightness, simulate a reference and a moving 3x3 patch per guide channel
 * (raw clipped to [0,1], then sqrt; green = sqrt of the G1/G2 mean), and bin
 * E[sigma^2] (summed reference variances) and E[d^2] (squared mean
 * distance) by the measured reference brightness (mean of the 3 channel
 * means). Empty bins are linearly interpolated (`np.interp`).
 *
 * Same model, priors and binning as the Python generator; the RNG differs
 * (splitmix64 + polar Gaussian instead of numba's), so the curves match to
 * Monte Carlo error rather than bit for bit (parity/noise_lut_parity).
 *
 * `alpha`/`beta` are RGBG (R, G1, B, G2), as from [DngRead.noiseAlphaBeta].
 */
object NoiseLutGen {

    const val DEFAULT_BINS = 1001

    /**
     * 2M trials: measured against the 10M Python LUT the curves agree to
     * well under the per-pixel noise of the statistic they correct
     * (parity/noise_lut_parity), at a fraction of the cost on a phone.
     */
    const val DEFAULT_TRIALS = 2_000_000

    private class Rng(seed: Long) {
        private var s = seed
        private var spare = 0.0
        private var hasSpare = false
        fun nextLong(): Long {
            s += -0x61c8864680b583ebL
            var z = s
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
        fun gaussian(): Double {
            if (hasSpare) { hasSpare = false; return spare }
            while (true) {
                val u = 2 * nextDouble() - 1
                val v = 2 * nextDouble() - 1
                val q = u * u + v * v
                if (q > 0 && q < 1) {
                    val f = sqrt(-2 * ln(q) / q)
                    spare = v * f; hasSpare = true
                    return u * f
                }
            }
        }
    }

    private fun noisy(rng: Rng, b: Double, a: Double, be: Double): Double {
        val v = b + sqrt(max(a * b + be, 0.0)) * rng.gaussian()
        return v.coerceIn(0.0, 1.0)
    }

    /** Welford over 9 samples -> (mean, population var) into out[0..1]. */
    private inline fun patch(out: DoubleArray, sample: () -> Double) {
        var mean = 0.0
        var m2 = 0.0
        for (k in 0 until 9) {
            val v = sample()
            val d = v - mean
            mean += d / (k + 1)
            m2 += d * (v - mean)
        }
        out[0] = mean
        out[1] = max(m2 / 9.0, 0.0)
    }

    private class Acc(bins: Int) {
        val counts = LongArray(bins)
        val sig = DoubleArray(bins)
        val d = DoubleArray(bins)
    }

    fun generate(
        alpha: DoubleArray,
        beta: DoubleArray,
        bins: Int = DEFAULT_BINS,
        trials: Int = DEFAULT_TRIALS,
        seed: Long = 0,
        threads: Int = Runtime.getRuntime().availableProcessors(),
    ): NoiseLut.Curves {
        require(alpha.size == 4 && beta.size == 4) { "alpha/beta must be RGBG" }
        require(bins >= 2 && trials >= 1)
        require((alpha + beta).all { it.isFinite() && it >= 0.0 }) { "alpha/beta must be finite and >= 0" }
        val blocks = max(1, threads) * 4
        val pool = Executors.newFixedThreadPool(max(1, threads))
        val accs = try {
            pool.invokeAll((0 until blocks).map { blk ->
                Callable {
                    val acc = Acc(bins)
                    val rng = Rng(seed * 1_000_003L + 104_729L * (blk + 1))
                    val o = DoubleArray(2)
                    val start = (trials.toLong() * blk / blocks).toInt()
                    val stop = (trials.toLong() * (blk + 1) / blocks).toInt()
                    for (t in start until stop) {
                        val lat = (t + 0.5) / trials
                        patch(o) { sqrt(noisy(rng, lat, alpha[0], beta[0])) }
                        val rr = o[0]; val vr = o[1]
                        patch(o) { sqrt(0.5 * (noisy(rng, lat, alpha[1], beta[1]) + noisy(rng, lat, alpha[3], beta[3]))) }
                        val rg = o[0]; val vg = o[1]
                        patch(o) { sqrt(noisy(rng, lat, alpha[2], beta[2])) }
                        val rb = o[0]; val vb = o[1]
                        patch(o) { sqrt(noisy(rng, lat, alpha[0], beta[0])) }
                        val mr = o[0]
                        patch(o) { sqrt(0.5 * (noisy(rng, lat, alpha[1], beta[1]) + noisy(rng, lat, alpha[3], beta[3]))) }
                        val mg = o[0]
                        patch(o) { sqrt(noisy(rng, lat, alpha[2], beta[2])) }
                        val mb = o[0]
                        val i = floor((rr + rg + rb) / 3.0 * (bins - 1) + 0.5).toInt().coerceIn(0, bins - 1)
                        acc.counts[i]++
                        acc.sig[i] += vr + vg + vb
                        val dr = rr - mr; val dg = rg - mg; val db = rb - mb
                        acc.d[i] += dr * dr + dg * dg + db * db
                    }
                    acc
                }
            }).map { it.get() }
        } finally {
            pool.shutdown()
        }
        val total = Acc(bins)
        for (a in accs) for (i in 0 until bins) {
            total.counts[i] += a.counts[i]; total.sig[i] += a.sig[i]; total.d[i] += a.d[i]
        }
        return NoiseLut.Curves(
            sigmaSq = finalize(total.counts, total.sig),
            dSq = finalize(total.counts, total.d),
            alpha = alpha.copyOf(),
            beta = beta.copyOf(),
        )
    }

    /** Bin means with `np.interp` over the populated bins (edges held). */
    private fun finalize(counts: LongArray, sums: DoubleArray): FloatArray {
        val pop = counts.indices.filter { counts[it] > 0 }
        check(pop.isNotEmpty()) { "Monte Carlo simulation did not populate any bins" }
        val mean = DoubleArray(counts.size)
        for (i in pop) mean[i] = sums[i] / counts[i]
        val out = FloatArray(counts.size)
        var k = 0
        for (i in counts.indices) {
            out[i] = when {
                i <= pop.first() -> mean[pop.first()]
                i >= pop.last() -> mean[pop.last()]
                else -> {
                    while (pop[k + 1] < i) k++
                    val x0 = pop[k]; val x1 = pop[k + 1]
                    if (i == x0) mean[x0] else mean[x0] + (mean[x1] - mean[x0]) * (i - x0) / (x1 - x0)
                }
            }.toFloat()
        }
        return out
    }

    /**
     * [generate] memoised per noise profile in `dir` (raw little-endian
     * float file keyed by alpha/beta/bins/trials), so a sensor/ISO pays the
     * simulation once. A missing or unreadable cache simply regenerates.
     */
    fun cached(dir: File, alpha: DoubleArray, beta: DoubleArray,
               bins: Int = DEFAULT_BINS, trials: Int = DEFAULT_TRIALS): NoiseLut.Curves {
        val key = (alpha + beta).joinToString("_") { java.lang.Long.toHexString(it.toRawBits()) }.hashCode()
        val f = File(dir, "noise_lut_v1_${bins}_${trials}_${Integer.toHexString(key)}.bin")
        try {
            if (f.length() == 8L * bins) {
                val bb = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val s = FloatArray(bins) { bb.float }
                val d = FloatArray(bins) { bb.float }
                return NoiseLut.Curves(s, d, alpha.copyOf(), beta.copyOf())
            }
        } catch (_: Exception) { }
        val c = generate(alpha, beta, bins, trials)
        try {
            dir.mkdirs()
            val bb = java.nio.ByteBuffer.allocate(8 * bins).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            c.sigmaSq.forEach { bb.putFloat(it) }; c.dSq.forEach { bb.putFloat(it) }
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(bb.array())
            tmp.renameTo(f)
        } catch (_: Exception) { }
        return c
    }
}
