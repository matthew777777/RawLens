// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Unblocker / variance-loss mask (Sabre `unblocker.cc` analogue).
 * Implements [docs/raw-sr-unblocker.md]; the document is normative.
 *
 * Block-based fusion destroys variance it cannot see: a Nyquist checker
 * averages to flat under 2x2 boxing, so its high-frequency energy would be
 * trusted at full weight by the kernel means. The unblocker measures, per
 * quad, how much signal variance survives a 2x2 box lowpass and attenuates
 * the frame's robustness where variance was lost.
 *
 * The ratio is noise-gated, not pure: photon noise also loses ~4x variance
 * under boxing, but noise carries no detail to protect and its fusion is
 * the denoiser — so noise-dominated quads keep weight 1 and only quads
 * whose variance clearly exceeds the noise floor are attenuated. A pure
 * `Vlow/Vfull` ratio would slash every noisy flat by ~4x.
 *
 * Operates on quad gray (the inpainted, GAT-independent mean plane — the
 * same field the alignment pyramid consumes). The GPU
 * `unblocker_downsample/weight/modulate.glsl` passes mirror this file
 * exactly; degenerate fixtures (flat, Nyquist checker) agree bitwise.
 *
 * Scalar arithmetic over flat arrays; row-sharded over the shared worker
 * pool with disjoint rows (bitwise-identical at any worker count).
 */
object RawSrUnblocker {
    /**
     * Signal must exceed this multiple of the expected noise variance
     * before any attenuation applies. Below it the quad is
     * noise-dominated (or silent) and keeps weight 1.
     */
    const val UNBLOCKER_NOISE_GATE = 2.0

    /** Attenuation below this weight marks the quad UNBLOCKED for diagnostics. */
    const val UNBLOCKED_THRESHOLD = 0.999

    /**
     * Per-quad unblocker weight for one frame.
     *
     * @param gray quad gray values (row-major).
     * @param alpha beta expected per-quad noise variance `Vn = alpha*x + beta`
     * in the gray domain (green-channel normalized coefficients: quad gray
     * is luma-ish and Sabre keeps a green-only fast path; the approximation
     * is documented because this is a regime gate, not metrology). NaN,
     * non-finite, or all-zero coefficients mean no usable model: every quad
     * keeps weight 1 (no model, no gate — never a fabricated attenuation).
     */
    fun computeFrame(
        gray: RawSrGrayImage,
        alpha: Double,
        beta: Double
    ): FloatArray {
        val width = gray.width
        val height = gray.height
        require(width > 0 && height > 0)
        require(gray.values.size == width * height)
        if (!alpha.isFinite() || !beta.isFinite() || (alpha == 0.0 && beta == 0.0)) {
            return FloatArray(width * height) { 1f }
        }
        val values = gray.values
        // Half-quad box mean (2x2 quad average): the lowpass whose variance
        // loss we measure. Ceil-sized so odd quad grids keep their edge row.
        val halfW = (width + 1) / 2
        val halfH = (height + 1) / 2
        val half = FloatArray(halfW * halfH)
        RawSrWorkers.forEachShard(halfH) { y0, y1 ->
            for (hy in y0 until y1) for (hx in 0 until halfW) {
                var sum = 0.0
                var count = 0
                for (i in 0..1) for (j in 0..1) {
                    val qx = hx * 2 + j
                    val qy = hy * 2 + i
                    if (qx >= width || qy >= height) continue
                    val v = values[qy * width + qx].toDouble()
                    if (!v.isFinite()) continue
                    sum += v
                    count++
                }
                half[hy * halfW + hx] = if (count > 0) (sum / count).toFloat() else 0f
            }
        }
        // Local 3x3 variances, clamp-to-edge, mirroring evaluate's refVar.
        fun varianceAt(field: FloatArray, fw: Int, fh: Int, x: Int, y: Int): Double {
            var sum = 0.0
            var squares = 0.0
            for (i in -1..1) for (j in -1..1) {
                val v = field[(y + i).coerceIn(0, fh - 1) * fw + (x + j).coerceIn(0, fw - 1)].toDouble()
                sum += v
                squares += v * v
            }
            val mean = sum / 9.0
            return maxOf(squares / 9.0 - mean * mean, 0.0)
        }
        val out = FloatArray(width * height)
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until width) {
                val o = y * width + x
                val full = varianceAt(values, width, height, x, y)
                // Non-finite taps poison the window: keep weight 1 rather
                // than attenuating on a measurement we cannot trust.
                if (!full.isFinite()) {
                    out[o] = 1f
                    continue
                }
                val g = values[o].toDouble()
                val noise = if (!g.isFinite()) Double.NaN
                else alpha * maxOf(g, 0.0) + beta
                if (!noise.isFinite() || noise < 0.0) {
                    out[o] = 1f
                    continue
                }
                if (full <= UNBLOCKER_NOISE_GATE * noise) {
                    out[o] = 1f
                    continue
                }
                val hx = (x / 2).coerceIn(0, halfW - 1)
                val hy = (y / 2).coerceIn(0, halfH - 1)
                val low = varianceAt(half, halfW, halfH, hx, hy)
                if (!low.isFinite()) {
                    out[o] = 1f
                    continue
                }
                val signalFull = full - noise
                val signalLow = maxOf(low - noise / 4.0, 0.0)
                // signalFull > 0 strictly: full > 2*noise >= noise holds on
                // this branch (noise >= 0), so no epsilon discipline is owed.
                out[o] = (signalLow / signalFull).toFloat().coerceIn(0f, 1f)
            }
        }
        return out
    }

    /**
     * Fold an unblocker field into a robustness field: `r *= clamp(u, 0, 1)`
     * with non-finite products sanitized to zero, and [RawSrRobustness.FLAG_UNBLOCKED]
     * ORed where the weight actually attenuates. Returns a new field; the
     * input is never mutated.
     */
    fun applyToFrame(
        frame: RawSrRobustness.FrameRobustness,
        u: FloatArray
    ): RawSrRobustness.FrameRobustness {
        require(u.size == frame.width * frame.height) { "Unblocker field must match the robustness grid" }
        val r = FloatArray(u.size)
        val flags = IntArray(u.size)
        for (i in u.indices) {
            val weight = u[i].coerceIn(0f, 1f)
            val scaled = frame.r[i] * weight
            r[i] = if (scaled.isFinite()) scaled else 0f
            var flag = frame.flags[i]
            if (weight.isFinite() && weight < UNBLOCKED_THRESHOLD) {
                flag = flag or RawSrRobustness.FLAG_UNBLOCKED
            }
            flags[i] = flag
        }
        return RawSrRobustness.FrameRobustness(frame.width, frame.height, r, flags)
    }
}
