package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.core.ComplexSpectrum
import com.rawlens.srpipeline.core.Fft
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

// Stage: coarse-to-fine block-matching alignment.
//
// Mirrors `handheld_super_resolution/block_matching.py`:
//   - [alignLvlL1]  <-> `align_lvl_block_matching_L1` / `cpu_l1_local_search`
//   - [alignLvlL2]  <-> `align_lvl_block_matching_L2` (FFT correlation path)
//   - [extractFlowPatches] <-> `extract_flow_patches`
//
// Image convention: `Array<FloatArray>` rows `[y][x]`, Float values.
// Flow convention: `Array<Array<FloatArray>>` indexed `[tileY][tileX][0|1]`
// where `[0]` is x and `[1]` is y, matching the Python `alignments` array.
//
// NOTE (core): [l2CorrelationScores] below is an exact naive-spatial
// equivalent of the FFT path (`rfft2/conj/irfft2/fftshift` + crop +
// box-filter window sums + `L2_search - 2*corr` argmin). The per-tile
// reference spectra in [AlignmentInit] use the real [Fft.forward2D] and are
// the delegation point when the L2 matcher moves to the FFT path (cf. [L2]).
// The gaussian pyramid below goes through [Resample] (exact `downsample`
// mirror: valid convolution + floored dims), not `core.Pyramid` (clamped
// borders, full-size dims), so the levels stay bit-close to the reference.

/** Search metrics per pyramid level, cf. `config.alignment.block_matching.metrics`. */
enum class BlockMetric { L1, L2 }

/** Rounds half away from zero; mirrors `utils.round_half_away` (CUDA `round`). */
fun roundHalfAway(x: Float): Int =
    if (x >= 0f) floor(x + 0.5f).toInt() else ceil(x - 0.5f).toInt()

/**
 * Exhaustive L1 block matching around the current flow, one tile per task.
 * Mirrors `cpu_l1_local_search`: out-of-bounds moving samples read as 0,
 * scan order is `shiftY` outer / `shiftX` inner, and the *first* minimum wins
 * (strict `<` comparison). Updates [flow] in place.
 */
fun l1LocalSearch(
    ref: Array<FloatArray>,
    moving: Array<FloatArray>,
    searchRadius: Int,
    flow: Array<Array<FloatArray>>,
    tileSize: Int,
) {
    val h = moving.size
    val w = moving[0].size
    val nTy = flow.size
    val nTx = flow[0].size
    for (tileY in 0 until nTy) {
        for (tileX in 0 until nTx) {
            val flowX = roundHalfAway(flow[tileY][tileX][0])
            val flowY = roundHalfAway(flow[tileY][tileX][1])
            var bestErr = Float.POSITIVE_INFINITY
            var bestSx = -searchRadius
            var bestSy = -searchRadius
            for (sy in -searchRadius..searchRadius) {
                for (sx in -searchRadius..searchRadius) {
                    var err = 0f
                    for (ty in 0 until tileSize) {
                        val y = tileY * tileSize + ty
                        for (tx in 0 until tileSize) {
                            val x = tileX * tileSize + tx
                            val my = y + flowY + sy
                            val mx = x + flowX + sx
                            val m = if (my in 0 until h && mx in 0 until w) moving[my][mx] else 0f
                            val diff = ref[y][x] - m
                            err += if (diff >= 0f) diff else -diff
                        }
                    }
                    if (err < bestErr) {
                        bestErr = err
                        bestSx = sx
                        bestSy = sy
                    }
                }
            }
            flow[tileY][tileX][0] = (flowX + bestSx).toFloat()
            flow[tileY][tileX][1] = (flowY + bestSy).toFloat()
        }
    }
}

/**
 * Patches of `2*radius+patchSize` centred on `tile*patchSize + round(flow)`,
 * clamped to the image borders. Mirrors `extract_flow_patches` (which uses
 * `flow.round()`, i.e. round-half-to-even, hence [round] here and not
 * [roundHalfAway]).
 *
 * @return `[nTy][nTx][search][search]` patch grid.
 */
fun extractFlowPatches(
    frame: Array<FloatArray>,
    flow: Array<Array<FloatArray>>,
    patchSize: Int,
    radius: Int,
): Array<Array<Array<FloatArray>>> {
    val h = frame.size
    val w = frame[0].size
    val nTy = flow.size
    val nTx = flow[0].size
    val ps = 2 * radius + patchSize
    return Array(nTy) { ty ->
        Array(nTx) { tx ->
            val dx = round(flow[ty][tx][0]).toInt()
            val dy = round(flow[ty][tx][1]).toInt()
            Array(ps) { oy ->
                FloatArray(ps) { ox ->
                    val y = (ty * patchSize + dy + (oy - radius)).coerceIn(0, h - 1)
                    val x = (tx * patchSize + dx + (ox - radius)).coerceIn(0, w - 1)
                    frame[y][x]
                }
            }
        }
    }
}

/**
 * Naive-spatial equivalent of the L2 FFT correlation scores for one tile.
 *
 * The Python path computes `L2_search - 2*corr` where `corr` is the
 * FFT cross-correlation of the reference tile with the moving search area
 * and `L2_search` holds box-filtered moving-window energies. Since the
 * reference energy is constant per tile,
 * `argmin(L2_search - 2*corr) == argmin(SSD)` with the same clamped
 * sampling as [extractFlowPatches]; this returns that SSD error map
 * `[2R+1][2R+1]` indexed `[sy+R][sx+R]`.
 *
 * TODO(core): delegate to `core.Fft.correlate` when available.
 */
fun l2CorrelationScores(
    ref: Array<FloatArray>,
    moving: Array<FloatArray>,
    tileY: Int,
    tileX: Int,
    flowX: Int,
    flowY: Int,
    tileSize: Int,
    searchRadius: Int,
): Array<FloatArray> {
    val h = moving.size
    val w = moving[0].size
    val size = 2 * searchRadius + 1
    return Array(size) { syi ->
        FloatArray(size) { sxi ->
            val sy = syi - searchRadius
            val sx = sxi - searchRadius
            var err = 0f
            for (ty in 0 until tileSize) {
                val y = tileY * tileSize + ty
                for (tx in 0 until tileSize) {
                    val x = tileX * tileSize + tx
                    val my = (y + flowY + sy).coerceIn(0, h - 1)
                    val mx = (x + flowX + sx).coerceIn(0, w - 1)
                    val d = ref[y][x] - moving[my][mx]
                    err += d * d
                }
            }
            err
        }
    }
}

/**
 * L2 block matching via [l2CorrelationScores] argmin. Mirrors
 * `align_lvl_block_matching_L2` (`search_size = 2R+T`, `corr_size = 2R+1`,
 * `flow += (dx, dy)` of the minimum). Updates [flow] in place.
 */
fun l2BlockMatch(
    ref: Array<FloatArray>,
    moving: Array<FloatArray>,
    flow: Array<Array<FloatArray>>,
    tileSize: Int,
    searchRadius: Int,
) {
    val nTy = flow.size
    val nTx = flow[0].size
    for (tileY in 0 until nTy) {
        for (tileX in 0 until nTx) {
            val flowX = roundHalfAway(flow[tileY][tileX][0])
            val flowY = roundHalfAway(flow[tileY][tileX][1])
            val scores = l2CorrelationScores(ref, moving, tileY, tileX, flowX, flowY, tileSize, searchRadius)
            var best = Float.POSITIVE_INFINITY
            var bSx = 0
            var bSy = 0
            for (syi in scores.indices) {
                for (sxi in scores[syi].indices) {
                    if (scores[syi][sxi] < best) {
                        best = scores[syi][sxi]
                        bSx = sxi - searchRadius
                        bSy = syi - searchRadius
                    }
                }
            }
            // `align_lvl_block_matching_L2` adds the winning integer shift
            // to the *full* float flow (`alignment += (dx, dy)`), keeping
            // the upscaled subpixel part; only the search is centred on
            // the rounded flow. (The L1 path instead stores
            // `round_half_away(flow) + shift`; see [l1LocalSearch].)
            flow[tileY][tileX][0] = flow[tileY][tileX][0] + bSx
            flow[tileY][tileX][1] = flow[tileY][tileX][1] + bSy
        }
    }
}

/**
 * Per-level dispatch mirroring `alignment.align_lvl` metric selection.
 *
 * @param tileSizes per-level tile sizes, cf. `config.alignment.tile_sizes`.
 * @param searchRadii per-level search radii, cf. `config.alignment.search_radii`.
 * @param metrics per-level metric, cf. `config.alignment.block_matching.metrics`
 *   (default `[L1, L2, L2, L2]` fine -> coarse).
 */
fun alignLvl(
    ref: Array<FloatArray>,
    moving: Array<FloatArray>,
    flow: Array<Array<FloatArray>>,
    level: Int,
    tileSizes: List<Int>,
    searchRadii: List<Int>,
    metrics: List<BlockMetric>,
) {
    val tileSize = tileSizes[level]
    val radius = searchRadii[level]
    when (metrics[level]) {
        BlockMetric.L1 -> l1LocalSearch(ref, moving, radius, flow, tileSize)
        BlockMetric.L2 -> l2BlockMatch(ref, moving, flow, tileSize, radius)
    }
}

/**
 * Config consumed by the coarse-to-fine loop, cf. `config.alignment`.
 * Every list is fine-to-coarse (`[0]` is the finest level), as in Python.
 *
 * @param tileSize scalar tile size used only for the circular reference
 *   padding, cf. `config.alignment.tile_size` (defaults to the finest entry
 *   of [tileSizes]).
 * @param metrics per-level matcher, cf.
 *   `config.alignment.block_matching.metrics` (default finest `L1`, coarser
 *   `L2`, generalising `[L1, L2, L2, L2]` to any level count).
 * @param nIter ICA Gauss-Newton steps, cf. `config.alignment.ica.n_iter`.
 * @param icaClip clip the ICA step to the search radius, cf.
 *   `config.alignment.ica.clip` (a 64px tile still forces 32767).
 * @param flowUpscaleMode `bilinear` or `nearest`, cf.
 *   `config.alignment.flow_upscale_mode`.
 * @param preprocessing non-linearity applied to the *reference* pyramid
 *   only, cf. `config.alignment.preprocessing` (the moving pyramid is built
 *   without it, mirroring the Python asymmetry in `align`).
 */
data class AlignLoopConfig(
    val factors: List<Int>,
    val tileSizes: List<Int>,
    val searchRadii: List<Int>,
    val metrics: List<BlockMetric> =
        List(tileSizes.size) { if (it == 0) BlockMetric.L1 else BlockMetric.L2 },
    val tileSize: Int = tileSizes.first(),
    val nIter: Int = 3,
    val icaClip: Boolean = true,
    val flowUpscaleMode: String = "bilinear",
    val preprocessing: String? = null,
)

private fun requireAlignLoopConfig(config: AlignLoopConfig, name: String) {
    val n = config.factors.size
    require(n >= 1) { "$name.factors must be non-empty" }
    require(config.tileSizes.size == n && config.searchRadii.size == n && config.metrics.size == n) {
        "$name lists must all hold $n levels " +
            "(tileSizes=${config.tileSizes.size}, searchRadii=${config.searchRadii.size}, " +
            "metrics=${config.metrics.size})"
    }
    require(config.factors.all { it >= 1 }) { "$name.factors must be >= 1 (got ${config.factors})" }
    require(config.tileSizes.all { it >= 1 }) { "$name.tileSizes must be >= 1 (got ${config.tileSizes})" }
    require(config.searchRadii.all { it >= 0 }) { "$name.searchRadii must be >= 0 (got ${config.searchRadii})" }
    require(config.tileSize >= 1) { "$name.tileSize must be >= 1 (got ${config.tileSize})" }
    require(config.nIter >= 0) { "$name.nIter must be >= 0 (got ${config.nIter})" }
}

/**
 * Reference-side alignment state, cf. `init_alignment` return
 * `(pyramid, tiled_pyr, tiled_fft, gradx_pyramid, grady_pyramid,
 * hessian_pyramid)`. Every list is coarse-to-fine (as returned by
 * `build_gaussian_pyramid`).
 *
 * The padded reference tiles (`tiled_pyr`) are represented by their spectra:
 * [tiledSpectra]`[level][tileY][tileX]` holds the [Fft.forward2D] spectrum of
 * the `tileSize x tileSize` reference tile zero-padded with a `searchRadius`
 * border (full-complex equivalent of the `rfft2` path). The tile-grid dims
 * double as the flow shape contract (`ref_tiled_fft_lvl.shape[:2]`).
 */
data class AlignmentInit(
    val refPyramid: List<Array<FloatArray>>,
    val tiledSpectra: List<Array<Array<ComplexSpectrum>>>,
    val gradXPyramid: List<Array<FloatArray>>,
    val gradYPyramid: List<Array<FloatArray>>,
    val hessians: List<Array<Array<Array<FloatArray>>>>,
)

/**
 * Circular (wrap) padding with zero top/left, cf. `F.pad(..., 'circular')`
 * in `init_alignment` (`paddingTop = paddingLeft = 0`).
 */
fun circularPad(image: Array<FloatArray>, padBottom: Int, padRight: Int): Array<FloatArray> {
    requireRectImage(image, "image")
    require(padBottom >= 0 && padRight >= 0) {
        "padding must be >= 0 (got bottom=$padBottom right=$padRight)"
    }
    val h = image.size
    val w = image[0].size
    // All indices are non-negative, so `%` is `floorMod` here (no trunc
    // correction needed, cf. the floor-vs-trunc audit on the loop below).
    return Array(h + padBottom) { y ->
        FloatArray(w + padRight) { x -> image[y % h][x % w] }
    }
}

/**
 * Per-tile reference spectra for one pyramid level: each `tileSize` tile
 * centred in a `(tileSize + 2*radius)` zero window, cf. `unfold` +
 * constant-0 `pad` + `rfft2` in `init_alignment`.
 */
fun tiledSpectra(level: Array<FloatArray>, tileSize: Int, searchRadius: Int): Array<Array<ComplexSpectrum>> {
    requireRectImage(level, "level")
    require(tileSize >= 1) { "tileSize must be >= 1 (got $tileSize)" }
    require(searchRadius >= 0) { "searchRadius must be >= 0 (got $searchRadius)" }
    // Floor division on positive dims, matching `unfold` tile counts
    // (`imsize // tile_size`, remainder pixels dropped).
    val nTy = level.size / tileSize
    val nTx = level[0].size / tileSize
    require(nTy >= 1 && nTx >= 1) {
        "level ${level.size}x${level[0].size} holds no ${tileSize}px tiles"
    }
    val window = tileSize + 2 * searchRadius
    return Array(nTy) { ty ->
        Array(nTx) { tx ->
            val flat = FloatArray(window * window)
            for (y in 0 until tileSize) {
                for (x in 0 until tileSize) {
                    flat[(y + searchRadius) * window + (x + searchRadius)] =
                        level[ty * tileSize + y][tx * tileSize + x]
                }
            }
            Fft.forward2D(flat, window, window)
        }
    }
}

/**
 * Reference-side initialisation, cf. `init_alignment` (call order: pad,
 * gaussian pyramid with preprocessing, then per-level `init_ica` + tiling +
 * spectra, coarse-to-fine index `len(factors) - i - 1`).
 */
fun initAlignment(refGrey: Array<FloatArray>, config: AlignLoopConfig): AlignmentInit {
    requireRectImage(refGrey, "refGrey")
    requireAlignLoopConfig(config, "config")
    val n = config.factors.size
    val h = refGrey.size
    val w = refGrey[0].size
    // `(ts - h % ts) * (h % ts != 0)`: no pad when already a multiple.
    val padH = if (h % config.tileSize == 0) 0 else config.tileSize - h % config.tileSize
    val padW = if (w % config.tileSize == 0) 0 else config.tileSize - w % config.tileSize
    val padded = circularPad(refGrey, padH, padW)
    val pyramid = Resample.buildGaussianPyramid(padded, config.factors, config.preprocessing)
    val spectra = mutableListOf<Array<Array<ComplexSpectrum>>>()
    val gradX = mutableListOf<Array<FloatArray>>()
    val gradY = mutableListOf<Array<FloatArray>>()
    val hessians = mutableListOf<Array<Array<Array<FloatArray>>>>()
    for (i in pyramid.indices) {
        val c = n - i - 1
        val init = Hessian.initIca(pyramid[i], config.tileSizes[c])
        spectra.add(tiledSpectra(pyramid[i], config.tileSizes[c], config.searchRadii[c]))
        gradX.add(init.gradX)
        gradY.add(init.gradY)
        hessians.add(init.hessian)
    }
    return AlignmentInit(pyramid, spectra, gradX, gradY, hessians)
}

/**
 * Flow upscaling between pyramid levels, cf. `upscale_lvl`:
 * `repeat = factors[l+1] // (tileSizes[l] // tileSizes[l+1])`, bilinear (or
 * nearest) re-tiling, `*= factors[l+1]`, zero-pad shortfall to [targetTiles].
 * Delegates to [Resample.upscaleLvl] (no duplicated logic).
 *
 * @param level fine-to-coarse index `l` of the level being entered.
 */
fun upscaleLvl(
    flow: Array<Array<FloatArray>>,
    targetTiles: IntArray,
    level: Int,
    config: AlignLoopConfig,
): Array<Array<FloatArray>> {
    requireAlignLoopConfig(config, "config")
    require(level in 0 until config.factors.size - 1) {
        "level must address a finer level with a coarser parent " +
            "(got $level for ${config.factors.size} levels)"
    }
    return Resample.upscaleLvl(
        flow,
        targetTiles,
        newTileSize = config.tileSizes[level],
        prevTileSize = config.tileSizes[level + 1],
        upsamplingFactor = config.factors[level + 1],
        mode = config.flowUpscaleMode,
    )
}

/**
 * Coarse-to-fine loop, cf. `align` (call order per level `p`: `list_id =
 * len(pyramid) - p - 1`; first (coarsest) level zero-inits the flow from the
 * tiled-FFT shape, later levels [upscaleLvl]; each level runs block matching
 * then *always* ICA, cf. `align_lvl`).
 *
 * The moving pyramid is built without preprocessing, mirroring the Python
 * asymmetry. Each level calls the existing [alignLvl] dispatch
 * ([l1LocalSearch]/[l2BlockMatch]) and [Ica.alignLvl] ([icaRefine]); nothing
 * is reimplemented here.
 *
 * @return per-level flows coarse-to-fine (one deep copy per level, taken
 *   after that level's ICA); the last entry is the finest-level flow
 *   `[nTy][nTx][2]` (x, y), cf. the contiguous `alignments` array returned
 *   by `align`.
 */
fun alignLevelsPerLevel(
    init: AlignmentInit,
    movingGrey: Array<FloatArray>,
    config: AlignLoopConfig,
): List<Array<Array<FloatArray>>> {
    requireRectImage(movingGrey, "movingGrey")
    requireAlignLoopConfig(config, "config")
    val n = config.factors.size
    require(
        init.refPyramid.size == n && init.tiledSpectra.size == n &&
            init.gradXPyramid.size == n && init.gradYPyramid.size == n &&
            init.hessians.size == n,
    ) { "init pyramids must all hold $n levels" }
    val movingPyramid = Resample.buildGaussianPyramid(movingGrey, config.factors)
    require(movingPyramid.size == n) { "moving pyramid must hold $n levels" }
    val levels = mutableListOf<Array<Array<FloatArray>>>()
    var flow: Array<Array<FloatArray>>? = null
    for (p in 0 until n) {
        // `list_id`: fine-to-coarse config index of coarse-to-fine row `p`.
        val c = n - p - 1
        val nTy = init.tiledSpectra[p].size
        val nTx = init.tiledSpectra[p][0].size
        flow = if (flow == null) {
            Array(nTy) { Array(nTx) { floatArrayOf(0f, 0f) } }
        } else {
            upscaleLvl(flow, intArrayOf(nTy, nTx), c, config)
        }
        val current = flow
        alignLvl(
            init.refPyramid[p], movingPyramid[p], current, c,
            config.tileSizes, config.searchRadii, config.metrics,
        )
        Ica.alignLvl(
            init.refPyramid[p], init.gradXPyramid[p], init.gradYPyramid[p],
            init.hessians[p], movingPyramid[p], current,
            tileSize = config.tileSizes[c], nIter = config.nIter,
            clip = config.icaClip, searchRadius = config.searchRadii[c],
        )
        flow = current
        levels.add(Array(current.size) { ty -> Array(current[ty].size) { tx -> current[ty][tx].copyOf() } })
    }
    return levels
}

/**
 * Coarse-to-fine loop, cf. `align`: the finest entry of
 * [alignLevelsPerLevel] (see it for the call order and dispatch contract).
 *
 * @return finest-level flow `[nTy][nTx][2]` (x, y), cf. the contiguous
 *   `alignments` array returned by `align`.
 */
fun alignLevels(
    init: AlignmentInit,
    movingGrey: Array<FloatArray>,
    config: AlignLoopConfig,
): Array<Array<FloatArray>> = alignLevelsPerLevel(init, movingGrey, config).last()
