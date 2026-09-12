// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLES31
import java.io.Closeable

data class RawSrGpuOutput(
    /** Ordinary linear camera-RGB numerators (rgb; Prompt 4D merge contract). */
    val numeratorTextureId: Int,
    /** Independent per-channel R/G/B denominators (rgb; RGBA32F, contract §6). */
    val denominatorTextureId: Int,
    val width: Int,
    val height: Int,
    val acceptedFrames: Int,
    /** Only the last moving flow is live. Use onFlow for per-frame diagnostics. */
    val flowTextureIds: List<Int>,
    /** Accumulated per-quad robustness Rc; identically zero in reference-only mode. */
    val rcTextureId: Int,
    /** Reference-only A/B numerators (rgb): the reference-last pass through the same path. */
    val refNumeratorTextureId: Int,
    /** Reference-only A/B denominators (rgb): backs the local fallback. */
    val refDenominatorTextureId: Int,
    /** Final merged linear RGB after the reference-last add, normalization, and fallback. */
    val mergedTextureId: Int,
    /** Per-pixel local-fallback mask (1 where any channel fell back to reference-only). */
    val fallbackTextureId: Int,
    /** Per-pixel out-of-bounds diagnostic counter (contract §7). */
    val oobTextureId: Int,
    val peakTextureBytes: Long = 0
)

/** Writer-thread confined, borrowed-plane executor. Outputs are live only inside callbacks.
 * Prompt 4D Bayer-direct merge: the reference uploads/normalizes once and stays
 * resident with the persistent accumulators; each accepted moving frame runs the
 * unchanged alignment/covariance/robustness stages into exactly one moving-frame
 * workspace that is released before the next frame, and the captured reference
 * accumulates last with r_ref = 1. Reference-only A/B runs the same path with
 * the moving frames skipped and Rc identically zero.
 */
class Gles31RawSrProcessor(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private var session: Session? = null
    private var ownerThread: Long? = null
    private var processing = false

    /** Inputs must be planner-approved and reference-first. No Image ownership is transferred.
     * onFlow executes before the workspace is retired; never retain its texture ID.
     */
    fun <T> processPacked(
        frames: List<RawSrPackedFrame>,
        config: RawSrAlignmentConfig? = null,
        referenceOnly: Boolean = false,
        onFlow: (frameIndex: Int, textureId: Int, columns: Int, rows: Int) -> Unit = { _, _, _, _ -> },
        onCovariance: (frameIndex: Int, textureId: Int, width: Int, height: Int) -> Unit = { _, _, _, _ -> },
        onRobustness: (frameIndex: Int, rTextureId: Int, flagsTextureId: Int, width: Int, height: Int)
            -> Unit = { _, _, _, _, _ -> },
        consume: (RawSrGpuOutput) -> T
    ): T {
        val inputs = frames.toList()
        require(inputs.isNotEmpty())
        val ref = inputs.first()
        require(inputs.all { it.width == ref.width && it.height == ref.height && it.pattern == ref.pattern })
        val estimate = RawSrTuning.fromReference(ref)
        val resolvedConfig = config ?: estimate.tuning.alignmentConfig()
        if (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            android.util.Log.d("RawLensRawSrTuning",
            estimate.debugSummary() + " explicitAlignmentOverride=${config != null} activeTileQuads=${resolvedConfig.tileSize}")
        // Prompt 4E precision fix: the kernel guide is computed once on the CPU
        // (double precision, exactly the oracle input) and uploaded, instead of
        // re-deriving it in float32 on the GPU. Oracle parity by construction.
        val cpuGuides = inputs.map { RawSrCovarianceGuide.guide(it).gray }
        return execute(inputs.size, ref.width, ref.height, ref.pattern, resolvedConfig, estimate.tuning,
            cpuGuides,
            inputs.map { RawSrRobustness.gpuParams(it) },
            referenceOnly, onFlow, onCovariance, onRobustness,
            { index, active, arena ->
                val raw = inputs[index].uploadInput()
                val codes = uploadCodes(raw, active, arena)
                LoadedFrame(normalize(raw, codes, active, arena), codes)
            }, consume)
    }

    /** CPU-oracle test adapter only. Production callers use processPacked.
     * A null tuning resolves conservatively from reference brightness without a noise
     * profile (MISSING_PROFILE, SNR 6); pass an explicit tuning for oracle comparisons.
     * The adapter carries no raw codes, so robustness is skipped, every moving frame
     * merges with unit weight, and Rc stays zero.
     */
    fun <T> process(
        frames: List<UnpackedRawCfa>,
        config: RawSrAlignmentConfig = RawSrAlignmentConfig(),
        tuning: RawSrTuning? = null,
        referenceOnly: Boolean = false,
        onCovariance: (frameIndex: Int, textureId: Int, width: Int, height: Int) -> Unit = { _, _, _, _ -> },
        onRobustness: (frameIndex: Int, rTextureId: Int, flagsTextureId: Int, width: Int, height: Int)
            -> Unit = { _, _, _, _, _ -> },
        consume: (RawSrGpuOutput) -> T
    ): T {
        require(frames.isNotEmpty())
        val ref = frames.first()
        require(frames.all { it.width == ref.width && it.height == ref.height && it.pattern == ref.pattern })
        val resolvedTuning = tuning ?: RawSrTuning.estimate(
            if (ref.values.isEmpty()) 0.0 else ref.values.average(), null).tuning
        return execute(frames.size, ref.width, ref.height, ref.pattern, config, resolvedTuning,
            null,
            List(frames.size) { RawSrRobustness.bypass() }, referenceOnly,
            { _, _, _, _ -> }, onCovariance, onRobustness, { index, active, arena ->
                LoadedFrame(arena.texture(ref.width, ref.height, GLES30.GL_R32F).also {
                    it.uploadR32f(frames[index].values, active.uploads)
                }, null)
            }, consume)
    }

    private data class LoadedFrame(val cfa: Gles31AmazeProcessor.GlTexture, val codes: Gles31AmazeProcessor.GlTexture?)

    private fun uploadCodes(raw: GpuRawAmazeInput, active: Session, arena: Arena): Gles31AmazeProcessor.GlTexture {
        val codes = arena.texture(raw.width, raw.height, GLES30.GL_R16UI)
        codes.uploadRaw16(raw.buffer, raw.layout, raw.crop)
        return codes
    }

    private fun normalize(raw: GpuRawAmazeInput, codes: Gles31AmazeProcessor.GlTexture,
                          active: Session, arena: Arena): Gles31AmazeProcessor.GlTexture {
        val cfa = arena.texture(raw.width, raw.height, GLES30.GL_R32F)
        val model = raw.lensShading
        val gains = arena.texture(model?.columns ?: 1, model?.rows ?: 1, GLES30.GL_RGBA32F)
        gains.uploadRgba32f(model?.gains ?: floatArrayOf(1f, 1f, 1f, 1f), active.uploads)
        val black = FloatArray(4) { raw.normalization.blackAt(raw.sensorCropLeft + (it and 1), raw.sensorCropTop + (it shr 1)) }
        val rect = model?.activeArray ?: IntRectSnapshot(0, 0, 1, 1)
        // Exactly the same upload, local-phase black levels and shading contract as AMaZE.
        active.pass("raw/preprocess.glsl") {
            sampler("u_raw", codes); sampler("u_lens", gains)
            ivec2("u_size", raw.width, raw.height)
            ivec2("u_sensor_origin", raw.sensorCropLeft, raw.sensorCropTop)
            ivec4("u_fc", AmazePipelineContract.cfaUniform(raw.pattern)); vec4("u_black", black)
            float("u_white", raw.normalization.whiteLevel)
            ivec2("u_lens_size", gains.width, gains.height)
            ivec4("u_active", intArrayOf(rect.left, rect.top, rect.right, rect.bottom))
            integer("u_apply_lens", if (model != null && !model.alreadyApplied) 1 else 0)
            image(0, cfa, GLES30.GL_R32F); dispatch(raw.width, raw.height, 8, 8)
        }
        arena.release(gains)
        return cfa
    }

    /** Prompt 4C linear guide: unshaded normalized R/G/B per quad plus rail mask. */
    private fun linearFromCodes(codes: Gles31AmazeProcessor.GlTexture, params: RawSrRobustness.GpuParams,
                                pattern: BayerPattern, active: Session, arena: Arena): Gles31AmazeProcessor.GlTexture {
        val linear = arena.texture(codes.width / 2, codes.height / 2, GLES30.GL_RGBA32F)
        active.pass("rawsr/linear_guide.glsl") {
            sampler("u_raw", codes); ivec2("u_size", linear.width, linear.height)
            ivec4("u_fc", AmazePipelineContract.cfaUniform(pattern))
            vec4("u_black", params.black); float("u_white", params.white)
            vec4("u_slope", params.slope); vec4("u_offset", params.offset)
            integer("u_model_valid", if (params.modelValid) 1 else 0)
            image(0, linear, GLES30.GL_RGBA32F); dispatch(linear.width, linear.height, 8, 8)
        }
        return linear
    }

    private fun robustnessPass(refLinear: Gles31AmazeProcessor.GlTexture,
                               movLinear: Gles31AmazeProcessor.GlTexture,
                               flow: Gles31AmazeProcessor.GlTexture,
                               tuning: RawSrTuning, config: RawSrAlignmentConfig,
                               refParams: RawSrRobustness.GpuParams, movParams: RawSrRobustness.GpuParams,
                               active: Session, arena: Arena): Pair<Gles31AmazeProcessor.GlTexture, Gles31AmazeProcessor.GlTexture> {
        val rawR = arena.texture(refLinear.width, refLinear.height, GLES30.GL_R32F)
        val flags = arena.texture(refLinear.width, refLinear.height, GLES30.GL_R32UI)
        active.pass("rawsr/robustness.glsl") {
            sampler("u_ref_lin", refLinear); sampler("u_mov_lin", movLinear); sampler("u_flow", flow)
            ivec2("u_size", rawR.width, rawR.height)
            ivec2("u_tile_grid", flow.width, flow.height)
            integer("u_tile_size", config.tileSize)
            float("u_t", tuning.t.toFloat())
            float("u_s1", tuning.s1.toFloat()); float("u_s2", tuning.s2.toFloat())
            float("u_mth_quad", (tuning.mTh / 2.0).toFloat())
            float("u_max_residual", config.maxMeanAbsoluteResidual)
            vec3("u_mov_alpha", movParams.alpha); vec3("u_mov_beta", movParams.beta)
            integer("u_ref_valid", if (refParams.modelValid) 1 else 0)
            integer("u_mov_valid", if (movParams.modelValid) 1 else 0)
            image(0, rawR, GLES30.GL_R32F); image(1, flags, GLES30.GL_R32UI)
            dispatch(rawR.width, rawR.height, 8, 8)
        }
        return rawR to flags
    }

    private fun robustnessMin(rawR: Gles31AmazeProcessor.GlTexture, active: Session, arena: Arena) =
        arena.texture(rawR.width, rawR.height, GLES30.GL_R32F).also { r ->
            active.pass("rawsr/robustness_min.glsl") {
                sampler("u_raw", rawR); ivec2("u_size", r.width, r.height)
                image(0, r, GLES30.GL_R32F); dispatch(r.width, r.height, 8, 8)
            }
        }

    private fun accumulateRc(rc: Gles31AmazeProcessor.GlTexture, r: Gles31AmazeProcessor.GlTexture,
                             out: Gles31AmazeProcessor.GlTexture, active: Session) {
        active.pass("rawsr/robustness_accumulate.glsl") {
            sampler("u_rc", rc); sampler("u_r", r)
            ivec2("u_size", out.width, out.height)
            image(0, out, GLES30.GL_R32F); dispatch(out.width, out.height, 8, 8)
        }
    }

    /** Prompt 4D Bayer-direct accumulation into one accumulator pair, plus the
     * burst-nearest pair. Moving frames feed [num]/[den] and
     * [nearNum]/[nearDen]; the reference-last pass feeds the reference-only
     * A/B pair through the identical shader with zero shift and unit
     * robustness, adding its nearest contribution into the same near pair
     * (no separate reference-nearest textures).
     */
    private fun mergeAccumulate(
        cfa: Gles31AmazeProcessor.GlTexture,
        flow: Gles31AmazeProcessor.GlTexture,
        precision: Gles31AmazeProcessor.GlTexture,
        r: Gles31AmazeProcessor.GlTexture,
        useR: Boolean,
        isReference: Boolean,
        num: Gles31AmazeProcessor.GlTexture,
        den: Gles31AmazeProcessor.GlTexture,
        nearNum: Gles31AmazeProcessor.GlTexture,
        nearDen: Gles31AmazeProcessor.GlTexture,
        oob: Gles31AmazeProcessor.GlTexture,
        pattern: BayerPattern,
        config: RawSrAlignmentConfig,
        active: Session
    ) {
        active.pass("rawsr/merge_accumulate.glsl") {
            sampler("u_cfa", cfa); sampler("u_flow", flow)
            sampler("u_precision", precision); sampler("u_r", r)
            sampler("u_num", num); sampler("u_den", den); sampler("u_oob", oob)
            sampler("u_near_num", nearNum); sampler("u_near_den", nearDen)
            ivec2("u_size", cfa.width, cfa.height)
            ivec2("u_tile_grid", flow.width, flow.height)
            integer("u_tile_size", config.tileSize)
            ivec2("u_guide_size", precision.width, precision.height)
            ivec4("u_fc", AmazePipelineContract.cfaUniform(pattern))
            integer("u_is_reference", if (isReference) 1 else 0)
            integer("u_use_r", if (useR) 1 else 0)
            image(0, num, GLES30.GL_RGBA32F); image(1, den, GLES30.GL_RGBA32F)
            image(2, oob, GLES30.GL_R32F)
            image(3, nearNum, GLES30.GL_RGBA32F); image(4, nearDen, GLES30.GL_RGBA32F)
            dispatch(cfa.width, cfa.height, 8, 8)
        }
    }

    /** Prompt 4B.1 guide: unshaded normalize + per-phase GAT + quad average from codes.
     * The alignment pyramid keeps using the shaded CFA path; only kernel covariance
     * consumes this texture. The caller releases both textures. */
    /** Prompt 4E precision fix: the kernel guide arrives computed on the CPU
     * (double precision, exactly the oracle input) and is uploaded verbatim.
     * Single-precision GPU re-derivation used to diverge from the oracle in
     * scattered quads. The caller releases the texture. */
    private fun uploadGuide(gray: RawSrGrayImage, active: Session, arena: Arena): Gles31AmazeProcessor.GlTexture {
        val texture = arena.texture(gray.width, gray.height, GLES30.GL_R32F)
        texture.uploadR32f(gray.values, active.uploads)
        return texture
    }

    private fun pyramid(cfa: Gles31AmazeProcessor.GlTexture, active: Session, arena: Arena,
                        config: RawSrAlignmentConfig): List<Gles31AmazeProcessor.GlTexture> {
        val levels = ArrayList<Gles31AmazeProcessor.GlTexture>()
        var source = arena.texture(cfa.width / 2, cfa.height / 2, GLES30.GL_R32F)
        active.pass("rawsr/bayer_quad_gray.glsl") {
            sampler("u_cfa", cfa); ivec2("u_size", source.width, source.height)
            image(0, source, GLES30.GL_R32F); dispatch(source.width, source.height, 8, 8)
        }
        levels += source
        while (levels.size < config.levels) {
            val factor = config.factorAt(levels.size)
            if (source.width / factor < 4 || source.height / factor < 4) break
            val weights = RawSrAlignment.gaussianWeights(factor)
            val horizontal = arena.texture(source.width / factor, source.height, GLES30.GL_R32F)
            active.pass("rawsr/pyramid_downsample.glsl") {
                sampler("u_source", source); ivec2("u_size", horizontal.width, horizontal.height)
                integer("u_factor", factor); integer("u_axis", 0); floats("u_weights[0]", weights)
                image(0, horizontal, GLES30.GL_R32F); dispatch(horizontal.width, horizontal.height, 8, 8)
            }
            val next = arena.texture(horizontal.width, source.height / factor, GLES30.GL_R32F)
            active.pass("rawsr/pyramid_downsample.glsl") {
                sampler("u_source", horizontal)
                ivec2("u_size", next.width, next.height)
                integer("u_factor", factor); integer("u_axis", 1); floats("u_weights[0]", weights)
                image(0, next, GLES30.GL_R32F); dispatch(next.width, next.height, 8, 8)
            }
            arena.release(horizontal)
            levels += next; source = next
        }
        return levels
    }

    private fun align(refs: List<Gles31AmazeProcessor.GlTexture>, moving: List<Gles31AmazeProcessor.GlTexture>,
                      active: Session, arena: Arena, config: RawSrAlignmentConfig): Gles31AmazeProcessor.GlTexture {
        val forward = alignDirectional(refs, moving, active, arena, config)
        val reverse = alignDirectional(moving, refs, active, arena, config)
        val result = arena.texture(forward.width, forward.height, GLES30.GL_RGBA32F)
        active.pass("rawsr/flow_consistency.glsl") {
            sampler("u_forward", forward); sampler("u_reverse", reverse)
            ivec2("u_size", refs[0].width, refs[0].height)
            ivec2("u_tile_grid", forward.width, forward.height)
            integer("u_tile_size", config.tileSize)
            float("u_max_consistency", config.maxFlowConsistencyError)
            image(0, result, GLES30.GL_RGBA32F); dispatch(result.width, result.height, 1, 1)
        }
        arena.release(forward); arena.release(reverse)
        return result
    }

    private fun alignDirectional(refs: List<Gles31AmazeProcessor.GlTexture>, moving: List<Gles31AmazeProcessor.GlTexture>,
                      active: Session, arena: Arena, config: RawSrAlignmentConfig): Gles31AmazeProcessor.GlTexture {
        var previous: Gles31AmazeProcessor.GlTexture? = null
        for (level in refs.indices.reversed()) {
            val ref = refs[level]
            val columns = ceilDiv(ref.width, config.tileSize); val rows = ceilDiv(ref.height, config.tileSize)
            val matched = arena.texture(columns, rows, GLES30.GL_RGBA32F)
            active.pass("rawsr/block_match.glsl") {
                sampler("u_reference", ref); sampler("u_moving", moving[level]); sampler("u_initial_flow", previous ?: ref)
                ivec2("u_size", ref.width, ref.height); ivec2("u_tile_grid", columns, rows)
                ivec2("u_previous_grid", previous?.width ?: 1, previous?.height ?: 1)
                integer("u_tile_size", config.tileSize); integer("u_search_radius", config.radiusAt(level))
                integer("u_scale", config.factorAt(level + 1)); integer("u_l1", if (level == 0) 1 else 0)
                float("u_min_fraction", config.minSampleFraction)
                integer("u_has_initial_flow", if (previous == null) 0 else 1)
                image(0, matched, GLES30.GL_RGBA32F); dispatch(columns, rows, 1, 1)
            }
            previous?.let(arena::release)
            if (level != 0) { previous = matched; continue }
            val refined = arena.texture(columns, rows, GLES30.GL_RGBA32F)
            active.pass("rawsr/lk_refine.glsl") {
                sampler("u_reference", ref); sampler("u_moving", moving[level]); sampler("u_flow", matched)
                ivec2("u_size", ref.width, ref.height); ivec2("u_tile_grid", columns, rows)
                integer("u_tile_size", config.tileSize)
                float("u_min_determinant", config.minHessianDeterminant)
                float("u_max_residual", config.maxMeanAbsoluteResidual)
                float("u_min_condition", config.minConditionRatio)
                float("u_min_fraction", config.minSampleFraction)
                image(0, refined, GLES30.GL_RGBA32F); dispatch(columns, rows, 1, 1)
            }
            arena.release(matched); previous = refined
        }
        return requireNotNull(previous)
    }

    private fun kernelCovariance(gray: Gles31AmazeProcessor.GlTexture, tuning: RawSrTuning,
                               active: Session, arena: Arena): Gles31AmazeProcessor.GlTexture {
        val packed = arena.texture(gray.width, gray.height, GLES30.GL_RGBA32F)
        active.pass("rawsr/kernel_covariance.glsl") {
            sampler("u_gray", gray); ivec2("u_size", gray.width, gray.height)
            float("u_k_detail", tuning.kDetail.toFloat()); float("u_k_denoise", tuning.kDenoise.toFloat())
            float("u_d_th", tuning.dTh.toFloat()); float("u_d_tr", tuning.dTr.toFloat())
            float("u_k_stretch", tuning.kStretch.toFloat()); float("u_k_shrink", tuning.kShrink.toFloat())
            image(0, packed, GLES30.GL_RGBA32F); dispatch(gray.width, gray.height, 8, 8)
        }
        return packed
    }

    private fun <T> execute(count: Int, width: Int, height: Int, pattern: BayerPattern,
        config: RawSrAlignmentConfig, tuning: RawSrTuning, guideGrays: List<RawSrGrayImage>?,
        robust: List<RawSrRobustness.GpuParams>,
        referenceOnly: Boolean,
        onFlow: (Int, Int, Int, Int) -> Unit,
        onCovariance: (Int, Int, Int, Int) -> Unit,
        onRobustness: (Int, Int, Int, Int, Int) -> Unit,
        load: (Int, Session, Arena) -> LoadedFrame,
        consume: (RawSrGpuOutput) -> T
    ): T {
        require(count in 1..30 && width % 2 == 0 && height % 2 == 0)
        require(guideGrays == null || guideGrays.size == count) { "One CPU kernel guide per burst frame is required" }
        require(robust.size == count) { "One robustness parameter set per burst frame is required" }
        require(config.tileSize <= 32 && config.searchRadius <= 6 && config.lkIterations == 3) {
            "Configuration exceeds RAW-SR shader limits"
        }
        val active = ensureSession()
        active.egl.makeCurrent()
        check(!processing) { "RAW-SR callbacks must not re-enter processing" }
        processing = true
        try {
          Arena().use { arena ->
            val quadsW = width / 2
            val quadsH = height / 2
            // Persistent 4D merge state, retained for the whole burst: RGB numerators,
            // independent R/G/B denominators, the reference-only A/B pair backing the
            // local fallback, the fallback mask, and the OOB diagnostic counter.
            val numerator = arena.texture(width, height, GLES30.GL_RGBA32F)
            val denominator = arena.texture(width, height, GLES30.GL_RGBA32F)
            val refNumerator = arena.texture(width, height, GLES30.GL_RGBA32F)
            val refDenominator = arena.texture(width, height, GLES30.GL_RGBA32F)
            val nearNumerator = arena.texture(width, height, GLES30.GL_RGBA32F)
            val nearDenominator = arena.texture(width, height, GLES30.GL_RGBA32F)
            val fallback = arena.texture(width, height, GLES30.GL_R32F)
            val oob = arena.texture(width, height, GLES30.GL_R32F)
            val merged = arena.texture(width, height, GLES30.GL_RGBA32F)
            active.pass("rawsr/clear_accumulators.glsl") {
                ivec2("u_size", width, height)
                image(0, numerator, GLES30.GL_RGBA32F); image(1, denominator, GLES30.GL_RGBA32F)
                image(2, refNumerator, GLES30.GL_RGBA32F); image(3, refDenominator, GLES30.GL_RGBA32F)
                image(4, fallback, GLES30.GL_R32F); image(5, oob, GLES30.GL_R32F)
                image(6, nearNumerator, GLES30.GL_RGBA32F); image(7, nearDenominator, GLES30.GL_RGBA32F)
                dispatch(width, height, 8, 8)
            }
            // Placeholder binding for shader inputs the reference/unused paths skip.
            val dummy = arena.texture(1, 1, GLES30.GL_RGBA32F)
            dummy.uploadRgba32f(floatArrayOf(0f, 0f, 0f, 1f), active.uploads)
            // Rc ping-pong: one moving frame accumulates at a time.
            var rc = arena.texture(quadsW, quadsH, GLES30.GL_R32F)
            var rcNext = arena.texture(quadsW, quadsH, GLES30.GL_R32F)
            rc.uploadR32f(FloatArray(quadsW * quadsH), active.uploads)
            // The reference uploads and normalizes exactly once and stays resident
            // with the persistent accumulators for the whole burst.
            val refLoaded = load(0, active, arena)
            val refCfa = refLoaded.cfa
            val refs = pyramid(refCfa, active, arena, config)
            // Kernel precision consumes the CPU-computed guide (4E precision
            // fix); the adapter path has no codes and reuses plain quad gray.
            // The alignment pyramid above is untouched.
            val refGuide = if (refLoaded.codes != null)
                guideGrays?.get(0)?.let { uploadGuide(it, active, arena) } else null
            val refPrecision = kernelCovariance(refGuide ?: refs[0], tuning, active, arena)
            refGuide?.let(arena::release)
            onCovariance(0, refPrecision.id, refPrecision.width, refPrecision.height)
            val fuseMoving = !referenceOnly && count > 1
            // The reference holds its linear guide for the whole burst like the pyramid.
            val refLinear = if (fuseMoving && refLoaded.codes != null)
                linearFromCodes(refLoaded.codes, robust[0], pattern, active, arena) else null
            refLoaded.codes?.let(arena::release)
            var lastFlow: Gles31AmazeProcessor.GlTexture? = null
            // Fence handles for frames still executing on the GPU. Waiting on
            // these (never a pipeline drain) is what bounds driver-side
            // in-flight storage; handles are deleted once signaled.
            val inFlight = ArrayDeque<Long>()
            if (fuseMoving) {
                for (index in 1 until count) {
                    // Exactly one moving-frame workspace is live at a time: every
                    // texture created below is released before the next RAW plane
                    // is uploaded, so peak memory is independent of burst length.
                    // The previous flow retires here (its onFlow callback already
                    // ran), never overlapping the next frame's alignment.
                    lastFlow?.let(arena::release)
                    lastFlow = null
                    val loaded = load(index, active, arena)
                    val cfa = loaded.cfa
                    val levels = pyramid(cfa, active, arena, config)
                    val flow = align(refs, levels, active, arena, config)
                    val codes = loaded.codes
                    val guide = if (codes != null)
                        guideGrays?.get(index)?.let { uploadGuide(it, active, arena) } else null
                    val guideSource = guide ?: levels[0]
                    val covariance = kernelCovariance(guideSource, tuning, active, arena)
                    onCovariance(index, covariance.id, covariance.width, covariance.height)
                    guide?.let(arena::release)
                    var rTexture: Gles31AmazeProcessor.GlTexture? = null
                    val useR = codes != null && refLinear != null
                    if (useR) {
                        val linear = linearFromCodes(codes!!, robust[index], pattern, active, arena)
                        val (rawR, flags) = robustnessPass(refLinear!!, linear, flow, tuning, config,
                            robust[0], robust[index], active, arena)
                        val r = robustnessMin(rawR, active, arena)
                        arena.release(rawR)
                        onRobustness(index, r.id, flags.id, r.width, r.height)
                        accumulateRc(rc, r, rcNext, active)
                        val old = rc; rc = rcNext; rcNext = old
                        arena.release(flags); arena.release(linear)
                        rTexture = r
                    }
                    // Validated alignment/covariance/robustness feed the merge unchanged:
                    // warped moving means went into r above, never raw texels. Each valid
                    // observation lands in its actual CFA channel weighted by kernel
                    // and the reused robustness output.
                    mergeAccumulate(cfa, flow, covariance, rTexture ?: dummy, useR,
                        isReference = false, numerator, denominator,
                        nearNumerator, nearDenominator, oob, pattern, config, active)
                    arena.release(covariance)
                    rTexture?.let(arena::release)
                    codes?.let(arena::release)
                    lastFlow = flow
                    onFlow(index, flow.id, flow.width, flow.height)
                    levels.forEach(arena::release)
                    arena.release(cfa)
                    // Deletion alone can leave an entire burst queued in the driver. Bound in-flight
                    // storage too, not just Java texture handles: a fence per frame throttled to
                    // MAX_IN_FLIGHT_FRAMES (HDR+/Stacker-style bounded queue) stalls only when the
                    // GPU actually falls behind, unlike glFinish which drains the pipeline every
                    // frame. Command order is unaffected — pixels are bit-identical.
                    inFlight.addLast(GLES31.glFenceSync(GLES31.GL_SYNC_GPU_COMMANDS_COMPLETE, 0))
                    throttleInFlight(inFlight)
                }
            }
            // The captured reference accumulates last with r_ref = 1 through the same
            // pass, filling the reference-only A/B buffers that back the fallback.
            mergeAccumulate(refCfa, dummy, refPrecision, dummy, useR = false,
                isReference = true, refNumerator, refDenominator,
                nearNumerator, nearDenominator, oob, pattern, config, active)
            arena.release(refCfa)
            refs.forEach(arena::release)
            refLinear?.let(arena::release)
            arena.release(refPrecision)
            // Reference-last add, per-channel normalization, and local reset plus
            // reference-only fallback where confidence is insufficient. Quads
            // with less than one frame-equivalent of accumulated robustness
            // are overwritten with the reference-only value (§9 support
            // overwrite); the rule is inert unless Rc was tracked, i.e. the
            // packed path with moving frames (reference-only and adapter
            // outputs already equal the reference where it matters).
            val trackSupport = fuseMoving && refLinear != null
            active.pass("rawsr/merge_finalize.glsl") {
                sampler("u_num", numerator); sampler("u_den", denominator)
                sampler("u_ref_num", refNumerator); sampler("u_ref_den", refDenominator)
                sampler("u_near_num", nearNumerator); sampler("u_near_den", nearDenominator)
                sampler("u_rc", rc)
                ivec2("u_size", width, height)
                float("u_min_support", if (trackSupport) RawSrBayerMerge.MIN_SUPPORT else 0f)
                image(0, merged, GLES30.GL_RGBA32F); image(1, fallback, GLES30.GL_R32F)
                dispatch(width, height, 8, 8)
            }
            // No glFinish here: the finalize dispatch is command-ordered after
            // the whole burst, and whatever consumes the outputs (texture
            // sampling, or a readback in the test/production saver) carries
            // its own synchronization. Draining here would idle the GPU for
            // no pixel difference.
            drainInFlight(inFlight)
            val processed = if (referenceOnly) 1 else count
            return consume(RawSrGpuOutput(numerator.id, denominator.id, width, height, processed,
                listOfNotNull(lastFlow?.id), rc.id, refNumerator.id, refDenominator.id,
                merged.id, fallback.id, oob.id, arena.memory.peakBytes))
          }
        } finally {
            processing = false
        }
    }

    /**
     * Bounded GPU queue (HDR+/Stacker pattern): at most [MAX_IN_FLIGHT_FRAMES]
     * frames execute-or-queued in the driver, so peak driver storage stays
     * burst-length independent without ever draining the pipeline. A stall
     * happens only when the GPU genuinely falls behind the submit thread.
     */
    private fun throttleInFlight(inFlight: ArrayDeque<Long>) {
        while (inFlight.size > MAX_IN_FLIGHT_FRAMES) {
            val oldest = inFlight.removeFirst()
            // Bounded wait; on expiry the GPU still finishes in order and the
            // throttle bound merely softens for one frame — never a hang.
            GLES31.glClientWaitSync(oldest, GLES31.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_WAIT_NS)
            GLES31.glDeleteSync(oldest)
        }
    }

    private fun drainInFlight(inFlight: ArrayDeque<Long>) {
        while (inFlight.isNotEmpty()) {
            val fence = inFlight.removeFirst()
            GLES31.glClientWaitSync(fence, GLES31.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_WAIT_NS)
            GLES31.glDeleteSync(fence)
        }
    }

    private class Arena : Closeable {
        val memory = RawSrTextureMemory()
        private val live = LinkedHashSet<Gles31AmazeProcessor.GlTexture>()
        fun texture(width: Int, height: Int, format: Int) =
            Gles31AmazeProcessor.GlTexture(width, height, format).also {
                live.add(it); memory.allocate(it.byteSize)
            }
        fun release(texture: Gles31AmazeProcessor.GlTexture) {
            check(live.remove(texture)) { "Texture released twice" }
            texture.close(); memory.release(texture.byteSize)
        }
        override fun close() {
            GLES31.glFinish()
            live.toList().asReversed().forEach(::release)
            check(memory.liveBytes == 0L)
        }
    }

    override fun close() {
        check(!processing) { "Cannot close RAW-SR during a live callback" }
        val active = session ?: return
        check(ownerThread == Thread.currentThread().id)
        active.egl.makeCurrent(); active.uploads.close(); active.programs.close(); active.egl.close()
        session = null; ownerThread = null
    }

    private fun ensureSession(): Session {
        val thread = Thread.currentThread().id
        check(ownerThread == null || ownerThread == thread) { "RAW-SR GLES session crossed worker threads" }
        return session ?: Session(Gles31AmazeProcessor.EglComputeContext(),
            Gles31AmazeProcessor.ProgramCache(appContext), Gles31AmazeProcessor.UploadBuffers())
            .also { session = it; ownerThread = thread }
    }

    private data class Session(
        val egl: Gles31AmazeProcessor.EglComputeContext,
        val programs: Gles31AmazeProcessor.ProgramCache,
        val uploads: Gles31AmazeProcessor.UploadBuffers
    ) {
        fun pass(asset: String, block: Bound.() -> Unit) = Bound(programs.get(asset)).block()
    }

    private class Bound(private val program: Int) {
        private var unit = 0
        init { GLES31.glUseProgram(program) }
        fun sampler(name: String, texture: Gles31AmazeProcessor.GlTexture) {
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + unit); GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture.id)
            GLES31.glUniform1i(location(name), unit++)
        }
        fun image(binding: Int, texture: Gles31AmazeProcessor.GlTexture, format: Int) =
            GLES31.glBindImageTexture(binding, texture.id, 0, false, 0, GLES31.GL_WRITE_ONLY, format)
        fun ivec2(name: String, x: Int, y: Int) = GLES31.glUniform2i(location(name), x, y)
        fun ivec4(name: String, values: IntArray) = GLES31.glUniform4iv(location(name), 1, values, 0)
        fun vec3(name: String, values: FloatArray) = GLES31.glUniform3fv(location(name), 1, values, 0)
        fun vec4(name: String, values: FloatArray) = GLES31.glUniform4fv(location(name), 1, values, 0)
        fun floats(name: String, values: FloatArray) = GLES31.glUniform1fv(location(name), values.size, values, 0)
        fun integer(name: String, value: Int) = GLES31.glUniform1i(location(name), value)
        fun float(name: String, value: Float) = GLES31.glUniform1f(location(name), value)
        fun dispatch(width: Int, height: Int, localX: Int, localY: Int) {
            GLES31.glDispatchCompute(ceilDiv(width, localX), ceilDiv(height, localY), 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
            check(GLES31.glGetError() == GLES31.GL_NO_ERROR) { "RAW-SR GLES dispatch failed" }
        }
        private fun location(name: String): Int = GLES31.glGetUniformLocation(program, name)
            .also { check(it >= 0) { "RAW-SR uniform $name missing" } }
    }

    private companion object {
        fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor
        /** Frames allowed to queue/execute in the driver (bounded memory). */
        const val MAX_IN_FLIGHT_FRAMES = 2
        /** Fence wait slice (10 s); expiry softens the bound, never hangs. */
        const val FENCE_WAIT_NS = 10_000_000_000L
    }
}
