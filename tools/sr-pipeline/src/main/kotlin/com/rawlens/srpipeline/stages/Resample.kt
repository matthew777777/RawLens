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
        val h = image.size
        val w = image[0].size
        if (factor == 1) return Array(h) { y -> image[y].copyOf() }
        val sigma = factor * 0.5
        val radius = (4 * factor * 0.5 + 0.5).toInt()
        val kernel = gaussianKernel1d(sigma, radius)
        val k = kernel.size
        require(h >= k && w >= k) {
            "image ${h}x$w is smaller than the gaussian kernel $k (factor $factor)"
        }
        val h2 = (h - k + 1) / factor
        val w2 = (w - k + 1) / factor
        val out = Array(h2) { FloatArray(w2) }
        downsampleInto(image, factor, out)
        return out
    }

    /**
     * [downsample] into caller-owned `out` (same values, no
     * allocation): `out` must be exactly h2×w2. The separable blur
     * streams row by row (one `w`-float row transient, no full-frame
     * tmp/blurred intermediates): a retained full-frame scratch pair
     * was tried first and reverted — 100 MB of live heap on ART, which
     * counts direct-adjacent Java arrays against the same 512 MB
     * ceiling that OOM'd the on-device merge. Per-element accumulation
     * order matches [downsample] exactly, so output is bit-identical.
     *
     * `padH`/`padW` fold the [circularPad] input padding into the reads
     * (cf. `buildGaussianPyramidInto`): element (y, x) of the virtual
     * padded image is `image[y % h][x % w]`, so no 50 MB pad array is
     * retained or allocated. Requires the pads to be smaller than the
     * image (true for tile padding); the wrap is a single predictable
     * branch per tap.
     */
    fun downsampleInto(
        image: Array<FloatArray>,
        factor: Int,
        out: Array<FloatArray>,
        padH: Int = 0,
        padW: Int = 0,
    ) {
        requireRectImage(image, "image")
        require(factor >= 1) { "factor must be >= 1 (got $factor)" }
        require(padH >= 0 && padW >= 0) { "padding must be >= 0" }
        val h = image.size
        val w = image[0].size
        require(padH < h && padW < w) { "padding must be smaller than the image" }
        val H = h + padH
        val W = w + padW
        if (factor == 1) {
            require(out.size == H && out[0].size == W) { "downsampleInto factor-1 out shape mismatch" }
            for (y in 0 until H) {
                val orow = out[y]
                val irow = image[y % h]
                for (x in 0 until W) orow[x] = irow[x % w]
            }
            return
        }
        val sigma = factor * 0.5
        val radius = (4 * factor * 0.5 + 0.5).toInt()
        val kernel = gaussianKernel1d(sigma, radius)
        val k = kernel.size
        require(H >= k && W >= k) {
            "padded image ${H}x$W is smaller than the gaussian kernel $k (factor $factor)"
        }
        val h2 = (H - k + 1) / factor
        val w2 = (W - k + 1) / factor
        require(out.size == h2 && out[0].size == w2) { "downsampleInto out shape mismatch" }
        // (h2-1)*factor+k-1 <= H-1 (and likewise for width): h2 is the
        // floored quotient, so every tap below stays in bounds; taps at
        // row >= h (col >= w) wrap once by construction of the branch.
        val tmpRow = FloatArray(W)
        for (y2 in 0 until h2) {
            val r = y2 * factor
            for (x in 0 until W) {
                val xx = if (x < w) x else x - w
                var acc = 0f
                for (i in 0 until k) {
                    val yy = r + i
                    acc += kernel[i] * image[if (yy < h) yy else yy - h][xx]
                }
                tmpRow[x] = acc
            }
            val orow = out[y2]
            for (x2 in 0 until w2) {
                var acc = 0f
                val base = x2 * factor
                for (j in 0 until k) acc += kernel[j] * tmpRow[base + j]
                orow[x2] = acc
            }
        }
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
     * [buildGaussianPyramid] into caller-owned `levels` (same layout and
     * values, no allocation): `levels[i]` must match the i-th returned
     * level's shape for this run geometry. Every element is overwritten
     * per build, so one holder serves all frames (pad + levels ≈ 87 MB
     * retained at full-res; the blur itself streams row by row, cf.
     * [downsampleInto]). Preprocessing (when non-null) still allocates
     * per call — production configs leave it null (Python mirror),
     * keeping the hot path garbage-free.
     */
    fun pyramidShapes(h: Int, w: Int, factors: List<Int>): List<Pair<Int, Int>> {
        require(factors.isNotEmpty()) { "factors must be non-empty" }
        // Coarse-first level shapes, mirroring buildGaussianPyramid's
        // dim math exactly (factor 1 keeps dims; otherwise (d-k+1)/f
        // floored with the factor's gaussian kernel). Sizes the reuse
        // holder without materializing the 50 MB pad image.
        val fineToCoarse = ArrayList<Pair<Int, Int>>(factors.size)
        var (ch, cw) = h to w
        for (f in factors) {
            if (f != 1) {
                val radius = (4 * f * 0.5 + 0.5).toInt()
                val k = gaussianKernel1d(f * 0.5, radius).size
                ch = (ch - k + 1) / f
                cw = (cw - k + 1) / f
            }
            fineToCoarse.add(ch to cw)
        }
        return fineToCoarse.reversed()
    }

    /**
     * [buildGaussianPyramid] into caller-owned `levels` (same layout and
     * values, no allocation): `levels[i]` must match the i-th returned
     * level's shape for this run geometry (cf. [pyramidShapes]).
     * `padH`/`padW` fold the input [circularPad] into the first level's
     * reads (see [downsampleInto]); deeper levels read full slots.
     * Every element is overwritten per build, so one holder serves all
     * frames (~37 MB of levels retained at full-res, no pad array).
     * Preprocessing (when non-null) still allocates per call —
     * production configs leave it null (Python mirror), keeping the
     * hot path garbage-free.
     */
    fun buildGaussianPyramidInto(
        image: Array<FloatArray>,
        padH: Int,
        padW: Int,
        factors: List<Int>,
        preprocessing: String?,
        levels: MutableList<Array<FloatArray>>,
    ) {
        requireRectImage(image, "image")
        require(factors.isNotEmpty()) { "factors must be non-empty" }
        require(levels.size == factors.size) { "pyramid holder size mismatch" }
        // Preprocessing sees the padded image in the fresh path; with
        // pads the exotic preprocessed combo materializes one transient
        // pad (never the production null-preprocessing path).
        var ph = padH
        var pw = padW
        var level = if (preprocessing != null) {
            if (ph == 0 && pw == 0) Grad.preprocess(image, preprocessing)
            else {
                ph = 0
                pw = 0
                Grad.preprocess(circularPad(image, padH, padW), preprocessing)
            }
        } else image
        val fineToCoarse = ArrayList<Array<FloatArray>>(factors.size)
        for ((i, factor) in factors.withIndex()) {
            // Pyramid levels shrink monotonically, so the holder slot for
            // this depth (addressed coarse-first below) always fits.
            val slot = levels[levels.size - 1 - i]
            downsampleInto(level, factor, slot, ph, pw)
            ph = 0
            pw = 0
            fineToCoarse.add(slot)
            level = slot
        }
        for (i in fineToCoarse.indices) levels[i] = fineToCoarse[fineToCoarse.size - 1 - i]
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
