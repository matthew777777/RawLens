// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Prompt 4C CPU oracle: noise-aware motion robustness (Wronski/IPOL Alg. 6–9).
 * Implements [docs/raw-sr-robustness.md]; the document is normative.
 *
 * Pipeline per moving frame: linear 3-channel guide → 3x3 local stats → warp
 * moving means into reference coordinates (nearest tile flow, bilinear samples)
 * → expected disagreement from measured reference variance plus model moving
 * variance → flow-irregularity scaling → threshold → 5x5 local minimum.
 * Unreliable tiles test the zero-shift hypothesis explicitly (flagged, never
 * silent). Hard gates (bounds, validity, residual, saturation, conflict) force
 * exact zero weights with explicit per-quad flags.
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
    /** Hot-pixel rail: the quad holds a masked stuck-bright tap (see RawSrHotPixel). */
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
     * Linear 3-channel guide on the quad grid: R/B single samples, G mean of two.
     * Values are unshaded normalized observations; alpha/beta are the channel
     * normalized noise coefficients; rail marks quads with any near-rail sample.
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
                red[o] = r
                green[o] = (g * 0.5f)
                blue[o] = b
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
     * @param noiseLut measured noise correction (Jamy-L `compute_d_sigma`
     * noise-correction mirror): sigma² floors at the LUT value and d² shrinks
     * by `(d²/(d²+d²_LUT))²` at the measured reference brightness. Null keeps
     * the analytic expected variance. Only applies when both models are valid.
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
        // Reference local statistics (Alg. 8): 3x3 clamp-to-edge mean and variance.
        val refMean = Array(3) { FloatArray(width * height) }
        val refVar = Array(3) { FloatArray(width * height) }
        // Moving local means only; moving variance comes from the noise model.
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
                    refVar[c][o] = maxOf(squares / 9f - mean * mean, 0f)
                    movMean[c][o] = movSum / 9f
                }
            }
        }
        val threshold = tuning.t.toFloat()
        val motionThreshold = motionThresholdQuad(tuning)
        val raw = FloatArray(width * height)
        val flags = IntArray(width * height)
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            val flowScratch = FloatArray(4)
            for (y in y0 until y1) for (x in 0 until width) {
                val o = y * width + x
                // Smooth (bilinear) sampling: tile borders must not reach the merge.
                // Into form is bitwise-identical with no per-quad boxing.
                flow.flowAtSmoothInto(x.toFloat(), y.toFloat(), flowScratch)
                val tileDx = flowScratch[0]
                val tileDy = flowScratch[1]
                val tileResidual = flowScratch[2]
                val tileReliable = flowScratch[3] >= 0.5f
                var flag = 0
                if (!tileDx.isFinite() || !tileDy.isFinite()) {
                    raw[o] = 0f
                    flags[o] = FLAG_INVALID_FLOW
                    continue
                }
                // Warp target and saturation use the tested displacement: flow when the
                // tile is reliable, the explicit zero-shift hypothesis otherwise.
                val dx = if (tileReliable) tileDx else 0f
                val dy = if (tileReliable) tileDy else 0f
            val centerX = x + dx
            val centerY = y + dy
            if (centerX < 0f || centerY < 0f || centerX >= width || centerY >= height) {
                raw[o] = 0f
                flags[o] = FLAG_OUT_OF_BOUNDS
                continue
            }
            val warpQuadX = floor(centerX + 0.5f).toInt().coerceIn(0, width - 1)
            val warpQuadY = floor(centerY + 0.5f).toInt().coerceIn(0, height - 1)
            if (reference.rail[o] || moving.rail[warpQuadY * width + warpQuadX]) {
                raw[o] = 0f
                // Hot quads rail through the same zero-weight gate, but keep
                // their own flag: a stuck tap is a sensor defect, not a
                // saturated highlight, and the merge-debug payload tells them
                // apart. Mirrored in robustness.glsl.
                flags[o] = if (reference.hot[o] || moving.hot[warpQuadY * width + warpQuadX])
                    FLAG_HOTPIXEL else FLAG_SATURATED
                continue
            }
            if (tileReliable && tileResidual > config.maxMeanAbsoluteResidual) {
                raw[o] = 0f
                flags[o] = FLAG_RESIDUAL
                continue
            }
            // Bilinear moving means with interpolation weight correction.
            var distance = 0.0
            var variance = 0.0
            val x0 = floor(centerX).toInt()
            val y0 = floor(centerY).toInt()
            val fx = centerX - x0
            val fy = centerY - y0
            var weightSquares = 0.0
            for (c in 0..2) {
                var warped = 0.0
                for (i in 0..1) for (j in 0..1) {
                    val xx = (x0 + j).coerceIn(0, width - 1)
                    val yy = (y0 + i).coerceIn(0, height - 1)
                    val weight = (if (j == 0) 1.0 - fx.toDouble() else fx.toDouble()) *
                        (if (i == 0) 1.0 - fy.toDouble() else fy.toDouble())
                    warped += movMean[c][yy * width + xx] * weight
                    if (c == 0) weightSquares += weight * weight
                }
                val error = refMean[c][o] - warped
                distance += error * error
                if (moving.modelValid) {
                    val expected = moving.alpha[c] * warped + moving.beta[c]
                    variance += refVar[c][o] + weightSquares * maxOf(expected, 0.0) / 9.0
                }
            }
            // Measured noise correction (null LUT is a no-op): brightness is the
            // mean reference channel mean, exactly the LUT's binning key.
            var correctedDistance = distance
            var correctedVariance = variance
            if (noiseLut != null && reference.modelValid && moving.modelValid) {
                val brightness = ((refMean[0][o] + refMean[1][o] + refMean[2][o]) / 3f).coerceIn(0f, 1f)
                val sample = noiseLut.sample(brightness)
                correctedVariance = maxOf(variance, sample.sigmaSq.toDouble())
                if (distance > 0.0) {
                    val shrink = distance / (distance + sample.dSq.toDouble())
                    correctedDistance = distance * shrink * shrink
                }
            }
            var value: Float
            if (!reference.modelValid || !moving.modelValid) {
                // No usable model on at least one side: the photo gate stands
                // open; alignment, validity, and saturation gates still bind.
                value = 1f
                flag = if (reference.model == Model.ZERO_NOISE && moving.model == Model.ZERO_NOISE)
                    FLAG_MODEL_ZERO else FLAG_MODEL_MISSING
            } else if (correctedVariance <= 0.0) {
                // Exact silence on both sides agrees; any difference rejects.
                value = if (correctedDistance == 0.0) 1f else 0f
                if (correctedDistance != 0.0) flag = FLAG_PHOTO_CONFLICT
            } else {
                val irregular = flowIrregular(flow, x, y, motionThreshold)
                val scale = if (!tileReliable || irregular) tuning.s1.toFloat() else tuning.s2.toFloat()
                value = (scale * exp(-(correctedDistance / correctedVariance).toFloat()) - threshold).coerceIn(0f, 1f)
                if (value == 0f) flag = FLAG_PHOTO_CONFLICT
                // Reliable-but-irregular tiles merge under the motion scale:
                // mark them so device builds can attribute lost support to
                // flow spread rather than photo conflict. Unreliable tiles
                // keep their own flags below; this bit never fires for them.
                if (tileReliable && irregular) flag = flag or FLAG_MOTION_IRREGULAR
            }
            if (!tileReliable) {
                if (value > 0f) {
                    flag = flag or FLAG_STATIC_HYPOTHESIS
                } else {
                    flag = (flag and FLAG_PHOTO_CONFLICT.inv()) or FLAG_FLOW_UNRELIABLE
                    value = 0f
                }
            }
            if (!value.isFinite()) {
                value = 0f
                flag = flag or FLAG_PHOTO_CONFLICT
            }
                raw[o] = value
                flags[o] = flag
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

    /** 3x3 tile flow spread (in-bounds tiles only); true when motion is irregular. */
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
            if (!dx.isFinite() || !dy.isFinite()) return true
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
