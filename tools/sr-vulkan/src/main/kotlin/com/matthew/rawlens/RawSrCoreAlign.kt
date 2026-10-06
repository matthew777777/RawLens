// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Shared SR core: alignment costs, ICA refinement, inter-level upscaling
 * (CPU donor [RawSrAlignment]; the Vulkan shaders transcribe the same
 * formulas in float32).
 *
 * One formula, two instantiations: every function below is the exact scalar
 * math moved verbatim out of the donor (L1/L2 block-match costs, ICA
 * refine step including the transcribed tile-8 sampler-clamp / tree-clip
 * quirks — tile 64 is standard zero-fill sampling with the step clip
 * disabled at 32767, exactly as the reference `cpu_ica` runs it —,
 * auxiliary reliability, NEAREST / BILINEAR / BICUBIC upscaling), so CPU
 * behavior is identical by construction. The [RawSrAlignment.alignPair]
 * orchestration and its quirk comments stay in the donor. No
 * Android/GL dependencies.
 */
object RawSrCoreAlign {
    /**
     * Reference `utils.round_half_away` (CUDA round() semantics): halves round
     * away from zero, unlike [Math.rint] (halves to even). Shared by L1 seeds
     * and the [RawSrCoreRobustness] Dogson warp centers.
     */
    fun roundHalfAway(x: Double): Int =
        if (x >= 0.0) floor(x + 0.5).toInt() else ceil(x - 0.5).toInt()

    /**
     * L1 candidate cost (reference finest level): SAD with zero-filled
     * out-of-image moving taps. ([offX], [offY] are the seed + candidate
     * integer offsets.)
     */
    fun blockCostL1(
        ref: RawSrGrayImage, mov: RawSrGrayImage,
        tx: Int, ty: Int, tileSize: Int, offX: Int, offY: Int
    ): Double {
        var sad = 0.0
        for (y in 0 until tileSize) {
            val my = ty * tileSize + y + offY
            for (x in 0 until tileSize) {
                val mx = tx * tileSize + x + offX
                val m = if (my in 0 until mov.height && mx in 0 until mov.width)
                    mov.values[my * mov.width + mx].toDouble() else 0.0
                sad += abs(ref.values[(ty * tileSize + y) * ref.width + tx * tileSize + x] - m)
            }
        }
        return sad
    }

    /**
     * L2 candidate cost (reference coarse levels): SSD with edge-clamped
     * moving taps.
     */
    fun blockCostL2(
        ref: RawSrGrayImage, mov: RawSrGrayImage,
        tx: Int, ty: Int, tileSize: Int, offX: Int, offY: Int
    ): Double {
        var ssd = 0.0
        for (y in 0 until tileSize) {
            val my = (ty * tileSize + y + offY).coerceIn(0, mov.height - 1)
            for (x in 0 until tileSize) {
                val mx = (tx * tileSize + x + offX).coerceIn(0, mov.width - 1)
                val e = ref.values[(ty * tileSize + y) * ref.width + tx * tileSize + x] -
                    mov.values[my * mov.width + mx]
                ssd += e * e
            }
        }
        return ssd
    }

    /** Zero-filled bilinear moving sample (ICA sizes 16/32/64 taps). */
    fun sampleZeroFilled(mov: RawSrGrayImage, x: Double, y: Double): Double {
        val floorX = floor(x).toInt()
        val floorY = floor(y).toInt()
        val fracX = x - floorX
        val fracY = y - floorY
        fun tap(tx: Int, ty: Int): Double =
            if (ty in 0 until mov.height && tx in 0 until mov.width)
                mov.values[ty * mov.width + tx].toDouble() else 0.0
        val top = tap(floorX, floorY) + (tap(floorX + 1, floorY) - tap(floorX, floorY)) * fracX
        val bot = tap(floorX, floorY + 1) + (tap(floorX + 1, floorY + 1) - tap(floorX, floorY + 1)) * fracX
        return top + (bot - top) * fracY
    }

    /**
     * Edge-clamped bilinear moving sample (ICA size 8; frac from the
     * unclamped floor, ceil from the CLAMPED floor — reference order).
     */
    fun sampleClamped(mov: RawSrGrayImage, x: Double, y: Double): Double {
        val floorX = floor(x).toInt()
        val floorY = floor(y).toInt()
        val fracX = x - floorX
        val fracY = y - floorY
        val fx0 = floorX.coerceIn(0, mov.width - 1)
        val fy0 = floorY.coerceIn(0, mov.height - 1)
        val fx1 = (fx0 + 1).coerceIn(0, mov.width - 1)
        val fy1 = (fy0 + 1).coerceIn(0, mov.height - 1)
        val m00 = mov.values[fy0 * mov.width + fx0].toDouble()
        val m01 = mov.values[fy0 * mov.width + fx1].toDouble()
        val m10 = mov.values[fy1 * mov.width + fx0].toDouble()
        val m11 = mov.values[fy1 * mov.width + fx1].toDouble()
        val top = m00 + (m01 - m00) * fracX
        val bot = m10 + (m11 - m10) * fracX
        return top + (bot - top) * fracY
    }

    /**
     * 8-px tree reduction with per-round partial clipping (reference
     * `cpu_ica` tile-8 branch): N = 32,16,8,4,2,1; s[tid] += clip(s[tid+N], +-radius).
     * Returns the clipped (B0, B1); the step itself stays unclipped.
     */
    fun treeClippedSums(terms: DoubleArray, radius: Int): Pair<Double, Double> {
        val acc = terms.copyOf()
        var n = 32
        while (n > 0) {
            for (tid in 0 until n) {
                acc[tid * 2] += acc[(tid + n) * 2].coerceIn(-radius.toDouble(), radius.toDouble())
                acc[tid * 2 + 1] += acc[(tid + n) * 2 + 1].coerceIn(-radius.toDouble(), radius.toDouble())
            }
            n /= 2
        }
        return acc[0] to acc[1]
    }

    /**
     * Steepest-descent sums over one tile: (B0, B1, per-term table or null).
     * Sizes 16/32/64 sample zero-filled per pixel (reference `cpu_ica`
     * unified branch; the retired CUDA `ica_kernel_64` sliding window with
     * its floor+2 off-by-one no longer exists in the reference); size 8
     * clamps its sampler and records per-term tables for [treeClippedSums].
     */
    fun steepestSums(
        ref: RawSrGrayImage, mov: RawSrGrayImage,
        gx: DoubleArray, gy: DoubleArray,
        tx: Int, ty: Int, tileSize: Int, flowX: Double, flowY: Double
    ): Triple<Double, Double, DoubleArray?> {
        var sumX = 0.0
        var sumY = 0.0
        val terms = if (tileSize == 8) DoubleArray(64 * 2) else null
        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                val px = tx * tileSize + x
                val py = ty * tileSize + y
                val interp = if (tileSize == 8) sampleClamped(mov, px + flowX, py + flowY)
                else sampleZeroFilled(mov, px + flowX, py + flowY)
                val ro = py * ref.width + px
                val e = interp - ref.values[ro]
                val bx = -gx[ro] * e
                val by = -gy[ro] * e
                sumX += bx
                sumY += by
                if (terms != null) {
                    terms[(y * tileSize + x) * 2] = bx
                    terms[(y * tileSize + x) * 2 + 1] = by
                }
            }
        }
        return Triple(sumX, sumY, terms)
    }

    /**
     * One inverse-compositional step `H^-1 B` (unclipped; the caller clips
     * to +-radius except for tile 8, whose tree-clipped sums take the step
     * as-is, and tile 64, whose clip is disabled at 32767).
     */
    fun icaStep(
        detInv: Double,
        h00: Double, h01: Double, h11: Double,
        b0: Double, b1: Double
    ): Pair<Double, Double> {
        val stepX = detInv * (h11 * b0 - h01 * b1)
        val stepY = detInv * (-h01 * b0 + h00 * b1)
        return stepX to stepY
    }

    /** Mean absolute residual of one tile under ([flowX], [flowY]). */
    fun meanAbsidual(
        ref: RawSrGrayImage, mov: RawSrGrayImage,
        tx: Int, ty: Int, tileSize: Int, flowX: Double, flowY: Double
    ): Double {
        var acc = 0.0
        for (y in 0 until tileSize) {
            for (x in 0 until tileSize) {
                val px = tx * tileSize + x
                val py = ty * tileSize + y
                val interp = if (tileSize == 8) sampleClamped(mov, px + flowX, py + flowY)
                else sampleZeroFilled(mov, px + flowX, py + flowY)
                acc += abs(interp - ref.values[py * ref.width + px])
            }
        }
        return acc / (tileSize * tileSize)
    }

    /**
     * Inter-level flow propagation (reference `upscale_lvl`): null prior
     * yields zeros; otherwise interpolate by [repeat] =
     * factor / (newTileSize / prevTileSize), scale by [factor], then
     * zero-pad (or crop, F.pad semantics) to ([columns], [rows]).
     * Coordinates follow align_corners=False with edge-clamped reads;
     * bicubic uses Keys a=-0.75 like torch.
     *
     * The reference only ever pads (it never crops an overshoot): the
     * coarse grid derives from the fine grid by valid convolution plus
     * strided take, both floors-only, so priorTiles * repeat <= tiles on
     * every real grid (swept over raw sizes x {16, 32, 64}; the bound is
     * strict because valid convolution removes 2r > 0 taps). The loop
     * bounds below therefore crop nothing on real grids and the trailing
     * zeros coincide exactly with the reference F.pad.
     */
    fun upsampleFlow(
        prior: RawSrAlignment.LevelFlow?, columns: Int, rows: Int, factor: Int,
        newTileSize: Int, prevTileSize: Int,
        mode: RawSrAlignmentConfig.FlowUpscaleMode
    ): RawSrAlignment.LevelFlow {
        if (prior == null) return RawSrAlignment.LevelFlow(columns, rows, DoubleArray(columns * rows * 2))
        val repeat = factor / (newTileSize / prevTileSize)
        val upW = prior.columns * repeat
        val upH = prior.rows * repeat
        val scaled = DoubleArray(columns * rows * 2)
        for (ty in 0 until rows) {
            for (tx in 0 until columns) {
                if (tx < upW && ty < upH) {
                    scaled[(ty * columns + tx) * 2] =
                        interpolateChannel(prior, 0, tx, ty, repeat, mode) * factor
                    scaled[(ty * columns + tx) * 2 + 1] =
                        interpolateChannel(prior, 1, tx, ty, repeat, mode) * factor
                }
                // else zero (F.pad constant 0; oversized grids crop by loop bounds)
            }
        }
        return RawSrAlignment.LevelFlow(columns, rows, scaled)
    }

    private fun interpolateChannel(
        prior: RawSrAlignment.LevelFlow, channel: Int, tx: Int, ty: Int, repeat: Int,
        mode: RawSrAlignmentConfig.FlowUpscaleMode
    ): Double {
        fun tap(ix: Int, iy: Int): Double {
            val cx = ix.coerceIn(0, prior.columns - 1)
            val cy = iy.coerceIn(0, prior.rows - 1)
            return prior.values[(cy * prior.columns + cx) * 2 + channel]
        }
        return when (mode) {
            RawSrAlignmentConfig.FlowUpscaleMode.NEAREST -> tap(tx / repeat, ty / repeat)
            RawSrAlignmentConfig.FlowUpscaleMode.BILINEAR -> {
                val sx = (tx + 0.5) / repeat - 0.5
                val sy = (ty + 0.5) / repeat - 0.5
                val x0 = floor(sx).toInt()
                val y0 = floor(sy).toInt()
                val fx = sx - x0
                val fy = sy - y0
                val top = tap(x0, y0) + (tap(x0 + 1, y0) - tap(x0, y0)) * fx
                val bot = tap(x0, y0 + 1) + (tap(x0 + 1, y0 + 1) - tap(x0, y0 + 1)) * fx
                top + (bot - top) * fy
            }
            RawSrAlignmentConfig.FlowUpscaleMode.BICUBIC -> {
                val sx = (tx + 0.5) / repeat - 0.5
                val sy = (ty + 0.5) / repeat - 0.5
                val x0 = floor(sx).toInt()
                val y0 = floor(sy).toInt()
                val fx = sx - x0
                val fy = sy - y0
                var acc = 0.0
                for (j in -1..2) {
                    var row = 0.0
                    for (i in -1..2) {
                        row += tap(x0 + i, y0 + j) * cubicKeys(abs(i - fx))
                    }
                    acc += row * cubicKeys(abs(j - fy))
                }
                acc
            }
        }
    }

    /** Bicubic Keys kernel with torch's a = -0.75. */
    private fun cubicKeys(x: Double): Double {
        val a = -0.75
        return if (x <= 1.0) {
            (a + 2.0) * x * x * x - (a + 3.0) * x * x + 1.0
        } else if (x < 2.0) {
            a * x * x * x - 5.0 * a * x * x + 8.0 * a * x - 4.0 * a
        } else {
            0.0
        }
    }

    /**
     * Auxiliary per-tile reliability for frame rejection only: the Hessian
     * solved, the flow is finite, and the post-ICA residual passes the
     * gate. Flows never consult this.
     */
    fun auxiliaryReliable(residual: Double, detOk: Boolean, dx: Double, dy: Double, config: RawSrAlignmentConfig): Boolean {
        return detOk && dx.isFinite() && dy.isFinite() && residual <= config.maxMeanAbsoluteResidual
    }
}
