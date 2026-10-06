// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Host planning for the GPU FFT grey pass (`rawsr/fft_stage.glsl` +
 * `rawsr/fft_remap.glsl`): the CPU [RawSrFft] mixed-radix decimation in
 * time, flattened into independent per-output stages. Pure (unit-testable,
 * no GL); mirrored byte-identical in app/ and tools/sr-vulkan/.
 *
 * The GPU transcribes the CPU recursion exactly: the factor chain takes
 * the smallest prime factor first at every level (the same
 * [smallestFactor], the same [RawSrFft.MAX_RADIX] rejection), each stage
 * pass computes
 * out[(o*p+k1)*m'+j] = W_m^{j*k1} * sum_{t<p} in[o*m+t*m'+j] * W_p^{t*k1}
 * (the CPU `ditStaged` stage 1+2 with the `naive` sum in the same t
 * order), and a final permutation gathers natural order from the
 * flattened stage order (the flattened index carries the first-stage
 * digit most significant; the CPU scatters natural order at every
 * level, so the permutation reorders without arithmetic).
 *
 * Precision contract: the GPU runs float32 (the only complex-capable
 * image format; neither target GPU family exposes shaderFloat64), so the
 * twiddle tables below are the CPU double tables rounded to float and
 * parity with [RawSrFft] is tolerance-based (pinned by
 * VkFftGreyParityTest), like every other GPU pass. The float oracle
 * [RawSrFftF32] transcribes the same recursion in Float for the tight
 * GPU-vs-algorithm bound.
 */
object RawSrFftPlan {
    /** Largest flattened stage count (camera sizes need <= 13 for 8192). */
    const val MAX_LEVELS = 16

    /**
     * One flattened stage over lines of length [n]: the current sub-size
     * [m] (m <= n, m == n at the first stage) splits into [radix] x
     * (m / radix). The pass reads the [n]-line layout and writes the
     * next flattened layout; after the last stage the permute
     * ([flattenIndex]) gathers natural order.
     */
    data class Stage(val m: Int, val radix: Int) {
        init {
            require(m >= 1 && radix >= 2 && m % radix == 0) {
                "Bad FFT stage: m=$m radix=$radix"
            }
        }
        /** Inner size m' = m / radix. */
        val inner: Int get() = m / radix
    }

    /**
     * Smallest prime factor of [n], transcribed from [RawSrFft] (same
     * trial division, same visit order).
     */
    fun smallestFactor(n: Int): Int {
        var p = 2
        while (p * p <= n) {
            if (n % p == 0) return p
            p += if (p == 2) 1 else 2
        }
        return n
    }

    /**
     * Stage chain for lines of length [n]: smallest factor first at every
     * level, exactly the CPU recursion's split order. Sizes with a prime
     * factor above [RawSrFft.MAX_RADIX] are rejected with the same error
     * (no camera size needs them). Empty for n == 1 (identity).
     */
    fun stages(n: Int): List<Stage> {
        require(n >= 1) { "FFT size must be positive" }
        val out = ArrayList<Stage>()
        var m = n
        while (m > 1) {
            val p = smallestFactor(m)
            require(p <= RawSrFft.MAX_RADIX) {
                "FFT size $n has prime factor $p above ${RawSrFft.MAX_RADIX}; no camera size needs this"
            }
            out.add(Stage(m, p))
            m /= p
        }
        require(out.size <= MAX_LEVELS) { "FFT size $n needs ${out.size} stages (max $MAX_LEVELS)" }
        return out
    }

    /**
     * Natural order to flattened stage order: [factors] is the stage
     * radix chain in order (p1 first). The natural index carries the
     * first-stage digit least significant (k = k1 + p1*(k1' + ...));
     * the flattened index carries it most significant. The mode-2
     * permute gathers out[pos] = in[flattenIndex(pos)] (+ scale). Pure
     * index math, transcribed 1:1 in `fft_remap.glsl` mode 2.
     */
    fun flattenIndex(index: Int, factors: List<Int>): Int {
        var tmp = index
        var flat = 0
        for (f in factors) {
            val d = tmp % f
            tmp = (tmp - d) / f
            flat = flat * f + d
        }
        return flat
    }

    /**
     * Twiddle table W_n (forward) or its conjugate (inverse) as an
     * RGBA32F row (re=R, im=G, zeros in B/A): the CPU [RawSrFft] double
     * table rounded to float, same angles, same order. Uploaded as an
     * n x 1 texture per stage ([Stage.m] and [Stage.radix] tables).
     */
    fun twiddleRow(n: Int, inverse: Boolean): FloatArray {
        require(n >= 1) { "FFT size must be positive" }
        val out = FloatArray(n * 4)
        for (k in 0 until n) {
            val angle = 2.0 * PI * k / n
            out[k * 4] = cos(angle).toFloat()
            out[k * 4 + 1] = (if (inverse) sin(angle) else -sin(angle)).toFloat()
        }
        return out
    }
}
