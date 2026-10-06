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
    const val MAX_RING = RawSrCoreFinish.MAX_RING

    /**
     * Heals dead lanes of [rgb] in place using [den] (both `pixels * 3`,
     * channel-minor). A lane is live iff its denominator exceeds [eps].
     * Returns the healed lane count. Reads complete before any write, so
     * repeated calls and any sharding agree. See [RawSrCoreFinish.inpaint].
     */
    fun inpaint(
        rgb: FloatArray,
        den: FloatArray,
        width: Int,
        height: Int,
        eps: Float = RawSrBayerMerge.EPS.toFloat()
    ): Int = RawSrCoreFinish.inpaint(rgb, den, width, height, eps)
}
