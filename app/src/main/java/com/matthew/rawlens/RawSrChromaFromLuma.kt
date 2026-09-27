// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Chroma-from-luma stabilization for the Linear-RGB product: rebuilds the R
 * and B lanes as `G * smooth(R/G)` / `G * smooth(B/G)` with a separable
 * sigma-1.0 Gaussian (radius 3) over the ratios. G is never touched, so luma
 * detail and edge sharpness survive bit-identically.
 *
 * Why: razor kernels (k1 ~ 0.125) latch onto single R/B taps, and the sparse
 * R/B lattices latch at different rows along slanted edges, so R and B jump
 * out of phase (rainbow staircase blocks). The G lattice is dense and stays
 * smooth. Smoothing the slowly-varying ratios while keeping G transfers the
 * luma edge profile onto chroma — the demosaic analogue the reference relies
 * on downstream of its re-mosaiced output. The Mosaic path never calls this
 * (it ships reference-identical taps for the converter to demosaic).
 *
 * Determinism: clamped borders, per-tap `max(G, EPS)` ratio guard (black is
 * black in all lanes, so dark taps contribute ~0, never spikes), center
 * pixels at/below [GUIDE_EPS] keep their original lanes, and Float
 * accumulation in fixed tap order matches `chroma_from_luma.glsl` op for op
 * (up to GPU FMA contraction, ~1ulp).
 */
object RawSrChromaFromLuma {
    /** Separable Gaussian sigma in pixels. */
    const val SIGMA = 1.0f

    /** Radius of the 7-tap separable kernel. */
    const val RADIUS = 3

    /**
     * Normalized guide floor: center pixels at/below this keep their original
     * R/B (deep shadow; ratios carry no information there).
     */
    const val GUIDE_EPS = 1e-4f

    /**
     * Normalized 7-tap weights, offsets 0..3 (mirrored). Identical decimal
     * strings to the GLSL twin so both sides parse bitwise-equal Floats.
     */
    val KERNEL = floatArrayOf(0.399050279652f, 0.242036229376f, 0.0540055826224f, 0.00443304817524f)

    /**
     * Stabilizes [rgb] (`pixels * 3`, channel-minor) in place. Returns the
     * count of pixels whose lanes were rebuilt (guide above [GUIDE_EPS]).
     */
    fun stabilize(rgb: FloatArray, width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        require(rgb.size == width * height * 3) { "rgb must hold 3 lanes per pixel" }
        val k = KERNEL
        // Horizontal pass into ratio temps (per-tap guarded ratios).
        val tmpR = FloatArray(width * height)
        val tmpB = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0f
                var sumB = 0f
                for (ox in -RADIUS..RADIUS) {
                    val xx = (x + ox).coerceIn(0, width - 1)
                    val o = (y * width + xx) * 3
                    val g = rgb[o + 1]
                    val gn = if (g > GUIDE_EPS) g else GUIDE_EPS
                    val w = k[kotlin.math.abs(ox)]
                    sumR += w * (rgb[o] / gn)
                    sumB += w * (rgb[o + 2] / gn)
                }
                tmpR[y * width + x] = sumR
                tmpB[y * width + x] = sumB
            }
        }
        // Vertical pass; reconstruct from the center guide.
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val o = (y * width + x) * 3
                val g = rgb[o + 1]
                if (!(g > GUIDE_EPS)) continue
                var sumR = 0f
                var sumB = 0f
                for (oy in -RADIUS..RADIUS) {
                    val yy = (y + oy).coerceIn(0, height - 1)
                    val w = k[kotlin.math.abs(oy)]
                    sumR += w * tmpR[yy * width + x]
                    sumB += w * tmpB[yy * width + x]
                }
                rgb[o] = g * sumR
                rgb[o + 2] = g * sumB
                count++
            }
        }
        return count
    }
}
