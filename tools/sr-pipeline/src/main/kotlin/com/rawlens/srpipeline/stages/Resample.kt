package com.rawlens.srpipeline.stages

import kotlin.math.exp
import kotlin.math.floor

/**
 * Resampling stage: gaussian downsampling, gaussian pyramids, flow upscaling.
 *
 * Mirrors `handheld_super_resolution/utils_image.py` (`downsample`) and
 * `handheld_super_resolution/alignment.py` (`build_gaussian_pyramid`,
 * `upscale_lvl`):
 * - `downsample`: factor 1 is a passthrough copy; otherwise a separable
 *   gaussian blur (`sigma = factor * 0.5`,
 *   `radius = int(4 * factor * 0.5 + 0.5)`, kernel normalised to sum 1, cf.
 *   scipy `gaussian_kernel1d` order 0; the `[::-1]` flip is a no-op on the
 *   symmetric kernel) with valid convolution, then strided sampling
 *   `[:h2 * factor:factor]` with `h2 = floor(filtered / factor)`.
 * - `build_gaussian_pyramid(image, factors)`: successive `downsample`
 *   starting from `factors[0]`, returned coarse-to-fine (reversed).
 * - `upscale_lvl(flow, npatchs, l, config)`: `repeat = up // (newTile //
 *   prevTile)`, `F.interpolate` bilinear/nearest by `repeat`, `*= up`,
 *   zero-pad shortfall to `npatchs` tiles.
 *
 * Python config contract: `factors` / `tile_sizes` come from
 * `config.alignment` (fine-to-coarse; the caller passes the two tile sizes
 * around level `l`), `mode` from `config.alignment.flow_upscale_mode`.
 */
object Resample {

    /**
     * 1D gaussian kernel, cf. scipy `gaussian_kernel1d(sigma, order=0,
     * radius)`: `exp(-0.5 (x/sigma)^2)` over `[-radius, radius]`, normalised
     * to sum 1. Computed in double precision, stored once as float.
     */
    fun gaussianKernel1d(sigma: Double, radius: Int): FloatArray {
        require(sigma > 0) { "sigma must be > 0 (got $sigma)" }
        require(radius >= 0) { "radius must be >= 0 (got $radius)" }
        val xs = DoubleArray(2 * radius + 1) { i ->
            val x = (i - radius).toDouble()
            exp(-0.5 * x * x / (sigma * sigma))
        }
        val sum = xs.sum()
        return FloatArray(xs.size) { (xs[it] / sum).toFloat() }
    }

    /**
     * Gaussian downsampling by `factor`, cf. `downsample(th_img,
     * 'gaussian', factor)`. Factor 1 returns a copy (Python returns the
     * tensor itself; a copy preserves the no-aliasing Kotlin convention).
     */
    fun downsample(image: Array<FloatArray>, factor: Int): Array<FloatArray> {
        requireRectImage(image, "image")
        require(factor >= 1) { "factor must be >= 1 (got $factor)" }
        if (factor == 1) return Array(image.size) { y -> image[y].copyOf() }
        val sigma = factor * 0.5
        val radius = (4 * factor * 0.5 + 0.5).toInt()
        val kernel = gaussianKernel1d(sigma, radius)
        val k = kernel.size
        val h = image.size
        val w = image[0].size
        require(h >= k && w >= k) {
            "image ${h}x$w is smaller than the gaussian kernel $k (factor $factor)"
        }
        val tmp = Array(h - k + 1) { y ->
            FloatArray(w) { x ->
                var acc = 0f
                for (i in 0 until k) acc += kernel[i] * image[y + i][x]
                acc
            }
        }
        val blurred = Array(tmp.size) { y ->
            FloatArray(w - k + 1) { x ->
                var acc = 0f
                for (j in 0 until k) acc += kernel[j] * tmp[y][x + j]
                acc
            }
        }
        val h2 = blurred.size / factor
        val w2 = blurred[0].size / factor
        return Array(h2) { y -> FloatArray(w2) { x -> blurred[y * factor][x * factor] } }
    }

    /**
     * Gaussian pyramid, coarse-to-fine, cf. `build_gaussian_pyramid(image,
     * factors)`. `preprocessing` applies [Grad.preprocess] first when
     * non-null, mirroring the Python argument.
     */
    fun buildGaussianPyramid(
        image: Array<FloatArray>,
        factors: List<Int>,
        preprocessing: String? = null,
    ): List<Array<FloatArray>> {
        requireRectImage(image, "image")
        require(factors.isNotEmpty()) { "factors must be non-empty" }
        var level = if (preprocessing != null) Grad.preprocess(image, preprocessing) else image
        val fineToCoarse = mutableListOf<Array<FloatArray>>()
        for (factor in factors) {
            level = downsample(level, factor)
            fineToCoarse.add(level)
        }
        return fineToCoarse.reversed()
    }

    /**
     * Flow upscaling to the next pyramid level, cf. `upscale_lvl`.
     *
     * @param flow current tile flow `[nTy][nTx][2]` (x, y).
     * @param targetTiles tile grid `[nTy, nTx]` of the next level (`npatchs`).
     * @param newTileSize tile size at level `l`, @param prevTileSize at `l+1`.
     * @param upsamplingFactor `factors[l+1]`.
     * @param mode `bilinear` (torch `F.interpolate` `align_corners=False`
     * mapping `src = (dst + 0.5) / repeat - 0.5` with clamped taps) or
     * `nearest` (integer repeat).
     */
    fun upscaleLvl(
        flow: Array<Array<FloatArray>>,
        targetTiles: IntArray,
        newTileSize: Int,
        prevTileSize: Int,
        upsamplingFactor: Int,
        mode: String = "bilinear",
    ): Array<Array<FloatArray>> {
        require(targetTiles.size == 2 && targetTiles[0] >= 1 && targetTiles[1] >= 1) {
            "targetTiles must be [nTy >= 1, nTx >= 1] (got ${targetTiles.toList()})"
        }
        require(newTileSize >= prevTileSize && newTileSize % prevTileSize == 0) {
            "tile sizes must divide (got new=$newTileSize prev=$prevTileSize)"
        }
        require(upsamplingFactor >= 1) { "upsamplingFactor must be >= 1 (got $upsamplingFactor)" }
        val repeat = upsamplingFactor / (newTileSize / prevTileSize)
        require(repeat >= 1) { "repeat factor must be >= 1 (got $repeat)" }
        val upsampled = when (mode) {
            "nearest" -> repeatFlow(flow, repeat)
            "bilinear" -> bilinearFlow(flow, repeat)
            else -> throw IllegalArgumentException("Unknown flow upscale mode: $mode")
        }
        val scaled = Array(upsampled.size) { ty ->
            Array(upsampled[ty].size) { tx ->
                floatArrayOf(
                    upsampled[ty][tx][0] * upsamplingFactor,
                    upsampled[ty][tx][1] * upsamplingFactor,
                )
            }
        }
        // Zero-pad shortfall, mirroring the `F.pad` tail of `upscale_lvl`.
        return Array(targetTiles[0]) { ty ->
            Array(targetTiles[1]) { tx ->
                if (ty < scaled.size && tx < scaled[0].size) scaled[ty][tx]
                else floatArrayOf(0f, 0f)
            }
        }
    }

    private fun repeatFlow(flow: Array<Array<FloatArray>>, repeat: Int): Array<Array<FloatArray>> =
        Array(flow.size * repeat) { ty ->
            Array(flow[0].size * repeat) { tx ->
                floatArrayOf(flow[ty / repeat][tx / repeat][0], flow[ty / repeat][tx / repeat][1])
            }
        }

    // strictfp: the tap blends are mul+add patterns the HotSpot JIT fuses
    // into FMA while ART evaluates them unfused, which diverged finer-level
    // flow init device-vs-desktop at 1e-8 (LEVELCK p=2/p=3) and broke e2e
    // bit-identity. Strict IEEE on both sides (no quality change: the same
    // correctly-rounded elementary ops, identically ordered).
    private fun bilinearFlow(flow: Array<Array<FloatArray>>, repeat: Int): Array<Array<FloatArray>> {
        if (repeat == 1) {
            return Array(flow.size) { ty ->
                Array(flow[ty].size) { tx -> floatArrayOf(flow[ty][tx][0], flow[ty][tx][1]) }
            }
        }
        val nTy = flow.size
        val nTx = flow[0].size
        return Array(nTy * repeat) { ty ->
            Array(nTx * repeat) { tx ->
                val fy = (ty + 0.5) / repeat - 0.5
                val fx = (tx + 0.5) / repeat - 0.5
                floatArrayOf(sampleBilinear(flow, nTy, nTx, 0, fy, fx),
                    sampleBilinear(flow, nTy, nTx, 1, fy, fx))
            }
        }
    }

    // strictfp: see bilinearFlow. Hoisted out of bilinearFlow (rather than a
    // local fun) so the modifier provably applies on every backend.
    private fun sampleBilinear(
        flow: Array<Array<FloatArray>>, nTy: Int, nTx: Int,
        channel: Int, fy: Double, fx: Double,
    ): Float {
        // Torch `border_clamp`: clamp floor(src) and floor(src)+1, not
        // the clamped floor plus one (they differ at the low border).
        val jy0 = floor(fy).toInt()
        val jx0 = floor(fx).toInt()
        val y0 = jy0.coerceIn(0, nTy - 1)
        val x0 = jx0.coerceIn(0, nTx - 1)
        val y1 = (jy0 + 1).coerceIn(0, nTy - 1)
        val x1 = (jx0 + 1).coerceIn(0, nTx - 1)
        val wy = (fy - floor(fy)).toFloat()
        val wx = (fx - floor(fx)).toFloat()
        val top = flow[y0][x0][channel] * (1 - wx) + flow[y0][x1][channel] * wx
        val bot = flow[y1][x0][channel] * (1 - wx) + flow[y1][x1][channel] * wx
        return top * (1 - wy) + bot * wy
    }
}
