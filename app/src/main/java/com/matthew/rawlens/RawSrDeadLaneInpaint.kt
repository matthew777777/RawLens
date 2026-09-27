// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Dead-lane inpaint for the Linear-RGB product: fills output lanes that had
 * no kernel support (denominator at or below [RawSrBayerMerge.EPS]) with the
 * mean of live same-lane neighbours.
 *
 * Reference context (Jamy-L `super_resolution.process`): the reference merge
 * divides raw (NaN at zero support), blacks NaN pixels, then re-mosaics the
 * RGB planes to Bayer CFA (`rggb_to_cfa`) — cross-lane dead cells are
 * discarded by construction and native-lane dead taps hide in the downstream
 * demosaic. The Linear-RGB DNG has no downstream demosaic, so its isolated
 * single-lane zeros would ship as green/magenta dots; this pass is the
 * demosaic analogue for dead lanes only. Live lanes are never touched, and
 * the Mosaic path never calls this (it ships reference-identical dead taps).
 *
 * Determinism: dead cells are collected first, fills are computed from the
 * pre-inpaint planes only (no chained fills), and ring enumeration plus
 * Float accumulation order match `inpaint_dead_lanes.glsl` exactly, so the
 * CPU and Vulkan paths agree bitwise.
 */
object RawSrDeadLaneInpaint {
    /** Rings searched for live same-lane neighbours, outermost last. */
    const val MAX_RING = 3

    /**
     * Heals dead lanes of [rgb] in place using [den] (both `pixels * 3`,
     * channel-minor). A lane is live iff its denominator exceeds [eps].
     * Returns the healed lane count. Reads complete before any write, so
     * repeated calls and any sharding agree.
     */
    fun inpaint(
        rgb: FloatArray,
        den: FloatArray,
        width: Int,
        height: Int,
        eps: Float = RawSrBayerMerge.EPS.toFloat()
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
}
