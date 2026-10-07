// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Chroma-from-luma stabilization for the Linear-RGB product: rebuilds the R
 * and B lanes as `G + smooth(R-G)` / `G + smooth(B-G)` with a separable
 * sigma-1.0 Gaussian (radius 3) over the color differences. G is never
 * touched, so luma detail and edge sharpness survive bit-identically.
 *
 * Why: razor kernels (k1 ~ 0.125) latch onto single R/B taps, and the sparse
 * R/B lattices latch at different rows along slanted edges, so R and B jump
 * out of phase (rainbow staircase blocks). The G lattice is dense and stays
 * smooth. Smoothing the slowly-varying differences while keeping G transfers
 * the luma edge profile onto chroma — the demosaic analogue the reference
 * relies on downstream of its re-mosaiced output. The Mosaic path never calls
 * this (it ships reference-identical taps for the converter to demosaic).
 * Differences, not ratios: ratio smoothing explodes where G pits to ~zero
 * in shadows and sprays magenta donuts; the difference form cannot amplify.
 *
 * Determinism: clamped borders, center pixels at/below [GUIDE_EPS] keep
 * their original lanes, and Float accumulation in fixed tap order matches
 * `chroma_from_luma.glsl` op for op (up to GPU FMA contraction, ~1ulp).
 */
object RawSrChromaFromLuma {
    /** Separable Gaussian sigma in pixels. */
    const val SIGMA = RawSrCoreFinish.SIGMA

    /** Radius of the 7-tap separable kernel. */
    const val RADIUS = RawSrCoreFinish.RADIUS

    /**
     * Normalized guide floor: center pixels at/below this keep their original
     * R/B (pure black; nothing to rebuild there).
     */
    const val GUIDE_EPS = RawSrCoreFinish.GUIDE_EPS

    /**
     * Normalized 7-tap weights, offsets 0..3 (mirrored). Identical decimal
     * strings to the GLSL twin so both sides parse bitwise-equal Floats.
     */
    val KERNEL: FloatArray = RawSrCoreFinish.KERNEL

    /**
     * Stabilizes [rgb] (`pixels * 3`, channel-minor) in place. Returns the
     * count of pixels whose lanes were rebuilt (guide above [GUIDE_EPS]).
     * See [RawSrCoreFinish.stabilize].
     */
    fun stabilize(rgb: FloatArray, width: Int, height: Int): Int =
        RawSrCoreFinish.stabilize(rgb, width, height)
}
