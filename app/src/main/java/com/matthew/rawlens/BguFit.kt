// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.ceil
import kotlin.math.round

/**
 * Fitted bilateral grid of 3x4 affine matrices from [BguFit.fit].
 *
 * Geometry contract (shared with tools/halide/generators/bgu_fit.cpp):
 * cell (x, y, z) centers on low-res (x*s, y*s) and luma z*r. Storage is the
 * default Halide output layout (x stride 1, channel outermost):
 * index = x + gw * (y + gh * (z + gz * ch)), ch = row * 4 + col.
 */
data class BguGrid(
    val gw: Int,
    val gh: Int,
    val gz: Int,
    val coeffs: FloatArray
) {
    init {
        require(gw > 0 && gh > 0 && gz > 0)
        require(coeffs.size == gw * gh * gz * 12)
    }

    /** Coefficient (row 0..2, col 0..3) of cell (x, y, z). */
    fun at(x: Int, y: Int, z: Int, row: Int, col: Int): Float {
        require(x in 0 until gw && y in 0 until gh && z in 0 until gz)
        require(row in 0..2 && col in 0..3)
        return coeffs[x + gw * (y + gh * (z + gz * (row * 4 + col)))]
    }

    override fun equals(other: Any?): Boolean =
        other is BguGrid && gw == other.gw && gh == other.gh && gz == other.gz &&
            coeffs.contentEquals(other.coeffs)

    override fun hashCode(): Int =
        31 * (31 * (31 * gw + gh) + gz) + coeffs.contentHashCode()
}

/**
 * BGU bilateral-grid fit facade: runs the Halide AOT [bgu_fit] filter that
 * solves per-cell 3x4 affine matrices mapping a low-res guide pair to a
 * developed pair. Shares the rawLensBgu library with [BguSpike].
 */
internal object BguFit {
    /** Default spatial bin (low-res px), luma bin size, and regularization. */
    const val DEFAULT_S = 16
    const val DEFAULT_R = 1f / 8
    const val DEFAULT_LAMBDA = 1e-6f

    val available: Boolean = BguSpike.available

    /**
     * Grid extents for a [w] x [h] low-res pair: ceil(w/s), ceil(h/s),
     * round(1/r) + 1 (z covers bin indices 0..1/r inclusive). Pure.
     */
    fun gridDims(w: Int, h: Int, s: Int = DEFAULT_S, r: Float = DEFAULT_R): IntArray {
        require(w > 0 && h > 0 && s >= 1 && r > 0f && r.isFinite())
        return intArrayOf(
            ceil(w.toDouble() / s).toInt(),
            ceil(h.toDouble() / s).toInt(),
            round(1.0 / r).toInt() + 1
        )
    }

    /**
     * Fit a grid mapping [guide] to [developed] (both [w] x [h] x 3 float
     * RGB, Halide planar: index = ch * w * h + y * w + x). Returns null when
     * unavailable, on shape mismatch, or on filter failure.
     */
    fun fit(
        guide: FloatArray,
        developed: FloatArray,
        w: Int,
        h: Int,
        s: Int = DEFAULT_S,
        r: Float = DEFAULT_R,
        lambda: Float = DEFAULT_LAMBDA
    ): BguGrid? {
        if (!available) return null
        if (guide.size != w * h * 3 || developed.size != w * h * 3) return null
        val dims = gridDims(w, h, s, r)
        val coeffs = FloatArray(dims[0] * dims[1] * dims[2] * 12)
        val rc = fitInto(guide, developed, w, h, coeffs, s, r, lambda)
        if (rc != 0) return null
        return BguGrid(dims[0], dims[1], dims[2], coeffs)
    }

    /**
     * Zero-alloc fit into a caller buffer (fit hot loop reuses it).
     * [coeffsOut] must be gw*gh*gz*12 for gridDims(w, h, s, r). Returns the
     * filter status (0 = ok).
     */
    fun fitInto(
        guide: FloatArray,
        developed: FloatArray,
        w: Int,
        h: Int,
        coeffsOut: FloatArray,
        s: Int = DEFAULT_S,
        r: Float = DEFAULT_R,
        lambda: Float = DEFAULT_LAMBDA
    ): Int {
        if (!available) return -1
        if (guide.size != w * h * 3 || developed.size != w * h * 3) return -1
        val dims = gridDims(w, h, s, r)
        if (coeffsOut.size != dims[0] * dims[1] * dims[2] * 12) return -1
        return fitIntoNative(
            guide, developed, w, h, s, r, lambda, dims[0], dims[1], dims[2], coeffsOut
        )
    }

    private external fun fitIntoNative(
        guide: FloatArray, developed: FloatArray, w: Int, h: Int,
        s: Int, r: Float, lambda: Float, gw: Int, gh: Int, gz: Int,
        coeffsOut: FloatArray
    ): Int
}

/**
 * Fit hot-loop buffer pool: reuses the look frame pair (fit-thread-local,
 * consumed synchronously) and ping-pongs two grid coefficient buffers (the
 * render thread uploads the published one while the next fit writes the
 * other). Reallocates only when dims change. Single-thread confined; the
 * ping-pong assumes a grid upload completes well within one fit period (a
 * torn upload would self-heal on the next publish).
 */
internal class BguFitBuffers {
    private var fw = 0
    private var fh = 0
    private var guide = FloatArray(0)
    private var developed = FloatArray(0)
    private var gw = 0
    private var gh = 0
    private var gz = 0
    private var grids = arrayOf(FloatArray(0), FloatArray(0))
    private var back = 0

    fun frame(w: Int, h: Int): BguLookOut {
        if (w != fw || h != fh) {
            guide = FloatArray(w * h * 3)
            developed = FloatArray(w * h * 3)
            fw = w
            fh = h
        }
        return BguLookOut(guide, developed, w, h)
    }

    fun backGrid(gw: Int, gh: Int, gz: Int): FloatArray {
        if (gw != this.gw || gh != this.gh || gz != this.gz) {
            val n = gw * gh * gz * 12
            grids = arrayOf(FloatArray(n), FloatArray(n))
            this.gw = gw
            this.gh = gh
            this.gz = gz
            back = 0
        }
        return grids[back]
    }

    fun flip() {
        back = 1 - back
    }
}
