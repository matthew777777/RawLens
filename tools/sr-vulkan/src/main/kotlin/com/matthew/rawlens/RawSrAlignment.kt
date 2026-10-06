// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.DoubleBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Checkout port of the Jamy-L alignment stage
 * (`alignment.py`, `block_matching.py`, `ICA.py`, `utils_image.py` at the
 * pinned commit): FFT full-resolution grey, circular padding, valid-convolution
 * Gaussian pyramid, L1 (finest) / direct-SSD L2 (coarse) block matching,
 * inverse-compositional refinement on every level, and dense inter-level
 * flow upscaling. Flows are RAW pixels on a RAW lattice throughout (the
 * reference's convention: its default FFT grey keeps full resolution).
 *
 * Reference defaults are the defaults here: 4 levels, factors [1,2,4,4],
 * radii [1,4,4,4] (fine level always 1), per-level tile sizes
 * [ts,ts,ts,ts/2], 3 ICA iterations, bilinear upscaling. Tile size is
 * SNR-driven ({64,32,16} raw px) via [RawSrTuning.alignmentConfig].
 *
 * Deliberate, documented deviations from the checkout text:
 * - L2 uses direct spatial SSD instead of the FFT-correlation formulation.
 *   The scores agree to float rounding (neither side can match cuFFT/cuBLAS
 *   bitwise anyway) and the argmin agrees except at chaos ties; direct sums
 *   keep the CPU oracle and the GPU path bitwise-agreeable where the two FFT
 *   implementations could never be.
 * - The 8-px ICA tree-reduction clipping is transcribed structurally but the
 *   sums run sequentially in double (CUDA warp/tree order is 1-ulp class).
 * - CPU arithmetic runs in double precision (the reference runs float32
 *   CUDA): more accurate, agreeing to ~1e-6; flows narrow to float on
 *   output. Flat-area argmin ties can flip between sides (either minimum is
 *   a valid argmin); flow-agreement tests use textured fixtures.
 * - A per-tile auxiliary reliability (Hessian solvability + post-ICA
 *   residual) feeds frame rejection only; it never alters a flow. The
 *   reference has no reverse pass, no consistency check, and no reliability,
 *   and neither does the flow path here.
 * - Transcribed reference quirks (commented at each site): tile-8 ICA clamps
 *   its sampler and tree-clips its reduction partials while larger sizes
 *   zero-fill and clip the step; tile-64 ICA additionally disables the step
 *   clip at 32767 (reference `align_lvl_ica`).
 */
/** Full-resolution single-channel image (FFT grey or one pyramid level). */
data class RawSrGrayImage(val width: Int, val height: Int, val values: FloatArray) {
    init { require(width > 0 && height > 0 && values.size == width * height) }
    operator fun get(x: Int, y: Int): Float = values[y * width + x]
}

data class RawSrAlignmentConfig(
    val levels: Int = 4,
    /** Raw-pixel tile size; the reference SNR schedule uses {64, 32, 16}. */
    val tileSize: Int = 16,
    /** Coarse-level search radius (the fine level always uses 1). */
    val searchRadius: Int = 4,
    val lkIterations: Int = 3,
    /**
     * Post-ICA mean-absolute-residual gate for the auxiliary per-tile
     * reliability consumed by frame rejection. Flows never consult it.
     */
    val maxMeanAbsoluteResidual: Double = 0.12,
    /**
     * Inter-level flow propagation (Jamy-L `flow_upscale_mode`):
     * torch.nn.functional.interpolate semantics (align_corners=False,
     * edge-clamped reads) with bicubic Keys a=-0.75. BILINEAR is the
     * unified JAMY-L default (reference
     * `AlignmentConfig.flow_upscale_mode` is bilinear); NEAREST/BICUBIC
     * stay explicit opt-ins for A/B only. A silent default flip moves both
     * CPU and GPU flows (pinned by `flowUpscaleDefaultsToBilinearLikeReference`).
     */
    val flowUpscale: FlowUpscaleMode = FlowUpscaleMode.BILINEAR
) {
    init {
        require(levels == 4) { "The checkout pyramid has exactly 4 levels" }
        require(tileSize in setOf(16, 32, 64)) { "Reference tile sizes are {64, 32, 16}" }
        require(searchRadius in 1..6)
        require(lkIterations in 1..5)
        require(maxMeanAbsoluteResidual.isFinite() && maxMeanAbsoluteResidual > 0.0)
    }
    /** Finest-first schedule: [1, searchRadius, searchRadius, searchRadius]. */
    fun radiusAt(level: Int) = if (level == 0) 1 else searchRadius
    /** Jamy-L [1,2,4,4] reduction schedule (downsample factor into [level]). */
    fun factorAt(level: Int) = FACTORS[level]
    /** Per-level tile sizes [ts,ts,ts,ts/2] (raw px). */
    fun tileSizeAt(level: Int) = if (level == LEVELS - 1) tileSize / 2 else tileSize

    /** Inter-level flow propagation mode; see [flowUpscale]. */
    enum class FlowUpscaleMode { NEAREST, BILINEAR, BICUBIC }

    companion object {
        const val LEVELS = 4
        val FACTORS = intArrayOf(1, 2, 4, 4)
    }
}

data class RawSrTileFlow(
    val centerX: Float,
    val centerY: Float,
    /** Displacement in raw pixels: moving(x + dx, y + dy) matches reference(x,y). */
    val dx: Float,
    val dy: Float,
    /** Post-ICA mean absolute residual over the tile (auxiliary diagnostic). */
    val residual: Float,
    /** Auxiliary reliability for frame rejection (never alters flows). */
    val reliable: Boolean
)

/**
 * Direct (allocation-free) tile reads for flat-backed flow fields. [RawSrMergeJob]'s
 * quad-upsampled fields hold ~3.1M tiles; boxing one [RawSrTileFlow] per tile access
 * allocates ~6 objects per merged pixel per frame and stalls the merge in GC.
 * Implementations back the [List] contract with flat arrays and expose the same
 * values without boxing. [RawSrAlignmentField.flowAtSmoothInto] prefers this path
 * and produces bitwise-identical results to [RawSrAlignmentField.flowAtSmooth].
 */
interface RawSrDirectFlowTiles {
    fun directDx(index: Int): Float
    fun directDy(index: Int): Float
    fun directResidual(index: Int): Float
    fun directReliable(index: Int): Boolean
}

data class RawSrAlignmentField(
    /** Raw-pixel image width covered by the field. */
    val imageWidth: Int,
    /** Raw-pixel image height covered by the field. */
    val imageHeight: Int,
    /** Raw-pixel tile size. */
    val tileSize: Int,
    val columns: Int,
    val rows: Int,
    val tiles: List<RawSrTileFlow>
) {
    init { require(tiles.size == columns * rows) }

    fun flowAt(x: Float, y: Float): RawSrTileFlow {
        val tx = RawSrCoreSampling.flowTileIndex(x, tileSize).coerceIn(0, columns - 1)
        val ty = RawSrCoreSampling.flowTileIndex(y, tileSize).coerceIn(0, rows - 1)
        return tiles[ty * columns + tx]
    }

    /**
     * Reference flow lookup (`merge.py::cpu_accumulate`,
     * `robustness.py::cpu_warp_dogson`): the containing tile's raw-unit
     * vector, no blending (`px = int(lr_x//tile_size)` — plain
     * truncation, one tile per pixel). The merge and the robustness warp
     * consume this verbatim: blending neighbor tiles at a flow
     * discontinuity invents a warp no tile estimated, and on periodic
     * texture the blend lands on a lookalike slat that over-accepts
     * (white-blinds pits). Allocation-free twin of [flowAt]: writes dx,
     * dy, residual, reliability (1f/0f) into [out] (size >= 4). Flat-
     * backed fields ([RawSrDirectFlowTiles]) read without boxing.
     */
    fun flowAtNearestInto(x: Float, y: Float, out: FloatArray) {
        val tx = RawSrCoreSampling.flowTileIndex(x, tileSize).coerceIn(0, columns - 1)
        val ty = RawSrCoreSampling.flowTileIndex(y, tileSize).coerceIn(0, rows - 1)
        val index = ty * columns + tx
        val direct = tiles as? RawSrDirectFlowTiles
        if (direct != null) {
            out[0] = direct.directDx(index)
            out[1] = direct.directDy(index)
            out[2] = direct.directResidual(index)
            out[3] = if (direct.directReliable(index)) 1f else 0f
        } else {
            val t = tiles[index]
            out[0] = t.dx
            out[1] = t.dy
            out[2] = t.residual
            out[3] = if (t.reliable) 1f else 0f
        }
    }

    /**
     * Raw-lattice coverage: the field spans ([rawW], [rawH]) plus less than
     * one tile of circular-pad slack per axis ([circularPad] rounds up to a
     * tile multiple). Consumers require this instead of exact equality so
     * padded crops keep working.
     */
    fun coversRaw(rawW: Int, rawH: Int): Boolean =
        imageWidth >= rawW && imageWidth - rawW < tileSize &&
            imageHeight >= rawH && imageHeight - rawH < tileSize

    /**
     * Bilinear flow blend over the tile lattice (dormant A/B helper now:
     * the merge and the robustness warp consume the reference nearest
     * lookup, [flowAtNearestInto]; only [RawSrMergeJob.upsampleFlowToQuads]
     * still blends). Coordinates are raw pixels. Tile centers sit at
     * integer lattice points of u = (p + 0.5) / tileSize - 0.5; the four
     * surrounding tiles blend dx/dy, and confidence blends the reliable
     * bits with a 0.5 gate. Any non-finite corner flow falls back to the
     * containing tile.
     */
    fun flowAtSmooth(x: Float, y: Float): RawSrTileFlow {
        val ux = (x + 0.5f) / tileSize - 0.5f
        val uy = (y + 0.5f) / tileSize - 0.5f
        val x0 = floor(ux).toInt()
        val y0 = floor(uy).toInt()
        val fx = (ux - x0).coerceIn(0f, 1f)
        val fy = (uy - y0).coerceIn(0f, 1f)
        val xa = x0.coerceIn(0, columns - 1)
        val xb = (x0 + 1).coerceIn(0, columns - 1)
        val ya = y0.coerceIn(0, rows - 1)
        val yb = (y0 + 1).coerceIn(0, rows - 1)
        val t00 = tiles[ya * columns + xa]
        val t10 = tiles[ya * columns + xb]
        val t01 = tiles[yb * columns + xa]
        val t11 = tiles[yb * columns + xb]
        if (!t00.dx.isFinite() || !t00.dy.isFinite() ||
            !t10.dx.isFinite() || !t10.dy.isFinite() ||
            !t01.dx.isFinite() || !t01.dy.isFinite() ||
            !t11.dx.isFinite() || !t11.dy.isFinite()
        ) return flowAt(x, y)
        val w00 = (1f - fx) * (1f - fy)
        val w10 = fx * (1f - fy)
        val w01 = (1f - fx) * fy
        val w11 = fx * fy
        fun blend(get: (RawSrTileFlow) -> Float): Float =
            get(t00) * w00 + get(t10) * w10 + get(t01) * w01 + get(t11) * w11
        val conf = blend { if (it.reliable) 1f else 0f }
        return RawSrTileFlow(
            centerX = x,
            centerY = y,
            dx = blend { it.dx },
            dy = blend { it.dy },
            residual = flowAt(x, y).residual,
            reliable = conf >= 0.5f
        )
    }

    /**
     * Allocation-free twin of [flowAtSmooth]: writes dx, dy, residual (nearest
     * containing tile, same as [flowAtSmooth]), and reliability (1f/0f) into
     * [out] (size >= 4). Same formula, same order, same fallback — bitwise
     * identical to [flowAtSmooth]. Flat-backed fields ([RawSrDirectFlowTiles])
     * are read without boxing; other lists read through [tiles].
     */
    fun flowAtSmoothInto(x: Float, y: Float, out: FloatArray) {
        val ux = (x + 0.5f) / tileSize - 0.5f
        val uy = (y + 0.5f) / tileSize - 0.5f
        val x0 = floor(ux).toInt()
        val y0 = floor(uy).toInt()
        val fx = (ux - x0).coerceIn(0f, 1f)
        val fy = (uy - y0).coerceIn(0f, 1f)
        val xa = x0.coerceIn(0, columns - 1)
        val xb = (x0 + 1).coerceIn(0, columns - 1)
        val ya = y0.coerceIn(0, rows - 1)
        val yb = (y0 + 1).coerceIn(0, rows - 1)
        val direct = tiles as? RawSrDirectFlowTiles
        val i00 = ya * columns + xa
        val i10 = ya * columns + xb
        val i01 = yb * columns + xa
        val i11 = yb * columns + xb
        val dx00: Float; val dy00: Float; val dx10: Float; val dy10: Float
        val dx01: Float; val dy01: Float; val dx11: Float; val dy11: Float
        val c00: Float; val c10: Float; val c01: Float; val c11: Float
        if (direct != null) {
            dx00 = direct.directDx(i00); dy00 = direct.directDy(i00)
            dx10 = direct.directDx(i10); dy10 = direct.directDy(i10)
            dx01 = direct.directDx(i01); dy01 = direct.directDy(i01)
            dx11 = direct.directDx(i11); dy11 = direct.directDy(i11)
            c00 = if (direct.directReliable(i00)) 1f else 0f
            c10 = if (direct.directReliable(i10)) 1f else 0f
            c01 = if (direct.directReliable(i01)) 1f else 0f
            c11 = if (direct.directReliable(i11)) 1f else 0f
        } else {
            val t00 = tiles[i00]; val t10 = tiles[i10]
            val t01 = tiles[i01]; val t11 = tiles[i11]
            dx00 = t00.dx; dy00 = t00.dy
            dx10 = t10.dx; dy10 = t10.dy
            dx01 = t01.dx; dy01 = t01.dy
            dx11 = t11.dx; dy11 = t11.dy
            c00 = if (t00.reliable) 1f else 0f
            c10 = if (t10.reliable) 1f else 0f
            c01 = if (t01.reliable) 1f else 0f
            c11 = if (t11.reliable) 1f else 0f
        }
        val tx = RawSrCoreSampling.flowTileIndex(x, tileSize).coerceIn(0, columns - 1)
        val ty = RawSrCoreSampling.flowTileIndex(y, tileSize).coerceIn(0, rows - 1)
        val nearest = ty * columns + tx
        val nearestResidual = direct?.directResidual(nearest) ?: tiles[nearest].residual
        if (!dx00.isFinite() || !dy00.isFinite() ||
            !dx10.isFinite() || !dy10.isFinite() ||
            !dx01.isFinite() || !dy01.isFinite() ||
            !dx11.isFinite() || !dy11.isFinite()
        ) {
            if (direct != null) {
                out[0] = direct.directDx(nearest)
                out[1] = direct.directDy(nearest)
                out[2] = nearestResidual
                out[3] = if (direct.directReliable(nearest)) 1f else 0f
            } else {
                val n = tiles[nearest]
                out[0] = n.dx
                out[1] = n.dy
                out[2] = n.residual
                out[3] = if (n.reliable) 1f else 0f
            }
            return
        }
        val w00 = (1f - fx) * (1f - fy)
        val w10 = fx * (1f - fy)
        val w01 = (1f - fx) * fy
        val w11 = fx * fy
        out[0] = dx00 * w00 + dx10 * w10 + dx01 * w01 + dx11 * w11
        out[1] = dy00 * w00 + dy10 * w10 + dy01 * w01 + dy11 * w11
        out[2] = nearestResidual
        out[3] = if (c00 * w00 + c10 * w10 + c01 * w01 + c11 * w11 >= 0.5f) 1f else 0f
    }

    /**
     * Bilateral flow-field regularization (RawLens improvement, not
     * reference): each tile's vector is replaced by the 3x3 bilateral
     * average with the joint range kernel
     * `w = exp(-(|dx|^2 + |dy|^2) / (2*sigma^2))` over the vector
     * difference to the center tile. Coherent sub-sigma steps (the tile
     * quilt: per-tile flow differences that extend scene edges along
     * tile lines while robustness stays high) collapse toward the
     * neighborhood consensus, while supra-sigma motion discontinuities
     * keep their estimated vectors (blinds-safe: a 5px step weighs
     * exp(-12.5) at sigma 1). Isolated single-tile spikes are NOT
     * collapsed (the center always weighs 1) — that is robustness's job
     * (a wrong vector mismatches and rejects), not the bilateral's.
     * Non-finite neighbors are skipped (weight 0); a non-finite center
     * rides through unchanged. Residuals, reliability, centers, and dims
     * ride with their tile. [sigmaPx] <= 0 (or non-finite) returns this
     * field unchanged. Window positions are edge-clamped (duplicated
     * neighbors weigh per position, standard clamped bilateral).
     * Float32 accumulation matches the GPU `flow_regularize.glsl` twin.
     */
    fun bilateralFiltered(sigmaPx: Float): RawSrAlignmentField {
        if (!sigmaPx.isFinite() || sigmaPx <= 0f) return this
        val denom = 2f * sigmaPx * sigmaPx
        val direct = tiles as? RawSrDirectFlowTiles
        val out = ArrayList<RawSrTileFlow>(columns * rows)
        for (ty in 0 until rows) {
            for (tx in 0 until columns) {
                val i = ty * columns + tx
                val center = tiles[i]
                val cdx = direct?.directDx(i) ?: center.dx
                val cdy = direct?.directDy(i) ?: center.dy
                if (!cdx.isFinite() || !cdy.isFinite()) {
                    out.add(center)
                    continue
                }
                var wx = 0f
                var wy = 0f
                var wsum = 0f
                for (oy in -1..1) {
                    val ny = (ty + oy).coerceIn(0, rows - 1)
                    for (ox in -1..1) {
                        val nx = (tx + ox).coerceIn(0, columns - 1)
                        val ni = ny * columns + nx
                        val ndx = direct?.directDx(ni) ?: tiles[ni].dx
                        val ndy = direct?.directDy(ni) ?: tiles[ni].dy
                        if (!ndx.isFinite() || !ndy.isFinite()) continue
                        val ddx = ndx - cdx
                        val ddy = ndy - cdy
                        val w = exp(-(ddx * ddx + ddy * ddy) / denom).toFloat()
                        wx += ndx * w
                        wy += ndy * w
                        wsum += w
                    }
                }
                // wsum > 0 always: the center contributes weight 1.
                out.add(RawSrTileFlow(center.centerX, center.centerY,
                    wx / wsum, wy / wsum, center.residual, center.reliable))
            }
        }
        return RawSrAlignmentField(imageWidth, imageHeight, tileSize, columns, rows, out)
    }
}

/** Checkout alignment front end + coarse-to-fine driver (see the file header). */
object RawSrAlignment {
    /** ICA Hessian solvability gate (reference `abs(det) < 1e-10`). */
    const val ICA_DET_EPS = 1e-10

    /**
     * FFT grey (`compute_grey_images`, method FFT): forward DFT, fftshift,
     * zero the outer quarters of the shifted spectrum, ifftshift, inverse
     * DFT, real part. Full resolution; input is the normalized mosaic.
     *
     * The complex planes live in ashmem (see [RawSrFft.FftPlanePair]):
     * on-heap they cost two ~100MB arrays at 12MP and OOM a 512MB-heap
     * save before the linear merge writes anything. Same doubles, same
     * order: bitwise-identical to the array implementation.
     */
    fun fftGrey(mosaic: FloatArray, width: Int, height: Int): RawSrGrayImage {
        require(mosaic.size == width * height)
        val n = width * height
        RawSrFft.FftPlanePair.allocate(n).use { planes ->
            val re = planes.re
            val im = planes.im
            for (i in 0 until n) re.put(i, mosaic[i].toDouble())
            RawSrFft.fft2d(re, im, width, height, false)
            fftShift(re, im, width, height, forward = true)
            val qy = height / 4
            val qx = width / 4
            for (y in 0 until qy) for (x in 0 until width) {
                val i = y * width + x
                re.put(i, 0.0)
                im.put(i, 0.0)
            }
            for (y in height - qy until height) for (x in 0 until width) {
                val i = y * width + x
                re.put(i, 0.0)
                im.put(i, 0.0)
            }
            for (y in 0 until height) for (x in 0 until qx) {
                val i = y * width + x
                re.put(i, 0.0)
                im.put(i, 0.0)
            }
            for (y in 0 until height) for (x in width - qx until width) {
                val i = y * width + x
                re.put(i, 0.0)
                im.put(i, 0.0)
            }
            fftShift(re, im, width, height, forward = false)
            RawSrFft.fft2d(re, im, width, height, true)
            return RawSrGrayImage(width, height, FloatArray(n) { re.get(it).toFloat() })
        }
    }

    /**
     * torch.fft.fftshift (forward) / ifftshift (backward): circular shift by
     * floor(n/2) / (n - floor(n/2)) per axis, in place.
     *
     * The array overload wraps and delegates here, so both spellings run
     * one implementation and agree bitwise.
     */
    fun fftShift(re: DoubleBuffer, im: DoubleBuffer, width: Int, height: Int, forward: Boolean) {
        shiftAxis(re, im, width, height, rowWise = true, forward)
        shiftAxis(re, im, width, height, rowWise = false, forward)
    }

    /**
     * Array spelling of the buffer fftshift above; wraps and delegates, so
     * results are bitwise-identical to the off-heap path.
     */
    fun fftShift(re: DoubleArray, im: DoubleArray, width: Int, height: Int, forward: Boolean) {
        fftShift(DoubleBuffer.wrap(re), DoubleBuffer.wrap(im), width, height, forward)
    }

    private fun shiftAxis(
        re: DoubleBuffer, im: DoubleBuffer, width: Int, height: Int,
        rowWise: Boolean, forward: Boolean
    ) {
        val n = if (rowWise) width else height
        val shift = if (forward) n / 2 else n - n / 2
        if (shift == 0) return
        if (rowWise) {
            RawSrWorkers.forEachShard(height) { y0, y1 ->
                val tmpRe = DoubleArray(width)
                val tmpIm = DoubleArray(width)
                for (y in y0 until y1) {
                    val base = y * width
                    for (x in 0 until width) {
                        tmpRe[(x + shift) % width] = re.get(base + x)
                        tmpIm[(x + shift) % width] = im.get(base + x)
                    }
                    for (x in 0 until width) {
                        re.put(base + x, tmpRe[x])
                        im.put(base + x, tmpIm[x])
                    }
                }
            }
        } else {
            RawSrWorkers.forEachShard(width) { x0, x1 ->
                val tmpRe = DoubleArray(height)
                val tmpIm = DoubleArray(height)
                for (x in x0 until x1) {
                    for (y in 0 until height) {
                        tmpRe[(y + shift) % height] = re.get(y * width + x)
                        tmpIm[(y + shift) % height] = im.get(y * width + x)
                    }
                    for (y in 0 until height) {
                        re.put(y * width + x, tmpRe[y])
                        im.put(y * width + x, tmpIm[y])
                    }
                }
            }
        }
    }

    /**
     * Circular pad (torch F.pad circular) to a tile multiple: right/bottom
     * strips wrap from the left/top. The reference pads the reference grey
     * only; the moving grey stays unpadded.
     */
    fun circularPad(grey: RawSrGrayImage, tileSize: Int): RawSrGrayImage {
        val padW = (tileSize - grey.width % tileSize) % tileSize
        val padH = (tileSize - grey.height % tileSize) % tileSize
        if (padW == 0 && padH == 0) return grey
        val width = grey.width + padW
        val height = grey.height + padH
        val out = FloatArray(width * height)
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            for (y in y0 until y1) {
                val sy = y % grey.height
                for (x in 0 until width) {
                    out[y * width + x] = grey.values[sy * grey.width + (x % grey.width)]
                }
            }
        }
        return RawSrGrayImage(width, height, out)
    }

    /**
     * Separable Gaussian kernel matching scipy's `_gaussian_kernel1d`
     * (radius int(2f+0.5), sigma f/2, sum-normalized), the reference
     * `downsample` (gaussian) kernel.
     */
    fun gaussianKernel1d(factor: Int): DoubleArray {
        require(factor >= 1)
        val radius = (2 * factor + 0.5).toInt()
        val sigma = factor * 0.5
        val kernel = DoubleArray(radius * 2 + 1) { i ->
            val x = (i - radius).toDouble()
            kotlin.math.exp(-0.5 * x * x / (sigma * sigma))
        }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        return kernel
    }

    /**
     * Reference `downsample` (gaussian): factor 1 returns the input;
     * otherwise separable valid convolution then strided take every
     * [factor]-th pixel starting at 0 (floor((size - 2r) / factor) kept).
     */
    fun downsample(image: RawSrGrayImage, factor: Int): RawSrGrayImage {
        if (factor == 1) return image
        val kernel = gaussianKernel1d(factor)
        val radius = kernel.size / 2
        val convW = image.width - 2 * radius
        val convH = image.height - 2 * radius
        require(convW > 0 && convH > 0) {
            "Level ${image.width}x${image.height} too small for factor-$factor valid convolution"
        }
        val rowPass = DoubleArray(convW * image.height)
        RawSrWorkers.forEachShard(image.height) { y0, y1 ->
            for (y in y0 until y1) {
                for (x in 0 until convW) {
                    var acc = 0.0
                    for (k in kernel.indices) acc += image.values[y * image.width + x + k] * kernel[k]
                    rowPass[y * convW + x] = acc
                }
            }
        }
        val full = DoubleArray(convW * convH)
        RawSrWorkers.forEachShard(convH) { y0, y1 ->
            for (y in y0 until y1) {
                for (x in 0 until convW) {
                    var acc = 0.0
                    for (k in kernel.indices) acc += rowPass[(y + k) * convW + x] * kernel[k]
                    full[y * convW + x] = acc
                }
            }
        }
        val width = convW / factor
        val height = convH / factor
        require(width > 0 && height > 0) { "Downsampled level is empty" }
        val out = FloatArray(width * height)
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            for (y in y0 until y1) {
                for (x in 0 until width) {
                    out[y * width + x] = full[(y * factor) * convW + x * factor].toFloat()
                }
            }
        }
        return RawSrGrayImage(width, height, out)
    }

    /** Fine-to-coarse pyramid: base, down(2), down(4), down(4). */
    fun pyramid(base: RawSrGrayImage): List<RawSrGrayImage> {
        val levels = ArrayList<RawSrGrayImage>(RawSrAlignmentConfig.LEVELS)
        levels.add(base)
        for (level in 1 until RawSrAlignmentConfig.LEVELS) {
            levels.add(downsample(levels[level - 1], RawSrAlignmentConfig.FACTORS[level]))
        }
        return levels
    }

    /** Floor tile grid for [level]: partial right/bottom strips are dropped. */
    fun levelGrid(level: RawSrGrayImage, tileSize: Int): Pair<Int, Int> {
        val columns = level.width / tileSize
        val rows = level.height / tileSize
        require(columns > 0 && rows > 0) {
            "Level ${level.width}x${level.height} smaller than one $tileSize-px tile"
        }
        return columns to rows
    }

    /** Per-level working flow: interleaved dx/dy doubles, row-major tiles. */
    class LevelFlow(val columns: Int, val rows: Int, val values: DoubleArray) {
        init { require(values.size == columns * rows * 2) }
        fun dx(i: Int) = values[i * 2]
        fun dy(i: Int) = values[i * 2 + 1]
    }

    /**
     * L1 block matching (reference finest level): seed rounded half-away
     * ([roundHalfAway], like `cpu_l1_local_search`), SAD with zero-filled
     * out-of-image moving taps, first-minimum scan (sy outer, sx inner,
     * strictly-less update), result integers (the incoming subpixel is
     * dropped). Returns flows + mean SAD residuals.
     *
     * L2 seeds keep half-even ([Math.rint]): the reference rounds those with
     * torch.round, which is half-even like rint.
     */

    /**
     * Reference `utils.round_half_away` (CUDA round() semantics): halves round
     * away from zero, unlike [Math.rint] (halves to even). Shared by L1 seeds
     * and [RawSrRobustness] Dogson warp centers.
     */
    internal fun roundHalfAway(x: Double): Int = RawSrCoreAlign.roundHalfAway(x)
    fun blockMatchL1(
        ref: RawSrGrayImage, mov: RawSrGrayImage, seed: LevelFlow,
        tileSize: Int, radius: Int
    ): Pair<LevelFlow, DoubleArray> {
        val (columns, rows) = levelGrid(ref, tileSize)
        require(seed.columns == columns && seed.rows == rows)
        val out = DoubleArray(columns * rows * 2)
        val residual = DoubleArray(columns * rows)
        val area = tileSize * tileSize.toDouble()
        RawSrWorkers.forEachShard(rows) { ty0, ty1 ->
            for (ty in ty0 until ty1) {
                for (tx in 0 until columns) {
                    val i = ty * columns + tx
                    val seedX = roundHalfAway(seed.dx(i))
                    val seedY = roundHalfAway(seed.dy(i))
                    var best = Double.POSITIVE_INFINITY
                    var bestX = 0
                    var bestY = 0
                    for (sy in -radius..radius) {
                        for (sx in -radius..radius) {
                            val sad = RawSrCoreAlign.blockCostL1(
                                ref, mov, tx, ty, tileSize, seedX + sx, seedY + sy)
                            if (sad < best) {
                                best = sad
                                bestX = sx
                                bestY = sy
                            }
                        }
                    }
                    out[i * 2] = (seedX + bestX).toDouble()
                    out[i * 2 + 1] = (seedY + bestY).toDouble()
                    residual[i] = best / area
                }
            }
        }
        return LevelFlow(columns, rows, out) to residual
    }

    /**
     * L2 block matching (reference coarse levels): same scan, but SSD with
     * edge-clamped moving taps, and the integer winner adds onto the
     * incoming (fractional) seed instead of replacing it.
     */
    fun blockMatchL2(
        ref: RawSrGrayImage, mov: RawSrGrayImage, seed: LevelFlow,
        tileSize: Int, radius: Int
    ): Pair<LevelFlow, DoubleArray> {
        val (columns, rows) = levelGrid(ref, tileSize)
        require(seed.columns == columns && seed.rows == rows)
        val out = DoubleArray(columns * rows * 2)
        val residual = DoubleArray(columns * rows)
        val area = tileSize * tileSize.toDouble()
        RawSrWorkers.forEachShard(rows) { ty0, ty1 ->
            for (ty in ty0 until ty1) {
                for (tx in 0 until columns) {
                    val i = ty * columns + tx
                    val seedX = Math.rint(seed.dx(i)).toInt()
                    val seedY = Math.rint(seed.dy(i)).toInt()
                    var best = Double.POSITIVE_INFINITY
                    var bestX = 0
                    var bestY = 0
                    for (sy in -radius..radius) {
                        for (sx in -radius..radius) {
                            val ssd = RawSrCoreAlign.blockCostL2(
                                ref, mov, tx, ty, tileSize, seedX + sx, seedY + sy)
                            if (ssd < best) {
                                best = ssd
                                bestX = sx
                                bestY = sy
                            }
                        }
                    }
                    out[i * 2] = seed.dx(i) + bestX
                    out[i * 2 + 1] = seed.dy(i) + bestY
                    residual[i] = best / area
                }
            }
        }
        return LevelFlow(columns, rows, out) to residual
    }

    /** Unhalved central-difference gradients ([-1,0,1], zero-padded borders). */
    fun imageGradients(level: RawSrGrayImage): Pair<DoubleArray, DoubleArray> {
        val gx = DoubleArray(level.width * level.height)
        val gy = DoubleArray(level.width * level.height)
        RawSrWorkers.forEachShard(level.height) { y0, y1 ->
            for (y in y0 until y1) {
                for (x in 0 until level.width) {
                    val left = if (x > 0) level.values[y * level.width + x - 1].toDouble() else 0.0
                    val right = if (x < level.width - 1) level.values[y * level.width + x + 1].toDouble() else 0.0
                    val up = if (y > 0) level.values[(y - 1) * level.width + x].toDouble() else 0.0
                    val down = if (y < level.height - 1) level.values[(y + 1) * level.width + x].toDouble() else 0.0
                    gx[y * level.width + x] = right - left
                    gy[y * level.width + x] = down - up
                }
            }
        }
        return gx to gy
    }

    /** Per-tile structure tensor over full floor-grid tiles (h00,h01,h11). */
    fun tileHessians(
        grads: Pair<DoubleArray, DoubleArray>, width: Int, height: Int, tileSize: Int
    ): DoubleArray {
        val (gx, gy) = grads
        val columns = width / tileSize
        val rows = height / tileSize
        val out = DoubleArray(columns * rows * 3)
        RawSrWorkers.forEachShard(rows) { ty0, ty1 ->
            for (ty in ty0 until ty1) {
                for (tx in 0 until columns) {
                    var h00 = 0.0
                    var h01 = 0.0
                    var h11 = 0.0
                    for (y in 0 until tileSize) {
                        for (x in 0 until tileSize) {
                            val o = (ty * tileSize + y) * width + tx * tileSize + x
                            h00 += gx[o] * gx[o]
                            h01 += gx[o] * gy[o]
                            h11 += gy[o] * gy[o]
                        }
                    }
                    val i = ty * columns + tx
                    out[i * 3] = h00
                    out[i * 3 + 1] = h01
                    out[i * 3 + 2] = h11
                }
            }
        }
        return out
    }

    /**
     * Inverse-compositional refinement (reference `ICA.py`, every level):
     * fixed template gradients, 3 iterations of H^-1 B with per-size
     * sampling semantics. Tiles with |det| < 1e-10 keep the block-match
     * flow. Returns refined flows, mean-abs residuals, and det verdicts.
     *
     * Per-size quirks transcribed verbatim: 8 clamps its sampler and clips
     * tree-reduction partials (no step clip); 16/32/64 zero-fill per pixel
     * and clip the step to +-radius, except 64 disables the step clip
     * (radius 32767, reference `align_lvl_ica`).
     */
    fun refineIca(
        ref: RawSrGrayImage, mov: RawSrGrayImage,
        grads: Pair<DoubleArray, DoubleArray>, hessians: DoubleArray,
        seed: LevelFlow, tileSize: Int, radius: Int, iterations: Int
    ): Triple<LevelFlow, DoubleArray, BooleanArray> {
        val (gx, gy) = grads
        val columns = seed.columns
        val rows = seed.rows
        val out = seed.values.copyOf()
        val residual = DoubleArray(columns * rows)
        val detOk = BooleanArray(columns * rows)
        RawSrWorkers.forEachShard(rows) { ty0, ty1 ->
            for (ty in ty0 until ty1) {
                for (tx in 0 until columns) {
                    val i = ty * columns + tx
                    val h00 = hessians[i * 3]
                    val h01 = hessians[i * 3 + 1]
                    val h11 = hessians[i * 3 + 2]
                    val det = h00 * h11 - h01 * h01
                    var flowX = seed.dx(i)
                    var flowY = seed.dy(i)
                    var meanAbs = 0.0
                    if (abs(det) >= ICA_DET_EPS) {
                        detOk[i] = true
                        val detInv = 1.0 / det
                        val clip = if (tileSize == 64) 32767.0 else radius.toDouble()
                        repeat(iterations) {
                            val b = RawSrCoreAlign.steepestSums(ref, mov, gx, gy, tx, ty, tileSize, flowX, flowY)
                            var stepX: Double
                            var stepY: Double
                            if (tileSize == 8) {
                                // Tree-clipped B sums, unclipped step (reference).
                                val (cb0, cb1) = RawSrCoreAlign.treeClippedSums(b.third!!, radius)
                                val (sx, sy) = RawSrCoreAlign.icaStep(detInv, h00, h01, h11, cb0, cb1)
                                stepX = sx
                                stepY = sy
                            } else {
                                val (sx, sy) = RawSrCoreAlign.icaStep(detInv, h00, h01, h11, b.first, b.second)
                                stepX = sx.coerceIn(-clip, clip)
                                stepY = sy.coerceIn(-clip, clip)
                            }
                            flowX += stepX
                            flowY += stepY
                        }
                        meanAbs = RawSrCoreAlign.meanAbsidual(ref, mov, tx, ty, tileSize, flowX, flowY)
                    } else {
                        meanAbs = RawSrCoreAlign.meanAbsidual(ref, mov, tx, ty, tileSize, flowX, flowY)
                    }
                    out[i * 2] = flowX
                    out[i * 2 + 1] = flowY
                    residual[i] = meanAbs
                }
            }
        }
        return Triple(LevelFlow(columns, rows, out), residual, detOk)
    }

    // ICA sampling/steps, inter-level upscaling, and reliability live in
    // [RawSrCoreAlign]; this object keeps the front end (FFT grey, padding,
    // pyramid, gradients, Hessians) and the alignPair orchestration.

    /**
     * Inter-level flow propagation (reference `upscale_lvl`; see
     * [RawSrCoreAlign.upsampleFlow]).
     */
    fun upsampleFlow(
        prior: LevelFlow?, columns: Int, rows: Int, factor: Int,
        newTileSize: Int, prevTileSize: Int,
        mode: RawSrAlignmentConfig.FlowUpscaleMode
    ): LevelFlow = RawSrCoreAlign.upsampleFlow(prior, columns, rows, factor, newTileSize, prevTileSize, mode)

    /**
     * Auxiliary per-tile reliability for frame rejection only (see
     * [RawSrCoreAlign.auxiliaryReliable]). Flows never consult this.
     */
    fun auxiliaryReliable(residual: Double, detOk: Boolean, dx: Double, dy: Double, config: RawSrAlignmentConfig): Boolean =
        RawSrCoreAlign.auxiliaryReliable(residual, detOk, dx, dy, config)

    /**
     * Coarse-to-fine alignment of one moving grey against the reference
     * grey (reference `align`): zero init at the coarsest level, then per
     * level upscale -> block match (L1 finest, L2 coarse) -> ICA refine.
     * [refPyramid] must come from [pyramid] of circularly padded reference
     * grey ([circularPad]); [movPyramid] from [pyramid] of unpadded grey.
     */
    fun alignPair(
        refPyramid: List<RawSrGrayImage>, movPyramid: List<RawSrGrayImage>,
        config: RawSrAlignmentConfig
    ): RawSrAlignmentField {
        require(refPyramid.size == RawSrAlignmentConfig.LEVELS)
        require(movPyramid.size == RawSrAlignmentConfig.LEVELS)
        var flow: LevelFlow? = null
        var residual = DoubleArray(0)
        var detOk = BooleanArray(0)
        for (level in RawSrAlignmentConfig.LEVELS - 1 downTo 0) {
            val tileSize = config.tileSizeAt(level)
            val (columns, rows) = levelGrid(refPyramid[level], tileSize)
            val seeded = if (flow == null) {
                LevelFlow(columns, rows, DoubleArray(columns * rows * 2))
            } else {
                upsampleFlow(
                    flow, columns, rows, config.factorAt(level + 1),
                    tileSize, config.tileSizeAt(level + 1), config.flowUpscale
                )
            }
            val ref = refPyramid[level]
            val mov = movPyramid[level]
            val radius = config.radiusAt(level)
            val (matched, _) = if (level == 0) blockMatchL1(ref, mov, seeded, tileSize, radius)
            else blockMatchL2(ref, mov, seeded, tileSize, radius)
            val grads = imageGradients(ref)
            val hessians = tileHessians(grads, ref.width, ref.height, tileSize)
            val (refined, res, det) = refineIca(
                ref, mov, grads, hessians, matched, tileSize, radius, config.lkIterations
            )
            flow = refined
            residual = res
            detOk = det
        }
        val fine = flow!!
        val base = refPyramid[0]
        val tileSize = config.tileSizeAt(0)
        val tiles = List(fine.columns * fine.rows) { i ->
            val tx = i % fine.columns
            val ty = i / fine.columns
            RawSrTileFlow(
                centerX = tx * tileSize + tileSize / 2f,
                centerY = ty * tileSize + tileSize / 2f,
                dx = fine.dx(i).toFloat(),
                dy = fine.dy(i).toFloat(),
                residual = residual[i].toFloat(),
                reliable = auxiliaryReliable(residual[i], detOk[i], fine.dx(i), fine.dy(i), config)
            )
        }
        return RawSrAlignmentField(base.width, base.height, tileSize, fine.columns, fine.rows, tiles)
    }

    /**
     * Burst alignment sharing one reference pyramid (values identical to
     * per-pair pyramids; deterministic). [refGrey] is padded inside;
     * [movGreys] stay unpadded.
     */
    fun alignBurst(
        refGrey: RawSrGrayImage, movGreys: List<RawSrGrayImage>,
        config: RawSrAlignmentConfig
    ): List<RawSrAlignmentField> {
        val refPyramid = pyramid(circularPad(refGrey, config.tileSize))
        return movGreys.map { movGrey -> alignPair(refPyramid, pyramid(movGrey), config) }
    }
}
