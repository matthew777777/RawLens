// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Deterministic CPU oracle for the GLES RAW-SR alignment pipeline. Coordinates are Bayer quads. */
data class RawSrGrayImage(val width: Int, val height: Int, val values: FloatArray) {
    init { require(width > 0 && height > 0 && values.size == width * height) }
    operator fun get(x: Int, y: Int): Float = values[y * width + x]
}

data class RawSrAlignmentConfig(
    val levels: Int = 4,
    val tileSize: Int = 12,
    val searchRadius: Int = 4,
    val lkIterations: Int = 3,
    val minHessianDeterminant: Float = 1e-5f,
    val maxMeanAbsoluteResidual: Float = 0.12f,
    val minConditionRatio: Float = 1e-4f,
    val minSampleFraction: Float = 0.75f,
    val maxFlowConsistencyError: Float = 1.5f,
    /**
     * Pyramid levels running inverse-compositional refinement; null keeps the
     * documented finest-only schedule (IPOL). Jamy-L refines every level; pass
     * the full range to mirror that.
     */
    val icaLevels: Set<Int>? = null,
    /**
     * Inter-level flow propagation (Jamy-L `flow_upscale_mode`). NEAREST keeps
     * the documented IPOL three-candidate non-interpolating propagation;
     * BILINEAR/BICUBIC densely upsample the prior tile field instead (CPU
     * oracle only — the GLES path rejects non-nearest configs explicitly).
     */
    val flowUpscale: FlowUpscaleMode = FlowUpscaleMode.NEAREST
) {
    init {
        require(levels in 1..6)
        require(tileSize in 4..32 && searchRadius in 1..6 && lkIterations == 3)
        require(minHessianDeterminant.isFinite() && minHessianDeterminant > 0f)
        require(maxMeanAbsoluteResidual.isFinite() && maxMeanAbsoluteResidual > 0f)
        require(minConditionRatio.isFinite() && minConditionRatio > 0f && minConditionRatio < 0.25f)
        require(minSampleFraction in 0.5f..1f)
        require(maxFlowConsistencyError.isFinite() && maxFlowConsistencyError > 0f)
        require(icaLevels == null || icaLevels.all { it in 0 until levels }) {
            "ICA levels $icaLevels must lie in 0 until $levels"
        }
    }
    /** Finest-first schedule: [1, searchRadius, searchRadius, ...]. */
    fun radiusAt(level: Int) = if (level == 0) 1 else searchRadius
    /** Jamy-L [1,2,4,4] reduction schedule, extended with 4 for optional levels. */
    fun factorAt(level: Int) = if (level == 0) 1 else if (level == 1) 2 else 4
    /** True when [level] runs inverse-compositional refinement. */
    fun refineAt(level: Int) = icaLevels?.contains(level) ?: (level == 0)

    /** Inter-level flow propagation mode; see [flowUpscale]. */
    enum class FlowUpscaleMode { NEAREST, BILINEAR, BICUBIC }
}

data class RawSrTileFlow(
    val centerX: Float,
    val centerY: Float,
    /** Displacement sampled in the moving image so moving(x + dx, y + dy) matches reference(x,y). */
    val dx: Float,
    val dy: Float,
    val residual: Float,
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
    val imageWidth: Int,
    val imageHeight: Int,
    val tileSize: Int,
    val columns: Int,
    val rows: Int,
    val tiles: List<RawSrTileFlow>
) {
    init { require(tiles.size == columns * rows) }

    fun flowAt(x: Float, y: Float): RawSrTileFlow {
        val tx = (x / tileSize).toInt().coerceIn(0, columns - 1)
        val ty = (y / tileSize).toInt().coerceIn(0, rows - 1)
        return tiles[ty * columns + tx]
    }

    /**
     * Bilinear flow for merge/robustness consumption (reference-faithful: no
     * tile borders visible to the merge). Tile centers sit at integer lattice
     * points of u = (p + 0.5) / tileSize - 0.5; the four surrounding tiles
     * blend dx/dy, and confidence blends the reliable bits with a 0.5 gate
     * (an isoline, not a grid line). Residual stays nearest (containing
     * tile): the residual gate is a threshold, and thresholding a blended
     * approximately-agreeing value would shift gate boundaries between CPU
     * and GPU — the gate keeps its exact old boundary. Any non-finite corner
     * flow falls back to the containing tile, preserving invalid-flow
     * propagation. Bitwise-agreeing twins live in robustness.glsl and
     * merge_accumulate.glsl; [flowAt] stays nearest for alignment internals.
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
        val tx = (x / tileSize).toInt().coerceIn(0, columns - 1)
        val ty = (y / tileSize).toInt().coerceIn(0, rows - 1)
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
}

object RawSrAlignment {
    /** Removes CFA colour modulation by averaging each complete 2x2 Bayer cell. */
    fun bayerQuadGray(raw: UnpackedRawCfa): RawSrGrayImage {
        require(raw.width % 2 == 0 && raw.height % 2 == 0)
        val width = raw.width / 2
        val height = raw.height / 2
        val out = FloatArray(width * height)
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until width) {
                val p = (y * 2) * raw.width + x * 2
                out[y * width + x] = (raw.values[p] + raw.values[p + 1] +
                    raw.values[p + raw.width] + raw.values[p + raw.width + 1]) * 0.25f
            }
        }
        return RawSrGrayImage(width, height, out)
    }

    /** Half-sample reflect borders, sigma=factor/2, truncate=4 sigma, origin at factor*p. */
    internal fun gaussianWeights(factor: Int): FloatArray {
        val sigma = factor * 0.5
        val radius = factor * 2
        val weights = DoubleArray(radius * 2 + 1) { i ->
            val x = (i - radius).toDouble()
            kotlin.math.exp(-x * x / (2.0 * sigma * sigma))
        }
        val total = weights.sum()
        return FloatArray(weights.size) { (weights[it] / total).toFloat() }
    }

    private fun reflect(p: Int, size: Int): Int = when {
        p < 0 -> -p - 1
        p >= size -> 2 * size - p - 1
        else -> p
    }

    fun pyramid(base: RawSrGrayImage, requestedLevels: Int): List<RawSrGrayImage> {
        require(requestedLevels in 1..6)
        val result = arrayListOf(base)
        while (result.size < requestedLevels) {
            val source = result.last()
            val factor = if (result.size == 1) 2 else 4
            if (source.width / factor < 4 || source.height / factor < 4) break
            val width = source.width / factor; val height = source.height / factor
            val weights = gaussianWeights(factor); val radius = factor * 2
            val horizontal = FloatArray(width * source.height)
            RawSrWorkers.forEachShard(source.height) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until width) {
                    var sum = 0f
                    for (k in weights.indices) sum += source[reflect(x * factor + k - radius, source.width), y] * weights[k]
                    horizontal[y * width + x] = sum
                }
            }
            val values = FloatArray(width * height)
            RawSrWorkers.forEachShard(height) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until width) {
                    var sum = 0f
                    for (k in weights.indices) sum += horizontal[reflect(y * factor + k - radius, source.height) * width + x] * weights[k]
                    values[y * width + x] = sum
                }
            }
            result += RawSrGrayImage(width, height, values)
        }
        return result
    }

    fun align(reference: RawSrGrayImage, moving: RawSrGrayImage,
              config: RawSrAlignmentConfig = RawSrAlignmentConfig()): RawSrAlignmentField {
        require(reference.width == moving.width && reference.height == moving.height)
        val refs = pyramid(reference, config.levels); val movs = pyramid(moving, refs.size)
        val forward = alignDirectional(refs, movs, config)
        val reverse = alignDirectional(movs, refs, config)
        return checkConsistency(forward, reverse, config)
    }

    internal fun checkConsistency(forward: RawSrAlignmentField, reverse: RawSrAlignmentField,
                                  config: RawSrAlignmentConfig): RawSrAlignmentField {
        return forward.copy(tiles = forward.tiles.map { tile ->
            val x = tile.centerX + tile.dx; val y = tile.centerY + tile.dy
            val inBounds = x >= 0f && y >= 0f && x < reverse.imageWidth && y < reverse.imageHeight
            val back = reverse.flowAt(x, y)
            val consistent = max(abs(tile.dx + back.dx), abs(tile.dy + back.dy)) <= config.maxFlowConsistencyError
            tile.copy(reliable = tile.reliable && inBounds && back.reliable && consistent)
        })
    }

    /**
     * Dense inter-level flow upsampling (Jamy-L `upscale_lvl` mirror, without
     * the zero-padding branch): tile (tx, ty) of the [columns]x[rows] current
     * grid samples the prior tile field at half-pixel-centered coordinates
     * (equivalent to align_corners=False) and scales by [factor] into
     * current-level pixels. Every prior tile blends whether reliable or not —
     * these are block-matching seeds only, and the search still validates.
     * Non-finite prior flow contributes a zero seed (never NaN seeds).
     */
    internal fun upsampleFlow(
        prior: RawSrAlignmentField,
        columns: Int,
        rows: Int,
        factor: Int,
        mode: RawSrAlignmentConfig.FlowUpscaleMode
    ): FloatArray {
        require(columns > 0 && rows > 0 && factor >= 1)
        require(mode != RawSrAlignmentConfig.FlowUpscaleMode.NEAREST) {
            "Nearest propagation uses the three-candidate path, not dense upsampling"
        }
        val out = FloatArray(columns * rows * 2)
        val pw = prior.columns
        val ph = prior.rows
        // Flat prior reads (dx, dy per tile) so the blend below boxes nothing.
        val pdx = FloatArray(pw * ph)
        val pdy = FloatArray(pw * ph)
        val direct = prior.tiles as? RawSrDirectFlowTiles
        for (i in pdx.indices) {
            val dx = direct?.directDx(i) ?: prior.tiles[i].dx
            val dy = direct?.directDy(i) ?: prior.tiles[i].dy
            pdx[i] = if (dx.isFinite()) dx else 0f
            pdy[i] = if (dy.isFinite()) dy else 0f
        }
        val bicubic = mode == RawSrAlignmentConfig.FlowUpscaleMode.BICUBIC
        for (ty in 0 until rows) for (tx in 0 until columns) {
            val px = (tx + 0.5f) * pw / columns - 0.5f
            val py = (ty + 0.5f) * ph / rows - 0.5f
            val o = (ty * columns + tx) * 2
            if (bicubic) {
                out[o] = bicubicAt(pdx, pw, ph, px, py) * factor
                out[o + 1] = bicubicAt(pdy, pw, ph, px, py) * factor
            } else {
                out[o] = bilinearAt(pdx, pw, ph, px, py) * factor
                out[o + 1] = bilinearAt(pdy, pw, ph, px, py) * factor
            }
        }
        return out
    }

    private fun bilinearAt(grid: FloatArray, w: Int, h: Int, px: Float, py: Float): Float {
        val cx = px.coerceIn(0f, (w - 1).toFloat())
        val cy = py.coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(cx).toInt().coerceIn(0, w - 1)
        val y0 = floor(cy).toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = cx - x0
        val fy = cy - y0
        return grid[y0 * w + x0] * (1f - fx) * (1f - fy) +
            grid[y0 * w + x1] * fx * (1f - fy) +
            grid[y1 * w + x0] * (1f - fx) * fy +
            grid[y1 * w + x1] * fx * fy
    }

    private fun bicubicAt(grid: FloatArray, w: Int, h: Int, px: Float, py: Float): Float {
        val cx = px.coerceIn(0f, (w - 1).toFloat())
        val cy = py.coerceIn(0f, (h - 1).toFloat())
        val x0 = floor(cx).toInt().coerceIn(0, w - 1)
        val y0 = floor(cy).toInt().coerceIn(0, h - 1)
        val fx = cx - x0
        val fy = cy - y0
        // Catmull-Rom rows through the 4x4 clamp-to-edge neighborhood.
        val row = FloatArray(4)
        for (i in 0..3) {
            val yy = (y0 - 1 + i).coerceIn(0, h - 1)
            row[i] = catmullRom(
                grid[yy * w + (x0 - 1).coerceIn(0, w - 1)],
                grid[yy * w + x0],
                grid[yy * w + (x0 + 1).coerceIn(0, w - 1)],
                grid[yy * w + (x0 + 2).coerceIn(0, w - 1)],
                fx)
        }
        return catmullRom(row[0], row[1], row[2], row[3], fy)
    }

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (2f * p1 + (-p0 + p2) * t +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    private fun alignDirectional(refs: List<RawSrGrayImage>, movs: List<RawSrGrayImage>,
                                 config: RawSrAlignmentConfig): RawSrAlignmentField {
        var previous: RawSrAlignmentField? = null
        for (level in refs.indices.reversed()) {
            val ref = refs[level]; val mov = movs[level]
            val columns = (ref.width + config.tileSize - 1) / config.tileSize
            val rows = (ref.height + config.tileSize - 1) / config.tileSize
            // Tile-sharded: tiles are independent given the read-only prior
            // level, so shards fill disjoint index ranges (bitwise-identical).
            // The 3-candidate seed probe is unrolled (was: listOf + 3 Pairs
            // per tile) with the identical visit order and strict-less update.
            val flows = arrayOfNulls<RawSrTileFlow>(columns * rows)
            val prior = previous
            // Dense inter-level seeds, computed once per level (not per tile):
            // null keeps the three-candidate propagation below.
            val denseSeeds = if (prior != null && config.flowUpscale != RawSrAlignmentConfig.FlowUpscaleMode.NEAREST)
                upsampleFlow(prior, columns, rows, config.factorAt(level + 1), config.flowUpscale)
            else null
            RawSrWorkers.forEachShard(rows) { ty0, ty1 ->
                for (ty in ty0 until ty1) for (tx in 0 until columns) {
                    val left = tx * config.tileSize; val top = ty * config.tileSize
                    val right = min(left + config.tileSize, ref.width); val bottom = min(top + config.tileSize, ref.height)
                    val bounds = intArrayOf(left, top, right, bottom)
                    var seedX = 0; var seedY = 0
                    if (prior != null) {
                        val factor = config.factorAt(level + 1)
                        if (denseSeeds != null) {
                            // Densely upsampled prior flow, rounded to the integer
                            // block-matching seed (Jamy-L propagation mirror).
                            val o = (ty * columns + tx) * 2
                            seedX = round(denseSeeds[o]).toInt()
                            seedY = round(denseSeeds[o + 1]).toInt()
                        } else {
                            // Integer subdivision, never normalized grid ratios or interpolated flow.
                            val px = tx / factor; val py = ty / factor
                            val nx = if (tx % factor < factor / 2) -1 else 1
                            val ny = if (ty % factor < factor / 2) -1 else 1
                            var best = INVALID_RESIDUAL
                            for (k in 0..2) {
                                val cx = px + (if (k == 1) nx else 0)
                                val cy = py + (if (k == 2) ny else 0)
                                if (cx !in 0 until prior.columns || cy !in 0 until prior.rows) continue
                                val candidate = prior.tiles[cy * prior.columns + cx]
                                if (!candidate.reliable) continue
                                val dx = (candidate.dx * factor).toInt(); val dy = (candidate.dy * factor).toInt()
                                val score = score(ref, mov, bounds, dx, dy, true, config)
                                if (score < best) { best = score; seedX = dx; seedY = dy }
                            }
                        }
                    }
                    val best = blockMatch(ref, mov, bounds, seedX, seedY, config.radiusAt(level), level == 0, config)
                    val refined = if (config.refineAt(level)) refineLk(ref, mov, bounds, best, config) else best
                    flows[ty * columns + tx] = RawSrTileFlow((left + right - 1) * 0.5f, (top + bottom - 1) * 0.5f,
                        refined[0], refined[1], refined[2], refined[3] > 0f)
                }
            }
            @Suppress("UNCHECKED_CAST")
            previous = RawSrAlignmentField(ref.width, ref.height, config.tileSize, columns, rows,
                (flows as Array<RawSrTileFlow>).asList())
        }
        return requireNotNull(previous)
    }

    internal const val INVALID_RESIDUAL = 1e6f

    private fun score(ref: RawSrGrayImage, mov: RawSrGrayImage, bounds: IntArray,
                      dx: Int, dy: Int, l1: Boolean, config: RawSrAlignmentConfig): Float {
        var error = 0f; var count = 0
        for (y in bounds[1] until bounds[3]) for (x in bounds[0] until bounds[2]) {
            val mx = x + dx; val my = y + dy
            if (mx !in 0 until mov.width || my !in 0 until mov.height) continue
            val d = mov[mx, my] - ref[x, y]
            if (!d.isFinite()) return INVALID_RESIDUAL
            error += if (l1) abs(d) else d * d
            count++
        }
        val area = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1])
        return if (count >= max(4, kotlin.math.ceil(area * config.minSampleFraction).toInt()) && error.isFinite())
            (error / count).coerceAtMost(INVALID_RESIDUAL) else INVALID_RESIDUAL
    }

    private fun blockMatch(ref: RawSrGrayImage, mov: RawSrGrayImage, bounds: IntArray,
                           baseX: Int, baseY: Int, radius: Int, l1: Boolean,
                           config: RawSrAlignmentConfig): FloatArray {
        var bestX = baseX; var bestY = baseY
        var best = INVALID_RESIDUAL; var distance = Int.MAX_VALUE
        for (oy in -radius..radius) for (ox in -radius..radius) {
            val score = score(ref, mov, bounds, baseX + ox, baseY + oy, l1, config)
            val d = ox * ox + oy * oy
            if (score < best || (score == best && score < INVALID_RESIDUAL && d < distance)) {
                best = score; bestX = baseX + ox; bestY = baseY + oy; distance = d
            }
        }
        return floatArrayOf(bestX.toFloat(), bestY.toFloat(), best, if (best < INVALID_RESIDUAL) 1f else 0f)
    }

    private fun refineLk(ref: RawSrGrayImage, mov: RawSrGrayImage, bounds: IntArray,
                         start: FloatArray, config: RawSrAlignmentConfig): FloatArray {
        val left = bounds[0]; val top = bounds[1]; val right = bounds[2]; val bottom = bounds[3]
        var dx = start[0]; var dy = start[1]
        // Fixed template gradients/Hessian: inverse-compositional translation, not forward-additive LK.
        var hxx = 0f; var hxy = 0f; var hyy = 0f; var samples = 0
        for (y in max(1, top) until min(bottom, ref.height - 1))
            for (x in max(1, left) until min(right, ref.width - 1)) {
                val gx = (ref[x + 1, y] - ref[x - 1, y]) * 0.5f
                val gy = (ref[x, y + 1] - ref[x, y - 1]) * 0.5f
                hxx += gx * gx; hxy += gx * gy; hyy += gy * gy; samples++
            }
        val determinant = hxx * hyy - hxy * hxy
        val trace = hxx + hyy
        var valid = start[3] > 0f && samples >= 4 && determinant.isFinite() &&
            determinant > config.minHessianDeterminant &&
            determinant > config.minConditionRatio * trace * trace
        repeat(3) {
            var bx = 0f; var by = 0f; var count = 0
            if (valid) {
                for (y in max(1, top) until min(bottom, ref.height - 1))
                    for (x in max(1, left) until min(right, ref.width - 1)) {
                        val sample = bilinearOrNull(mov, x + dx, y + dy) ?: continue
                        val gx = (ref[x + 1, y] - ref[x - 1, y]) * 0.5f
                        val gy = (ref[x, y + 1] - ref[x, y - 1]) * 0.5f
                        val error = sample - ref[x, y]
                        bx += gx * error; by += gy * error; count++
                    }
                // Never silently change the template support/Hessian when a warp leaves bounds.
                valid = count == samples && bx.isFinite() && by.isFinite()
                if (valid) {
                    val stepX = (hyy * bx - hxy * by) / determinant
                    val stepY = (hxx * by - hxy * bx) / determinant
                    valid = stepX.isFinite() && stepY.isFinite()
                    if (valid) { dx -= stepX.coerceIn(-1f, 1f); dy -= stepY.coerceIn(-1f, 1f) }
                }
            }
        }
        var residual = 0f; var count = 0
        for (y in top until bottom) for (x in left until right) {
            bilinearOrNull(mov, x + dx, y + dy)?.let {
                residual += abs(it - ref[x, y]); count++
            }
        }
        val area = (right - left) * (bottom - top)
        val enough = count >= max(4, kotlin.math.ceil(area * config.minSampleFraction).toInt())
        val mean = if (enough && residual.isFinite()) (residual / count).coerceAtMost(INVALID_RESIDUAL) else INVALID_RESIDUAL
        valid = valid && enough && mean <= config.maxMeanAbsoluteResidual
        return floatArrayOf(dx, dy, mean, if (valid) 1f else 0f)
    }

    internal fun bilinearOrNull(image: RawSrGrayImage, x: Float, y: Float): Float? {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= image.width || y0 + 1 >= image.height) return null
        val fx = x - x0; val fy = y - y0
        return (image[x0, y0] * (1f - fx) + image[x0 + 1, y0] * fx) * (1f - fy) +
            (image[x0, y0 + 1] * (1f - fx) + image[x0 + 1, y0 + 1] * fx) * fy
    }
}

data class RawSrRgbImage(val width: Int, val height: Int, val values: FloatArray) {
    init { require(values.size == width * height * 3) }
    operator fun get(x: Int, y: Int, channel: Int): Float = values[(y * width + x) * 3 + channel]
}

data class RawSrMergeResult(
    val image: RawSrRgbImage,
    val denominator: FloatArray,
    val alignments: List<RawSrAlignmentField>,
    val acceptedFrames: Int
)

/** Phase-B 1x RGB accumulator. It is deliberately simple and serves as an A/B oracle for GLES. */
object RawSrMergePrototype {
    fun merge(
        frames: List<UnpackedRawCfa>,
        config: RawSrAlignmentConfig = RawSrAlignmentConfig(),
        referenceOnly: Boolean = false
    ): RawSrMergeResult {
        require(frames.isNotEmpty())
        val reference = frames.first()
        require(frames.all {
            it.width == reference.width && it.height == reference.height && it.pattern == reference.pattern
        }) { "RAW-SR frames must have identical dimensions and CFA phase" }
        val referenceGray = RawSrAlignment.bayerQuadGray(reference)
        val alignments = if (referenceOnly) emptyList() else frames.drop(1).map {
            RawSrAlignment.align(referenceGray, RawSrAlignment.bayerQuadGray(it), config)
        }
        val rgbFrames = (if (referenceOnly) frames.take(1) else frames).map(::demosaic)
        val numerator = FloatArray(reference.width * reference.height * 3)
        val denominator = FloatArray(reference.width * reference.height)
        for (frameIndex in rgbFrames.indices) {
            val rgb = rgbFrames[frameIndex]
            val field = alignments.getOrNull(frameIndex - 1)
            for (y in 0 until reference.height) for (x in 0 until reference.width) {
                val flow = field?.flowAt(x * 0.5f, y * 0.5f)
                if (flow != null && !flow.reliable) continue
                val sx = x + (flow?.dx ?: 0f) * 2f
                val sy = y + (flow?.dy ?: 0f) * 2f
                val sample = sampleRgb(rgb, sx, sy) ?: continue
                val p = y * reference.width + x
                denominator[p] += 1f
                for (channel in 0..2) numerator[p * 3 + channel] += sample[channel]
            }
        }
        for (p in denominator.indices) {
            val weight = denominator[p]
            if (weight > 0f) for (channel in 0..2) numerator[p * 3 + channel] /= weight
        }
        return RawSrMergeResult(
            RawSrRgbImage(reference.width, reference.height, numerator), denominator,
            alignments, if (referenceOnly) 1 else 1 + alignments.count { field ->
                field.tiles.count(RawSrTileFlow::reliable) >= field.tiles.size / 2
            }
        )
    }

    /** Phase-safe bilinear-like interpolation that never mixes unlike CFA samples directly. */
    fun demosaic(raw: UnpackedRawCfa): RawSrRgbImage {
        val out = FloatArray(raw.width * raw.height * 3)
        for (y in 0 until raw.height) for (x in 0 until raw.width) {
            val p = (y * raw.width + x) * 3
            for (channel in 0..2) {
                val wanted = when (channel) { 0 -> CfaColor.RED; 1 -> CfaColor.GREEN; else -> CfaColor.BLUE }
                var sum = 0f
                var weight = 0f
                for (oy in -1..1) for (ox in -1..1) {
                    val sx = x + ox; val sy = y + oy
                    if (sx !in 0 until raw.width || sy !in 0 until raw.height) continue
                    if (raw.pattern.colorAt(sx, sy) != wanted) continue
                    val w = if (ox == 0 && oy == 0) 4f else if (ox == 0 || oy == 0) 2f else 1f
                    sum += raw.values[sy * raw.width + sx] * w
                    weight += w
                }
                if (weight == 0f) {
                    // Only possible at tiny borders; expand by one Bayer period.
                    for (oy in -2..2) for (ox in -2..2) {
                        val sx = x + ox; val sy = y + oy
                        if (sx in 0 until raw.width && sy in 0 until raw.height &&
                            raw.pattern.colorAt(sx, sy) == wanted) {
                            sum += raw.values[sy * raw.width + sx]; weight += 1f
                        }
                    }
                }
                out[p + channel] = if (weight > 0f) sum / weight else 0f
            }
        }
        return RawSrRgbImage(raw.width, raw.height, out)
    }

    private fun sampleRgb(image: RawSrRgbImage, x: Float, y: Float): FloatArray? {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= image.width || y0 + 1 >= image.height) return null
        val fx = x - x0; val fy = y - y0
        return FloatArray(3) { channel ->
            (image[x0, y0, channel] * (1f - fx) + image[x0 + 1, y0, channel] * fx) * (1f - fy) +
                (image[x0, y0 + 1, channel] * (1f - fx) + image[x0 + 1, y0 + 1, channel] * fx) * fy
        }
    }
}
