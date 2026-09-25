// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Unified Vulkan SR merge orchestrator (shared phone/desktop bytes): pass
// sequence, uniform values, dispatch shapes, arena discipline, and the
// consume() contract. The host types (VkImage/VkArena/VkSession/VkBound)
// live in VkCompute.kt.
package com.matthew.rawlens

import android.content.Context
import android.opengl.GLES30
import java.io.Closeable

/** Writer-thread confined, borrowed-plane executor. Outputs are live only inside callbacks.
 * Prompt 4D Bayer-direct merge: the reference uploads/normalizes once and stays
 * resident with the persistent accumulators; each accepted moving frame runs the
 * unchanged alignment/covariance/robustness stages into exactly one moving-frame
 * workspace that is released before the next frame, and the captured reference
 * accumulates last with r_ref = 1. Reference-only A/B runs the same path with
 * the moving frames skipped and Rc identically zero.
 */
class VkRawSrProcessor(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private var session: VkSession? = null
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
        noiseLut: RawSrNoiseLut.Lut? = null,
        onUnblocker: (frameIndex: Int, uTextureId: Int, width: Int, height: Int) -> Unit = { _, _, _, _ -> },
        enableChroma: Boolean = false,
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
        // Computed lazily per frame: each guide is a full quad-res float field
        // (~12.5MB at 12MP), so retaining the whole burst upfront (~100MB for
        // 8 frames) pins the Dalvik heap at 0% free and stalls the viewfinder
        // in GC while merging. Values are pure in the frame bytes, so lazy
        // evaluation is pixel-identical; a frame whose guide throws still fails
        // the merge, only after (harmless, arena-released) partial GPU work.
        val guideProvider = { index: Int -> RawSrCovarianceGuide.guide(inputs[index]).gray }
        // KernelNet drives the anisotropic weights directly when its model is
        // ready: per-frame precision fields computed lazily on first use and
        // uploaded verbatim (same packing the merge consumes), bypassing the
        // analytic kernel_covariance pass. Null keeps the validated path
        // unchanged. Fields are never retained: each upload drops its heap
        // array immediately, so burst length cannot grow Dalvik peak.
        val kernelNet = kernelNetProvider(inputs, ref.width, ref.height)
        // The measured noise LUT is reference-derived and shared across the
        // burst (same sensor profile); only the reference entry is consumed.
        val robustParams = inputs.map { RawSrRobustness.gpuParams(it).copy(noiseLut = noiseLut) }
        // L1 chroma gate: per-frame single-sample green noise, derived from
        // the same captured profile. All-null unless explicitly enabled, so
        // production stays on the legacy path until a device A/B flips it.
        val chromaFrames = if (enableChroma) inputs.map {
            val input = it.uploadInput()
            chromaParamsFor(input, it.noiseProfile)
        } else List(inputs.size) { null }
        return execute(inputs.size, ref.width, ref.height, ref.pattern, resolvedConfig, estimate.tuning,
            guideProvider,
            robustParams,
            referenceOnly, onFlow, onCovariance, onRobustness, onUnblocker,
            kernelNet, ref.highlightNeutral, chromaFrames = chromaFrames,
            { index, active, arena ->
                val raw = inputs[index].uploadInput()
                val codes = uploadCodes(raw, active, arena)
                LoadedFrame(normalize(raw, codes, active, arena), codes)
            }, consume)
    }

    /**
     * Lazy per-frame KernelNet fields, or null when the model is not ready
     * (non-blocking probe — never stalls the caller on model init).
     *
     * Memory contract (512MB-heap save path): one reusable direct scratch pair
     * (~22MB at 12MP), heap model planes (~9MB), and precision grid (~50MB)
     * are allocated once per burst. No full-resolution unpacked CFA is built;
     * if that fails the provider is null and
     * the save runs fully analytic. Each field is computed on first use for
     * its frame, uploaded, and dropped — nothing is retained across frames,
     * and the kernel-only bridge builds no analytic field (that would cost
     * a retained 50MB/frame). Any per-frame failure yields null and the
     * analytic shader runs for that frame instead.
     *
     * Not thread-safe by design: [execute] invokes frames strictly in
     * sequence (reference, then moving in order), so the shared scratch is
     * never live twice. The Java wrapper serializes native inference anyway.
     *
     * The input CFA is the plain unpack (no lens-shading correction — the
     * packed-frame path carries no metadata for it). Shading is a slow
     * spatial gain; kernel shape estimation is edge-driven and effectively
     * gain-invariant, so the approximation is documented, not plumbed.
     */
    private fun kernelNetProvider(
        inputs: List<RawSrPackedFrame>,
        width: Int,
        height: Int
    ): ((index: Int) -> RawSrKernelCovariance.MatrixField?)? {
        if (!RawSrKernelNetAniso.enabled || !RawSrKernelNetAniso.isReady()) return null
        val grayScratch: java.nio.FloatBuffer
        val outScratch: java.nio.FloatBuffer
        val valuesScratch: FloatArray
        val planesScratch: FloatArray
        try {
            // Quad-res luma in, quarter-res params out (the model halves
            // internally); matches RawSrKernelNetAniso.lumaPlane and the
            // native (dim-1)/2+1 output sizing.
            val quadW = (width - 1) / 2 + 1
            val quadH = (height - 1) / 2 + 1
            val grayBytes = quadW.toLong() * quadH * 4L
            if (grayBytes > Int.MAX_VALUE) throw OutOfMemoryError("luma scratch too large")
            grayScratch = java.nio.ByteBuffer.allocateDirect(grayBytes.toInt())
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            val outW = (quadW - 1) / 2 + 1
            val outH = (quadH - 1) / 2 + 1
            valuesScratch = FloatArray(quadW * quadH * 4)
            planesScratch = FloatArray(outW * outH * 3)
            outScratch = java.nio.ByteBuffer.allocateDirect(outW * outH * 3 * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        } catch (_: Throwable) {
            android.util.Log.w("RawLensKernelNet", "scratch unavailable, analytic GPU path")
            return null
        }
        return { index ->
            try {
                val frame = inputs[index]
                if (frame.width != width || frame.height != height) null
                else RawSrKernelNetAniso.precisionForPackedKernelOnly(
                    frame, RawSrKernelNetAniso.sigmaFor(frame.noiseProfile),
                    grayScratch, outScratch, valuesScratch, planesScratch)
            } catch (_: Throwable) {
                null
            }
        }
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
        chroma: RawSrBayerMerge.ChromaParams? = null,
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
            { _, _, _, _ -> }, onCovariance, onRobustness,
            chromaFrames = chroma?.let { List(frames.size) { _ -> it } },
            kernelNet = null, load = { index, active, arena ->
                LoadedFrame(arena.texture(ref.width, ref.height, GLES30.GL_R32F).also {
                    it.uploadR32f(frames[index].values, active.uploads)
                }, null)
            }, consume = consume)
    }

    private data class LoadedFrame(val cfa: VkImage, val codes: VkImage?)

    private fun uploadCodes(raw: GpuRawAmazeInput, active: VkSession, arena: VkArena): VkImage {
        val codes = arena.texture(raw.width, raw.height, GLES30.GL_R16UI)
        codes.uploadRaw16(raw.buffer, raw.layout, raw.crop)
        return codes
    }

    private fun normalize(raw: GpuRawAmazeInput, codes: VkImage,
                          active: VkSession, arena: VkArena): VkImage {
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
    private fun linearFromCodes(codes: VkImage, params: RawSrRobustness.GpuParams,
                                pattern: BayerPattern, active: VkSession, arena: VkArena): VkImage {
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

    /**
     * Measured noise LUT as a bins x 1 RGBA32F texture (R = sigma_sq,
     * G = d_sq); null uploads a 1x1 zero dummy that the shader ignores via
     * u_lut_enabled = 0 (samplers must always bind something valid).
     */
    private fun uploadNoiseLut(lut: RawSrNoiseLut.Lut?, active: VkSession, arena: VkArena): VkImage {
        if (lut == null) {
            return arena.texture(1, 1, GLES30.GL_RGBA32F).also {
                it.uploadRgba32f(FloatArray(4), active.uploads)
            }
        }
        val packed = FloatArray(lut.bins * 4)
        for (i in 0 until lut.bins) {
            packed[i * 4] = lut.sigmaSq[i]
            packed[i * 4 + 1] = lut.dSq[i]
        }
        return arena.texture(lut.bins, 1, GLES30.GL_RGBA32F).also {
            it.uploadRgba32f(packed, active.uploads)
        }
    }

    private fun robustnessPass(refLinear: VkImage,
                               movLinear: VkImage,
                               flow: VkImage,
                               tuning: RawSrTuning, config: RawSrAlignmentConfig,
                               refParams: RawSrRobustness.GpuParams, movParams: RawSrRobustness.GpuParams,
                               lutTexture: VkImage,
                               refHot: VkImage,
                               movHot: VkImage,
                               active: VkSession, arena: VkArena): Pair<VkImage, VkImage> {
        val rawR = arena.texture(refLinear.width, refLinear.height, GLES30.GL_R32F)
        val flags = arena.texture(refLinear.width, refLinear.height, GLES30.GL_R32UI)
        active.pass("rawsr/robustness.glsl") {
            sampler("u_ref_lin", refLinear); sampler("u_mov_lin", movLinear); sampler("u_flow", flow)
            sampler("u_hot_ref", refHot); sampler("u_hot_mov", movHot)
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
            // Reference-derived LUT: the correction is keyed by reference
            // brightness and both frames share the sensor profile.
            sampler("u_lut", lutTexture)
            integer("u_lut_bins", refParams.noiseLut?.bins ?: 1)
            integer("u_lut_enabled", if (refParams.noiseLut != null) 1 else 0)
            image(0, rawR, GLES30.GL_R32F); image(1, flags, GLES30.GL_R32UI)
            dispatch(rawR.width, rawR.height, 8, 8)
        }
        return rawR to flags
    }

    private fun robustnessMin(rawR: VkImage, active: VkSession, arena: VkArena) =
        arena.texture(rawR.width, rawR.height, GLES30.GL_R32F).also { r ->
            active.pass("rawsr/robustness_min.glsl") {
                sampler("u_raw", rawR); ivec2("u_size", r.width, r.height)
                image(0, r, GLES30.GL_R32F); dispatch(r.width, r.height, 8, 8)
            }
        }

    /**
     * Hot-pixel mask from raw codes (hot_mask.glsl): R32UI, 1 where the tap
     * is a stuck-bright outlier. Consumed by the robustness gate (quad
     * projection) and the CFA inpaint below. Null codes (adapter path) skip
     * both: with no codes there is no detection domain.
     */
    private fun hotMask(codes: VkImage, params: RawSrRobustness.GpuParams,
                        active: VkSession, arena: VkArena): VkImage {
        val mask = arena.texture(codes.width, codes.height, GLES30.GL_R32UI)
        active.pass("rawsr/hot_mask.glsl") {
            sampler("u_raw", codes)
            ivec2("u_size", mask.width, mask.height)
            float("u_white", params.white)
            vec4("u_slope", params.slope); vec4("u_offset", params.offset)
            integer("u_model_valid", if (params.modelValid) 1 else 0)
            image(0, mask, GLES30.GL_R32UI); dispatch(mask.width, mask.height, 8, 8)
        }
        return mask
    }

    /**
     * Inpainted copy of a normalized CFA plane (hot_inpaint.glsl): masked
     * taps take their unmasked same-colour ring mean. The caller releases
     * the input; the output replaces it everywhere downstream (pyramid,
     * guide, merge), while the robustness gate still zeroes hot quads via
     * the mask.
     */
    private fun hotInpaint(cfa: VkImage, mask: VkImage,
                           pattern: BayerPattern, active: VkSession, arena: VkArena): VkImage {
        val out = arena.texture(cfa.width, cfa.height, GLES30.GL_R32F)
        active.pass("rawsr/hot_inpaint.glsl") {
            sampler("u_cfa", cfa); sampler("u_hot", mask)
            ivec2("u_size", out.width, out.height)
            ivec4("u_fc", AmazePipelineContract.cfaUniform(pattern))
            image(0, out, GLES30.GL_R32F); dispatch(out.width, out.height, 8, 8)
        }
        return out
    }

    /**
     * Unblocker fold (Sabre analogue): per-frame variance-loss weight from
     * the frame's quad gray, multiplied into the eroded robustness before
     * Rc accumulation. Returns (weight, attenuated r, attenuated flags);
     * the caller releases all three. The weight texture is exposed for
     * diagnostics before release, mirroring onFlow/onCovariance discipline.
     */
    private fun unblockerPass(
        gray: VkImage,
        r: VkImage,
        flags: VkImage,
        params: RawSrRobustness.GpuParams,
        active: VkSession,
        arena: VkArena
    ): Triple<VkImage, VkImage, VkImage> {
        val halfW = (gray.width + 1) / 2
        val halfH = (gray.height + 1) / 2
        val half = arena.texture(halfW, halfH, GLES30.GL_R32F)
        active.pass("rawsr/unblocker_downsample.glsl") {
            sampler("u_gray", gray)
            ivec2("u_size", halfW, halfH)
            ivec2("u_full_size", gray.width, gray.height)
            image(0, half, GLES30.GL_R32F); dispatch(halfW, halfH, 8, 8)
        }
        val weight = arena.texture(gray.width, gray.height, GLES30.GL_R32F)
        active.pass("rawsr/unblocker_weight.glsl") {
            sampler("u_gray", gray); sampler("u_half", half)
            ivec2("u_size", gray.width, gray.height)
            ivec2("u_half_size", halfW, halfH)
            vec2("u_ab", params.alpha[1], params.beta[1])
            integer("u_model_valid", if (params.modelValid) 1 else 0)
            image(0, weight, GLES30.GL_R32F); dispatch(gray.width, gray.height, 8, 8)
        }
        arena.release(half)
        val rOut = arena.texture(gray.width, gray.height, GLES30.GL_R32F)
        val flagsOut = arena.texture(gray.width, gray.height, GLES30.GL_R32UI)
        active.pass("rawsr/unblocker_modulate.glsl") {
            sampler("u_r", r); sampler("u_u", weight); sampler("u_flags_in", flags)
            ivec2("u_size", gray.width, gray.height)
            image(0, rOut, GLES30.GL_R32F); image(1, flagsOut, GLES30.GL_R32UI)
            dispatch(gray.width, gray.height, 8, 8)
        }
        return Triple(weight, rOut, flagsOut)
    }

    private fun accumulateRc(rc: VkImage, r: VkImage,
                             out: VkImage, active: VkSession) {        active.pass("rawsr/robustness_accumulate.glsl") {
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
    /**
     * L1 chroma-gate noise for one packed frame: normalized-domain
     * single-sample green slope/offset (mean of the two green phases from
     * [RawSrCovarianceGuide.noiseTables]). Deliberately NOT the x0.25
     * green-mean pair in `GpuParams` — that quarter scaling describes the
     * variance of the 2-sample green mean used by the robustness photo term,
     * while the gate keys single-sample green levels. Null (missing,
     * invalid, or zero-noise model, or a non-Bayer quad) disables the gate
     * for the frame and keeps the legacy merge path.
     */
    private fun chromaParamsFor(
        input: GpuRawAmazeInput,
        profile: ImmutableDoubleValues?
    ): RawSrBayerMerge.ChromaParams? {
        val tables = RawSrCovarianceGuide.noiseTables(input, profile)
        if (tables.modelClass != RawSrCovarianceGuide.ModelClass.VALID &&
            tables.modelClass != RawSrCovarianceGuide.ModelClass.ZERO_SHOT
        ) return null
        var s = 0.0
        var o = 0.0
        var greens = 0
        for (phase in 0..3) {
            val sx = input.sensorCropLeft + (phase and 1)
            val sy = input.sensorCropTop + ((phase shr 1) and 1)
            if (input.normalization.sensorPattern.colorAt(sx, sy) != CfaColor.GREEN) continue
            s += tables.alpha[phase]
            o += tables.beta[phase]
            greens++
        }
        if (greens != 2) return null
        return RawSrBayerMerge.ChromaParams(s / greens, o / greens)
    }

    private fun mergeAccumulate(
        cfa: VkImage,
        flow: VkImage,
        precision: VkImage,
        r: VkImage,
        useR: Boolean,
        isReference: Boolean,
        num: VkImage,
        den: VkImage,
        nearNum: VkImage,
        nearDen: VkImage,
        oob: VkImage,
        pattern: BayerPattern,
        config: RawSrAlignmentConfig,
        active: VkSession,
        chroma: RawSrBayerMerge.ChromaParams? = null
    ) {
        active.pass("rawsr/merge_accumulate.glsl") {
            sampler("u_cfa", cfa); sampler("u_flow", flow)
            sampler("u_precision", precision); sampler("u_r", r)
            sampler("u_num", num); sampler("u_den", den); sampler("u_oob", oob)
            sampler("u_near_num", nearNum); sampler("u_near_den", nearDen)
            ivec2("u_size", cfa.width, cfa.height)
            ivec2("u_tile_grid", flow.width, flow.height)
            integer("u_tile_size", config.tileSize)
            float("u_motion_edge", RawSrBayerMerge.MOTION_EDGE_QUAD)
            ivec2("u_guide_size", precision.width, precision.height)
            ivec4("u_fc", AmazePipelineContract.cfaUniform(pattern))
            integer("u_is_reference", if (isReference) 1 else 0)
            integer("u_use_r", if (useR) 1 else 0)
            // Opt-in green-guided chroma deweight (oracle ChromaParams twin):
            // null (the default, including every current call site) binds 0
            // and runs the legacy path bitwise-identically.
            integer("u_use_chroma", if (chroma != null && !isReference) 1 else 0)
            float("u_green_noise_s", chroma?.greenNoiseS?.toFloat() ?: 0f)
            float("u_green_noise_o", chroma?.greenNoiseO?.toFloat() ?: 0f)
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
    private fun uploadGuide(gray: RawSrGrayImage, active: VkSession, arena: VkArena): VkImage {
        val texture = arena.texture(gray.width, gray.height, GLES30.GL_R32F)
        texture.uploadR32f(gray.values, active.uploads)
        return texture
    }

    private fun pyramid(cfa: VkImage, active: VkSession, arena: VkArena,
                        config: RawSrAlignmentConfig): List<VkImage> {
        val levels = ArrayList<VkImage>()
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

    private fun align(refs: List<VkImage>, moving: List<VkImage>,
                      active: VkSession, arena: VkArena, config: RawSrAlignmentConfig): VkImage {
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

    private fun alignDirectional(refs: List<VkImage>, moving: List<VkImage>,
                      active: VkSession, arena: VkArena, config: RawSrAlignmentConfig): VkImage {
        var previous: VkImage? = null
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
            if (!config.refineAt(level)) { previous = matched; continue }
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

    private fun kernelCovariance(gray: VkImage, tuning: RawSrTuning,
                               active: VkSession, arena: VkArena): VkImage {
        val packed = arena.texture(gray.width, gray.height, GLES30.GL_RGBA32F)
        active.pass("rawsr/kernel_covariance.glsl") {
            sampler("u_gray", gray); ivec2("u_size", gray.width, gray.height)
            float("u_k_detail", tuning.kDetail.toFloat()); float("u_k_denoise", tuning.kDenoise.toFloat())
            float("u_d_th", tuning.dTh.toFloat()); float("u_d_tr", tuning.dTr.toFloat())
            float("u_k_stretch", tuning.kStretch.toFloat()); float("u_k_shrink", tuning.kShrink.toFloat())
            // Analytic fallback law: steerable + hard threshold, matching the
            // RawSrKernelCovariance defaults (KernelNet bypasses this pass when ready).
            integer("u_kernel_type", 0); integer("u_selection_law", 1)
            image(0, packed, GLES30.GL_RGBA32F); dispatch(gray.width, gray.height, 8, 8)
        }
        return packed
    }

    private fun uploadPrecision(field: RawSrKernelCovariance.MatrixField,
                                active: VkSession, arena: VkArena): VkImage {
        require(field.values.size == field.width * field.height * 4)
        return arena.texture(field.width, field.height, GLES30.GL_RGBA32F).also {
            it.uploadRgba32f(field.values, active.uploads)
        }
    }

    /**
     * Lazily computed KernelNet precision as a live GL texture, or null when
     * unavailable — then the caller runs the analytic pass. Never throws:
     * compute and upload are both guarded so a pressured heap degrades to
     * analytic instead of killing the save. The returned texture follows the
     * usual arena discipline; the heap field is droppable on return.
     */
    private fun kernelNetTexture(
        kernelNet: ((index: Int) -> RawSrKernelCovariance.MatrixField?)?,
        index: Int,
        active: VkSession,
        arena: VkArena
    ): VkImage? {
        if (kernelNet == null) return null
        return try {
            kernelNet.invoke(index)?.let { uploadPrecision(it, active, arena) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun <T> execute(count: Int, width: Int, height: Int, pattern: BayerPattern,
        config: RawSrAlignmentConfig, tuning: RawSrTuning, guideProvider: ((index: Int) -> RawSrGrayImage?)?,
        robust: List<RawSrRobustness.GpuParams>,
        referenceOnly: Boolean,
        onFlow: (Int, Int, Int, Int) -> Unit,
        onCovariance: (Int, Int, Int, Int) -> Unit,
        onRobustness: (Int, Int, Int, Int, Int) -> Unit,
        onUnblocker: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
        kernelNet: ((index: Int) -> RawSrKernelCovariance.MatrixField?)? = null,
        highlightNeutral: FloatArray = floatArrayOf(1f, 1f, 1f),
        chromaFrames: List<RawSrBayerMerge.ChromaParams?>? = null,
        load: (Int, VkSession, VkArena) -> LoadedFrame,
        consume: (RawSrGpuOutput) -> T
    ): T {
        require(count in 1..30 && width % 2 == 0 && height % 2 == 0)
        require(robust.size == count) { "One robustness parameter set per burst frame is required" }
        require(config.tileSize <= 32 && config.searchRadius <= 6 && config.lkIterations == 3) {
            "Configuration exceeds RAW-SR shader limits"
        }
        require(config.flowUpscale == RawSrAlignmentConfig.FlowUpscaleMode.NEAREST) {
            "GLES path supports nearest inter-level propagation only; bilinear/bicubic upsampling is CPU-oracle-only"
        }
        val active = ensureSession()
        check(!processing) { "RAW-SR callbacks must not re-enter processing" }
        processing = true
        try {
          VkArena(active.vk).use { arena ->
            val quadsW = width / 2
            val quadsH = height / 2
            // Only moving/nearest accumulators and OOB diagnostics live for the
            // whole burst. Reference accumulators and final outputs are allocated later.
            val numerator = arena.texture(width, height, GLES30.GL_RGBA32F)
            val denominator = arena.texture(width, height, GLES30.GL_RGBA32F)
            val nearNumerator = arena.texture(width, height, GLES30.GL_RGBA32F)
            val nearDenominator = arena.texture(width, height, GLES30.GL_RGBA32F)
            val oob = arena.texture(width, height, GLES30.GL_R32F)
            active.pass("rawsr/clear_accumulators.glsl") {
                ivec2("u_size", width, height)
                image(0, numerator, GLES30.GL_RGBA32F); image(1, denominator, GLES30.GL_RGBA32F)
                image(5, oob, GLES30.GL_R32F)
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
            // with the persistent accumulators for the whole burst. Hot taps
            // are inpainted into the resident CFA (the pyramid, guide, and
            // merge read the clean plane); the reference mask stays alive
            // with the linear guide because every moving robustness pass
            // gates against it.
            val refLoaded = load(0, active, arena)
            val refHotMask = refLoaded.codes?.let { hotMask(it, robust[0], active, arena) }
            val refCfaRaw = refLoaded.cfa
            val refCfa = if (refHotMask != null)
                hotInpaint(refCfaRaw, refHotMask, pattern, active, arena).also { arena.release(refCfaRaw) }
            else refCfaRaw
            val refs = pyramid(refCfa, active, arena, config)
            // Kernel precision consumes the CPU-computed guide (4E precision
            // fix); the adapter path has no codes and reuses plain quad gray.
            // The alignment pyramid above is untouched.
            // KernelNet does not consume the analytic guide. Build it only on fallback,
            // avoiding an otherwise unused full quad plane and CPU pass per frame.
            val refPrecision = kernelNetTexture(kernelNet, 0, active, arena) ?: run {
                val guide = if (refLoaded.codes != null)
                    guideProvider?.invoke(0)?.let { uploadGuide(it, active, arena) } else null
                kernelCovariance(guide ?: refs[0], tuning, active, arena).also {
                    guide?.let(arena::release)
                }
            }
            onCovariance(0, refPrecision.id, refPrecision.width, refPrecision.height)
            val fuseMoving = !referenceOnly && count > 1
            // The reference holds its linear guide for the whole burst like the pyramid.
            val refLinear = if (fuseMoving && refLoaded.codes != null)
                linearFromCodes(refLoaded.codes, robust[0], pattern, active, arena) else null
            // Reference-derived LUT texture, live for the whole burst like refLinear.
            val lutTexture = if (fuseMoving && refLinear != null)
                uploadNoiseLut(robust[0].noiseLut, active, arena) else null
            refLoaded.codes?.let(arena::release)
            var lastFlow: VkImage? = null
            if (fuseMoving) {
                for (index in 1 until count) {
                    // Exactly one moving-frame workspace is live at a time: every
                    // texture created below is released before the next RAW plane
                    // is uploaded, so peak memory is independent of burst length.
                    // The previous flow retires here (its onFlow callback already
                    // ran), never overlapping the next frame's alignment.
                    lastFlow?.let(arena::release)
                    lastFlow = null
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val loaded = load(index, active, arena)
                    val cfaRaw = loaded.cfa
                    val tLoad = android.os.SystemClock.elapsedRealtime()
                    val codes = loaded.codes
                    // Per-frame hot mask + inpaint inside the one-workspace
                    // discipline: the mask dies with the frame, so peak memory
                    // stays burst-length independent like everything else here.
                    val movHotMask = codes?.let { hotMask(it, robust[index], active, arena) }
                    val cfa = if (movHotMask != null)
                        hotInpaint(cfaRaw, movHotMask, pattern, active, arena)
                            .also { arena.release(cfaRaw) }
                    else cfaRaw
                    val levels = pyramid(cfa, active, arena, config)
                    val tPyr = android.os.SystemClock.elapsedRealtime()
                    val flow = align(refs, levels, active, arena, config)
                    val tAlign = android.os.SystemClock.elapsedRealtime()
                    val covariance = kernelNetTexture(kernelNet, index, active, arena) ?: run {
                        val guide = if (codes != null)
                            guideProvider?.invoke(index)?.let { uploadGuide(it, active, arena) } else null
                        kernelCovariance(guide ?: levels[0], tuning, active, arena).also {
                            guide?.let(arena::release)
                        }
                    }
                    onCovariance(index, covariance.id, covariance.width, covariance.height)
                    val tCov = android.os.SystemClock.elapsedRealtime()
                    var rTexture: VkImage? = null
                    val useR = codes != null && refLinear != null
                    if (useR) {
                        val linear = linearFromCodes(codes!!, robust[index], pattern, active, arena)
                        val refHotForGate = checkNotNull(refHotMask) {
                            "Packed path always masks the reference alongside moving frames"
                        }
                        val movHotForGate = checkNotNull(movHotMask) {
                            "Packed path always masks moving frames with codes present"
                        }
                        val (rawR, flags) = robustnessPass(refLinear!!, linear, flow, tuning, config,
                            robust[0], robust[index], lutTexture!!, refHotForGate, movHotForGate,
                            active, arena)
                        val r = robustnessMin(rawR, active, arena)
                        arena.release(rawR)
                        // Unblocker (Sabre analogue): attenuate the eroded
                        // robustness where boxing destroyed signal variance,
                        // before Rc accumulation consumes it. levels[0] is the
                        // frame's own inpainted quad gray, resident and
                        // correctly sized; the reference never attenuates.
                        val (uWeight, ru, flagsU) = unblockerPass(
                            levels[0], r, flags, robust[index], active, arena)
                        arena.release(r); arena.release(flags)
                        onUnblocker(index, uWeight.id, uWeight.width, uWeight.height)
                        arena.release(uWeight)
                        onRobustness(index, ru.id, flagsU.id, ru.width, ru.height)
                        accumulateRc(rc, ru, rcNext, active)
                        val old = rc; rc = rcNext; rcNext = old
                        arena.release(flagsU); arena.release(linear)
                        rTexture = ru
                    }
                    val tRobust = android.os.SystemClock.elapsedRealtime()
                    // Validated alignment/covariance/robustness feed the merge unchanged:
                    // warped moving means went into r above, never raw texels. Each valid
                    // observation lands in its actual CFA channel weighted by kernel
                    // and the reused robustness output.
                    mergeAccumulate(cfa, flow, covariance, rTexture ?: dummy, useR,
                        isReference = false, numerator, denominator,
                        nearNumerator, nearDenominator, oob, pattern, config, active,
                        chroma = chromaFrames?.getOrNull(index))
                    val tMerge = android.os.SystemClock.elapsedRealtime()
                    if (android.util.Log.isLoggable("RawLensRawSr", android.util.Log.DEBUG)) {
                        android.util.Log.d("RawLensRawSr", "gpu frame $index ${width}x$height" +
                            " load=${tLoad - t0}ms pyramid=${tPyr - tLoad}ms align=${tAlign - tPyr}ms" +
                            " cov=${tCov - tAlign}ms robust=${tRobust - tCov}ms merge=${tMerge - tRobust}ms")
                    }
                    arena.release(covariance)
                    rTexture?.let(arena::release)
                    codes?.let(arena::release)
                    movHotMask?.let(arena::release)
                    lastFlow = flow
                    onFlow(index, flow.id, flow.width, flow.height)
                    levels.forEach(arena::release)
                    arena.release(cfa)

                }
            }
            // Unused by moving frames: defer these planes until their workspace retires.
            val refNumerator = arena.texture(width, height, GLES30.GL_RGBA32F)
            val refDenominator = arena.texture(width, height, GLES30.GL_RGBA32F)
            active.pass("rawsr/clear_reference.glsl") {
                ivec2("u_size", width, height)
                image(0, refNumerator, GLES30.GL_RGBA32F)
                image(1, refDenominator, GLES30.GL_RGBA32F)
                dispatch(width, height, 8, 8)
            }
            // The captured reference accumulates last with r_ref = 1 through the same
            // pass, filling the reference-only A/B buffers that back the fallback.
            mergeAccumulate(refCfa, dummy, refPrecision, dummy, useR = false,
                isReference = true, refNumerator, refDenominator,
                nearNumerator, nearDenominator, oob, pattern, config, active)
            arena.release(refCfa)
            refs.forEach(arena::release)
            refLinear?.let(arena::release)
            refHotMask?.let(arena::release)
            lutTexture?.let(arena::release)
            arena.release(refPrecision)
            arena.release(dummy)
            arena.release(rcNext)
            // Reference-last add, per-channel normalization, and local reset plus
            // reference-only fallback where confidence is insufficient. Quads
            // with less than one frame-equivalent of accumulated robustness
            // are overwritten with the reference-only value (§9 support
            // overwrite); the rule is inert unless Rc was tracked, i.e. the
            // packed path with moving frames (reference-only and adapter
            // outputs already equal the reference where it matters).
            val trackSupport = fuseMoving && refLinear != null
            // Finalize writes every pixel, so outputs need no initial clear.
            val merged = arena.texture(width, height, GLES30.GL_RGBA32F)
            val fallback = arena.texture(width, height, GLES30.GL_R32F)
            active.pass("rawsr/merge_finalize.glsl") {
                sampler("u_num", numerator); sampler("u_den", denominator)
                sampler("u_ref_num", refNumerator); sampler("u_ref_den", refDenominator)
                sampler("u_near_num", nearNumerator); sampler("u_near_den", nearDenominator)
                sampler("u_rc", rc)
                vec4("u_highlight_neutral", floatArrayOf(highlightNeutral[0], highlightNeutral[1], highlightNeutral[2], 0f))
                ivec2("u_size", width, height)
                float("u_min_support", if (trackSupport) RawSrBayerMerge.MIN_SUPPORT else 0f)
                image(0, merged, GLES30.GL_RGBA32F); image(1, fallback, GLES30.GL_R32F)
                dispatch(width, height, 8, 8)
            }
            // Finalization has completed; these are not exposed to the consumer.
            // Free another two RGBA32F planes before JPEG development/readback.
            arena.release(nearNumerator)
            arena.release(nearDenominator)
            val processed = if (referenceOnly) 1 else count
            return consume(RawSrGpuOutput(numerator.id, denominator.id, width, height, processed,
                listOfNotNull(lastFlow?.id), rc.id, refNumerator.id, refDenominator.id,
                merged.id, fallback.id, oob.id, arena.memory.peakBytes))
          }
        } finally {
            processing = false
        }
    }

    override fun close() {
        check(!processing) { "Cannot close RAW-SR during a live callback" }
        val active = session ?: return
        check(ownerThread == Thread.currentThread().id)
        active.uploads.close(); active.programs.close(); active.vk.close()
        session = null; ownerThread = null
    }

    private fun ensureSession(): VkSession {
        val thread = Thread.currentThread().id
        check(ownerThread == null || ownerThread == thread) { "RAW-SR Vulkan session crossed worker threads" }
        return session ?: run {
            val vk = SrVulkan.open()
            VkSession(vk, VkProgramCache(vk, appContext.assets), UploadBuffers())
        }
            .also { session = it; ownerThread = thread }
    }

    private fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor
}
