// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.floor

/**
 * Shared SR core: sampling coordinates (CPU donors
 * [RawSrBayerMerge]/[MosaicSrReconstructor]/[RawSrAlignmentField] delegate
 * here; the Vulkan shaders transcribe the same formulas in float32).
 *
 * One formula, two instantiations: every function below is the exact scalar
 * math moved verbatim out of the CPU merge twins, so CPU behavior is
 * identical by construction. No Android/GL dependencies; no allocation.
 */
object RawSrCoreSampling {
    /**
     * Reference flow lookup (`merge.py::cpu_accumulate`,
     * `robustness.py::cpu_warp_dogson`): the containing tile index
     * `int(lr//tile)` — plain truncation, no blending.
     */
    fun flowTileIndex(lr: Float, tileSize: Int): Int = (lr / tileSize).toInt()

    /**
     * Reference robustness fetch (`merge.py::cpu_accumulate`): the shifted
     * quad index `floor(s)` for `s = lr/2 - 1` (the integer -1 folds into
     * the floor; the N=0 lane lands on quad 0 like the reference
     * truncation, and the far edge clamps at the call site).
     */
    fun robustnessQuad(s: Double): Int = floor(s).toInt()

    /**
     * Nearest quad with the one-quad shift, sampled at ([sx], [sy]).
     * Mirrors the linear merge and mosaic twins; the two agree exactly.
     */
    fun sampleRobustness(
        r: FloatArray, width: Int, height: Int, sx: Double, sy: Double
    ): Double {
        val qx = robustnessQuad(sx).coerceIn(0, width - 1)
        val qy = robustnessQuad(sy).coerceIn(0, height - 1)
        return r[qy * width + qx].toDouble()
    }

    /** Guide/raw scale consumed by [covarianceGuideCoord] (hoisted per frame). */
    fun guideScale(guide: Int, raw: Int): Double = guide.toDouble() / raw

    /**
     * Source-anchored covariance lookup coordinate:
     * `g = source*(guide/raw) - 0.5`.
     */
    fun covarianceGuideCoord(source: Double, scale: Double): Double = source * scale - 0.5

    /**
     * Output pixel (p) centers on raw source `(p + 0.5) / factor`, like the
     * reference `lr = (hr + 0.5) / scale`.
     */
    fun sourceCenter(out: Int, factor: Double): Double = (out + 0.5) / factor

    /**
     * Flow lookup position: the reference `cpu_accumulate` flow lookup runs
     * at the raw source position ([sourceCenter]), read from the containing
     * tile with no blending. Named alias so the shader transcription cites
     * the lookup site, not the source variable.
     */
    fun flowLookupPos(out: Int, factor: Double): Double = sourceCenter(out, factor)

    /**
     * Robustness fetch position: the source pixel center
     * `s = (p + 0.5) / factor / 2 - 1` consumed by [sampleRobustness].
     */
    fun robustnessSamplePos(out: Int, factor: Double): Double = (out + 0.5) / factor / 2.0 - 1.0

    /**
     * Reference covariance interpolation + inversion (`merge.py::accumulate`):
     * sign-preserving `modf` fractions, `int()` (truncation) floors clipped
     * at 0, ceilings clipped at the far edge, row-then-column lerp order,
     * then the unconditional analytic 2x2 inverse into [out] (no PD gate:
     * edge extrapolation may go indefinite and the reference still merges
     * it). Null skips the pixel only for non-finite guide coordinates,
     * out-of-range floors, or non-finite corners (reference-undefined; the
     * estimator emits finite PD fields, so the guard never fires on real
     * inputs).
     */
    fun interpolateCovariance(
        covariance: FloatArray, guideW: Int, guideH: Int, gx: Double, gy: Double,
        out: DoubleArray
    ): DoubleArray? {
        if (!gx.isFinite() || !gy.isFinite()) return null
        // Reference modf/int semantics: the fraction keeps the sign (so the
        // sub-center edge extrapolates) and truncation clips at 0.
        // Sign-preserving remainder, exactly like C modf / Python math.modf.
        val fx = gx % 1.0
        val fy = gy % 1.0
        val x0 = (gx - fx).toInt().coerceAtLeast(0)
        val y0 = (gy - fy).toInt().coerceAtLeast(0)
        if (x0 >= guideW || y0 >= guideH) return null
        val x1 = minOf(x0 + 1, guideW - 1)
        val y1 = minOf(y0 + 1, guideH - 1)
        // Corner base offsets, computed once instead of per channel; the
        // four loads below address the same texels as the loop did.
        val b00 = (y0 * guideW + x0) * 4
        val b10 = (y0 * guideW + x1) * 4
        val b01 = (y1 * guideW + x0) * 4
        val b11 = (y1 * guideW + x1) * 4
        // Unrolled channels with the identical per-channel formula and
        // order (row-then-column lerp after the finiteness gate). Channel
        // m10 (c==2) keeps its gate but needs no lerp: the merge consumes
        // only cxx/cxy/cyy, and a non-finite corner still skips the pixel.
        val c00 = covariance[b00].toDouble()
        val c10 = covariance[b10].toDouble()
        val c01 = covariance[b01].toDouble()
        val c11 = covariance[b11].toDouble()
        if (!c00.isFinite() || !c10.isFinite() || !c01.isFinite() || !c11.isFinite()) return null
        val top0 = c00 + fx * (c10 - c00)
        val bot0 = c01 + fx * (c11 - c01)
        val cxx = top0 + fy * (bot0 - top0)
        val d00 = covariance[b00 + 1].toDouble()
        val d10 = covariance[b10 + 1].toDouble()
        val d01 = covariance[b01 + 1].toDouble()
        val d11 = covariance[b11 + 1].toDouble()
        if (!d00.isFinite() || !d10.isFinite() || !d01.isFinite() || !d11.isFinite()) return null
        val top1 = d00 + fx * (d10 - d00)
        val bot1 = d01 + fx * (d11 - d01)
        val cxy = top1 + fy * (bot1 - top1)
        if (!covariance[b00 + 2].isFinite() || !covariance[b10 + 2].isFinite() ||
            !covariance[b01 + 2].isFinite() || !covariance[b11 + 2].isFinite()
        ) return null
        val e00 = covariance[b00 + 3].toDouble()
        val e10 = covariance[b10 + 3].toDouble()
        val e01 = covariance[b01 + 3].toDouble()
        val e11 = covariance[b11 + 3].toDouble()
        if (!e00.isFinite() || !e10.isFinite() || !e01.isFinite() || !e11.isFinite()) return null
        val top3 = e00 + fx * (e10 - e00)
        val bot3 = e01 + fx * (e11 - e01)
        val cyy = top3 + fy * (bot3 - top3)
        // Reference inverts unconditionally (`inv_det = 1/det`, no PD check):
        // sub-center edge extrapolation (negative modf fractions) can yield
        // a non-PD lerp, and the reference still merges it (z clamps at 0,
        // so indefinite quads read w = 1, singular quads read w = 0 off
        // center). Skipping here would black the left/top border where the
        // reference merges. Double division by zero yields ±Inf (never
        // throws), and the tap loop's maxOf(z, 0.0) + finite checks keep the
        // weights safe exactly like the reference clamp.
        val det = cxx * cyy - cxy * cxy
        out[0] = cyy / det
        // One division, two stores: same operands, same quotient, saves a
        // per-pixel divide with bitwise-identical results.
        val negCxyOverDet = -cxy / det
        out[1] = negCxyOverDet
        out[2] = negCxyOverDet
        out[3] = cxx / det
        return out
    }
}
