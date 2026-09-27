// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * CPU oracle: noise-aware motion robustness, Jamy-L Algs. 6–9 verbatim
 * (`robustness.py`: `compute_robustness`, `compute_guide_image`,
 * `compute_local_stats`, `warp_stats`, `compute_d_sigma`, `compute_s`,
 * `robustness_threshold`, `local_min`).
 *
 * Pipeline per moving frame: sqrt 3-channel guide (Alg. 7) → 3x3 local stats
 * (Alg. 8) → Dogson-biquadratic warp of the moving means into reference
 * coordinates at the bilinear flow sample → color distance over measured
 * reference variance, with the measured-LUT noise correction → s1/s2
 * flow-irregularity scaling → `clamp(S*exp(-d²/σ²)-t)` threshold → 5x5 local
 * minimum (Alg. 9). Out-of-bounds warps and non-finite flow weigh exactly
 * zero; the reference defines no other gate (no rail, residual, reliability,
 * or model-validity bypass), so the base path has none either. The flag
 * constants below stay for diagnostics and A/B; the base path reports OOB
 * and invalid-flow rejections only.
 *
 * Scalar Float/Double arithmetic over flat arrays; no object is allocated per
 * pixel.
 */
object RawSrRobustness {
    const val FLAG_FLOW_UNRELIABLE = 1
    const val FLAG_STATIC_HYPOTHESIS = 2
    const val FLAG_RESIDUAL = 4
    const val FLAG_OUT_OF_BOUNDS = 8
    const val FLAG_INVALID_FLOW = 16
    const val FLAG_SATURATED = 32
    const val FLAG_PHOTO_CONFLICT = 64
    const val FLAG_MODEL_MISSING = 128
    const val FLAG_MODEL_ZERO = 256
    /** Defective-tap rail: the quad holds a masked stuck-bright or stuck-dark tap (see RawSrHotPixel). */
    const val FLAG_HOTPIXEL = 512
    /** Unblocker attenuation: the quad lost signal variance to blocking (see RawSrUnblocker). */
    const val FLAG_UNBLOCKED = 1024
    /**
     * Motion-irregular tile with reliable flow: the quad merged under the
     * s1 (motion) scale rather than s2. Diagnostic only — same weight math;
     * lets device builds map where support is lost to flow spread vs photo
     * conflict (shadow-uniformity attribution).
     */
    const val FLAG_MOTION_IRREGULAR = 2048

    /** The single 4C-chosen constant: rail proximity in noise sigmas (§2). */
    const val RAIL_SIGMA = 3.0

    /** Mth is published in raw pixels; our flow lives in quad units. */
    internal fun motionThresholdQuad(tuning: RawSrTuning) = (tuning.mTh / 2.0).toFloat()

    /**
     * Sqrt 3-channel guide on the quad grid (Alg. 7): `sqrt(R)`,
     * `sqrt(mean of the two greens)`, `sqrt(B)` over unshaded normalized
     * observations. Alpha/beta are the channel normalized noise coefficients;
     * rail marks quads with any near-rail sample. The base robustness path
     * consumes the three color planes only; the remaining fields feed
     * diagnostics and A/B stages.
     */
    /** Model provenance; only VALID and ZERO_SHOT close the photo gate. */
    enum class Model { VALID, ZERO_SHOT, MISSING, INVALID, ZERO_NOISE }

    data class LinearGuide(
        val width: Int,
        val height: Int,
        val red: FloatArray,
        val green: FloatArray,
        val blue: FloatArray,
        val alpha: FloatArray,
        val beta: FloatArray,
        val model: Model,
        val rail: BooleanArray,
        /**
         * Hot-pixel quads (any masked tap; see RawSrHotPixel.quadHot). Hot
         * quads always rail, but keep their own flag so diagnostics can tell
         * a stuck tap from a saturated one. Defaults to all-clear so existing
         * constructions without a mask keep their behaviour.
         */
        val hot: BooleanArray = BooleanArray(width * height)
    ) {
        val modelValid: Boolean get() = model == Model.VALID || model == Model.ZERO_SHOT
        init {
            require(width > 0 && height > 0)
            require(red.size == width * height && green.size == width * height && blue.size == width * height)
            require(alpha.size == 3 && beta.size == 3)
            require(rail.size == width * height)
            require(hot.size == width * height)
        }
        fun channel(index: Int): FloatArray = when (index) {
            0 -> red
            1 -> green
            else -> blue
        }
    }

    data class FrameRobustness(val width: Int, val height: Int, val r: FloatArray, val flags: IntArray) {
        init {
            require(width > 0 && height > 0)
            require(r.size == width * height && flags.size == width * height)
        }
    }

    data class RcField(val width: Int, val height: Int, val values: FloatArray) {
        init {
            require(width > 0 && height > 0 && values.size == width * height)
        }
    }

    /** Adapter-path parameters: no codes exist, so robustness is skipped. */
    fun bypass(): GpuParams = GpuParams(FloatArray(3), FloatArray(3), FloatArray(4),
        FloatArray(4), FloatArray(4), 1f, false)

    /** GPU parameters per frame: channel coefficients plus the code-domain pairs. */
    data class GpuParams(
        val alpha: FloatArray,
        val beta: FloatArray,
        val slope: FloatArray,
        val offset: FloatArray,
        val black: FloatArray,
        val white: Float,
        val modelValid: Boolean,
        /** Measured noise LUT (reference-derived); null disables the GPU correction. */
        val noiseLut: RawSrNoiseLut.Lut? = null
    ) {
        init {
            require(alpha.size == 3 && beta.size == 3 && slope.size == 4 && offset.size == 4 && black.size == 4)
            require(white.isFinite())
        }
    }

    /**
     * Linear 3-channel guide on the quad grid (see [linearGuide]). When
     * [hotMask] is null the frame's hot-pixel mask is detected internally;
     * pass an explicit mask to share one detection across the guide and the
     * sample inpainting.
     */
    fun linearGuide(frame: RawSrPackedFrame, hotMask: BooleanArray? = null): LinearGuide {
        require(frame.width % 2 == 0 && frame.height % 2 == 0) {
            "Robustness guide requires complete Bayer quads"
        }
        val input = frame.uploadInput()
        val tables = RawSrCovarianceGuide.noiseTables(input, frame.noiseProfile)
        val model = when (tables.modelClass) {
            RawSrCovarianceGuide.ModelClass.VALID -> Model.VALID
            RawSrCovarianceGuide.ModelClass.ZERO_SHOT -> Model.ZERO_SHOT
            RawSrCovarianceGuide.ModelClass.MISSING -> Model.MISSING
            RawSrCovarianceGuide.ModelClass.INVALID -> Model.INVALID
            RawSrCovarianceGuide.ModelClass.ZERO_NOISE -> Model.ZERO_NOISE
        }
        val modelValid = model == Model.VALID || model == Model.ZERO_SHOT
        val source = input.buffer.duplicate().order(ByteOrder.nativeOrder())
        val base = source.position()
        val crop = input.crop
        val outWidth = crop.width / 2
        val outHeight = crop.height / 2
        val red = FloatArray(outWidth * outHeight)
        val green = FloatArray(outWidth * outHeight)
        val blue = FloatArray(outWidth * outHeight)
        val alpha = FloatArray(3)
        val beta = FloatArray(3)
        val rail = BooleanArray(outWidth * outHeight)
        // Hot-pixel rail shares this pass: one code read serves the guide
        // values, the saturation rail, and the mask projection below.
        val mask = if (hotMask != null) {
            require(hotMask.size == crop.width * crop.height) { "Hot mask must cover the frame" }
            hotMask
        } else {
            RawSrHotPixel.detectPacked(frame)
        }
        val quadHot = RawSrHotPixel.quadHot(mask, crop.width, crop.height)
        // Channel coefficients are constant per frame: reduce the first quad's
        // phases once (every quad holds one R, two G, one B sample). Sealed
        // up front with the identical 2x2 reduction so the pixel loop below
        // shards over disjoint rows with no shared mutable state.
        run {
            var aR = 0.0; var bR = 0.0; var aG = 0.0; var bG = 0.0; var aB = 0.0; var bB = 0.0
            for (i in 0..1) for (j in 0..1) {
                val sx = input.sensorCropLeft + j
                val sy = input.sensorCropTop + i
                val phase = ((i and 1) shl 1) or (j and 1)
                when (input.normalization.sensorPattern.colorAt(sx, sy)) {
                    CfaColor.RED -> { aR = tables.alpha[phase]; bR = tables.beta[phase] }
                    CfaColor.GREEN -> { aG += tables.alpha[phase]; bG += tables.beta[phase] }
                    CfaColor.BLUE -> { aB = tables.alpha[phase]; bB = tables.beta[phase] }
                }
            }
            alpha[0] = aR.toFloat(); alpha[1] = (aG * 0.25).toFloat(); alpha[2] = aB.toFloat()
            beta[0] = bR.toFloat(); beta[1] = (bG * 0.25).toFloat(); beta[2] = bB.toFloat()
        }
        RawSrWorkers.forEachShard(outHeight) { y0, y1 ->
            for (qy in y0 until y1) for (qx in 0 until outWidth) {
                var r = 0f
                var g = 0f
                var b = 0f
                var greens = 0
                var quadRail = false
                for (i in 0..1) for (j in 0..1) {
                    val x = qx * 2 + j
                    val y = qy * 2 + i
                    val sx = input.sensorCropLeft + x
                    val sy = input.sensorCropTop + y
                    val phase = ((y and 1) shl 1) or (x and 1)
                    val offset = base + (crop.top + y) * input.layout.rowStride + (crop.left + x) * 2
                    val code = source.getShort(offset).toInt() and 65535
                    val black = input.normalization.blackAt(sx, sy).toDouble()
                    val white = input.normalization.whiteLevel.toDouble()
                    val value = ((code - black) / (white - black)).toFloat()
                    if (modelValid) {
                        val sigma = sqrt(tables.slope[phase] * code + tables.offset[phase])
                        // Highlight-side rail only: a tap within 3σ of white
                        // is clipped and carries no signal. There is NO shadow
                        // rail — signal within 3σ of black is normal
                        // read-noise-limited data, and zeroing it would deny
                        // shadows every moving frame (lifted noise floor);
                        // below-black evidence is judged by the photo term.
                        if (code > white - RAIL_SIGMA * sigma) quadRail = true
                    }
                    when (input.normalization.sensorPattern.colorAt(sx, sy)) {
                        CfaColor.RED -> {
                            r = value
                        }
                        CfaColor.GREEN -> {
                            g += value; greens++
                        }
                        CfaColor.BLUE -> {
                            b = value
                        }
                    }
                }
                require(greens == 2) { "Bayer quads must hold exactly two green samples" }
                val o = qy * outWidth + qx
                // Alg. 7: sqrt of the clipped quad value; green takes the
                // square root of the two-tap mean, not the mean of roots.
                red[o] = sqrt(maxOf(r, 0f))
                green[o] = sqrt(maxOf(g * 0.5f, 0f))
                blue[o] = sqrt(maxOf(b, 0f))
                // A hot quad always rails: its mean carries a stuck tap even
                // where no sample happens to sit near the code rail.
                rail[o] = quadRail || quadHot[o]
            }
        }
        return LinearGuide(outWidth, outHeight, red, green, blue, alpha, beta, model, rail, quadHot)
    }

    fun gpuParams(frame: RawSrPackedFrame): GpuParams {
        val input = frame.uploadInput()
        val tables = RawSrCovarianceGuide.noiseTables(input, frame.noiseProfile)
        val modelValid = tables.modelClass == RawSrCovarianceGuide.ModelClass.VALID ||
            tables.modelClass == RawSrCovarianceGuide.ModelClass.ZERO_SHOT
        // Channel reduction mirrors linearGuide's first quad.
        val alpha = FloatArray(3)
        val beta = FloatArray(3)
        var greens = 0
        for (i in 0..1) for (j in 0..1) {
            val sx = input.sensorCropLeft + j
            val sy = input.sensorCropTop + i
            val phase = ((i and 1) shl 1) or (j and 1)
            when (input.normalization.sensorPattern.colorAt(sx, sy)) {
                CfaColor.RED -> {
                    alpha[0] = tables.alpha[phase].toFloat(); beta[0] = tables.beta[phase].toFloat()
                }
                CfaColor.GREEN -> {
                    alpha[1] += tables.alpha[phase].toFloat(); beta[1] += tables.beta[phase].toFloat(); greens++
                }
                CfaColor.BLUE -> {
                    alpha[2] = tables.alpha[phase].toFloat(); beta[2] = tables.beta[phase].toFloat()
                }
            }
        }
        require(greens == 2) { "Bayer quads must hold exactly two green samples" }
        // Variance of the 2-sample green MEAN is (v1+v2)/4, not the mean of
        // the pair variances: the quarter keeps green differences honest
        // against single-sample R/B in the photo term (chroma mottling when
        // green is discounted 2x). Mirrors linearGuide's first quad.
        alpha[1] *= 0.25f
        beta[1] *= 0.25f
        val black = FloatArray(4) {
            input.normalization.blackAt(input.sensorCropLeft + (it and 1), input.sensorCropTop + (it shr 1))
        }
        return GpuParams(
            alpha, beta,
            FloatArray(4) { tables.slope[it].toFloat() }, FloatArray(4) { tables.offset[it].toFloat() },
            black, input.normalization.whiteLevel, modelValid)
    }

    /**
     * Reference `compute_robustness` (Alg. 6) over the sqrt guides: Dogson
     * warp of the moving means at the nearest-tile flow, color distance over
     * measured reference variance with the optional measured-LUT correction,
     * s1/s2 flow-irregularity scaling, threshold, 5x5 local minimum (Alg. 9).
     *
     * @param config accepted for call-site stability and ignored: the
     * reference defines no residual or reliability gate.
     * @param noiseLut measured noise correction (reference `noise_correction`):
     * sigma² floors at the LUT value and d² shrinks by
     * `(d²/(d²+d²_LUT))²` at the measured reference brightness. Null disables
     * the correction, exactly like the reference.
     */
    fun evaluate(
        reference: LinearGuide,
        moving: LinearGuide,
        flow: RawSrAlignmentField,
        tuning: RawSrTuning,
        config: RawSrAlignmentConfig,
        noiseLut: RawSrNoiseLut.Lut? = null
    ): FrameRobustness {
        require(reference.width == moving.width && reference.height == moving.height)
        require(flow.imageWidth == reference.width && flow.imageHeight == reference.height)
        val width = reference.width
        val height = reference.height
        // Local statistics (Alg. 8): 3x3 clamp-to-edge means everywhere, plus
        // the reference variances. The moving variance never enters: sigma²
        // is measured reference variance only (plus the LUT floor).
        val refMean = Array(3) { FloatArray(width * height) }
        val refVar = Array(3) { FloatArray(width * height) }
        val movMean = Array(3) { FloatArray(width * height) }
        for (c in 0..2) {
            val ref = reference.channel(c)
            val mov = moving.channel(c)
            RawSrWorkers.forEachShard(height) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until width) {
                    var sum = 0f
                    var squares = 0f
                    var movSum = 0f
                    for (i in -1..1) for (j in -1..1) {
                        val xx = (x + j).coerceIn(0, width - 1)
                        val yy = (y + i).coerceIn(0, height - 1)
                        val v = ref[yy * width + xx]
                        sum += v
                        squares += v * v
                        movSum += mov[yy * width + xx]
                    }
                    val o = y * width + x
                    val mean = sum / 9f
                    refMean[c][o] = mean
                    // Reference stores the raw (possibly slightly negative)
                    // variance; the max() below only guards float rounding,
                    // and sigma² <= 0 still rejects through the threshold.
                    refVar[c][o] = maxOf(squares / 9f - mean * mean, 0f)
                    movMean[c][o] = movSum / 9f
                }
            }
        }
        val threshold = tuning.t.toFloat()
        val motionThreshold = motionThresholdQuad(tuning)
        val s1 = tuning.s1.toFloat()
        val s2 = tuning.s2.toFloat()
        val raw = FloatArray(width * height)
        val flags = IntArray(width * height)
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            val warped = DoubleArray(3)
            val flowScratch = FloatArray(4)
            for (y in y0 until y1) for (x in 0 until width) {
                val o = y * width + x
                // Bilinear flow sampling: the warp target blends the four
                // surrounding tiles so tile borders never quilt the
                // robustness field (the tile-spread irregularity gate below
                // still reads discrete tiles). Non-finite corners fall back
                // to the containing tile.
                flow.flowAtSmoothInto(x.toFloat(), y.toFloat(), flowScratch)
                val dx = flowScratch[0]
                val dy = flowScratch[1]
                if (!dx.isFinite() || !dy.isFinite()) {
                    raw[o] = 0f
                    flags[o] = FLAG_INVALID_FLOW
                    continue
                }
                val centerX = x + dx.toDouble()
                val centerY = y + dy.toDouble()
                if (centerX < 0.0 || centerY < 0.0 || centerX >= width || centerY >= height) {
                    // Reference OOB: warped means are +inf, so R = 0.
                    raw[o] = 0f
                    flags[o] = FLAG_OUT_OF_BOUNDS
                    continue
                }
                warpDogson(movMean, width, height, centerX, centerY, warped)
                var distance = 0.0
                var variance = 0.0
                for (c in 0..2) {
                    val error = refMean[c][o] - warped[c]
                    distance += error * error
                    variance += refVar[c][o]
                }
                // Measured noise correction (null LUT is a no-op): brightness
                // is the mean reference channel mean, the LUT's binning key.
                var correctedDistance = distance
                var correctedVariance = variance
                if (noiseLut != null) {
                    val brightness = ((refMean[0][o] + refMean[1][o] + refMean[2][o]) / 3f).coerceIn(0f, 1f)
                    val sample = noiseLut.sample(brightness)
                    correctedVariance = maxOf(variance, sample.sigmaSq.toDouble())
                    if (distance > 0.0) {
                        val shrink = distance / (distance + sample.dSq.toDouble())
                        correctedDistance = distance * shrink * shrink
                    }
                }
                val scale = if (flowIrregular(flow, x, y, motionThreshold)) s1 else s2
                var value = (scale * exp(-(correctedDistance / correctedVariance).toFloat()) - threshold)
                    .coerceIn(0f, 1f)
                if (!value.isFinite()) {
                    // Matches the reference clamp outcome: sigma² = 0 rejects
                    // (0/0 disagrees through fmax, d² > 0 underflows to zero).
                    value = 0f
                }
                raw[o] = value
                flags[o] = 0
            }
        }
        // Local minimum over a 5x5 clamp window (Alg. 9); flags stay own-quad.
        val r = FloatArray(width * height)
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until width) {
                var minimum = Float.POSITIVE_INFINITY
                for (i in -2..2) for (j in -2..2)
                    minimum = minOf(minimum, raw[((y + i).coerceIn(0, height - 1)) * width + (x + j).coerceIn(0, width - 1)])
                r[y * width + x] = minimum
            }
        }
        return FrameRobustness(width, height, r, flags)
    }

    /** Reference `dogson_quadratic_kernel` (utils_image.py). */
    internal fun dogsonQuadratic(x: Double): Double {
        val a = kotlin.math.abs(x)
        return if (a <= 0.5) -2.0 * a * a + 1.0
        else if (a <= 1.5) a * a - 2.5 * a + 1.5
        else 0.0
    }

    /**
     * Reference `cuda_warp_dogson`: the moving 3x3 means resampled at
     * ([centerX], [centerY]) with the separable Dogson biquadratic kernel
     * over the rounded, edge-clamped 3x3 window, normalized by the summed
     * weights. The caller guarantees the center is in bounds.
     */
    internal fun warpDogson(
        movMean: Array<FloatArray>,
        width: Int,
        height: Int,
        centerX: Double,
        centerY: Double,
        out: DoubleArray
    ) {
        // Reference round(): half-to-even, like Math.rint.
        val centerQuadX = Math.rint(centerX).toInt()
        val centerQuadY = Math.rint(centerY).toInt()
        var wAcc = 0.0
        out[0] = 0.0
        out[1] = 0.0
        out[2] = 0.0
        for (i in -1..1) {
            val yy = (centerQuadY + i).coerceIn(0, height - 1)
            val wy = dogsonQuadratic(yy - centerY)
            for (j in -1..1) {
                val xx = (centerQuadX + j).coerceIn(0, width - 1)
                val w = wy * dogsonQuadratic(xx - centerX)
                for (c in 0..2) out[c] += movMean[c][yy * width + xx] * w
                wAcc += w
            }
        }
        out[0] /= wAcc
        out[1] /= wAcc
        out[2] /= wAcc
    }

    /**
     * Reference `cuda_compute_s`: 3x3 tile flow spread (in-bounds tiles,
     * reliability-blind); true when motion is irregular. Non-finite tiles
     * are reference-undefined and skipped as missing data; with no finite
     * tile the verdict is conservatively irregular.
     */
    internal fun flowIrregular(flow: RawSrAlignmentField, x: Int, y: Int, motionThreshold: Float): Boolean {
        val tileX = (x / flow.tileSize).coerceIn(0, flow.columns - 1)
        val tileY = (y / flow.tileSize).coerceIn(0, flow.rows - 1)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var finite = false
        val direct = flow.tiles as? RawSrDirectFlowTiles
        for (i in -1..1) for (j in -1..1) {
            val tx = tileX + j
            val ty = tileY + i
            if (tx < 0 || ty < 0 || tx >= flow.columns || ty >= flow.rows) continue
            val dx: Float
            val dy: Float
            if (direct != null) {
                val index = ty * flow.columns + tx
                dx = direct.directDx(index)
                dy = direct.directDy(index)
            } else {
                val tile = flow.tiles[ty * flow.columns + tx]
                dx = tile.dx
                dy = tile.dy
            }
            if (!dx.isFinite() || !dy.isFinite()) continue
            finite = true
            minX = minOf(minX, dx)
            minY = minOf(minY, dy)
            maxX = maxOf(maxX, dx)
            maxY = maxOf(maxY, dy)
        }
        if (!finite) return true
        val spreadX = maxX - minX
        val spreadY = maxY - minY
        return spreadX * spreadX + spreadY * spreadY > motionThreshold * motionThreshold
    }

    /**
     * Merge motion-edge stop verdict: true only on demonstrated tile
     * disagreement (two finite in-bounds tiles apart). A non-finite
     * neighbour is missing data, not motion — unlike [flowIrregular]
     * (which conservatively scales robustness weights on any doubt), a veto
     * here forfeits fusion, so unknown tiles merge with the robustness
     * gates as backstop. Mirrors the merge shader's finite-count gate
     * exactly: fewer than two finite tiles cannot disagree.
     */
    internal fun flowDisagrees(flow: RawSrAlignmentField, x: Int, y: Int, motionThreshold: Float): Boolean {
        val tileX = (x / flow.tileSize).coerceIn(0, flow.columns - 1)
        val tileY = (y / flow.tileSize).coerceIn(0, flow.rows - 1)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var finiteCount = 0
        val direct = flow.tiles as? RawSrDirectFlowTiles
        for (i in -1..1) for (j in -1..1) {
            val tx = tileX + j
            val ty = tileY + i
            if (tx < 0 || ty < 0 || tx >= flow.columns || ty >= flow.rows) continue
            val dx: Float
            val dy: Float
            val reliable: Boolean
            if (direct != null) {
                val index = ty * flow.columns + tx
                dx = direct.directDx(index)
                dy = direct.directDy(index)
                reliable = direct.directReliable(index)
            } else {
                val tile = flow.tiles[ty * flow.columns + tx]
                dx = tile.dx
                dy = tile.dy
                reliable = tile.reliable
            }
            if (!dx.isFinite() || !dy.isFinite()) continue
            if (!reliable) continue
            finiteCount++
            minX = minOf(minX, dx)
            minY = minOf(minY, dy)
            maxX = maxOf(maxX, dx)
            maxY = maxOf(maxY, dy)
        }
        if (finiteCount < 2) return false
        val spreadX = maxX - minX
        val spreadY = maxY - minY
        return spreadX * spreadX + spreadY * spreadY > motionThreshold * motionThreshold
    }

    /** The motion-edge decision is constant within an alignment tile. */
    internal fun flowDisagreementTiles(flow: RawSrAlignmentField, motionThreshold: Float): BooleanArray =
        BooleanArray(flow.columns * flow.rows) { index ->
            flowDisagrees(flow, (index % flow.columns) * flow.tileSize,
                (index / flow.columns) * flow.tileSize, motionThreshold)
        }

    /** Exact once-per-quad accumulation; rc null starts from zero. */
    fun accumulate(rc: RcField?, frame: FrameRobustness): RcField {
        val base = rc ?: RcField(frame.width, frame.height, FloatArray(frame.width * frame.height))
        require(base.width == frame.width && base.height == frame.height)
        val values = base.values.copyOf()
        for (i in values.indices) values[i] += frame.r[i]
        return RcField(frame.width, frame.height, values)
    }
}
