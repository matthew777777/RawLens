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
    companion object {
        /**
         * Production-peak transient bytes for a linear merge of a
         * [srcW]x[srcH] burst at [scale]: the max of the finalize peak and
         * the moving-frame peak, so a fit here is a safe run and a miss
         * refuses before any allocation. Pure (unit-testable, no GL).
         *
         * Finalize peak: five RGBA32F output planes (num, den, refNum,
         * refDen, merged) plus R32F fallback/oob with
         * [releaseAccumulators] trimming, resident Rc, and the last flow.
         * The inpaint peak (numerators already released) sits below it.
         *
         * Moving peak: merge accumulators (num, den, oob) plus the resident
         * reference planes and exactly one moving-frame workspace, sized
         * for the full alignment workspace: FFT scratch, the padded
         * full-resolution pyramid pair, and the per-level flow ping-pong.
         * The FFT term budgets one complex ping-pong pair at full
         * resolution: the GPU FFT grey pass (Open Q3 option (b))
         * consumes the uploaded mosaic plus two complex pairs and small
         * twiddle rows, and the CPU grey stays as fallback (host planes
         * plus one grey upload, below this term), so the estimate stays
         * a safe upper bound on both paths.
         */
        fun estimateTransientBytes(srcW: Int, srcH: Int, scale: RawSrLinearScale): Long {
            val (outW, outH) = planSrOutputDims(srcW, srcH, scale.factor)
            val out = outW.toLong() * outH
            val src = srcW.toLong() * srcH
            // execute() requires even source dims, so the quad grid is exact.
            val quads = (srcW / 2).toLong() * (srcH / 2)
            // Padded full-res grey geometry (circularPad rounds up to a tile
            // multiple; +64 covers the ts=64 worst case).
            val padW = srcW + 64L
            val padH = srcH + 64L
            val l0 = padW * padH
            // One full-res R32F pyramid (levels [1,2,4,4] applied to the
            // previous level; valid-convolution shrinkage only reduces):
            // resident L0..L3 plus the worst horizontal separable
            // transient (L0 -> L1).
            val pyramidBytes = (l0 + l0 / 4 + l0 / 16 + l0 / 64) * 4 + (l0 / 2) * 4
            // Flow workspace at the largest grid (ts=16 raw px; the grid
            // shrinks 4x L0 -> L1, 16x L1 -> L2, 4x L2 -> L3): matched +
            // refined + previous + upscale RGBA per live level.
            val g0 = (padW / 16 + 1) * (padH / 16 + 1)
            val flowWorkspaceBytes = (g0 + g0 / 4 + g0 / 64 + g0 / 256) * 16 * 4
            val lastFlowBytes = g0 * 16
            val finalizePeak = out * (5L * 16 + 2L * 4) + quads * 4 + lastFlowBytes
            val movingPeak = out * (2L * 16 + 4) + // num, den, oob
                src * 4 * 2 + // ref + moving CFA
                src * 2 * 2 + // ref + moving R16 codes
                src * 8 * 2 + // FFT complex ping-pong pair
                pyramidBytes * 2 + // ref (padded) + moving pyramid
                quads * 16 * 2 + // ref + moving covariance
                quads * 16 * 2 + // ref + moving linear guide
                quads * 4 + // transient GAT guide upload
                quads * 4 * 3 + // rawR + flags + r
                quads * 4 * 2 + // Rc ping-pong
                flowWorkspaceBytes + // per-level flow ping-pong
                lastFlowBytes + // sensor-space remap briefly doubles the finest flow
                src * 8 // host upload staging + judge readbacks
            return maxOf(finalizePeak, movingPeak)
        }

        /**
         * CPU-side view of one GPU flow readback for the shared frame-
         * rejection gate ([RawSrFrameRejection.judge]): the 1:1 lattice
         * carries RAW-pixel flows on RAW-pixel tiles throughout (the
         * reference convention), so dx/dy apply verbatim and [tileSize]
         * is the raw-pixel tile ([RawSrAlignmentConfig.tileSizeAt]).
         * RGBA is (dx, dy, meanAbsResidual, detOk): reliability is the
         * host-side auxiliary verdict — Hessian solved, finite flow,
         * residual under the gate — mirroring
         * [RawSrCoreAlign.auxiliaryReliable], which the CPU chain applies
         * to the identical values. Pure (unit-testable, no GL).
         */
        internal fun flowFieldFromReadback(
            rgba: FloatArray,
            columns: Int,
            rows: Int,
            imageWidth: Int,
            imageHeight: Int,
            tileSize: Int,
            maxMeanAbsoluteResidual: Double = RawSrAlignmentConfig().maxMeanAbsoluteResidual
        ): RawSrAlignmentField {
            require(columns > 0 && rows > 0)
            require(rgba.size == columns * rows * 4)
            val tiles = ArrayList<RawSrTileFlow>(columns * rows)
            for (ty in 0 until rows) for (tx in 0 until columns) {
                val base = (ty * columns + tx) * 4
                val dx = rgba[base]
                val dy = rgba[base + 1]
                val residual = rgba[base + 2]
                val detOk = rgba[base + 3] > 0f
                val left = tx * tileSize
                val top = ty * tileSize
                val right = minOf(left + tileSize, imageWidth)
                val bottom = minOf(top + tileSize, imageHeight)
                tiles.add(RawSrTileFlow(
                    centerX = (left + right) * 0.5f,
                    centerY = (top + bottom) * 0.5f,
                    dx = dx,
                    dy = dy,
                    residual = residual,
                    reliable = detOk && dx.isFinite() && dy.isFinite() &&
                        residual.toDouble() <= maxMeanAbsoluteResidual
                ))
            }
            return RawSrAlignmentField(imageWidth, imageHeight, tileSize, columns, rows, tiles)
        }

        /**
         * True when [availBytes] covers the estimate with headroom: at most
         * three quarters of free RAM is committed to the merge (a 12MP SR
         * save fits with ~2.7GB free, while thinner devices fall back to 1x
         * instead of OOMing mid-save; the caller's OOM catch stays as the
         * backstop for racing allocations).
         */
        fun upscaleFits(srcW: Int, srcH: Int, scale: RawSrLinearScale, availBytes: Long): Boolean =
            estimateTransientBytes(srcW, srcH, scale) * 4 <= availBytes * 3
    }

    private val appContext = context.applicationContext
    private var session: VkSession? = null
    private var ownerThread: Long? = null
    private var processing = false

    /** Inputs must be planner-approved and reference-first. No Image ownership is transferred.
     * onFlow executes before the workspace is retired; never retain its texture ID.
     * A null [tuningOverride] resolves tuning from the reference SNR scan; a fixed
     * tuning (e.g. [RawSrTuning.decoupledSharp]) replaces it for A/B presets.
     * Moving frames that fail the shared [RawSrFrameRejection] support gate
     * (judged on readback flow/robustness, like the CPU chain) skip both the
     * Rc and the merge accumulate; [RawSrGpuOutput.acceptedFrames] counts
     * the reference plus the kept moving frames. Rejections log at WARN.
     */
    fun <T> processPacked(
        frames: List<RawSrPackedFrame>,
        config: RawSrAlignmentConfig? = null,
        tuningOverride: RawSrTuning? = null,
        referenceOnly: Boolean = false,
        onFlow: (frameIndex: Int, textureId: Int, columns: Int, rows: Int) -> Unit = { _, _, _, _ -> },
        onCovariance: (frameIndex: Int, textureId: Int, width: Int, height: Int) -> Unit = { _, _, _, _ -> },
        onRobustness: (frameIndex: Int, rTextureId: Int, flagsTextureId: Int, width: Int, height: Int)
            -> Unit = { _, _, _, _, _ -> },
        /** Alignment-grey diagnostic: fires with the pyramid input (frame 0
         * is the reference). The input is the resident GPU FFT grey
         * (reference: circular-padded via circular_pad; moving:
         * unpadded); the CPU fallback uploads instead. */
        onGrey: (frameIndex: Int, textureId: Int, width: Int, height: Int) -> Unit = { _, _, _, _ -> },
        noiseLut: RawSrNoiseLut.Lut? = null,
        enableChroma: Boolean = false,
        /** Production memory trim: drop the four accumulator pairs once the
         * finalize/inpaint passes have consumed them, so the develop + save
         * tail inside [consume] runs without ~768MB of dead textures at
         * 12MP. Released IDs report 0; parity and quality harnesses that
         * read the accumulators keep the default. */
        releaseAccumulators: Boolean = false,
        /** Output grid: SR resolves the shared √2 grid (2x area, ~25 MP,
         * same lattice as mosaic SR). Alignment/covariance/robustness stay
         * source-anchored; only the merge-stage textures and dispatches
         * scale. */
        scale: RawSrLinearScale = RawSrLinearScale.X1,
        /** Merge-time support gate, shared with the CPU chain
         * ([RawSrMergeJob.mosaicChain]): same defaults, same verdicts for
         * identical flow/robustness. */
        rejectionPolicy: RawSrFrameRejection.Policy = RawSrFrameRejection.Policy(),
        consume: (RawSrGpuOutput) -> T
    ): T {
        val inputs = frames.toList()
        require(inputs.isNotEmpty())
        val ref = inputs.first()
        require(inputs.all { it.width == ref.width && it.height == ref.height && it.pattern == ref.pattern })
        val estimate = RawSrTuning.fromReference(ref)
        val tuning = tuningOverride ?: estimate.tuning
        val resolvedConfig = config ?: tuning.alignmentConfig()
        if (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            android.util.Log.d("RawLensRawSrTuning",
            estimate.debugSummary() + " explicitTuningOverride=${tuningOverride != null}" +
                " explicitAlignmentOverride=${config != null} activeTilePx=${resolvedConfig.tileSize}")
        // Analytic kernel covariance consumes plain quad gray of the
        // normalized CFA (dedicated quadGray dispatch in execute(); the
        // alignment pyramid base is FFT grey now), exactly the oracle
        // input: the CPU merge feeds bayerQuadGray of the same corrected CFA
        // (RawSrMergeJob.mergeFrame default) per the documented no-GAT
        // contract on RawSrKernelCovariance. A prior revision uploaded the
        // CPU-computed GAT guide here for estimator parity, but the oracle
        // merge never consumes GAT input: VST-domain gradients read strong
        // edges as full-detail (D=0, k1~0.13 razor) where the oracle reads
        // denoise-wide kernels, so the GPU latched onto single taps and wrote
        // 1px full-range spikes at high-contrast slanted edges (burst522
        // truck roof) that the CPU never showed. Null keeps the plain-gray
        // path; VkMergeParityTest pins strong-edge agreement.
        // KernelNet (RawSrKernelNetAniso.enabled, default on): per-frame
        // covariance fields computed lazily on first use and uploaded
        // verbatim (same packing the merge consumes), bypassing the analytic
        // kernel_covariance pass. Null (disabled, or model not ready) keeps
        // the analytic path unchanged. Fields are never retained: each upload
        // drops its heap array immediately, so burst length cannot grow
        // Dalvik peak.
        val kernelNet = kernelNetProvider(inputs, ref.width, ref.height)
        // GPU FFT grey (rewrite-plan Open Q3 option (b)) with CPU
        // fallback: the alignment input is the unshaded normalized unpack
        // ([RawSrMergeJob.unpack], exactly the CPU chain's `plain`); the
        // GPU FFTs the uploaded mosaic with the RGGB processing-space
        // flip fused into the first stage (reference cfa_to_rggb, like
        // the CPU chain), and any GPU failure falls back to the host
        // grey below. One mosaic is live at a time; each upload drops
        // its heap array immediately.
        val greyProvider: (Int) -> RawSrGrayImage = { index ->
            val plain = RawSrMergeJob.unpack(inputs[index])
            val processing = RawSrCfaOrientation.toProcessingSpace(
                plain.values, plain.width, plain.height,
                RawSrCfaOrientation.forPattern(plain.pattern))
            RawSrAlignment.fftGrey(processing, plain.width, plain.height)
        }
        val mosaicProvider: (Int) -> FftMosaic = { index ->
            val plain = RawSrMergeJob.unpack(inputs[index])
            FftMosaic(plain.values, plain.width, plain.height,
                RawSrCfaOrientation.forPattern(plain.pattern))
        }
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
        return execute(inputs.size, ref.width, ref.height, ref.pattern, resolvedConfig, tuning,
            null, greyProvider, mosaicProvider,
            robustParams,
            referenceOnly, onFlow, onCovariance, onRobustness,
            kernelNet, chromaFrames = chromaFrames,
            releaseAccumulators = releaseAccumulators,
            scale = scale,
            onGrey = onGrey,
            rejectionPolicy = rejectionPolicy,
            { index, active, arena ->
                val raw = inputs[index].uploadInput()
                val codes = uploadCodes(raw, active, arena)
                LoadedFrame(normalize(raw, codes, active, arena), codes)
            }, consume, ubGuideProvider = { index ->
                // 4E precedent: the CPU guide gray (double precision,
                // exactly the oracle input) is uploaded verbatim — the
                // pyramid's linear grey lives in a different domain and
                // would gate differently.
                try {
                    RawSrCovarianceGuide.guide(inputs[index]).gray
                } catch (_: Throwable) {
                    null
                }
            })
    }

    /**
     * Lazy per-frame KernelNet covariance fields (A/B only), or null when the
     * switch is off or the model is not ready (non-blocking probe — never
     * stalls the caller on model init).
     *
     * Memory contract (512MB-heap save path): one reusable direct scratch pair
     * (~22MB at 12MP), heap model planes (~9MB), and covariance grid (~50MB)
     * are allocated once per burst, on first use (after the reference FFT
     * grey has run and released its transient). No full-resolution unpacked
     * CFA is built; if that fails the provider yields null and
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
    /** Burst scratch for [kernelNetProvider], allocated once on first use (see below). */
    private data class KernelNetScratch(
        val gray: java.nio.FloatBuffer,
        val out: java.nio.FloatBuffer,
        val values: FloatArray,
        val planes: FloatArray,
        /** Auto-sigma ratio measured once on the reference, or null when unmeasurable. */
        val autoRatio: Float?
    )

    private fun kernelNetProvider(
        inputs: List<RawSrPackedFrame>,
        width: Int,
        height: Int
    ): ((index: Int) -> RawSrKernelCovariance.MatrixField?)? {
        if (!RawSrKernelNetAniso.enabled || !RawSrKernelNetAniso.isReady()) return null
        // Scratch (~81MB at 12MP) and the auto-sigma reference unpack
        // allocate on first use. Index 0 runs first per the execute
        // sequence note, so the values are identical to upfront init —
        // but upfront they pin the heap beside the retained burst while
        // the reference FFT grey (its own ~100MB transient) runs, tipping
        // 512MB-heap saves into OOM before anything is written. A failed
        // first use parks the provider on the analytic path, exactly like
        // the old upfront-null provider.
        var scratch: KernelNetScratch? = null
        var scratchFailed = false
        fun ensureScratch(): KernelNetScratch? {
            scratch?.let { return it }
            if (scratchFailed) return null
            try {
                // Quad-res luma in, quarter-res params out (the model halves
                // internally); matches RawSrKernelNetAniso.lumaPlane and the
                // native (dim-1)/2+1 output sizing.
                val quadW = (width - 1) / 2 + 1
                val quadH = (height - 1) / 2 + 1
                val grayBytes = quadW.toLong() * quadH * 4L
                if (grayBytes > Int.MAX_VALUE) throw OutOfMemoryError("luma scratch too large")
                val grayScratch = java.nio.ByteBuffer.allocateDirect(grayBytes.toInt())
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                val outW = (quadW - 1) / 2 + 1
                val outH = (quadH - 1) / 2 + 1
                val valuesScratch = FloatArray(quadW * quadH * 4)
                val planesScratch = FloatArray(outW * outH * 3)
                val outScratch = java.nio.ByteBuffer.allocateDirect(outW * outH * 3 * 4)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                // Auto-sigma input: measured once on the reference, shared
                // by every frame's sigma like the desktop chain's ratio.
                // Null when unmeasurable — sigmaFor then uses the profile
                // alone.
                val autoRatio = try {
                    val ref = inputs[0]
                    RawSrFrameNoiseMeter.compare(RawSrMergeJob.unpack(ref), ref.noiseProfile)?.ratio
                } catch (_: Throwable) {
                    null
                }
                scratch = KernelNetScratch(
                    grayScratch, outScratch, valuesScratch, planesScratch, autoRatio)
            } catch (_: Throwable) {
                scratchFailed = true
                android.util.Log.w("RawLensKernelNet", "scratch unavailable, analytic GPU path")
            }
            return scratch
        }
        return { index ->
            try {
                val ready = ensureScratch()
                val frame = inputs[index]
                if (ready == null || frame.width != width || frame.height != height) null
                else RawSrKernelNetAniso.precisionForPackedKernelOnly(
                    frame, RawSrKernelNetAniso.sigmaFor(frame.noiseProfile, ready.autoRatio),
                    ready.gray, ready.out, ready.values, ready.planes)
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
        scale: RawSrLinearScale = RawSrLinearScale.X1,
        consume: (RawSrGpuOutput) -> T
    ): T {
        require(frames.isNotEmpty())
        val ref = frames.first()
        require(frames.all { it.width == ref.width && it.height == ref.height && it.pattern == ref.pattern })
        val resolvedTuning = tuning ?: RawSrTuning.estimate(
            if (ref.values.isEmpty()) 0.0 else ref.values.average(), null).tuning
        // Test-adapter grey: the oracle CFA planes are already the
        // normalized mosaic the FFT front end consumes (identity flip,
        // like the CPU adapter path — no processing-space remap here).
        val greyAdapter: (Int) -> RawSrGrayImage = { index ->
            RawSrAlignment.fftGrey(frames[index].values, ref.width, ref.height)
        }
        val mosaicAdapter: (Int) -> FftMosaic = { index ->
            FftMosaic(frames[index].values, ref.width, ref.height,
                RawSrCfaOrientation.Flip.IDENTITY)
        }
        return execute(frames.size, ref.width, ref.height, ref.pattern, config, resolvedTuning,
            null, greyAdapter, mosaicAdapter,
            List(frames.size) { RawSrRobustness.bypass() }, referenceOnly,
            { _, _, _, _ -> }, onCovariance, onRobustness,
            scale = scale,
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
        codes.uploadRaw16(raw.buffer, raw.layout, raw.crop, active.uploads)
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

    /** Jamy-L Alg. 7 sqrt guide: unshaded normalized sqrt(R/G/B) per quad. */
    private fun linearFromCodes(codes: VkImage, params: RawSrRobustness.GpuParams,
                                pattern: BayerPattern, active: VkSession, arena: VkArena): VkImage {
        val linear = arena.texture(codes.width / 2, codes.height / 2, GLES30.GL_RGBA32F)
        active.pass("rawsr/linear_guide.glsl") {
            sampler("u_raw", codes); ivec2("u_size", linear.width, linear.height)
            ivec4("u_fc", AmazePipelineContract.cfaUniform(pattern))
            vec4("u_black", params.black); float("u_white", params.white)
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
                               lut: RawSrNoiseLut.Lut?,
                               lutTexture: VkImage,
                               active: VkSession, arena: VkArena): Pair<VkImage, VkImage> {
        val rawR = arena.texture(refLinear.width, refLinear.height, GLES30.GL_R32F)
        val flags = arena.texture(refLinear.width, refLinear.height, GLES30.GL_R32UI)
        active.pass("rawsr/robustness.glsl") {
            sampler("u_ref_lin", refLinear); sampler("u_mov_lin", movLinear); sampler("u_flow", flow)
            ivec2("u_size", rawR.width, rawR.height)
            ivec2("u_tile_grid", flow.width, flow.height)
            integer("u_tile_size", config.tileSize)
            float("u_t", tuning.t.toFloat())
            float("u_s1", tuning.s1.toFloat()); float("u_s2", tuning.s2.toFloat())
            // Raw-pixel threshold (reference Mt): the 1:1 flow lattice
            // carries raw-unit vectors (retired: the legacy quad lattice
            // halved this to u_mth_quad).
            float("u_mth", tuning.mTh.toFloat())
            // Reference-derived LUT: the correction is keyed by reference
            // brightness and both frames share the sensor profile.
            sampler("u_lut", lutTexture)
            integer("u_lut_bins", lut?.bins ?: 1)
            integer("u_lut_enabled", if (lut != null) 1 else 0)
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
     * Sabre-style unblocker fold for one moving frame (always on): 2x2 box
     * the moving grey, score variance loss, cap + veto the min'd
     * robustness, and spread through a second 5x5 minimum — the GPU twin
     * of the CPU `applyToFrameAndSpread` (the CPU guide gray uploaded
     * verbatim, same green coefficients, same thresholds). The verdict
     * judge ran on the pre-fold field, so frame selection is unchanged.
     * Returns null without a usable noise model (the caller keeps the
     * pre-fold field: no model, no gate).
     */
    private fun unblockerFold(
        rMin: VkImage,
        flags: VkImage,
        gray: VkImage,
        params: RawSrRobustness.GpuParams,
        flow: VkImage,
        tuning: RawSrTuning,
        config: RawSrAlignmentConfig,
        active: VkSession, arena: VkArena
    ): VkImage? {
        if (!params.modelValid) return null
        val halfW = (gray.width + 1) / 2
        val halfH = (gray.height + 1) / 2
        val half = arena.texture(halfW, halfH, GLES30.GL_R32F)
        active.pass("rawsr/unblocker_downsample.glsl") {
            sampler("u_gray", gray)
            ivec2("u_size", halfW, halfH)
            ivec2("u_full_size", gray.width, gray.height)
            image(0, half, GLES30.GL_R32F)
            dispatch(halfW, halfH, 8, 8)
        }
        val u = arena.texture(gray.width, gray.height, GLES30.GL_R32F)
        active.pass("rawsr/unblocker_weight.glsl") {
            sampler("u_gray", gray); sampler("u_half", half)
            ivec2("u_size", gray.width, gray.height)
            ivec2("u_half_size", halfW, halfH)
            vec2("u_ab", params.alpha[1], params.beta[1])
            integer("u_model_valid", 1)
            image(0, u, GLES30.GL_R32F)
            dispatch(gray.width, gray.height, 8, 8)
        }
        val rMod = arena.texture(rMin.width, rMin.height, GLES30.GL_R32F)
        val flagsMod = arena.texture(rMin.width, rMin.height, GLES30.GL_R32UI)
        active.pass("rawsr/unblocker_modulate.glsl") {
            sampler("u_r", rMin); sampler("u_u", u); sampler("u_flags_in", flags)
            sampler("u_flow", flow)
            ivec2("u_size", rMin.width, rMin.height)
            ivec2("u_tile_grid", flow.width, flow.height)
            integer("u_tile_size", config.tileSize)
            float("u_mth", tuning.mTh.toFloat())
            float("u_veto_u", RawSrUnblocker.VETO_UNBLOCKER_THRESHOLD)
            integer("u_unblocked", RawSrRobustness.FLAG_UNBLOCKED)
            image(0, rMod, GLES30.GL_R32F); image(1, flagsMod, GLES30.GL_R32UI)
            dispatch(rMin.width, rMin.height, 8, 8)
        }
        return robustnessMin(rMod, active, arena)
    }

    /**
     * Merge-time support gate for one moving frame: the shared CPU verdict
     * ([RawSrFrameRejection.judge]) on readback flow + robustness,
     * mirroring the CPU chain's `buildMovingFrame` gate. Rejection stays a
     * host decision — the caller skips both the Rc and the merge
     * accumulate for a rejected frame. The readbacks are small (flow grid
     * + quad grid) and fence-waited inside the download calls.
     */
    private fun judgeMovingFrame(
        flow: VkImage,
        r: VkImage,
        flags: VkImage,
        width: Int,
        height: Int,
        config: RawSrAlignmentConfig,
        policy: RawSrFrameRejection.Policy
    ): RawSrFrameRejection.Verdict {
        val field = flowFieldFromReadback(
            flow.downloadRgba32f(), flow.width, flow.height, width, height,
            config.tileSizeAt(0), config.maxMeanAbsoluteResidual)
        val robustness = RawSrRobustness.FrameRobustness(
            r.width, r.height, r.downloadR32f(), flags.downloadR32ui())
        return RawSrFrameRejection.judge(field, robustness, policy)
    }

    /**
     * Hot-pixel mask from raw codes (hot_mask.glsl): R32UI, 1 where the tap
     * is a stuck-bright outlier. Dormant: Jamy-L parity bypasses the stage
     * (see the reference CFA above), so nothing dispatches this pass.
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
     * taps take their unmasked same-colour ring mean. Dormant: Jamy-L
     * parity bypasses the stage (see the reference CFA above).
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

    private fun accumulateRc(rc: VkImage, r: VkImage,
                             out: VkImage, active: VkSession) {        active.pass("rawsr/robustness_accumulate.glsl") {
            sampler("u_rc", rc); sampler("u_r", r)
            ivec2("u_size", out.width, out.height)
            image(0, out, GLES30.GL_R32F); dispatch(out.width, out.height, 8, 8)
        }
    }

    /** Jamy-L Alg. 4 Bayer-direct accumulation into one accumulator pair.
     * Moving frames feed [num]/[den]; the reference-last pass feeds the
     * reference-only A/B pair through the identical shader with zero shift
     * and unit robustness.
     */
    /**
     * A/B chroma-gate noise for one packed frame: normalized-domain
     * single-sample green slope/offset (mean of the two green phases from
     * [RawSrCovarianceGuide.noiseTables]). Null (missing, invalid, or
     * zero-noise model, or a non-Bayer quad) disables the gate for the frame
     * and keeps the reference merge path.
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
        covariance: VkImage,
        r: VkImage,
        useR: Boolean,
        isReference: Boolean,
        num: VkImage,
        den: VkImage,
        oob: VkImage,
        pattern: BayerPattern,
        config: RawSrAlignmentConfig,
        active: VkSession,
        scale: RawSrLinearScale,
        chroma: RawSrBayerMerge.ChromaParams? = null
    ) {
        active.pass("rawsr/merge_accumulate.glsl") {
            sampler("u_cfa", cfa); sampler("u_flow", flow)
            sampler("u_covariance", covariance); sampler("u_r", r)
            sampler("u_num", num); sampler("u_den", den); sampler("u_oob", oob)
            ivec2("u_size", num.width, num.height)
            ivec2("u_tile_grid", flow.width, flow.height)
            integer("u_tile_size", config.tileSize)
            float("u_upscale", scale.factor.toFloat())
            ivec2("u_guide_size", covariance.width, covariance.height)
            ivec4("u_fc", AmazePipelineContract.cfaUniform(pattern))
            integer("u_is_reference", if (isReference) 1 else 0)
            integer("u_use_r", if (useR) 1 else 0)
            // A/B green-guided chroma deweight (oracle ChromaParams twin):
            // null (the default) binds 0 and runs the reference path.
            integer("u_use_chroma", if (chroma != null && !isReference) 1 else 0)
            float("u_green_noise_s", chroma?.greenNoiseS?.toFloat() ?: 0f)
            float("u_green_noise_o", chroma?.greenNoiseO?.toFloat() ?: 0f)
            // Chroma latch guard (base path, always on): the R/B precision-domain
            // scale 1/s^2 from the merge default (RawSrBayerMerge.CHROMA_SIGMA_MPY).
            float("u_chroma_z_scale",
                RawSrCoreKernel.chromaZScale(RawSrBayerMerge.CHROMA_SIGMA_MPY).toFloat())
            image(0, num, GLES30.GL_RGBA32F); image(1, den, GLES30.GL_RGBA32F)
            image(2, oob, GLES30.GL_R32F)
            dispatch(num.width, num.height, 8, 8)
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

    /**
     * CPU-fallback FFT grey upload: the caller releases the texture. The
     * reference grey is padded on the host before this call, the moving
     * grey is uploaded unpadded; the GPU path pads via circular_pad
     * instead ([greyTexture]).
     */
    private fun uploadGrey(grey: RawSrGrayImage, active: VkSession, arena: VkArena): VkImage {
        val texture = arena.texture(grey.width, grey.height, GLES30.GL_R32F)
        texture.uploadR32f(grey.values, active.uploads)
        return texture
    }

    /** GPU FFT input: unshaded normalized mosaic + its sensor→RGGB flip. */
    data class FftMosaic(
        val values: FloatArray,
        val width: Int,
        val height: Int,
        val flip: RawSrCfaOrientation.Flip
    )

    /**
     * Resident alignment grey for frame [index] (rewrite-plan Open Q3
     * option (b)): the GPU FFTs the uploaded mosaic ([fftGreyGpu]) and
     * the reference pads via circular_pad ([padGreyTexture]); any GPU
     * failure falls back to the host grey + host pad + upload, so a
     * flaky driver costs time, never a frame. Cancellation propagates
     * (the moving loop rethrows it; the reference has no catch either).
     * A null [padToTile] uploads unpadded (moving); otherwise pads to a
     * tile multiple (reference). The caller releases the texture.
     */
    private fun greyTexture(
        index: Int,
        padToTile: Int?,
        greyProvider: (Int) -> RawSrGrayImage,
        mosaicProvider: ((Int) -> FftMosaic)?,
        active: VkSession, arena: VkArena
    ): VkImage {
        if (mosaicProvider != null) {
            var cached: FftMosaic? = null
            try {
                val m = mosaicProvider.invoke(index)
                cached = m
                return padGreyTexture(
                    fftGreyGpu(m.values, m.width, m.height, m.flip, active, arena),
                    padToTile, active, arena)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w("RawLensRawSr",
                    "gpu frame $index FFT grey failed (${failure.message}), CPU fallback")
                // Reuse the already-unpacked mosaic for the CPU fallback
                // instead of unpacking the same frame twice: identical
                // plain values, identical processing-space remap and grey,
                // one less full-frame unpack + 50MB transient per fallback.
                val reuse = cached
                if (reuse != null) {
                    val processing = RawSrCfaOrientation.toProcessingSpace(
                        reuse.values, reuse.width, reuse.height, reuse.flip)
                    val cpu = RawSrAlignment.fftGrey(processing, reuse.width, reuse.height)
                    val padded = if (padToTile != null) RawSrAlignment.circularPad(cpu, padToTile) else cpu
                    return uploadGrey(padded, active, arena)
                }
            }
        }
        val cpu = greyProvider(index)
        val padded = if (padToTile != null) RawSrAlignment.circularPad(cpu, padToTile) else cpu
        return uploadGrey(padded, active, arena)
    }

    /**
     * Circular pad of a resident grey ([RawSrAlignment.circularPad]
     * semantics via `circular_pad.glsl`): adopts [grey] (released when
     * padding, returned as-is when [padToTile] is null or the dims are
     * already multiples). The caller releases the result.
     */
    private fun padGreyTexture(
        grey: VkImage, padToTile: Int?, active: VkSession, arena: VkArena
    ): VkImage {
        if (padToTile == null) return grey
        val padW = (padToTile - grey.width % padToTile) % padToTile
        val padH = (padToTile - grey.height % padToTile) % padToTile
        if (padW == 0 && padH == 0) return grey
        val padded = arena.texture(grey.width + padW, grey.height + padH, GLES30.GL_R32F)
        try {
            active.pass("rawsr/circular_pad.glsl") {
                sampler("u_source", grey)
                ivec2("u_size", padded.width, padded.height)
                image(0, padded, GLES30.GL_R32F)
                dispatch(padded.width, padded.height, 8, 8)
            }
        } catch (failure: Throwable) {
            arena.release(padded)
            throw failure
        }
        arena.release(grey)
        return padded
    }

    /**
     * GPU FFT grey ([RawSrAlignment.fftGrey] 1:1, float32): forward rows,
     * forward columns, fftshift + outer-quarter zeroing, ifftshift back,
     * inverse columns (1/h), inverse rows (1/w) — the same passes in the
     * same order, over ping-pong complex planes ([RawSrFftPlan] for the
     * stage math). The sensor→RGGB [flip] fuses into the first
     * stage's mosaic read. The returned resident R32F grey is adopted by
     * the pyramid; every other texture (mosaic, complex pairs, twiddle
     * rows) releases before return, including on failure, so the
     * fallback starts clean. Throws on any GPU failure. Internal for
     * the desktop GPU parity test (same-module callers only).
     */
    internal fun fftGreyGpu(
        mosaic: FloatArray, width: Int, height: Int,
        flip: RawSrCfaOrientation.Flip,
        active: VkSession, arena: VkArena
    ): VkImage {
        require(mosaic.size == width * height)
        // Every plane unwinds on failure (a half-allocated set cannot
        // leak: even texture creation itself may throw near OOM).
        val temps = ArrayList<VkImage>(5)
        try {
            val src = arena.texture(width, height, GLES30.GL_R32F).also(temps::add)
            src.uploadR32f(mosaic, active.uploads)
            val aRe = arena.texture(width, height, GLES30.GL_R32F).also(temps::add)
            val aIm = arena.texture(width, height, GLES30.GL_R32F).also(temps::add)
            val bRe = arena.texture(width, height, GLES30.GL_R32F).also(temps::add)
            val bIm = arena.texture(width, height, GLES30.GL_R32F).also(temps::add)
            val flipInt = when (flip) {
                RawSrCfaOrientation.Flip.IDENTITY -> 0
                RawSrCfaOrientation.Flip.HFLIP -> 1
                RawSrCfaOrientation.Flip.VFLIP -> 2
                RawSrCfaOrientation.Flip.ROT180 -> 3
            }
            // Every step reads one complex pair and writes an idle one
            // ([fftAxisInto]/[fftRemap] return the pair holding their
            // result); the mosaic feeds the first forward-rows step only
            // and is scratch afterwards.
            val a = ComplexLive(aRe, aIm)
            val b = ComplexLive(bRe, bIm)
            fun idleFor(live: ComplexLive): ComplexLive = if (live.re === aRe) b else a
            var cur = fftAxisInto(ComplexLive(src, src), a, width, height,
                axis = 0, inverse = false, firstFlip = flipInt, active, arena, spare = b)
            var dst = idleFor(cur)
            cur = fftAxisInto(cur, dst, width, height,
                axis = 1, inverse = false, firstFlip = null, active, arena)
            // fftshift + outer-quarter zeroing, then ifftshift back: the
            // CPU unshifts before the inverse FFT (mask in shifted
            // domain, transform in natural order), not after.
            dst = idleFor(cur)
            fftRemap(cur, dst, width, height,
                mode = 0, axis = 0, factors = emptyList(), scale = 1f, active)
            cur = dst
            dst = idleFor(cur)
            fftRemap(cur, dst, width, height,
                mode = 1, axis = 0, factors = emptyList(), scale = 1f, active)
            cur = dst
            // Inverse columns (1/h), inverse rows (1/w); the real plane
            // is the grey (the CPU reads it directly, no trailing shift).
            dst = idleFor(cur)
            cur = fftAxisInto(cur, dst, width, height,
                axis = 1, inverse = true, firstFlip = null, active, arena)
            dst = idleFor(cur)
            cur = fftAxisInto(cur, dst, width, height,
                axis = 0, inverse = true, firstFlip = null, active, arena)
            val grey = cur.re
            arena.release(src)
            arena.release(cur.im)
            val idle = idleFor(cur)
            arena.release(idle.re)
            arena.release(idle.im)
            temps.clear() // adopted grey excluded: only temps unwind below
            return grey
        } catch (failure: Throwable) {
            temps.forEach { runCatching { arena.release(it) } }
            throw failure
        }
    }

    /** One live complex pair (re/im planes) inside [fftGreyGpu]. */
    private data class ComplexLive(val re: VkImage, val im: VkImage)

    /**
     * 1D FFT along [axis] ([RawSrFftPlan.stages] chain + flatten-to-
     * natural permute, + 1/n scale when [inverse]): stage passes
     * ping-pong between [src] and [dst], then the permute writes the
     * idle pair.
     * Returns the pair holding the result (either [src] or [dst], by
     * stage-count parity — the caller re-homes by reference). A non-null
     * [firstFlip] fuses the mosaic load into the first stage (im unread,
     * flip on read); the mosaic pair is then never written (its re/im
     * alias one texture), so the remaining stages ping-pong [dst] with
     * the required [spare] pair instead. Null [firstFlip] needs no spare
     * ([src]/[dst] are distinct complex pairs).
     */
    private fun fftAxisInto(
        src: ComplexLive, dst: ComplexLive, width: Int, height: Int,
        axis: Int, inverse: Boolean, firstFlip: Int?,
        active: VkSession, arena: VkArena, spare: ComplexLive? = null
    ): ComplexLive {
        val n = if (axis == 0) width else height
        val chain = RawSrFftPlan.stages(n)
        if (firstFlip != null) {
            require(n > 1) { "Fused mosaic load needs a non-trivial axis" }
            val sparePair = requireNotNull(spare) { "Fused mosaic load needs a spare pair" }
            require(chain.isNotEmpty())
            fftStage(src, dst, width, height, axis, chain[0], inverse, firstFlip, active, arena)
            var cur = dst
            var nxt = sparePair
            for (index in 1..chain.lastIndex) {
                fftStage(cur, nxt, width, height, axis, chain[index], inverse,
                    firstFlip = null, active, arena)
                val tmp = cur; cur = nxt; nxt = tmp
            }
            // cur holds the last stage output, nxt is idle.
            fftRemap(cur, nxt, width, height, mode = 2, axis = axis,
                factors = chain.map { it.radix }, scale = 1f, active)
            return nxt
        }
        var cur = src
        var nxt = dst
        for ((index, stage) in chain.withIndex()) {
            fftStage(cur, nxt, width, height, axis, stage, inverse,
                firstFlip = null, active, arena)
            val tmp = cur; cur = nxt; nxt = tmp
        }
        // nxt is idle (or dst when the chain is empty, n == 1).
        fftRemap(cur, nxt, width, height, mode = 2, axis = axis,
            factors = chain.map { it.radix },
            scale = if (inverse) (1.0 / n).toFloat() else 1f, active)
        return nxt
    }

    /**
     * One [RawSrFftPlan.Stage] (`fft_stage.glsl`): [dst] =
     * stage([src]). Twiddle rows upload per stage and release with the
     * pass (<= 128KB each; caching across frames would pin textures for
     * no measurable win).
     */
    private fun fftStage(
        src: ComplexLive, dst: ComplexLive, width: Int, height: Int,
        axis: Int, stage: RawSrFftPlan.Stage, inverse: Boolean,
        firstFlip: Int?, active: VkSession, arena: VkArena
    ) {
        val twM = arena.texture(stage.m, 1, GLES30.GL_RGBA32F)
        twM.uploadRgba32f(RawSrFftPlan.twiddleRow(stage.m, inverse), active.uploads)
        val twP = arena.texture(stage.radix, 1, GLES30.GL_RGBA32F)
        twP.uploadRgba32f(RawSrFftPlan.twiddleRow(stage.radix, inverse), active.uploads)
        try {
            active.pass("rawsr/fft_stage.glsl") {
                sampler("u_re_in", src.re)
                sampler("u_im_in", src.im)
                sampler("u_tw_m", twM)
                sampler("u_tw_p", twP)
                ivec2("u_size", width, height)
                integer("u_axis", axis)
                integer("u_m", stage.m)
                integer("u_radix", stage.radix)
                integer("u_first", if (firstFlip != null) 1 else 0)
                integer("u_flip", firstFlip ?: 0)
                image(0, dst.re, GLES30.GL_R32F)
                image(1, dst.im, GLES30.GL_R32F)
                dispatch(width, height, 8, 8)
            }
        } finally {
            arena.release(twM)
            arena.release(twP)
        }
    }

    /**
     * One `fft_remap.glsl` pass ([dst] = remap([src])): mode 0 forward
     * fftshift + outer-quarter zeroing, 1 inverse fftshift, 2 natural-
     * order gather from the flattened stage order along [axis] over
     * [factors] with a [scale] multiply. The factor array always
     * uploads full (no stale tail).
     */
    private fun fftRemap(
        src: ComplexLive, dst: ComplexLive, width: Int, height: Int,
        mode: Int, axis: Int, factors: List<Int>, scale: Float,
        active: VkSession
    ) {
        active.pass("rawsr/fft_remap.glsl") {
            sampler("u_re_in", src.re)
            sampler("u_im_in", src.im)
            ivec2("u_size", width, height)
            integer("u_mode", mode)
            integer("u_axis", axis)
            integer("u_levels", factors.size)
            floats("u_factors[0]", FloatArray(RawSrFftPlan.MAX_LEVELS) { factors.getOrNull(it)?.toFloat() ?: 0f })
            float("u_scale", scale)
            image(0, dst.re, GLES30.GL_R32F)
            image(1, dst.im, GLES30.GL_R32F)
            dispatch(width, height, 8, 8)
        }
    }

    /**
     * Plain quad gray of the normalized CFA (bayer_quad_gray.glsl): the
     * analytic kernel-covariance input (no-GAT oracle contract). The
     * alignment pyramid base is GPU FFT grey now, so covariance no
     * longer reuses levels[0] — it keeps this dedicated dispatch. The
     * caller releases the texture. (Shader note: this pass is the
     * remaining consumer of bayer_quad_gray.glsl; it must survive the
     * align-path retirement of that shader.)
     */
    private fun quadGray(cfa: VkImage, active: VkSession, arena: VkArena): VkImage {
        val gray = arena.texture(cfa.width / 2, cfa.height / 2, GLES30.GL_R32F)
        active.pass("rawsr/bayer_quad_gray.glsl") {
            sampler("u_cfa", cfa); ivec2("u_size", gray.width, gray.height)
            image(0, gray, GLES30.GL_R32F); dispatch(gray.width, gray.height, 8, 8)
        }
        return gray
    }

    /**
     * 1:1 Gaussian pyramid over uploaded FFT grey (reference
     * `cuda_downsample`, gaussian): L0 is the uploaded grey itself,
     * then per level a separable valid convolution with the exact
     * [RawSrAlignment.gaussianKernel1d] taps and a strided take of
     * every `factor`-th pixel from 0. Levels are arena textures; the
     * caller releases all of them, including L0 (adopted here).
     *
     * Pass contract (`pyramid_downsample.glsl`, rewritten): axis=0
     * reads W×H and writes convW×H with out(ox,oy) =
     * Σ_k w[k]·src[ox+k, oy] (valid x-conv, no stride); axis=1 reads
     * convW×H and writes outW×outH with out(ox,oy) =
     * Σ_k w[k]·src[ox·factor, oy·factor+k] (valid y-conv fused with
     * the both-axes strided take). Pure valid convolution — no
     * reflection, no same-size output.
     */
    private fun pyramid(grey: VkImage, active: VkSession, arena: VkArena,
                        config: RawSrAlignmentConfig): List<VkImage> {
        val levels = ArrayList<VkImage>()
        levels += grey
        var source = grey
        while (levels.size < config.levels) {
            val factor = config.factorAt(levels.size)
            val kernel = RawSrAlignment.gaussianKernel1d(factor)
            val radius = kernel.size / 2
            val convW = source.width - 2 * radius
            val convH = source.height - 2 * radius
            if (convW < factor || convH < factor) break
            val weights = kernel.map { it.toFloat() }.toFloatArray()
            val horizontal = arena.texture(convW, source.height, GLES30.GL_R32F)
            active.pass("rawsr/pyramid_downsample.glsl") {
                sampler("u_source", source); ivec2("u_size", horizontal.width, horizontal.height)
                integer("u_factor", factor); integer("u_axis", 0); floats("u_weights[0]", weights)
                image(0, horizontal, GLES30.GL_R32F); dispatch(horizontal.width, horizontal.height, 8, 8)
            }
            val next = arena.texture(convW / factor, convH / factor, GLES30.GL_R32F)
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

    // Directional alignment only: the CPU alignPair has no reverse pass
    // and no fwd/bwd consistency check, so the GPU runs the same single
    // directional pass (retired per the rewrite plan Open Q1).
    // flow_consistency.glsl is deleted (nothing dispatched it); the 1:1
    // port has no consistency pass, like the reference.
    private fun align(refs: List<VkImage>, moving: List<VkImage>,
                      active: VkSession, arena: VkArena, config: RawSrAlignmentConfig): VkImage =
        alignDirectional(refs, moving, active, arena, config)

    /**
     * Coarse-to-fine 1:1 alignment (CPU [RawSrAlignment.alignPair]): per
     * level, upscale the coarser flow (`flow_upscale.glsl`, zero init at
     * the coarsest) -> block match (`block_match.glsl`, L1 at the finest
     * level, L2 above) -> ICA refine (`lk_refine.glsl`). Grids are floor
     * grids over the REFERENCE level ([RawSrAlignment.levelGrid]); the
     * moving level stays unpadded and the shaders transcribe the CPU
     * out-of-image semantics per stage. Flows are raw pixels on the raw
     * lattice at every level; the returned finest flow is RGBA32F
     * (dx, dy, meanAbsResidual, detOk).
     *
     * Pass contracts (all three shaders rewritten 1:1):
     * - flow_upscale.glsl (NEW): u_prior (coarser flow grid), u_src_grid,
     *   u_dst_grid, u_repeat (grid repeat), u_factor (flow scale),
     *   u_mode 0/1/2 = nearest/bilinear/bicubic. Per output texel:
     *   inside the upsampled extent, interpolate-then-scale; outside,
     *   zero (F.pad semantics). Transcribes [RawSrCoreAlign.upsampleFlow]
     *   (torch align_corners=False coords, edge-clamped taps, bicubic
     *   Keys a=-0.75).
     * - block_match.glsl: u_reference/u_moving (R32F levels), u_seed_flow
     *   (seeded flow, same grid), u_mov_size (moving level px dims for
     *   the zero-fill/clamp edge semantics), u_tile_grid,
     *   u_tile_size (per-level raw-px tile), u_search_radius, u_l1 (1 at
     *   the finest level). L1: half-away seed, zero-fill SAD,
     *   first-minimum sy-outer/sx-inner scan, integer result; L2:
     *   half-even seed, edge-clamped SSD, winner added to the fractional
     *   seed. Output (dx, dy, best/area, 1.0). Transcribes
     *   [RawSrCoreAlign.blockCostL1]/[RawSrCoreAlign.blockCostL2].
     * - lk_refine.glsl: u_reference/u_moving, u_flow (matched),
     *   u_ref_size/u_mov_size (level px dims for gradient borders and
     *   sampler edge semantics), u_tile_grid as above, u_tile_size,
     *   u_search_radius (step clip ±radius; tree-clip bound for ts=8),
     *   u_iterations. Computes unhalved central-difference gradients
     *   and per-tile Hessians from u_reference internally, then
     *   iterates H^-1 B with the transcribed quirks (ts=8 clamped
     *   sampler + tree-clipped sums, unclipped step; ts=16/32/64
     *   zero-fill + clipped step, 64 with the clip disabled at 32767).
     *   Output (dx, dy, meanAbsResidual, detOk). No residual gating
     *   here — reliability is a host-side auxiliary verdict
     *   ([flowFieldFromReadback]).
     */
    private fun alignDirectional(refs: List<VkImage>, moving: List<VkImage>,
                      active: VkSession, arena: VkArena, config: RawSrAlignmentConfig): VkImage {
        require(refs.isNotEmpty() && refs.size == moving.size) {
            "Reference and moving pyramids must match (${refs.size} vs ${moving.size} levels)"
        }
        var previous: VkImage? = null
        for (level in refs.indices.reversed()) {
            val ref = refs[level]
            val mov = moving[level]
            val tileSize = config.tileSizeAt(level)
            val columns = ref.width / tileSize
            val rows = ref.height / tileSize
            require(columns > 0 && rows > 0) {
                "Level ${ref.width}x${ref.height} smaller than one $tileSize-px tile"
            }
            val seeded = if (previous == null) {
                // Coarsest level: zero init (CPU alignPair seeded branch).
                arena.texture(columns, rows, GLES30.GL_RGBA32F).also {
                    it.uploadRgba32f(FloatArray(columns * rows * 4), active.uploads)
                }
            } else {
                val prior = previous!!
                // Repeat folds the L3 ts/2 schedule in (CPU upsampleFlow):
                // factor / (newTileSize / prevTileSize).
                val factor = config.factorAt(level + 1)
                val repeat = factor / (tileSize / config.tileSizeAt(level + 1))
                arena.texture(columns, rows, GLES30.GL_RGBA32F).also { up ->
                    active.pass("rawsr/flow_upscale.glsl") {
                        sampler("u_prior", prior)
                        ivec2("u_src_grid", prior.width, prior.height)
                        ivec2("u_dst_grid", columns, rows)
                        integer("u_repeat", repeat)
                        integer("u_factor", factor)
                        integer("u_mode", upscaleModeInt(config.flowUpscale))
                        image(0, up, GLES30.GL_RGBA32F); dispatch(columns, rows, 1, 1)
                    }
                }.also { arena.release(prior) }
            }
            val matched = arena.texture(columns, rows, GLES30.GL_RGBA32F)
            active.pass("rawsr/block_match.glsl") {
                sampler("u_reference", ref); sampler("u_moving", mov); sampler("u_seed_flow", seeded)
                ivec2("u_mov_size", mov.width, mov.height); ivec2("u_tile_grid", columns, rows)
                integer("u_tile_size", tileSize); integer("u_search_radius", config.radiusAt(level))
                integer("u_l1", if (level == 0) 1 else 0)
                image(0, matched, GLES30.GL_RGBA32F); dispatch(columns, rows, 1, 1)
            }
            arena.release(seeded)
            val refined = arena.texture(columns, rows, GLES30.GL_RGBA32F)
            active.pass("rawsr/lk_refine.glsl") {
                sampler("u_reference", ref); sampler("u_moving", mov); sampler("u_flow", matched)
                ivec2("u_ref_size", ref.width, ref.height); ivec2("u_mov_size", mov.width, mov.height)
                ivec2("u_tile_grid", columns, rows)
                integer("u_tile_size", tileSize)
                integer("u_search_radius", config.radiusAt(level))
                integer("u_iterations", config.lkIterations)
                image(0, refined, GLES30.GL_RGBA32F); dispatch(columns, rows, 1, 1)
            }
            arena.release(matched); previous = refined
        }
        return requireNotNull(previous)
    }

    /**
     * RGGB-processing-space flow texture back to sensor space (CPU
     * [RawSrCfaOrientation.remapFieldToSensor] twin): identity patterns
     * reuse the alignment output in place; others dispatch
     * flow_deflip and retire the processing-space texture. Merge,
     * robustness, judge, and onFlow all consume sensor space.
     */
    /**
     * Bilateral flow regularization (CPU
     * [RawSrAlignmentField.bilateralFiltered] twin). Runs only for a
     * positive sigma; otherwise returns [flow] untouched (no extra
     * pass, reference parity).
     */
    private fun regularizeFlow(flow: VkImage, sigmaPx: Double,
                               active: VkSession, arena: VkArena): VkImage {
        if (!(sigmaPx > 0.0)) return flow
        val out = arena.texture(flow.width, flow.height, GLES30.GL_RGBA32F)
        active.pass("rawsr/flow_regularize.glsl") {
            sampler("u_flow", flow)
            float("u_sigma", sigmaPx.toFloat())
            image(0, out, GLES30.GL_RGBA32F); dispatch(out.width, out.height, 8, 8)
        }
        arena.release(flow)
        return out
    }

    private fun remapFlowToSensor(flow: VkImage, flip: RawSrCfaOrientation.Flip,
                                 imageWidth: Int, imageHeight: Int, tileSize: Int,
                                 active: VkSession, arena: VkArena): VkImage {
        if (flip == RawSrCfaOrientation.Flip.IDENTITY) return flow
        val mode = when (flip) {
            RawSrCfaOrientation.Flip.HFLIP -> 1
            RawSrCfaOrientation.Flip.VFLIP -> 2
            else -> 3
        }
        val out = arena.texture(flow.width, flow.height, GLES30.GL_RGBA32F)
        active.pass("rawsr/flow_deflip.glsl") {
            sampler("u_flow", flow)
            integer("u_mode", mode)
            integer("u_img_w", imageWidth)
            integer("u_img_h", imageHeight)
            integer("u_tile", tileSize)
            image(0, out, GLES30.GL_RGBA32F); dispatch(out.width, out.height, 8, 8)
        }
        arena.release(flow)
        return out
    }

    /** flow_upscale.glsl u_mode encoding: nearest/bilinear/bicubic. */
    private fun upscaleModeInt(mode: RawSrAlignmentConfig.FlowUpscaleMode): Int = when (mode) {
        RawSrAlignmentConfig.FlowUpscaleMode.NEAREST -> 0
        RawSrAlignmentConfig.FlowUpscaleMode.BILINEAR -> 1
        RawSrAlignmentConfig.FlowUpscaleMode.BICUBIC -> 2
    }

    private fun kernelCovariance(gray: VkImage, tuning: RawSrTuning,
                               active: VkSession, arena: VkArena): VkImage {
        val packed = arena.texture(gray.width, gray.height, GLES30.GL_RGBA32F)
        active.pass("rawsr/kernel_covariance.glsl") {
            sampler("u_gray", gray); ivec2("u_size", gray.width, gray.height)
            float("u_k_detail", tuning.kDetail.toFloat()); float("u_k_denoise", tuning.kDenoise.toFloat())
            float("u_k_flat", tuning.flatSigma?.toFloat() ?: 0f)
            integer("u_use_flat", if (tuning.flatSigma != null) 1 else 0)
            float("u_detail_floor", tuning.detailFloor?.toFloat() ?: 0f)
            float("u_d_th", tuning.dTh.toFloat()); float("u_d_tr", tuning.dTr.toFloat())
            float("u_k_stretch", tuning.kStretch.toFloat()); float("u_k_shrink", tuning.kShrink.toFloat())
            // Reference law: steerable + linear, matching the
            // RawSrKernelCovariance defaults (the KernelNet A/B bypasses this
            // pass when enabled).
            integer("u_kernel_type", 0); integer("u_selection_law", 0)
            image(0, packed, GLES30.GL_RGBA32F); dispatch(gray.width, gray.height, 8, 8)
        }
        return packed
    }

    private fun uploadCovariance(field: RawSrKernelCovariance.MatrixField,
                                 active: VkSession, arena: VkArena): VkImage {
        require(field.values.size == field.width * field.height * 4)
        return arena.texture(field.width, field.height, GLES30.GL_RGBA32F).also {
            it.uploadRgba32f(field.values, active.uploads)
        }
    }

    /**
     * Lazily computed KernelNet covariance (A/B) as a live GL texture, or
     * null when unavailable — then the caller runs the analytic pass. Never
     * throws: compute and upload are both guarded so a pressured heap
     * degrades to analytic instead of killing the save. The returned texture
     * follows the usual arena discipline; the heap field is droppable on
     * return.
     */
    private fun kernelNetTexture(
        kernelNet: ((index: Int) -> RawSrKernelCovariance.MatrixField?)?,
        index: Int,
        active: VkSession,
        arena: VkArena
    ): VkImage? {
        if (kernelNet == null) return null
        return try {
            kernelNet.invoke(index)?.let { uploadCovariance(it, active, arena) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun <T> execute(count: Int, width: Int, height: Int, pattern: BayerPattern,
        config: RawSrAlignmentConfig, tuning: RawSrTuning, guideProvider: ((index: Int) -> RawSrGrayImage?)?,
        greyProvider: (index: Int) -> RawSrGrayImage,
        mosaicProvider: ((index: Int) -> FftMosaic)?,
        robust: List<RawSrRobustness.GpuParams>,
        referenceOnly: Boolean,
        onFlow: (Int, Int, Int, Int) -> Unit,
        onCovariance: (Int, Int, Int, Int) -> Unit,
        onRobustness: (Int, Int, Int, Int, Int) -> Unit,
        kernelNet: ((index: Int) -> RawSrKernelCovariance.MatrixField?)? = null,
        chromaFrames: List<RawSrBayerMerge.ChromaParams?>? = null,
        releaseAccumulators: Boolean = false,
        scale: RawSrLinearScale = RawSrLinearScale.X1,
        onGrey: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
        rejectionPolicy: RawSrFrameRejection.Policy = RawSrFrameRejection.Policy(),
        load: (Int, VkSession, VkArena) -> LoadedFrame,
        consume: (RawSrGpuOutput) -> T,
        ubGuideProvider: ((index: Int) -> RawSrGrayImage?)? = null
    ): T {
        require(count in 1..30 && width % 2 == 0 && height % 2 == 0)
        require(robust.size == count) { "One robustness parameter set per burst frame is required" }
        // Inter-level upscale runs on the GPU through `flow_upscale.glsl`
        // for every FlowUpscaleMode (u_mode 0/1/2 =
        // nearest/bilinear/bicubic), so the BILINEAR reference default
        // runs end to end here and explicit NEAREST configs take the
        // same passes with u_mode=0. (Retired: the legacy nearest-only
        // require and the tile<=32 shader-limit require —
        // RawSrAlignmentConfig validates its own ranges, and the
        // rewritten passes take tile/radius/iterations as uniforms.)
        val active = ensureSession()
        check(!processing) { "RAW-SR callbacks must not re-enter processing" }
        processing = true
        try {
          VkArena(active.vk).use { arena ->
            val quadsW = width / 2
            val quadsH = height / 2
            // Output grid via the shared SR planner (pixel-identical to
            // the mosaic SR grid). Alignment/covariance/robustness stay
            // source-sized; only the merge-stage textures and dispatches
            // below scale.
            val (outW, outH) = planSrOutputDims(width, height, scale.factor)
            // Only the moving accumulators and OOB diagnostics live for the
            // whole burst. Reference accumulators and final outputs are allocated later.
            val numerator = arena.texture(outW, outH, GLES30.GL_RGBA32F)
            val denominator = arena.texture(outW, outH, GLES30.GL_RGBA32F)
            val oob = arena.texture(outW, outH, GLES30.GL_R32F)
            active.pass("rawsr/clear_accumulators.glsl") {
                ivec2("u_size", outW, outH)
                image(0, numerator, GLES30.GL_RGBA32F); image(1, denominator, GLES30.GL_RGBA32F)
                image(5, oob, GLES30.GL_R32F)
                dispatch(outW, outH, 8, 8)
            }
            // Placeholder binding for shader inputs the reference/unused paths skip.
            val dummy = arena.texture(1, 1, GLES30.GL_RGBA32F)
            dummy.uploadRgba32f(floatArrayOf(0f, 0f, 0f, 1f), active.uploads)
            // Rc ping-pong: one moving frame accumulates at a time.
            var rc = arena.texture(quadsW, quadsH, GLES30.GL_R32F)
            var rcNext = arena.texture(quadsW, quadsH, GLES30.GL_R32F)
            rc.uploadR32f(FloatArray(quadsW * quadsH), active.uploads)
            // The reference uploads and normalizes exactly once and stays resident
            // with the persistent accumulators for the whole burst. Jamy-L
            // parity: no hot-pixel stage (the stuck-low gate misfires on
            // thin scene lines); the pyramid, guide, and merge read the
            // plain unpacked plane.
            val refLoaded = load(0, active, arena)
            val refCfa = refLoaded.cfa
            // GPU FFT grey (Open Q3 option (b)) with CPU fallback
            // ([greyTexture]): the reference pads to a tile multiple
            // (circular_pad on the GPU path, host pad on fallback); the
            // pyramid adopts the texture as L0.
            val refs = pyramid(
                greyTexture(0, config.tileSize, greyProvider, mosaicProvider, active, arena),
                active, arena, config)
            onGrey(0, refs[0].id, refs[0].width, refs[0].height)
            // Kernel covariance consumes plain quad gray (no-GAT oracle
            // contract), NOT the FFT-grey pyramid above: a null guide
            // provider selects a dedicated quadGray dispatch here and for
            // moving frames. KernelNet does not consume the analytic
            // guide. Build it only on fallback, avoiding an otherwise
            // unused full quad plane and CPU pass per frame.
            val refCovariance = kernelNetTexture(kernelNet, 0, active, arena) ?: run {
                val guide = if (refLoaded.codes != null)
                    guideProvider?.invoke(0)?.let { uploadGuide(it, active, arena) } else null
                if (guide != null) {
                    kernelCovariance(guide, tuning, active, arena).also { arena.release(guide) }
                } else {
                    val plain = quadGray(refCfa, active, arena)
                    kernelCovariance(plain, tuning, active, arena).also { arena.release(plain) }
                }
            }
            onCovariance(0, refCovariance.id, refCovariance.width, refCovariance.height)
            val fuseMoving = !referenceOnly && count > 1
            // The reference holds its linear guide for the whole burst like the pyramid.
            val refLinear = if (fuseMoving && refLoaded.codes != null)
                linearFromCodes(refLoaded.codes, robust[0], pattern, active, arena) else null
            // Reference-derived LUT texture, live for the whole burst like refLinear.
            val lutTexture = if (fuseMoving && refLinear != null)
                uploadNoiseLut(robust[0].noiseLut, active, arena) else null
            refLoaded.codes?.let(arena::release)
            var lastFlow: VkImage? = null
            var kept = 0
            // Alignment runs in RGGB processing space (see greyProvider);
            // flow textures map back to sensor space at the boundary.
            val cfaFlip = RawSrCfaOrientation.forPattern(pattern)
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
                    // GPU FFT grey first (Open Q3 option (b)) with CPU
                    // fallback ([greyTexture]): a frame whose grey fails
                    // entirely skips with nothing allocated, like a
                    // CPU-chain rejection. Cancellation still propagates.
                    val greyTex = try {
                        greyTexture(index, null, greyProvider, mosaicProvider, active, arena)
                    } catch (cancelled: java.util.concurrent.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        android.util.Log.w("RawLensRawSr",
                            "gpu frame $index skipped: FFT grey failed (${failure.message})")
                        continue
                    }
                    val tGrey = android.os.SystemClock.elapsedRealtime()
                    val loaded = load(index, active, arena)
                    // Jamy-L parity: no hot-pixel stage (see the reference
                    // CFA above); the raw plane feeds the merge directly.
                    val cfa = loaded.cfa
                    val tLoad = android.os.SystemClock.elapsedRealtime()
                    val codes = loaded.codes
                    // Moving grey stays UNPADDED (CPU: pyramid(gray));
                    // only the reference pads.
                    val levels = pyramid(greyTex, active, arena, config)
                    onGrey(index, levels[0].id, levels[0].width, levels[0].height)
                    val tPyr = android.os.SystemClock.elapsedRealtime()
                    val flow = regularizeFlow(remapFlowToSensor(
                        align(refs, levels, active, arena, config), cfaFlip,
                        levels[0].width, levels[0].height, config.tileSize, active, arena),
                        tuning.flowRegularizeSigma ?: 0.0, active, arena)
                    val tAlign = android.os.SystemClock.elapsedRealtime()
                    val covariance = kernelNetTexture(kernelNet, index, active, arena) ?: run {
                        val guide = if (codes != null)
                            guideProvider?.invoke(index)?.let { uploadGuide(it, active, arena) } else null
                        if (guide != null) {
                            kernelCovariance(guide, tuning, active, arena).also { arena.release(guide) }
                        } else {
                            val plain = quadGray(cfa, active, arena)
                            kernelCovariance(plain, tuning, active, arena).also { arena.release(plain) }
                        }
                    }
                    onCovariance(index, covariance.id, covariance.width, covariance.height)
                    val tCov = android.os.SystemClock.elapsedRealtime()
                    var rTexture: VkImage? = null
                    val useR = codes != null && refLinear != null
                    // The support gate runs on readback flow/robustness (a host
                    // decision, like the CPU chain): a rejected frame skips
                    // both the Rc and the merge accumulate. The CPU-oracle
                    // adapter carries no codes, so it never rejects.
                    var keep = true
                    if (useR) {
                        val linear = linearFromCodes(codes!!, robust[index], pattern, active, arena)
                        val (rawR, flags) = robustnessPass(refLinear!!, linear, flow, tuning, config,
                            robust[0].noiseLut, lutTexture!!, active, arena)
                        val r = robustnessMin(rawR, active, arena)
                        arena.release(rawR)
                        onRobustness(index, r.id, flags.id, r.width, r.height)
                        val verdict = judgeMovingFrame(
                            flow, r, flags, width, height, config, rejectionPolicy)
                        keep = verdict.keep
                        if (keep) {
                            val ubGray = ubGuideProvider?.invoke(index)
                                ?.let { uploadGuide(it, active, arena) }
                            val folded = if (ubGray != null) {
                                (unblockerFold(r, flags, ubGray, robust[index],
                                    flow, tuning, config, active, arena) ?: r)
                                    .also { arena.release(ubGray) }
                            } else r
                            accumulateRc(rc, folded, rcNext, active)
                            val old = rc; rc = rcNext; rcNext = old
                            rTexture = folded
                        } else {
                            android.util.Log.w("RawLensRawSr", "gpu frame $index rejected: " +
                                "${verdict.rejectReason} " +
                                "rel=${"%.3f".format(verdict.reliableFraction)} " +
                                "meanR=${"%.3f".format(verdict.meanRobustness)} " +
                                "support=${"%.3f".format(verdict.supportFraction)} " +
                                "span=${"%.2f".format(verdict.medianFlowSpanPx)}px")
                            rTexture = r
                        }
                        arena.release(flags); arena.release(linear)
                    }
                    val tRobust = android.os.SystemClock.elapsedRealtime()
                    // Validated alignment/covariance/robustness feed the merge unchanged:
                    // warped moving means went into r above, never raw texels. Each valid
                    // observation lands in its actual CFA channel weighted by kernel
                    // and the reused robustness output.
                    if (keep) {
                        mergeAccumulate(cfa, flow, covariance, rTexture ?: dummy, useR,
                            isReference = false, numerator, denominator,
                            oob, pattern, config, active, scale,
                            chroma = chromaFrames?.getOrNull(index))
                        kept++
                    }
                    val tMerge = android.os.SystemClock.elapsedRealtime()
                    if (android.util.Log.isLoggable("RawLensRawSr", android.util.Log.DEBUG)) {
                        android.util.Log.d("RawLensRawSr", "gpu frame $index ${width}x$height" +
                            " grey=${tGrey - t0}ms load=${tLoad - tGrey}ms pyramid=${tPyr - tLoad}ms" +
                            " align=${tAlign - tPyr}ms cov=${tCov - tAlign}ms robust=${tRobust - tCov}ms" +
                            " merge=${tMerge - tRobust}ms")
                    }
                    arena.release(covariance)
                    rTexture?.let(arena::release)
                    codes?.let(arena::release)
                    lastFlow = flow
                    onFlow(index, flow.id, flow.width, flow.height)
                    levels.forEach(arena::release)
                    arena.release(cfa)

                }
            }
            // Unused by moving frames: defer these planes until their workspace retires.
            val refNumerator = arena.texture(outW, outH, GLES30.GL_RGBA32F)
            val refDenominator = arena.texture(outW, outH, GLES30.GL_RGBA32F)
            active.pass("rawsr/clear_reference.glsl") {
                ivec2("u_size", outW, outH)
                image(0, refNumerator, GLES30.GL_RGBA32F)
                image(1, refDenominator, GLES30.GL_RGBA32F)
                dispatch(outW, outH, 8, 8)
            }
            // The captured reference accumulates last with r_ref = 1 through the same
            // pass, filling the reference-only A/B buffers.
            mergeAccumulate(refCfa, dummy, refCovariance, dummy, useR = false,
                isReference = true, refNumerator, refDenominator,
                oob, pattern, config, active, scale)
            arena.release(refCfa)
            refs.forEach(arena::release)
            refLinear?.let(arena::release)
            lutTexture?.let(arena::release)
            arena.release(refCovariance)
            arena.release(dummy)
            arena.release(rcNext)
            // Reference-last add and per-channel normalization; zero support
            // divides to 0. Finalize writes every pixel, so outputs need no
            // initial clear.
            val merged = arena.texture(outW, outH, GLES30.GL_RGBA32F)
            val fallback = arena.texture(outW, outH, GLES30.GL_R32F)
            active.pass("rawsr/merge_finalize.glsl") {
                sampler("u_num", numerator); sampler("u_den", denominator)
                sampler("u_ref_num", refNumerator); sampler("u_ref_den", refDenominator)
                ivec2("u_size", outW, outH)
                image(0, merged, GLES30.GL_RGBA32F); image(1, fallback, GLES30.GL_R32F)
                dispatch(outW, outH, 8, 8)
            }
            // Production trim, first half: finalize is the last reader of
            // the numerators (inpaint reads den/ref_den only), so drop them
            // before the inpainted plane allocates. Under the trim the
            // consume() IDs already report 0, so nothing downstream
            // observes the earlier release; harnesses keep the default.
            if (releaseAccumulators) {
                arena.release(numerator)
                arena.release(refNumerator)
            }
            // Dead-lane inpaint for the Linear-RGB product (RawSrDeadLaneInpaint
            // twin): the reference re-mosaics to Bayer and lets the downstream
            // demosaic absorb isolated dead lanes; linear RGB has no demosaic,
            // so unsupported lanes heal from live same-lane neighbours here.
            // The fallback mask still flags the pre-inpaint dead cells.
            val inpainted = arena.texture(outW, outH, GLES30.GL_RGBA32F)
            active.pass("rawsr/inpaint_dead_lanes.glsl") {
                sampler("u_merged", merged); sampler("u_den", denominator)
                sampler("u_ref_den", refDenominator)
                ivec2("u_size", outW, outH)
                image(0, inpainted, GLES30.GL_RGBA32F)
                dispatch(outW, outH, 8, 8)
            }
            arena.release(merged)
            // Production trim, second half: inpaint is the last reader of
            // the denominators (the numerators already dropped above), so
            // releasing them before the chroma pass and consume() drops the
            // remaining dead textures from the develop + save tail. Default
            // keeps every buffer live for parity and quality harnesses.
            if (releaseAccumulators) {
                arena.release(denominator)
                arena.release(refDenominator)
            }
            // Chroma-from-luma stabilization (RawSrChromaFromLuma twin):
            // razor kernels latch R/B onto single taps out of phase along
            // slanted edges (rainbow staircases); rebuilding chroma from
            // smoothed ratios under the untouched guide transfers the clean
            // luma edge profile onto R/B, the demosaic analogue the
            // reference relies on downstream of its re-mosaiced output.
            val stabilized = arena.texture(outW, outH, GLES30.GL_RGBA32F)
            active.pass("rawsr/chroma_from_luma.glsl") {
                sampler("u_merged", inpainted)
                ivec2("u_size", outW, outH)
                image(0, stabilized, GLES30.GL_RGBA32F)
                dispatch(outW, outH, 8, 8)
            }
            arena.release(inpainted)
            // Accepted frames are the reference plus the moving frames that
            // survived the support gate (reference-only keeps just the
            // reference). The caller refuses to save below 2, matching the
            // CPU chain's throw when no moving frame survives.
            val accepted = if (referenceOnly) 1 else 1 + kept
            val numId = if (releaseAccumulators) 0 else numerator.id
            val denId = if (releaseAccumulators) 0 else denominator.id
            val refNumId = if (releaseAccumulators) 0 else refNumerator.id
            val refDenId = if (releaseAccumulators) 0 else refDenominator.id
            return consume(RawSrGpuOutput(numId, denId, outW, outH, accepted,
                listOfNotNull(lastFlow?.id), rc.id, refNumId, refDenId,
                stabilized.id, fallback.id, oob.id, arena.memory.peakBytes))
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

}
