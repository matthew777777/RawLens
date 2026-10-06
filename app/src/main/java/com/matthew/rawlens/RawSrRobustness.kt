// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * CPU oracle: noise-aware motion robustness, Jamy-L Algs. 6–9 verbatim
 * (`robustness.py`: `compute_robustness`, `compute_guide_image`,
 * `compute_local_stats`, `warp_stats`, `compute_d_sigma`, `compute_s`,
 * `robustness_threshold`, `local_min`).
 *
 * Pipeline per moving frame: sqrt 3-channel guide (Alg. 7) → 3x3 local stats
 * (Alg. 8) → Dogson-biquadratic warp of the moving means into reference
 * coordinates at the bilinear-blended flow vector (Sabre-style dense
 * warp: the same continuous warp the merge gather uses, so r scores
 * the warp that actually renders) → color distance over measured
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

    /** Mth is published in raw pixels and our flows are raw-unit (Jamy-L convention). */
    internal fun motionThresholdPx(tuning: RawSrTuning) = tuning.mTh.toFloat()

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

    /** Reference 3x3 local means and variances over the sqrt guide (Alg. 8). */
    data class ReferenceStats(
        val width: Int,
        val height: Int,
        val mean: Array<FloatArray>,
        val variance: Array<FloatArray>
    ) {
        init {
            require(width > 0 && height > 0)
            require(mean.size == 3 && variance.size == 3)
            require(mean.all { it.size == width * height } && variance.all { it.size == width * height })
        }
    }

    /** Moving 3x3 local means over the sqrt guide (Alg. 8, means only). */
    data class MovingStats(val width: Int, val height: Int, val mean: Array<FloatArray>) {
        init {
            require(width > 0 && height > 0)
            require(mean.size == 3 && mean.all { it.size == width * height })
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
        // Per-phase calibration LUTs: black levels and CFA colours repeat
        // every 2x2, so the pixel loop indexes four-entry tables instead of
        // recomputing sensor lookups per tap. Entries are exactly what
        // blackAt/colorAt return for the tap's phase (parity-matched against
        // the sensor origin, odd crops included).
        val normalization = input.normalization
        val sensorLeft = input.sensorCropLeft
        val sensorTop = input.sensorCropTop
        val whiteLevel = normalization.whiteLevel.toDouble()
        val blackPhase = DoubleArray(4) { p ->
            normalization.blackAt(sensorLeft + (p and 1), sensorTop + (p shr 1)).toDouble()
        }
        val colorPhase = Array(4) { p ->
            normalization.sensorPattern.colorAt(sensorLeft + (p and 1), sensorTop + (p shr 1))
        }
        val rowStride = input.layout.rowStride
        val cropLeft = crop.left
        val cropTop = crop.top
        val slope = tables.slope
        val tableOffset = tables.offset
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
                    val phase = ((y and 1) shl 1) or (x and 1)
                    val offset = base + (cropTop + y) * rowStride + (cropLeft + x) * 2
                    val code = source.getShort(offset).toInt() and 65535
                    val black = blackPhase[phase]
                    val value = RawSrCoreGuide.normalize(code, black, whiteLevel).toFloat()
                    if (modelValid) {
                        val sigma = sqrt(slope[phase] * code + tableOffset[phase])
                        // Highlight-side rail only: a tap within 3σ of white
                        // is clipped and carries no signal. There is NO shadow
                        // rail — signal within 3σ of black is normal
                        // read-noise-limited data, and zeroing it would deny
                        // shadows every moving frame (lifted noise floor);
                        // below-black evidence is judged by the photo term.
                        if (code > whiteLevel - RAIL_SIGMA * sigma) quadRail = true
                    }
                    when (colorPhase[phase]) {
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
        require(flow.coversRaw(reference.width * 2, reference.height * 2)) {
            "Flow covers the raw lattice (2x the guide grid)"
        }
        return evaluateWithStats(
            referenceStats(reference), movingStats(moving), flow, tuning, config, noiseLut)
    }

    /**
     * Reference 3x3 clamp-to-edge means and variances (Alg. 8). Hoisted out
     * of [evaluate]: identical for every moving frame, so the production
     * stream computes it once and drops the guide instead of recomputing
     * ~75MB of stats per frame on a 512MB heap.
     */
    fun referenceStats(reference: LinearGuide): ReferenceStats {
        val width = reference.width
        val height = reference.height
        val mean = Array(3) { FloatArray(width * height) }
        val variance = Array(3) { FloatArray(width * height) }
        for (c in 0..2) {
            val ref = reference.channel(c)
            RawSrWorkers.forEachShard(height) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until width) {
                    var sum = 0f
                    var squares = 0f
                    for (i in -1..1) for (j in -1..1) {
                        val xx = (x + j).coerceIn(0, width - 1)
                        val yy = (y + i).coerceIn(0, height - 1)
                        val v = ref[yy * width + xx]
                        sum += v
                        squares += v * v
                    }
                    val o = y * width + x
                    val average = sum / 9f
                    mean[c][o] = average
                    // Reference Alg. 8 verbatim: the raw variance is stored
                    // unfloored (catastrophic cancellation can leave it
                    // slightly negative). Non-positive variance rejects
                    // through the §5 edge rule, deterministically.
                    variance[c][o] = squares / 9f - average * average
                }
            }
        }
        return ReferenceStats(width, height, mean, variance)
    }

    /**
     * Moving 3x3 clamp-to-edge means (Alg. 8). The moving variance never
     * enters: sigma² is measured reference variance only (plus the LUT
     * floor). Computed per frame, then the guide is dropped before the
     * verdict allocates its own planes.
     */
    fun movingStats(moving: LinearGuide): MovingStats {
        val width = moving.width
        val height = moving.height
        val mean = Array(3) { FloatArray(width * height) }
        for (c in 0..2) {
            val mov = moving.channel(c)
            RawSrWorkers.forEachShard(height) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until width) {
                    var movSum = 0f
                    for (i in -1..1) for (j in -1..1) {
                        val xx = (x + j).coerceIn(0, width - 1)
                        val yy = (y + i).coerceIn(0, height - 1)
                        movSum += mov[yy * width + xx]
                    }
                    mean[c][y * width + x] = movSum / 9f
                }
            }
        }
        return MovingStats(width, height, mean)
    }

    /**
     * Guide-row source for the fused stats below: the same sqrt-of-quad
     * values [linearGuide] stores in its color planes, computed one row at
     * a time. Rail, hot, alpha/beta and the noise tables never enter the
     * color planes, so this consults none of them.
     */
    private class PackedGuideRows(frame: RawSrPackedFrame) {
        val outWidth: Int
        val outHeight: Int
        private val buffer: java.nio.ByteBuffer
        private val base: Int
        private val rowStride: Int
        private val cropLeft: Int
        private val cropTop: Int
        private val blackPhase: DoubleArray
        private val colorPhase: Array<CfaColor>
        private val whiteLevel: Double

        init {
            require(frame.width % 2 == 0 && frame.height % 2 == 0) {
                "Robustness guide requires complete Bayer quads"
            }
            val input = frame.uploadInput()
            outWidth = input.crop.width / 2
            outHeight = input.crop.height / 2
            val view = input.buffer.duplicate().order(ByteOrder.nativeOrder())
            buffer = view
            base = view.position()
            rowStride = input.layout.rowStride
            cropLeft = input.crop.left
            cropTop = input.crop.top
            val normalization = input.normalization
            val sensorLeft = input.sensorCropLeft
            val sensorTop = input.sensorCropTop
            blackPhase = DoubleArray(4) { p ->
                normalization.blackAt(sensorLeft + (p and 1), sensorTop + (p shr 1)).toDouble()
            }
            colorPhase = Array(4) { p ->
                normalization.sensorPattern.colorAt(sensorLeft + (p and 1), sensorTop + (p shr 1))
            }
            whiteLevel = normalization.whiteLevel.toDouble()
        }

        /** One guide row into the three channel rows; bitwise-identical to [linearGuide]. */
        fun row(qy: Int, redRow: FloatArray, greenRow: FloatArray, blueRow: FloatArray) {
            for (qx in 0 until outWidth) {
                var r = 0f
                var g = 0f
                var b = 0f
                var greens = 0
                for (i in 0..1) for (j in 0..1) {
                    val x = qx * 2 + j
                    val y = qy * 2 + i
                    val phase = ((y and 1) shl 1) or (x and 1)
                    val offset = base + (cropTop + y) * rowStride + (cropLeft + x) * 2
                    val code = buffer.getShort(offset).toInt() and 65535
                    val black = blackPhase[phase]
                    val value = RawSrCoreGuide.normalize(code, black, whiteLevel).toFloat()
                    when (colorPhase[phase]) {
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
                redRow[qx] = sqrt(maxOf(r, 0f))
                greenRow[qx] = sqrt(maxOf(g * 0.5f, 0f))
                blueRow[qx] = sqrt(maxOf(b, 0f))
            }
        }
    }

    /**
     * Reference stats directly from packed codes, bitwise-identical to
     * `referenceStats(linearGuide(frame))`. Each shard keeps a 3-row guide
     * ring (~73KB at full res) instead of the whole 37MB guide, so the 75MB
     * stats allocate without the guide beside them on a 512MB heap.
     */
    fun referenceStatsFromPacked(frame: RawSrPackedFrame): ReferenceStats {
        val rows = PackedGuideRows(frame)
        val width = rows.outWidth
        val height = rows.outHeight
        val mean = Array(3) { FloatArray(width * height) }
        val variance = Array(3) { FloatArray(width * height) }
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            val cacheR = Array(3) { FloatArray(width) }
            val cacheG = Array(3) { FloatArray(width) }
            val cacheB = Array(3) { FloatArray(width) }
            val cacheGy = IntArray(3) { -1 }
            fun ensure(gy: Int) {
                val slot = gy % 3
                if (cacheGy[slot] != gy) {
                    rows.row(gy, cacheR[slot], cacheG[slot], cacheB[slot])
                    cacheGy[slot] = gy
                }
            }
            for (y in y0 until y1) {
                val gy0 = (y - 1).coerceIn(0, height - 1)
                val gy1 = y
                val gy2 = (y + 1).coerceIn(0, height - 1)
                ensure(gy0)
                ensure(gy1)
                ensure(gy2)
                val r0 = cacheR[gy0 % 3]
                val r1 = cacheR[gy1 % 3]
                val r2 = cacheR[gy2 % 3]
                val g0 = cacheG[gy0 % 3]
                val g1 = cacheG[gy1 % 3]
                val g2 = cacheG[gy2 % 3]
                val b0 = cacheB[gy0 % 3]
                val b1 = cacheB[gy1 % 3]
                val b2 = cacheB[gy2 % 3]
                for (x in 0 until width) {
                    val xx0 = (x - 1).coerceIn(0, width - 1)
                    val xx2 = (x + 1).coerceIn(0, width - 1)
                    // Channel sums replicate referenceStats order exactly:
                    // i outer (-1,0,+1), j inner (-1,0,+1).
                    var sumR = 0f
                    var sqR = 0f
                    var v = r0[xx0]; sumR += v; sqR += v * v
                    v = r0[x]; sumR += v; sqR += v * v
                    v = r0[xx2]; sumR += v; sqR += v * v
                    v = r1[xx0]; sumR += v; sqR += v * v
                    v = r1[x]; sumR += v; sqR += v * v
                    v = r1[xx2]; sumR += v; sqR += v * v
                    v = r2[xx0]; sumR += v; sqR += v * v
                    v = r2[x]; sumR += v; sqR += v * v
                    v = r2[xx2]; sumR += v; sqR += v * v
                    var sumG = 0f
                    var sqG = 0f
                    v = g0[xx0]; sumG += v; sqG += v * v
                    v = g0[x]; sumG += v; sqG += v * v
                    v = g0[xx2]; sumG += v; sqG += v * v
                    v = g1[xx0]; sumG += v; sqG += v * v
                    v = g1[x]; sumG += v; sqG += v * v
                    v = g1[xx2]; sumG += v; sqG += v * v
                    v = g2[xx0]; sumG += v; sqG += v * v
                    v = g2[x]; sumG += v; sqG += v * v
                    v = g2[xx2]; sumG += v; sqG += v * v
                    var sumB = 0f
                    var sqB = 0f
                    v = b0[xx0]; sumB += v; sqB += v * v
                    v = b0[x]; sumB += v; sqB += v * v
                    v = b0[xx2]; sumB += v; sqB += v * v
                    v = b1[xx0]; sumB += v; sqB += v * v
                    v = b1[x]; sumB += v; sqB += v * v
                    v = b1[xx2]; sumB += v; sqB += v * v
                    v = b2[xx0]; sumB += v; sqB += v * v
                    v = b2[x]; sumB += v; sqB += v * v
                    v = b2[xx2]; sumB += v; sqB += v * v
                    val o = y * width + x
                    val avgR = sumR / 9f
                    val avgG = sumG / 9f
                    val avgB = sumB / 9f
                    mean[0][o] = avgR
                    mean[1][o] = avgG
                    mean[2][o] = avgB
                    variance[0][o] = sqR / 9f - avgR * avgR
                    variance[1][o] = sqG / 9f - avgG * avgG
                    variance[2][o] = sqB / 9f - avgB * avgB
                }
            }
        }
        return ReferenceStats(width, height, mean, variance)
    }

    /**
     * Moving means directly from packed codes, bitwise-identical to
     * `movingStats(linearGuide(frame))`. Same 3-row ring as
     * [referenceStatsFromPacked]: no 37MB guide beside the 37MB means.
     */
    fun movingStatsFromPacked(frame: RawSrPackedFrame): MovingStats {
        val rows = PackedGuideRows(frame)
        val width = rows.outWidth
        val height = rows.outHeight
        val mean = Array(3) { FloatArray(width * height) }
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            val cacheR = Array(3) { FloatArray(width) }
            val cacheG = Array(3) { FloatArray(width) }
            val cacheB = Array(3) { FloatArray(width) }
            val cacheGy = IntArray(3) { -1 }
            fun ensure(gy: Int) {
                val slot = gy % 3
                if (cacheGy[slot] != gy) {
                    rows.row(gy, cacheR[slot], cacheG[slot], cacheB[slot])
                    cacheGy[slot] = gy
                }
            }
            for (y in y0 until y1) {
                val gy0 = (y - 1).coerceIn(0, height - 1)
                val gy1 = y
                val gy2 = (y + 1).coerceIn(0, height - 1)
                ensure(gy0)
                ensure(gy1)
                ensure(gy2)
                val r0 = cacheR[gy0 % 3]
                val r1 = cacheR[gy1 % 3]
                val r2 = cacheR[gy2 % 3]
                val g0 = cacheG[gy0 % 3]
                val g1 = cacheG[gy1 % 3]
                val g2 = cacheG[gy2 % 3]
                val b0 = cacheB[gy0 % 3]
                val b1 = cacheB[gy1 % 3]
                val b2 = cacheB[gy2 % 3]
                for (x in 0 until width) {
                    val xx0 = (x - 1).coerceIn(0, width - 1)
                    val xx2 = (x + 1).coerceIn(0, width - 1)
                    var sumR = 0f
                    sumR += r0[xx0]; sumR += r0[x]; sumR += r0[xx2]
                    sumR += r1[xx0]; sumR += r1[x]; sumR += r1[xx2]
                    sumR += r2[xx0]; sumR += r2[x]; sumR += r2[xx2]
                    var sumG = 0f
                    sumG += g0[xx0]; sumG += g0[x]; sumG += g0[xx2]
                    sumG += g1[xx0]; sumG += g1[x]; sumG += g1[xx2]
                    sumG += g2[xx0]; sumG += g2[x]; sumG += g2[xx2]
                    var sumB = 0f
                    sumB += b0[xx0]; sumB += b0[x]; sumB += b0[xx2]
                    sumB += b1[xx0]; sumB += b1[x]; sumB += b1[xx2]
                    sumB += b2[xx0]; sumB += b2[x]; sumB += b2[xx2]
                    val o = y * width + x
                    mean[0][o] = sumR / 9f
                    mean[1][o] = sumG / 9f
                    mean[2][o] = sumB / 9f
                }
            }
        }
        return MovingStats(width, height, mean)
    }

    /**
     * [evaluate] over precomputed local statistics: same Dogson warp, color
     * distance, s1/s2 scaling, threshold and 5x5 local minimum, bitwise
     * identical to [evaluate] for the same guides.
     */
    fun evaluateWithStats(
        reference: ReferenceStats,
        moving: MovingStats,
        flow: RawSrAlignmentField,
        tuning: RawSrTuning,
        config: RawSrAlignmentConfig,
        noiseLut: RawSrNoiseLut.Lut? = null
    ): FrameRobustness {
        require(reference.width == moving.width && reference.height == moving.height)
        require(flow.coversRaw(reference.width * 2, reference.height * 2)) {
            "Flow covers the raw lattice (2x the stats grid)"
        }
        val width = reference.width
        val height = reference.height
        val refMean = reference.mean
        val refVar = reference.variance
        val movMean = moving.mean
        val threshold = tuning.t.toFloat()
        val motionThreshold = motionThresholdPx(tuning)
        val s1 = tuning.s1.toFloat()
        val s2 = tuning.s2.toFloat()
        val raw = FloatArray(width * height)
        val flags = IntArray(width * height)
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            val warped = DoubleArray(3)
            val flowScratch = FloatArray(4)
            val distVar = DoubleArray(2)
            val corrected = DoubleArray(2)
            for (y in y0 until y1) for (x in 0 until width) {
                val o = y * width + x
                // Bilinear flow lookup (Sabre-style dense warp,
                // beyond the reference `cpu_warp_dogson` tile snap):
                // the SAME continuous warp the merge gather uses, so r
                // scores the warp that actually renders (a snapped r
                // over-accepts where the blended gather lands wrong).
                // The tile-spread irregularity gate below still reads
                // the discrete tiles (motion prior, unchanged).
                flow.flowAtSmoothInto((2 * x + 1).toFloat(), (2 * y + 1).toFloat(), flowScratch)
                val dx = flowScratch[0]
                val dy = flowScratch[1]
                if (!dx.isFinite() || !dy.isFinite()) {
                    raw[o] = 0f
                    flags[o] = FLAG_INVALID_FLOW
                    continue
                }
                // Reference cuda_warp_dogson verbatim: the stats grid is
                // half-resolution, so the raw-unit flow scales by 0.5.
                val centerX = x + dx.toDouble() * 0.5
                val centerY = y + dy.toDouble() * 0.5
                if (centerX < 0.0 || centerY < 0.0 || centerX >= width || centerY >= height) {
                    // Reference OOB: warped means are +inf, so R = 0.
                    raw[o] = 0f
                    flags[o] = FLAG_OUT_OF_BOUNDS
                    continue
                }
                RawSrCoreRobustness.warpDogson(movMean, width, height, centerX, centerY, warped)
                RawSrCoreRobustness.distanceAndVariance(refMean, refVar, warped, o, distVar)
                RawSrCoreRobustness.correctNoise(
                    distVar[0], distVar[1], refMean[0][o], refMean[1][o], refMean[2][o],
                    noiseLut, corrected)
                val scale = if (RawSrCoreRobustness.flowIrregular(flow, x, y, motionThreshold)) s1 else s2
                raw[o] = RawSrCoreRobustness.threshold(corrected[0], corrected[1], scale, threshold)
                flags[o] = 0
            }
        }
        // Local minimum over a 5x5 clamp window (Alg. 9); flags stay own-quad.
        val r = FloatArray(width * height)
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until width) {
                r[y * width + x] = RawSrCoreRobustness.localMin(raw, width, height, x, y)
            }
        }
        return FrameRobustness(width, height, r, flags)
    }

    /** Reference `dogson_quadratic_kernel` (utils_image.py); see [RawSrCoreRobustness]. */
    internal fun dogsonQuadratic(x: Double): Double = RawSrCoreRobustness.dogsonQuadratic(x)

    /**
     * Reference `cuda_warp_dogson` (see [RawSrCoreRobustness.warpDogson]).
     * The caller guarantees the center is in bounds.
     */
    internal fun warpDogson(
        movMean: Array<FloatArray>,
        width: Int,
        height: Int,
        centerX: Double,
        centerY: Double,
        out: DoubleArray
    ) = RawSrCoreRobustness.warpDogson(movMean, width, height, centerX, centerY, out)

    /**
     * Reference `cuda_compute_s` (see [RawSrCoreRobustness.flowIrregular]).
     */
    internal fun flowIrregular(flow: RawSrAlignmentField, x: Int, y: Int, motionThreshold: Float): Boolean =
        RawSrCoreRobustness.flowIrregular(flow, x, y, motionThreshold)

    /**
     * Merge motion-edge stop verdict: true only on demonstrated tile
     * disagreement (two finite in-bounds tiles apart). A non-finite
     * neighbour is missing data, not motion — unlike [flowIrregular]
     * (which conservatively scales robustness weights on any doubt), a veto
     * here forfeits fusion, so unknown tiles merge with the robustness
     * gates as backstop. Mirrors the merge shader's finite-count gate
     * exactly: fewer than two finite tiles cannot disagree. ([x], [y] are
     * guide (quad) coordinates like [flowIrregular].)
     */
    internal fun flowDisagrees(flow: RawSrAlignmentField, x: Int, y: Int, motionThreshold: Float): Boolean {
        val tileX = ((2 * x) / flow.tileSize).coerceIn(0, flow.columns - 1)
        val tileY = ((2 * y) / flow.tileSize).coerceIn(0, flow.rows - 1)
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

    /**
     * The motion-edge decision is constant within an alignment tile. Guide
     * coordinates of the tile origin (tile lattice is raw pixels at an even
     * [RawSrAlignmentField.tileSize], like every checkout level size).
     */
    internal fun flowDisagreementTiles(flow: RawSrAlignmentField, motionThreshold: Float): BooleanArray =
        BooleanArray(flow.columns * flow.rows) { index ->
            flowDisagrees(flow, (index % flow.columns) * flow.tileSize / 2,
                (index / flow.columns) * flow.tileSize / 2, motionThreshold)
        }

    /** Exact once-per-quad accumulation; rc null starts from zero. */
    fun accumulate(rc: RcField?, frame: FrameRobustness): RcField {
        val base = rc ?: RcField(frame.width, frame.height, FloatArray(frame.width * frame.height))
        require(base.width == frame.width && base.height == frame.height)
        return RcField(frame.width, frame.height, RawSrCoreRobustness.accumulateRcPlain(base.values, frame.r))
    }
}
