// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Shared SR core: finishing passes (CPU donors [RawSrDeadLaneInpaint] /
 * [RawSrChromaFromLuma]; the Vulkan shaders transcribe the same formulas in
 * float32).
 *
 * One formula, two instantiations: the pass bodies below are moved verbatim
 * out of the donors (inpaint ring enumeration order + sum/count, CFL 7-tap
 * weights + guide guard + two-pass order), so CPU behavior is identical by
 * construction. No Android/GL dependencies.
 */
object RawSrCoreFinish {
    /** Inpaint rings searched for live same-lane neighbours, outermost last. */
    const val MAX_RING = 3

    /** CFL separable Gaussian sigma in pixels. */
    const val SIGMA = 1.0f

    /** CFL radius of the 7-tap separable kernel. */
    const val RADIUS = 3

    /**
     * CFL normalized guide floor: center pixels at/below this keep their
     * original R/B (pure black; nothing to rebuild there).
     */
    const val GUIDE_EPS = 1e-4f

    /**
     * CFL normalized 7-tap weights, offsets 0..3 (mirrored). Identical
     * decimal strings to the GLSL twin so both sides parse bitwise-equal
     * Floats.
     */
    val KERNEL = floatArrayOf(0.399050279652f, 0.242036229376f, 0.0540055826224f, 0.00443304817524f)

    /**
     * Heals dead lanes of [rgb] in place using [den] (both `pixels * 3`,
     * channel-minor). A lane is live iff its denominator exceeds [eps].
     * Returns the healed lane count. Reads complete before any write, so
     * repeated calls and any sharding agree.
     *
     * Determinism: dead cells are collected first, fills are computed from
     * the pre-inpaint planes only (no chained fills), and ring enumeration
     * plus Float accumulation order match `inpaint_dead_lanes.glsl` exactly.
     */
    fun inpaint(
        rgb: FloatArray,
        den: FloatArray,
        width: Int,
        height: Int,
        eps: Float
    ): Int {
        require(width > 0 && height > 0)
        require(rgb.size == width * height * 3) { "rgb must hold 3 lanes per pixel" }
        require(den.size == rgb.size) { "denominator must match rgb" }
        // Phase 1: collect dead (pixel, lane) pairs in scan order.
        var deadCount = 0
        for (o in den.indices) if (!(den[o] > eps)) deadCount++
        if (deadCount == 0) return 0
        val dead = IntArray(deadCount * 2)
        var n = 0
        for (o in den.indices) if (!(den[o] > eps)) {
            dead[n++] = o / 3
            dead[n++] = o % 3
        }
        // Phase 2+3: compute fills from the untouched planes, then apply.
        val fills = FloatArray(deadCount)
        val healed = BooleanArray(deadCount)
        for (i in 0 until deadCount) {
            val p = dead[i * 2]
            val c = dead[i * 2 + 1]
            val x = p % width
            val y = p / width
            var sum = 0f
            var count = 0
            var ring = 1
            while (ring <= MAX_RING) {
                sum = 0f
                count = 0
                // Fixed row-major perimeter order, mirroring the shader:
                // top row, bottom row, then the open sides top-to-bottom.
                for (ox in -ring..ring) {
                    count += tap(rgb, den, x + ox, y - ring, c, width, height, eps) { sum += it }
                    count += tap(rgb, den, x + ox, y + ring, c, width, height, eps) { sum += it }
                }
                for (oy in -ring + 1..ring - 1) {
                    count += tap(rgb, den, x - ring, y + oy, c, width, height, eps) { sum += it }
                    count += tap(rgb, den, x + ring, y + oy, c, width, height, eps) { sum += it }
                }
                if (count > 0) break
                ring++
            }
            if (count > 0) {
                fills[i] = sum / count
                healed[i] = true
            }
        }
        var healedCount = 0
        for (i in 0 until deadCount) if (healed[i]) {
            rgb[dead[i * 2] * 3 + dead[i * 2 + 1]] = fills[i]
            healedCount++
        }
        return healedCount
    }

    /**
     * Adds one neighbour tap to the running sum when in bounds and live.
     * Returns 1 for a consumed tap, 0 otherwise. Inline: no per-tap lambda
     * allocation (the call sites are monomorphic hot loops).
     */
    private inline fun tap(
        rgb: FloatArray,
        den: FloatArray,
        x: Int,
        y: Int,
        c: Int,
        width: Int,
        height: Int,
        eps: Float,
        consume: (Float) -> Unit
    ): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        val o = (y * width + x) * 3 + c
        if (!(den[o] > eps)) return 0
        consume(rgb[o])
        return 1
    }

    /**
     * Chroma-from-luma stabilization: rebuilds the R and B lanes as
     * `G + smooth(R-G)` / `G + smooth(B-G)` with a separable sigma-1.0
     * Gaussian (radius 3) over the color differences, stabilizing [rgb]
     * (`pixels * 3`, channel-minor) in place. G is never touched. Returns
     * the count of pixels whose lanes were rebuilt (guide above
     * [GUIDE_EPS]).
     *
     * Difference domain, not ratios: smoothing R-G then re-adding the
     * guide cannot amplify (a convex blend of differences plus G), while
     * smoothing R/G explodes where G pits to ~zero in shadows and sprays
     * magenta donuts. No division anywhere, so black taps need no guard.
     *
     * Determinism: clamped borders, center pixels at/below [GUIDE_EPS]
     * keep their original lanes, and Float accumulation in fixed tap
     * order matches `chroma_from_luma.glsl` op for op (up to GPU FMA
     * contraction, ~1ulp).
     */
    fun stabilize(rgb: FloatArray, width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        require(rgb.size == width * height * 3) { "rgb must hold 3 lanes per pixel" }
        val k = KERNEL
        // Horizontal pass into difference temps.
        val tmpR = FloatArray(width * height)
        val tmpB = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0f
                var sumB = 0f
                for (ox in -RADIUS..RADIUS) {
                    val xx = (x + ox).coerceIn(0, width - 1)
                    val o = (y * width + xx) * 3
                    val w = k[kotlin.math.abs(ox)]
                    sumR += w * (rgb[o] - rgb[o + 1])
                    sumB += w * (rgb[o + 2] - rgb[o + 1])
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
                rgb[o] = g + sumR
                rgb[o + 2] = g + sumB
                count++
            }
        }
        return count
    }
}
