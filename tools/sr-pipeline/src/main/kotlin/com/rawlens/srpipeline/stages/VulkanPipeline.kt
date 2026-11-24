package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.SrpBridge
import java.nio.ByteBuffer

/**
 * Vulkan-backend burst pipeline: the same Alg.1 as [EndToEnd.run], with
 * every proven stage dispatched on-device and Kotlin doing only packing,
 * buffering, and orchestration (the [AlignGpu] precedent).
 *
 * Per frame the sequence is: upload-once frame resident -> grey FFT
 * ([SrpBridge.nativeGreyFftRes]) -> [AlignGpu] loop ->
 * [SrpBridge.nativeSmapRes] + [SrpBridge.nativeRobustComputeFrameRes] ->
 * GAT/decimate/kerngrad/[SrpBridge.nativeKernelsCovRes] over resident
 * chain scratch -> [SrpBridge.nativeMergeSplatFrameRes] into the
 * resident num/den pair -> [SrpBridge.divide]. Reference stats
 * ([SrpBridge.robustInit]) and kernels are built once up front. Same
 * shaders, same bytes as the staged path — see the `frame chain
 * residents match staged entries bit-exact` proof. The non-bayer
 * configuration keeps the staged entries verbatim.
 * Covariance border semantics are the port's (OOB-skipped reads on the
 * grey grid), which can differ from [EndToEnd.frameKernels]' partial
 * windows on the outermost rim; the end-to-end gate is interior-quantile.
 *
 * @param reference reference raw frame (`J_1`) as a decode thunk,
 *   invoked once first (same bytes and order as passing the frame).
 *   The thunk lets memory-tight callers release their frame right
 *   after this call: the decoded array dies with the ref phase below,
 *   so only one comparison frame is ever live during the loop.
 * @param comparisons remaining burst frames (`J_2..J_N`) as decode
 *   thunks, consumed once in order: each frame is decoded when its turn
 *   comes and released after merging (same bytes and order as an
 *   upfront list). Each thunk receives a reusable `w*h`-float staging
 *   buffer owned by the pipeline: decoding into it (fully rewritten)
 *   avoids one 50 MB staging allocation per frame; thunks that decode
 *   elsewhere may ignore it.
 * @param config pipeline config (tile sizes, kernel/robustness params,
 * curves; cf. [EndToEnd.run]).
 * @param handle native context from `SrpBridge.nativeCreate`.
 * @param alignSpirvs align-loop modules; [spirvs] the remaining stage
 * modules.
 * @return the downloaded `num` buffer after the in-place on-device
 * `divide`: `outH*outW*3` floats in pipeline row order (bottom-up),
 * NaN where no sample contributed. Callers apply the tail NaN-to-black
 * rule and the row flip (same bytes as the old row-array return, with
 * no 150 MB row copy on top of the download).
 */
object VulkanPipeline {
    /** noise_even.comp range kernel width, in merged-noise sigmas. */
    const val NOISE_EVEN_RANGE = 2f

    /**
     * Ghost-mask visibility scale K: a frame's rejection seeds mask growth with
     * weight d / (d + K (a L + b) / N), d = its squared local-mean difference to
     * the reference. Calibrated on WronskiVsKernelNetBenchTest motion scenes:
     * K 1 / 10 / 100 -> clean trail 57.8 / 57.7 / 57.0 dB (53.5 ungrown), iso100
     * 43.6 / 43.7 / 43.9 and dim 35.3 / 35.5 / 36.0 (44.0 / 36.0 ungrown):
     * at 100 noisy bursts keep their noise while clean ones lose the ghost.
     */
    const val GHOST_VISIBILITY = 100f


    /** Stage modules beyond the align loop's own [AlignGpu.Spirvs]. */
    data class Spirvs(
        val gat: ByteArray,
        val grad: ByteArray,
        val kernels: ByteArray,
        val divide: ByteArray,
        val robust: ByteArray,
        val mergeSplat: ByteArray,
        val mergePolynomial: ByteArray? = null,
        /** fft.comp for on-device FFT lowpass grey (cf. [SrpBridge.greyFft]). */
        val fft: ByteArray,
        /** corr.comp for the resident grey interleave (cf. [SrpBridge.nativeGreyFftRes]). */
        val corr: ByteArray,
        /**
         * grey_fft.comp: mixed-radix FFT grey (true lengths, no Bluestein
         * padding or host staging). Null keeps the Bluestein chain.
         */
        val greyFft: ByteArray? = null,
        /** grey_fir.comp: required when [PipelineConfig.greyMethod] is FIR7/FIR11/FIR15. */
        val greyFir: ByteArray? = null,
        /**
         * pyr_down.comp: with a FIR grey, keep the grey and the moving pyramid
         * GPU-resident (no 50 MB download, CPU pyramid or per-call re-upload).
         * Null keeps the host pyramid.
         */
        val pyrDown: ByteArray? = null,
        /** noise_acc.comp + noise_even.comp: required for [PipelineConfig.noiseEvening]. */
        val noiseAcc: ByteArray? = null,
        val noiseEven: ByteArray? = null,
        /** ca_warp.comp: required for [PipelineConfig.lateralCa]. */
        val caWarp: ByteArray? = null,
        /** Two-band reconstruction (PipelineConfig.twoBand). */
        val mergeTwoBand: ByteArray? = null,
        val twoBandResolve: ByteArray? = null,
        val twoBandDct: ByteArray? = null,
        /** twoband_tensor.comp: edge steering field (TwoBandReconstruction.Params.steer). */
        val twoBandTensor: ByteArray? = null,
        /** rob_feather.comp: feathered robustness (TwoBandReconstruction.Params.featherRadius). */
        val robFeather: ByteArray? = null,
    )


    private fun pack(data: FloatArray): ByteBuffer {
        val b = SrpBridge.directFloats(data.size)
        b.asFloatBuffer().put(data)
        b.rewind()
        return b
    }

    /**
     * Copies row-major `img` into reusable direct buffer `buf` (same
     * bytes as [packImage], no allocation). Run-level scratch: every
     * frame has the same shape, and entries fence-wait, so sequential
     * reuse is aliasing-safe.
     */
    private fun packImageInto(img: Array<FloatArray>, buf: ByteBuffer): ByteBuffer {
        buf.rewind()
        val fb = buf.asFloatBuffer()
        for (y in img.indices) fb.put(img[y])
        buf.rewind()
        return buf
    }

    /** Copies `data` into reusable direct buffer `buf` (same bytes as [pack]). */
    private fun packInto(data: FloatArray, buf: ByteBuffer): ByteBuffer {
        buf.rewind()
        buf.asFloatBuffer().put(data)
        buf.rewind()
        return buf
    }

    private fun checkRc(what: String, rc: Int) {
        require(rc == 0) { "VulkanPipeline: $what failed with status $rc" }
    }

    /**
     * Tail NaN rule on flat floats, cf. `process()`/`finish()`:
     * degenerate 0/0 pixels (no contributing sample) are NaN out of
     * `main()` and become black — uint16 DNG cannot store NaN (the
     * writer would map it to 0 per channel, leaking the surviving
     * channels), so zero the whole pixel. Per-pixel map: same values in
     * any row order.
     */
    fun nanToBlack(fb: java.nio.FloatBuffer, total: Int) {
        for (i in 0 until total) {
            if (fb.get(i).isNaN()) {
                val px = (i / 3) * 3
                fb.put(px, 0f)
                fb.put(px + 1, 0f)
                fb.put(px + 2, 0f)
            }
        }
    }

    /**
     * Vertical row flip in place (single-row temp): pipeline rows run
     * sensor-bottom-up while the DNG stores native top-down. A
     * permutation: same bytes as copying flipped.
     */
    fun flipRowsInPlace(fb: java.nio.FloatBuffer, outW: Int, outH: Int) {
        val row = FloatArray(outW * 3)
        val tmp = FloatArray(outW * 3)
        for (y in 0 until outH / 2) {
            val a = y * outW * 3
            val b = (outH - 1 - y) * outW * 3
            fb.position(a)
            fb.get(row)
            fb.position(b)
            fb.get(tmp)
            fb.position(b)
            fb.put(row)
            fb.position(a)
            fb.put(tmp)
        }
        fb.rewind()
    }

    /** Stage clock, enabled with `-Dsrpipeline.profile=true`. */
    private val profile = System.getProperty("srpipeline.profile", "false") == "true"
    private val greyCompare = System.getProperty("srpipeline.greyCompare", "false") == "true"

    /**
     * Device heap diagnosis: JVM live-set in MB. Printed at phase
     * boundaries when profiling (the 512 MB on-device ceiling makes
     * the live curve load-bearing; desktop pool peaks overcount).
     * `println` keeps it working on JVM and Android alike.
     */
    private fun heapMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576L
    }

    private fun heapLog(what: String) {
        if (!profile) return
        println("sr-pipeline heap: ${heapMb()} MB @ $what")
    }
    private val clock = java.util.LinkedHashMap<String, Long>()
    private val clockN = java.util.LinkedHashMap<String, Long>()

    private inline fun <T> timed(what: String, block: () -> T): T {
        if (!profile) return block()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            val dt = System.nanoTime() - t0
            clock[what] = (clock[what] ?: 0L) + dt
            clockN[what] = (clockN[what] ?: 0L) + 1
        }
    }

    private fun dumpClock() {
        if (!profile) return
        println("sr-pipeline profile (ms, calls):")
        for ((k, v) in clock.entries.sortedByDescending { it.value }) {
            println("  ${"%.1f".format(v / 1e6)} x${clockN[k]} $k")
        }
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0
        println("  jvm heap used: ${"%.0f".format(usedMb)} MB")
    }

    /**
     * [output], when given, receives the merged image (capacity >= the
     * output float count) and is returned; callers on Android pass a
     * [SrpBridge.nativeFloats] buffer they free themselves. Without it the
     * result is a Java-heap direct buffer, as before. The large per-run
     * scratch (grey, staging, kernel input) lives in native memory freed
     * when the run ends: ART counts allocateDirect against the Java heap,
     * and back-to-back merges fragmented it until the output no longer fit.
     */
    fun run(
        reference: () -> Array<FloatArray>,
        comparisons: List<(ByteBuffer) -> Array<FloatArray>>,
        config: PipelineConfig = PipelineConfig(),
        handle: Long,
        alignSpirvs: AlignGpu.Spirvs,
        spirvs: Spirvs,
        output: ByteBuffer? = null,
        /** Optional diagnostic/cache tap: borrowed colour-difference samples and reference-grid weights. */
        frameObserver: ((ByteBuffer, Array<Array<FloatArray>>, ByteBuffer) -> Unit)? = null,
        /** Diagnostic replacement for each comparison frame's regularized flow, before robustness. */
        flowTransform: ((Int, Array<Array<FloatArray>>) -> Array<Array<FloatArray>>)? = null,
        greenFit: GreenNormalTile? = null,
    ): ByteBuffer = SrpBridge.NativeFloats().use { scratch ->
        runScoped(reference, comparisons, config, handle, alignSpirvs, spirvs, output, scratch, frameObserver, flowTransform, greenFit)
    }

    private fun runScoped(
        reference: () -> Array<FloatArray>,
        comparisons: List<(ByteBuffer) -> Array<FloatArray>>,
        config: PipelineConfig,
        handle: Long,
        alignSpirvs: AlignGpu.Spirvs,
        spirvs: Spirvs,
        output: ByteBuffer?,
        scratch: SrpBridge.NativeFloats,
        frameObserver: ((ByteBuffer, Array<Array<FloatArray>>, ByteBuffer) -> Unit)?,
        flowTransform: ((Int, Array<Array<FloatArray>>) -> Array<Array<FloatArray>>)?,
        greenFit: GreenNormalTile?,
    ): ByteBuffer {
        require(handle != 0L) { "VulkanPipeline: null context handle" }
        require(!config.organicReconstruction) { "pooled polynomial reconstruction is currently a CPU prototype" }
        require(!config.mobilePolynomial || (config.bayer && config.colourDiff != null && spirvs.mergePolynomial != null)) {
            "mobile polynomial requires Bayer colour differences, Wronski and its dedicated shader"
        }
        val greenFrameWeights = LumaFrameWeights.forBurst(comparisons.size+1, config.grainRetention)
        var mergeFrameIndex = 0
        // Decode the reference first: its shape sizes all run scratch.
        // The box is nulled once the ref phase below is done, so the
        // 50 MB frame dies before the comparison loop on callers that
        // release their own copy in the thunk.
        var refBox: Array<FloatArray>? = reference()
        heapLog("ref-decoded")
        val h = refBox!!.size
        val w = refBox!![0].size
        // Mobile grey (FIR7/11/15): half-band taps shared with Grey.firLowpass.
        val firRadius = when (config.greyMethod) { "FIR", "FIR7" -> 7; "FIR11" -> 11; "FIR15" -> 15; else -> 0 }
        val firTaps = if (firRadius == 0) FloatArray(0) else Grey.halfBandTaps(firRadius).let { t ->
            FloatArray((firRadius + 1) / 2 + 1) { i -> if (i == 0) t[0] else t[2 * i - 1] }
        }
        require(firRadius == 0 || spirvs.greyFir != null) { "greyMethod ${config.greyMethod} needs grey_fir.spv" }
        val tile = config.tileSizes.first()
        val loop = EndToEnd.loopConfig(config)
        val outH = (h * config.scale).toInt()
        val outW = (w * config.scale).toInt()

        /**
         * Grey of `frame` into `rows` (same bytes as the staged path).
         * `packBuf` is the upload staging (caller-owned: a temp for the
         * one-shot ref grey, the shared `frameBuf` for the loop).
         */
        fun greyOfInto(
            frame: Array<FloatArray>,
            packBuf: ByteBuffer,
            out: ByteBuffer,
            rows: Array<FloatArray>,
        ): Array<FloatArray> {
            if (config.bayer && firRadius != 0) {
                val tmp = SrpBridge.createResident(handle, (w * h).toLong())
                require(tmp != 0L) { "VulkanPipeline: grey resident alloc failed" }
                try {
                    checkRc("greyUpload", SrpBridge.uploadResident(handle, tmp, packImageInto(frame, packBuf), (w * h).toLong()))
                    timed("greyFir") { checkRc("greyFir", SrpBridge.nativeGreyFirRes(handle, tmp, out.apply { rewind() },
                        spirvs.greyFir!!, w, h, firTaps, firRadius)) }
                } finally {
                    SrpBridge.freeResident(handle, tmp)
                }
                out.rewind()
                val fb = out.asFloatBuffer()
                for (y in 0 until h) fb.get(rows[y])
                return rows
            }
            if (!config.bayer || config.greyMethod != "FFT") {
                return if (config.bayer) Grey.computeGreyImages(frame, config.greyMethod) else frame
            }
            // Mixed-radix grey when available (the reference used to stay on
            // the Bluestein chain: ~13 s of a 43 s phone merge).
            val g = spirvs.greyFft
            var rc = 3
            if (g != null) {
                val tmp = SrpBridge.createResident(handle, (w * h).toLong())
                require(tmp != 0L) { "VulkanPipeline: grey resident alloc failed" }
                try {
                    checkRc("greyUpload", SrpBridge.uploadResident(handle, tmp, packImageInto(frame, packBuf), (w * h).toLong()))
                    rc = timed("greyFft") { SrpBridge.nativeGreyFftMixedRes(handle, tmp, out.apply { rewind() }, g, w, h) }
                    if (rc != 3) checkRc("greyFft mixed", rc)
                } finally {
                    SrpBridge.freeResident(handle, tmp)
                }
            }
            if (rc == 3) {
                timed("greyFft") { checkRc("greyFft", SrpBridge.greyFft(handle, packImageInto(frame, packBuf), out.apply { rewind() },
                    spirvs.fft, w, h)) }
            }
            // Same device bytes straight into the rows: no flat
            // intermediate (50 MB at full-res).
            out.rewind()
            val fb = out.asFloatBuffer()
            for (y in 0 until h) fb.get(rows[y])
            return rows
        }

        // Accumulators + stats (device-resident across frames: no host
        // round-trip per frame — download once after divide). The stats
        // residents replace the two 3-channel host buffers (75 MB at
        // full-res); same shader, same bytes (see the `robustness
        // residents match staged entries bit-exact` proof).
        val total = outH * outW * 3
        val gw = w / 2
        val gh = h / 2
        val meansRes = SrpBridge.createResident(handle, (3 * gw * gh).toLong())
        require(meansRes != 0L) { "VulkanPipeline: resident means alloc failed" }
        val varsRes = SrpBridge.createResident(handle, (3 * gw * gh).toLong())
        require(varsRes != 0L) { "VulkanPipeline: resident vars alloc failed" }
        val numRes = SrpBridge.createResident(handle, total.toLong())
        require(numRes != 0L) { "VulkanPipeline: resident num alloc failed" }
        val denRes = SrpBridge.createResident(handle, total.toLong())
        require(denRes != 0L) { "VulkanPipeline: resident den alloc failed" }
        // Noise evening (guide grid): per-frame robustness upload + vec4
        // accumulators (sum g r, sum (g r)^2, sum r, sum r^2).
        val twoBand = config.twoBand?.takeIf { config.bayer && config.colourDiff != null }
        if (twoBand != null) require(spirvs.mergeTwoBand != null && spirvs.twoBandResolve != null && spirvs.twoBandDct != null &&
            (!twoBand.steer || spirvs.twoBandTensor != null) && (twoBand.featherRadius == 0 || spirvs.robFeather != null)) {
            "VulkanPipeline: two-band needs merge_twoband / twoband_resolve / twoband_dct" }
        val tbAuxRes = if (twoBand != null) SrpBridge.createResident(handle, 4L * outW * outH) else 0L
        val tbRobRes = if (twoBand != null) SrpBridge.createResident(handle, (gw * gh).toLong()) else 0L
        val tbRobTmp = if (twoBand != null && twoBand.featherRadius > 0) SrpBridge.createResident(handle, (gw * gh).toLong()) else 0L
        var tbFlowRes = 0L
        if (twoBand != null) require(tbAuxRes != 0L && tbRobRes != 0L) { "VulkanPipeline: two-band alloc failed" }
        val evening = twoBand == null && config.noiseEvening && config.bayer && config.colourDiff != null &&
            spirvs.noiseAcc != null && spirvs.noiseEven != null
        val evenRRes = if (evening) SrpBridge.createResident(handle, (gw * gh).toLong()) else 0L
        val evenAccRes = if (evening) SrpBridge.createResident(handle, (4L * gw * gh)) else 0L
        if (evening) require(evenRRes != 0L && evenAccRes != 0L) { "VulkanPipeline: noise evening alloc failed" }
        var evenFrames = 0
        var evenSumG = 0.0
        var evenSumG2 = 0.0
        var pedestal = 0f
        // Supplement S.3 map (reference guide grid), 0 = off.
        var hfRes = 0L
        // Perf slice (a): upload-once frame + kernels chain scratch,
        // device-resident across entries and reused across frames
        // (sequential fence-waited dispatches: aliasing-safe). Same
        // shaders, same bytes as the staged path (see the `frame chain
        // residents match staged entries bit-exact` proof). Freed in
        // the run finally below. Bayer-only: the non-bayer leg keeps
        // the staged entries (it aliases a larger buffer into
        // kerngrad, which exact-size residents cannot express).
        val frameRes = SrpBridge.createResident(handle, (w * h).toLong())
        require(frameRes != 0L) { "VulkanPipeline: resident frame alloc failed" }
        // Two-band computes the kernel covariance once, from the reference
        // (edge-steered narrow kernel), so it keeps these residents.
        val noKernels = config.mobilePolynomial
        val kernPreRes = if (noKernels) 0L else SrpBridge.createResident(handle, (w * h).toLong())
        require(noKernels || kernPreRes != 0L) { "VulkanPipeline: resident kernPre alloc failed" }
        val kernGreyRes = if (noKernels) 0L else SrpBridge.createResident(handle, (gw * gh).toLong())
        require(noKernels || kernGreyRes != 0L) { "VulkanPipeline: resident kernGrey alloc failed" }
        val kernGradsRes = if (noKernels) 0L else SrpBridge.createResident(handle, ((gw - 1) * (gh - 1) * 2).toLong())
        require(noKernels || kernGradsRes != 0L) { "VulkanPipeline: resident kernGrads alloc failed" }
        val kernCovRes = if (noKernels) 0L else SrpBridge.createResident(handle, (gw * gh * 4).toLong())
        require(noKernels || kernCovRes != 0L) { "VulkanPipeline: resident kernCov alloc failed" }
        val polynomialLutRes = if(config.mobilePolynomial) SrpBridge.createResident(handle,MobilePolynomial.table.size.toLong()).also { r ->
            require(r != 0L) { "polynomial LUT allocation failed" }
            checkRc("polynomial LUT",SrpBridge.uploadResident(handle,r,pack(MobilePolynomial.table),MobilePolynomial.table.size.toLong()))
        } else 0L
        // 1. Ref phase (own scope): grey rows, init host temporaries
        // and the decoded frame all die here, before the loop. The ref
        // grey packs through a temp (dies with the block): the shared
        // pack staging is only allocated after the pin below, so it is
        // not live during the ~300 MB init window.
        val pinnedInit = run {
            // 1. Init + pin in one pass: the 50 MB grey rows are built
            // inside init and die with its pyramid build. Each level is pinned on-device as soon as it is
            // built (L2 spectra + statics, one upload per run, reused
            // across frames; freed in the run finally below), so only
            // one level's host arrays are live at a time; the pinned
            // copy carries stripped arrays, see pinLevelResidents.
            timed("alignInit") {
                AlignGpu.initAndPinGpu({
                    greyOfInto(refBox!!, scratch.floats(w * h), scratch.floats(w * h),
                        Array(h) { FloatArray(w) })
                }, loop, handle, alignSpirvs)
            }
        }

        // Resident spread map on the finest flow grid (s stays
        // on-device between the smap and robust legs; same expression
        // as the work block's ftx0/fty0 below). Freed in the run
        // finally below.
        val finestGrid = pinnedInit.levels.last()
        val sRes = SrpBridge.createResident(handle, (finestGrid.nTx * finestGrid.nTy).toLong())
        require(sRes != 0L) { "VulkanPipeline: resident s alloc failed" }

        // Shared pack/upload staging, allocated only now (post-pin):
        // keeping it out of the init window above saves 50 MB live
        // there. Fully rewritten per use from here on.
        val frameBuf = scratch.floats(w * h)

        heapLog("post-pin")
        // 2. Reference local stats straight into the residents.
        if (config.robustnessEnabled) {
            timed("robustInit") { checkRc("robustInit", SrpBridge.robustInitRes(
                handle, packImageInto(refBox!!, frameBuf), meansRes, varsRes,
                spirvs.robust, w, h)) }
            // Supplement S.3: the reference's variance-loss map, once.
            val rp0 = config.robustnessParams
            if (rp0.s3) {
                hfRes = SrpBridge.createResident(handle, (gw * gh).toLong())
                require(hfRes != 0L) { "VulkanPipeline: S.3 map alloc failed" }
                val a = config.kernelAlpha
                val b = config.kernelBeta
                timed("robustHf") { checkRc("robustHf", SrpBridge.nativeRobustHfRes(handle, meansRes, varsRes,
                    hfRes, spirvs.robust, w, h, rp0.s3Rho, rp0.s3K,
                    floatArrayOf(a[0], a[1], a[2]), floatArrayOf(b[0], b[1], b[2]))) }
            }
        }

        try {
        // Shared LUT + GAT profile buffers (rewound per use).
        val lutBuf = pack(config.sigmaSqCurve + config.dSqCurve)
        val lutN = config.sigmaSqCurve.size
        val abBuf = pack(config.kernelAlpha + config.kernelBeta)

        // 3. Merge-work scratch (own scope: the `run` block opened
        // here closes before the download below, so the work vectors die
        // first). `kernPre` is the loop grey output (grey bytes are
        // consumed into rows before the kernels legs run). The staged
        // non-bayer leg additionally uses it as the kernel preprocess
        // output; the bayer legs run on the run-scope residents above.
        // `frameBuf` lives outside (allocated post-pin, surviving into
        // the finish); the loop rows are loop-scoped below.
        run {
        val finest0 = pinnedInit.levels.last()
        val ftx0 = finest0.nTx
        val fty0 = finest0.nTy
        val rSize0 = if (config.bayer) gw * gh else w * h
        val kernPre = scratch.floats(w * h)
        val kp = config.kernelParams
        require(kp.selection != SelectionLaw.PAPER && !kp.paperAnisotropy) {
            "paper S.1 kernel variants are CPU-only (benchmark)"
        }
        val kpBuf = pack(floatArrayOf(kp.kDetail, kp.kDenoise, kp.dTh, kp.dTr,
            kp.kStretch, kp.kShrink,
            java.lang.Float.intBitsToFloat(if (kp.selection == SelectionLaw.HARD_THRESHOLD) 0 else 1),
            java.lang.Float.intBitsToFloat(if (kp.iso) 1 else 0), kp.kMin, kp.weakEdgeNoise, kp.flatSigma))
        val mergeFlowFlat = FloatArray(ftx0 * fty0 * 2)
        val mergeFlow = SrpBridge.directFloats(ftx0 * fty0 * 2)
        // Guide-grid robustness map staging: the robust-compute output
        // lands here directly (no host round-trip), and the no-robust
        // path fills it from `onesR`. Drops the two 12.5 MB host copies.
        val onesR = FloatArray(rSize0) { 1f }
        val mergeRDirect = SrpBridge.directFloats(rSize0)
        val smapOut = SrpBridge.directFloats(ftx0 * fty0)

        // 3a. Per-frame kernels via entries (GAT -> decimate -> kerngrad ->
        // cov on the grey grid), cf. `EndToEnd.frameKernels` + `estimate_kernels`.
        // Staged leg (non-bayer only): host scratch exactly as before.
        fun frameKernelsGpuStaged(frame: Array<FloatArray>): ByteBuffer {
            val kernGrey = SrpBridge.directFloats((w / 2) * (h / 2))
            val kernGrads = SrpBridge.directFloats((w / 2 - 1) * (h / 2 - 1) * 2)
            val kernCov = SrpBridge.directFloats((w / 2) * (h / 2) * 4)
            val pre = run {
                when (config.kernelPreprocessing) {
                    null -> return@run packImageInto(frame, kernPre)
                    "gat" -> {
                        timed("kernels gat") { checkRc("kernels gat", SrpBridge.gat(handle, packImageInto(frame, frameBuf), kernPre,
                            abBuf.apply { rewind() }, spirvs.gat, w, h, w * h, 0)) }
                        kernPre.apply { rewind() }
                    }
                    "sqrt", "gamma" -> {
                        val op = if (config.kernelPreprocessing == "sqrt") 2 else 3
                        val expBits = if (op == 3) java.lang.Float.floatToIntBits((1.0 / 2.2).toFloat()) else 0
                        timed("kernels grad") { checkRc("kernels grad", SrpBridge.grad(handle, packImageInto(frame, frameBuf), kernPre,
                            spirvs.grad, w, h, w * h, op, expBits)) }
                        kernPre.apply { rewind() }
                    }
                    else -> throw IllegalArgumentException("Unknown kernel preprocessing")
                }
            }
            val kgw = w / 2
            val kgh = h / 2
            val grey = if (config.bayer) {
                timed("kernels decimate") { checkRc("kernels decimate", SrpBridge.gat(handle, pre, kernGrey.apply { rewind() },
                    abBuf.apply { rewind() }, spirvs.gat, w, h, kgw * kgh, 1)) }
                kernGrey.apply { rewind() }
            } else pre
            val kw = kgw - 1
            val kh = kgh - 1
            timed("kernels kerngrad") { checkRc("kernels kerngrad", SrpBridge.grad(handle, grey, kernGrads.apply { rewind() },
                spirvs.grad, kgw, kgh, kw * kh, 0, 0)) }
            kernGrads.rewind()
            timed("kernels cov") { checkRc("kernels cov", SrpBridge.kernelsCov(handle, kernGrads, kpBuf.apply { rewind() }, kernCov.apply { rewind() },
                spirvs.kernels, kw, kh, kgw, kgh)) }
            return kernCov.apply { rewind() }
        }

        // Resident leg (bayer): the same chain over the run-scope
        // residents. `frameRes` must hold the current frame (established
        // by the grey leg, or the explicit ref upload below). Returns
        // the cov resident handle. Same shaders, same bytes.
        fun frameKernelsGpuRes(): Long {
            val kgw = w / 2
            val kgh = h / 2
            val kw = kgw - 1
            val kh = kgh - 1
            val pre: Long = run {
                when (config.kernelPreprocessing) {
                    null -> return@run frameRes
                    "gat" -> {
                        timed("kernels gat") { checkRc("kernels gat", SrpBridge.nativeGatRes(handle, frameRes, kernPreRes,
                            abBuf.apply { rewind() }, spirvs.gat, w, h, w * h, 0)) }
                        kernPreRes
                    }
                    "sqrt", "gamma" -> {
                        val op = if (config.kernelPreprocessing == "sqrt") 2 else 3
                        val expBits = if (op == 3) java.lang.Float.floatToIntBits((1.0 / 2.2).toFloat()) else 0
                        timed("kernels grad") { checkRc("kernels grad", SrpBridge.nativeGradRes(handle, frameRes, kernPreRes,
                            spirvs.grad, w, h, w * h, op, expBits)) }
                        kernPreRes
                    }
                    else -> throw IllegalArgumentException("Unknown kernel preprocessing")
                }
            }
            timed("kernels decimate") { checkRc("kernels decimate", SrpBridge.nativeGatRes(handle, pre, kernGreyRes,
                abBuf.apply { rewind() }, spirvs.gat, w, h, kgw * kgh, 1)) }
            timed("kernels kerngrad") { checkRc("kernels kerngrad", SrpBridge.nativeGradRes(handle, kernGreyRes, kernGradsRes,
                spirvs.grad, kgw, kgh, kw * kh, 0, 0)) }
            timed("kernels cov") { checkRc("kernels cov", SrpBridge.nativeKernelsCovRes(handle, kernGradsRes,
                kpBuf.apply { rewind() }, kernCovRes, spirvs.kernels, kw, kh, kgw, kgh)) }
            return kernCovRes
        }

        // Resident grey leg (bayer): uploads the frame once, then grey
        // runs on-device with the align rows filled from the download —
        // same bytes as the staged greyOfInto above. The upload also
        // establishes the frameRes invariant for the kernels, robust
        // and merge legs that follow in frameStep.
        fun greyOfIntoRes(frame: Array<FloatArray>, rows: Array<FloatArray>): Array<FloatArray> {
            timed("frameUpload") { checkRc("frameUpload", SrpBridge.uploadResident(handle, frameRes,
                packImageInto(frame, frameBuf), (w * h).toLong())) }
            if (config.bayer && firRadius != 0) {
                timed("greyFir") { checkRc("greyFir", SrpBridge.nativeGreyFirRes(handle, frameRes, kernPre.apply { rewind() },
                    spirvs.greyFir!!, w, h, firTaps, firRadius)) }
                kernPre.rewind()
                val fb = kernPre.asFloatBuffer()
                for (y in 0 until h) fb.get(rows[y])
                return rows
            }
            if (!config.bayer || config.greyMethod != "FFT") {
                return if (config.bayer) Grey.computeGreyImages(frame, config.greyMethod) else frame
            }
            val mixed = spirvs.greyFft?.let { g ->
                timed("greyFft") { SrpBridge.nativeGreyFftMixedRes(handle, frameRes, kernPre.apply { rewind() }, g, w, h) }
            }
            if (mixed != 0) {
                // No mixed module, or an axis needs Bluestein (rc 3).
                if (mixed != null && mixed != 3) checkRc("greyFft mixed", mixed)
                timed("greyFft") { checkRc("greyFft", SrpBridge.nativeGreyFftRes(handle, frameRes, kernPre.apply { rewind() },
                    spirvs.fft, spirvs.corr, w, h)) }
            } else if (greyCompare) {
                // Parity aid (-Dsrpipeline.greyCompare=true): the Bluestein
                // chain on the same frame, max |difference| to stdout.
                val mixedOut = FloatArray(w * h).also { kernPre.rewind(); kernPre.asFloatBuffer().get(it) }
                checkRc("greyFft ref", SrpBridge.nativeGreyFftRes(handle, frameRes, kernPre.apply { rewind() },
                    spirvs.fft, spirvs.corr, w, h))
                val ref = FloatArray(w * h).also { kernPre.rewind(); kernPre.asFloatBuffer().get(it) }
                var maxD = 0f; var maxV = 0f
                for (i in ref.indices) { maxD = maxOf(maxD, kotlin.math.abs(ref[i] - mixedOut[i])); maxV = maxOf(maxV, kotlin.math.abs(ref[i])) }
                println("sr-pipeline greyCompare ${w}x$h: max|mixed - bluestein| = $maxD (max |grey| $maxV)")
                kernPre.rewind(); kernPre.asFloatBuffer().put(mixedOut)
            }
            kernPre.rewind()
            val fb = kernPre.asFloatBuffer()
            for (y in 0 until h) fb.get(rows[y])
            return rows
        }

        // 3b. Splat one frame's samples into the resident accumulators.
        // `rBuf` already holds the guide-grid robustness map (written
        // by robust-compute, or filled with ones): no host round-trip.
        fun mergeFrame(frame: Array<FloatArray>, flow: Array<Array<FloatArray>>, rBuf: ByteBuffer, reference: Boolean = false) {
            val ftx = flow[0].size
            val fty = flow.size
            require(ftx == ftx0 && fty == fty0) { "flow grid changed mid-run" }
            for (y in 0 until fty) for (x in 0 until ftx) {
                mergeFlowFlat[(y * ftx + x) * 2] = flow[y][x][0]
                mergeFlowFlat[(y * ftx + x) * 2 + 1] = flow[y][x][1]
            }
            rBuf.rewind()
            if (!config.bayer) {
                val cov = frameKernelsGpuStaged(frame)
                timed("mergeSplat") { checkRc("mergeSplat", SrpBridge.mergeSplatRes(handle, packImageInto(frame, frameBuf),
                    packInto(mergeFlowFlat, mergeFlow), cov, rBuf, numRes, denRes,
                    spirvs.mergeSplat, w, h, outW, outH, tile,
                    config.scale, config.bayer, ftx, fty)) }
            } else if (twoBand != null) {
                // Edge steering: the reference frame's Wronski covariance (frameRes
                // still holds the raw frame here), kept in kernCovRes for every frame.
                if (reference && twoBand.steer) {
                    frameKernelsGpuRes()   // GAT grey + gradients (kernGradsRes); cov unused
                    val tp = IntArray(32)
                    tp[2] = gw * gh; tp[7] = java.lang.Float.floatToRawIntBits(twoBand.maxStretch.toFloat())
                    tp[12] = gw - 1; tp[13] = gh - 1; tp[14] = gw
                    timed("tbTensor") { checkRc("tbTensor", SrpBridge.dispatchRes(handle, spirvs.twoBandTensor!!,
                        longArrayOf(kernGradsRes, kernCovRes), longArrayOf(2L * (gw - 1) * (gh - 1), 4L * gw * gh),
                        tp, (gw * gh).toLong())) }
                }
                timed("colourDiffUpload") { checkRc("colourDiffUpload", SrpBridge.uploadResident(handle, frameRes,
                    ColourDiff.packInto(frame, frameBuf, config.colourDiff!![0], config.colourDiff[1]), (w * h).toLong())) }
                frameObserver?.invoke(frameBuf, flow, rBuf)
                if (tbFlowRes == 0L) tbFlowRes = SrpBridge.createResident(handle, 2L * ftx * fty).also {
                    require(it != 0L) { "VulkanPipeline: two-band flow alloc failed" } }
                checkRc("tbFlow", SrpBridge.uploadResident(handle, tbFlowRes, packInto(mergeFlowFlat, mergeFlow), 2L * ftx * fty))
                rBuf.rewind()
                checkRc("tbRob", SrpBridge.uploadResident(handle, tbRobRes, rBuf, (gw * gh).toLong()))
                if (twoBand.featherRadius > 0 && !reference) timed("tbFeather") {
                    // min x -> tmp, min y -> rob, mean x -> tmp, mean y -> rob.
                    val n = (gw * gh).toLong()
                    for ((k, op) in listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1).withIndex()) {
                        val fp = IntArray(32)
                        fp[2] = gw * gh; fp[11] = op.first; fp[12] = op.second; fp[13] = gw; fp[14] = gh; fp[15] = twoBand.featherRadius
                        val (src, dst) = if (k % 2 == 0) tbRobRes to tbRobTmp else tbRobTmp to tbRobRes
                        checkRc("tbFeather$k", SrpBridge.dispatchRes(handle, spirvs.robFeather!!, longArrayOf(src, dst), longArrayOf(n, n), fp, n))
                    }
                }
                val push = IntArray(32)
                push[0] = w; push[1] = h; push[2] = outW * outH; push[5] = tile
                push[6] = java.lang.Float.floatToRawIntBits(config.scale)
                push[7] = java.lang.Float.floatToRawIntBits(twoBand.sigmaHigh.toFloat())
                push[8] = java.lang.Float.floatToRawIntBits(twoBand.sigmaLow.toFloat())
                push[12] = ftx; push[13] = outW; push[14] = fty
                push[15] = if (twoBand.steer) gw else 0
                timed("mergeTwoBand") { checkRc("mergeTwoBand", SrpBridge.dispatchRes(handle, spirvs.mergeTwoBand!!,
                    longArrayOf(frameRes, tbFlowRes, tbRobRes, numRes, denRes, tbAuxRes, kernCovRes),
                    longArrayOf((w * h).toLong(), 2L * ftx * fty, (gw * gh).toLong(), total.toLong(), total.toLong(), 4L * outW * outH,
                        4L * gw * gh),
                    push, (outW * outH).toLong())) }
            } else {
                val covRes = if(config.mobilePolynomial) polynomialLutRes else frameKernelsGpuRes()
                // Mode bits: 1 = grey, 2 = reference kernels, 4 = reference
                // frame, 8 = detail reconstruction, 16 = continuous motion
                // (B-spline flow + bilinear robustness). Keep legacy Wronski mode.
                // Separate green detail is safe when R/B are colour
                // differences. Independent raw channels retain legacy widths.
                val mergeMode = if(config.mobilePolynomial) 16
                    else (if (config.continuousMotion) 16 else 0) or
                        // 32: Wronski kernels with the wide gather + detail green.
                        (if (config.detailReconstruction) 32 or (if (reference) 4 else 0) or
                            (if (config.colourDiff != null) 8 else 0) else 0)
                // Z1: kernels and robustness above read the original frame;
                // only the splat reads the colour-difference frame (ColourDiff).
                config.colourDiff?.let { cd ->
                    timed("colourDiffUpload") { checkRc("colourDiffUpload", SrpBridge.uploadResident(handle, frameRes,
                        ColourDiff.packInto(frame, frameBuf, cd[0], cd[1]), (w * h).toLong())) }
                }
                frameObserver?.invoke(frameBuf, flow, rBuf)
                greenFit?.recordResident(frameRes, flow, rBuf)
                val greenW = if ((config.mobilePolynomial || config.detailReconstruction) && config.colourDiff != null)
                    greenFrameWeights[mergeFrameIndex++] else 1f
                timed("mergeSplat") { checkRc("mergeSplat", SrpBridge.nativeMergeSplatFrameRes(handle, frameRes,
                    packInto(mergeFlowFlat, mergeFlow), covRes, rBuf, numRes, denRes,
                    (if(config.mobilePolynomial) spirvs.mergePolynomial!! else spirvs.mergeSplat), w, h, outW, outH, (if(config.mobilePolynomial) MobilePolynomial.table.size else gw * gh * 4), gw * gh, tile,
                    config.scale, mergeMode, ftx, fty,
                    if(config.mobilePolynomial) config.kernelAlpha.average().toFloat() else if (config.colourDiff != null) config.chromaScale else 1f,
                    greenW,
                    if(config.mobilePolynomial) config.kernelBeta.average().toFloat() else config.frameEqualize)) }
                if (evening) {
                    // Same r and green weight the splat just used, on the guide grid.
                    rBuf.rewind()
                    checkRc("noiseAccUpload", SrpBridge.uploadResident(handle, evenRRes, rBuf, (gw * gh).toLong()))
                    rBuf.rewind()
                    val push = IntArray(32)
                    push[2] = gw * gh
                    push[8] = java.lang.Float.floatToRawIntBits(greenW)
                    push[10] = if (evenFrames == 0) 1 else 0
                    timed("noiseAcc") { checkRc("noiseAcc", SrpBridge.dispatchRes(handle, spirvs.noiseAcc!!,
                        longArrayOf(evenRRes, evenAccRes), longArrayOf((gw * gh).toLong(), 4L * gw * gh), push, (gw * gh).toLong())) }
                    evenFrames++; evenSumG += greenW; evenSumG2 += greenW.toDouble() * greenW
                }
            }
        }

        // 4. Reference merge (zero flow on the finest tile grid, r = 1
        // over the guide grid), then release the decoded frame: it is
        // dead before the comparison loop below. The bayer legs read
        // the frame from its resident, so upload it once here (the
        // loop frames upload in the grey leg instead).
        val finest = pinnedInit.levels.last()
        val continuousMotion = config.continuousMotion || config.mobilePolynomial
        // Ghost-mask visibility: merged noise variance (a L + b) / N of the
        // green-ish guide, scaled by GHOST_VISIBILITY (see the constant).
        val ghostFrames = greenFrameWeights.size.coerceAtLeast(1)
        val ghostA = if (config.ghostGrow) GHOST_VISIBILITY * (config.kernelAlpha[1] + config.kernelAlpha[3]) / 2f / ghostFrames else 0f
        val ghostB = if (config.ghostGrow) GHOST_VISIBILITY * (config.kernelBeta[1] + config.kernelBeta[3]) / 2f / ghostFrames else 0f
        val motionField = if (continuousMotion) {
            val count = finest.nTx * finest.nTy * 4
            val packed = FloatArray(count)
            if (finest.hessRes != 0L) {
                val buffer = SrpBridge.directFloats(count)
                checkRc("motion confidence", SrpBridge.downloadResident(handle, finest.hessRes, buffer, count.toLong()))
                buffer.asFloatBuffer().get(packed)
            } else {
                finest.hessPacked.copyInto(packed)
            }
            MotionField(finest.nTx, finest.nTy, packed)
        } else null
        val zeroFlow = Array(finest.nTy) { Array(finest.nTx) { floatArrayOf(0f, 0f) } }
        if (config.bayer) {
            timed("refUpload") { checkRc("refUpload", SrpBridge.uploadResident(handle, frameRes,
                packImageInto(refBox!!, frameBuf), (w * h).toLong())) }
        }
        // Blurry-frame weighting: reference detail energy (green noise model).
        val sharpA = (config.kernelAlpha[1] + config.kernelAlpha[3]) / 2f
        val sharpB = (config.kernelBeta[1] + config.kernelBeta[3]) / 2f
        pedestal = if (config.blackPedestal && config.bayer && config.colourDiff != null)
            timed("blackPedestal") { BlackPedestal.estimate(refBox!!, config.kernelAlpha, config.kernelBeta) }.also {
                println("sr-pipeline black pedestal %.5f (R %.5f G %.5f B %.5f, se %.6f, %d blocks, neutral %b)".format(
                    it.pedestal, it.r, it.g, it.b, it.sigma, it.blocks, it.neutral))
            }.pedestal else 0f
        val refEnergy = if (config.blurGamma > 0f && config.bayer)
            timed("sharpness") { FrameSharpness.energy(refBox!!, sharpA, sharpB) } else 0.0
        mergeFrame(refBox!!, zeroFlow, packInto(onesR, mergeRDirect), reference = true)
        refBox = null
        // Memory-tight: the ref phase's garbage (ref CFA, init pack
        // staging, pin upload scratch — ~200 MB at full-res) is dead
        // here; collect BEFORE the comparison loop so frame-0's decode
        // and align cannot OOM on uncollected garbage at the 512 MB
        // ceiling. Same discipline as the per-frame gc below.
        if (config.memoryTight) System.gc()

        // 5. Per comparison frame (decoded just in time, released after
        // merging — only one comparison is ever live; a prefetch worker
        // was tried and reverted: it cost wall time to CPU contention
        // and broke -Xmx512m survival with its extra live frame).
        // Loop rows are scoped here: they die with the work block.
        val loopRows = Array(h) { FloatArray(w) }
        // Retained moving-pyramid holder (run geometry fixed): kills
        // ~140 MB of pad+pyramid garbage per frame with identical
        // values (every element overwritten per build; the blur
        // streams row by row and the pad is fused into the reads, so
        // only the ~37 MB levels are retained).
        val pyramidReuse = AlignGpu.PyramidReuse()
        // Mobile path: resident grey + GPU moving pyramid (same pad contract
        // as AlignGpu.alignLevelsGpu). Freed after the comparison loop.
        val resPyr: AlignGpu.ResidentPyramid? =
            if (config.bayer && firRadius != 0 && spirvs.pyrDown != null) {
                val ts = loop.tileSize
                AlignGpu.ResidentPyramid(handle, h, w,
                    if (h % ts == 0) 0 else ts - h % ts, if (w % ts == 0) 0 else ts - w % ts,
                    loop.factors, spirvs.pyrDown, loop.binomialPyramid)
            } else null
        var frameNo = 0
        // One frame end to end (own scope): the decoded frame, pyramid,
        // per-call transients and flow all die here, so a memory-tight
        // collector reclaims them before the next frame.
        fun frameStep(frameThunk: (ByteBuffer) -> Array<FloatArray>) {
            // The frame decodes into the shared pack staging (same
            // bytes as a private staging buffer): the rows are built
            // before the first pack overwrites it.
            val frame = frameThunk(frameBuf)
            heapLog("frame-$frameNo-decoded")
            frameNo++
            // Resident grey leg on bayer (uploads frameRes as a side
            // effect); the staged greyOfInto serves the ref phase and
            // the non-bayer configuration.
            val estimatedFlow = if (resPyr != null) {
                timed("frameUpload") { checkRc("frameUpload", SrpBridge.uploadResident(handle, frameRes,
                    packImageInto(frame, frameBuf), (w * h).toLong())) }
                timed("greyFir") { checkRc("greyFir", SrpBridge.nativeGreyFirToRes(handle, frameRes, resPyr.grey,
                    spirvs.greyFir!!, w, h, firTaps, firRadius, resPyr.greyTmp)) }
                timed("pyrGpu") { resPyr.build() }
                timed("alignLoop") { AlignGpu.alignLevelsGpuResident(pinnedInit, resPyr, loop, handle, alignSpirvs) }
            } else {
                val frameGrey = if (config.bayer) greyOfIntoRes(frame, loopRows)
                    else greyOfInto(frame, frameBuf, kernPre, loopRows)
                timed("alignLoop") { AlignGpu.alignLevelsGpu(pinnedInit, frameGrey, loop, handle, alignSpirvs, pyramidReuse) }
            }
            motionField?.let { field ->
                timed("motionRegularize") { field.regularize(estimatedFlow, config.robustnessParams.mt) }
            }
            val flow = flowTransform?.invoke(frameNo, estimatedFlow) ?: estimatedFlow
            val ftx = flow[0].size
            val fty = flow.size
            require(ftx == ftx0 && fty == fty0) { "flow grid changed mid-run" }
            // Robustness map straight into the merge staging (same
            // bytes as the old host round-trip through `robustR`).
            val rBuf: ByteBuffer = if (config.robustnessEnabled) {
                for (y in 0 until fty) for (x in 0 until ftx) {
                    mergeFlowFlat[(y * ftx + x) * 2] = flow[y][x][0]
                    mergeFlowFlat[(y * ftx + x) * 2 + 1] = flow[y][x][1]
                }
                val rp = config.robustnessParams
                if (!config.bayer) {
                    timed("smap") { checkRc("smap", SrpBridge.smap(handle, packInto(mergeFlowFlat, mergeFlow), smapOut.apply { rewind() },
                        spirvs.robust, ftx, fty, rp.mt, rp.s1, rp.s2)) }
                    smapOut.rewind()
                    timed("robustCompute") { checkRc("robustCompute", SrpBridge.robustComputeRes(handle, packImageInto(frame, frameBuf),
                        meansRes, varsRes,
                        packInto(mergeFlowFlat, mergeFlow), lutBuf.apply { rewind() }, smapOut, mergeRDirect.apply { rewind() },
                        spirvs.robust, w, h, tile, lutN,
                        config.robustnessParams.noiseCorrection,
                        config.robustnessParams.t, ftx, fty)) }
                } else {
                    timed("smap") { checkRc("smap", SrpBridge.nativeSmapRes(handle, packInto(mergeFlowFlat, mergeFlow), sRes,
                        spirvs.robust, ftx, fty,
                        java.lang.Float.floatToIntBits(rp.mt),
                        java.lang.Float.floatToIntBits(rp.s1),
                        java.lang.Float.floatToIntBits(rp.s2))) }
                    timed("robustCompute") { checkRc("robustCompute", SrpBridge.nativeRobustComputeFrameRes(handle, frameRes,
                        meansRes, varsRes,
                        packInto(mergeFlowFlat, mergeFlow), lutBuf.apply { rewind() }, sRes, mergeRDirect.apply { rewind() },
                        spirvs.robust, w, h, tile, lutN,
                        // Bit 0: noise correction; bit 1: use the same continuous
                        // motion field as the reconstruction (continuous motion).
                        (if (config.robustnessParams.noiseCorrection) 1 else 0) or (if (continuousMotion) 2 else 0) or
                            // Bit 2: ghost mask (opening + growth past the motion smoothing).
                            (if (config.ghostGrow) 4 else 0),
                        java.lang.Float.floatToIntBits(config.robustnessParams.t), ftx, fty,
                        hfRes, java.lang.Float.floatToIntBits(rp.s1),
                        java.lang.Float.floatToRawIntBits(ghostA), java.lang.Float.floatToRawIntBits(ghostB))) }
                }
                mergeRDirect
            } else {
                packInto(onesR, mergeRDirect)
            }
            // Debug: SRP_R_DUMP=<dir> writes each frame's robustness map
            // (guide grid, float32) and prints its mean / rejected share.
            System.getenv("SRP_R_DUMP")?.takeIf { it.isNotEmpty() }?.let { dir ->
                val fb = rBuf.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                val n = rSize0
                val r = FloatArray(n).also { fb.position(0); fb.get(it) }
                val idx = (java.io.File(dir).list()?.count { it.startsWith("r_") } ?: 0)
                java.io.File(dir, "r_%02d_%dx%d.f32".format(idx, if (config.bayer) w / 2 else w, if (config.bayer) h / 2 else h))
                    .writeBytes(java.nio.ByteBuffer.allocate(n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .also { b -> r.forEach { b.putFloat(it) } }.array())
                java.io.File(dir, "flow_%02d_%dx%d.f32".format(idx, ftx, fty))
                    .writeBytes(java.nio.ByteBuffer.allocate(ftx * fty * 2 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .also { b -> mergeFlowFlat.forEach { b.putFloat(it) } }.array())
                System.err.println("SRP_R frame $idx mean ${"%.3f".format(r.average())} rejected(<0.1) ${"%.1f".format(100.0 * r.count { it < 0.1f } / n)}%")
            }
            if (refEnergy > 0.0) {
                val e = timed("sharpness") { FrameSharpness.energy(frame, sharpA, sharpB) }
                val wBlur = FrameSharpness.weight(e, refEnergy, config.blurGamma)
                println("sr-pipeline frame sharpness %.3f of reference -> weight %.3f".format(e / refEnergy, wBlur))
                if (wBlur < 1f) {
                    val fb = rBuf.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                    for (i in 0 until rSize0) fb.put(i, fb.get(i) * wBlur)
                }
            }
            mergeFrame(frame, flow, rBuf)
        }
        try {
            for (frameThunk in comparisons) {
                frameStep(frameThunk)
                // Memory-tight discipline (cf. `memoryTight`): everything
                // above is dead here; collect before the next frame so GC
                // timing cannot decide survival at the 512 MB ceiling.
                if (config.memoryTight) System.gc()
            }
        } finally {
            resPyr?.free()
        }
        } // 3. work block ends: kernel scratch, loop rows and merge
          // vectors die here, before the 150 MB download below.

        // 6. Normalise in place (on-device), download once. Only the
        // pack staging, pinned residents and tiny profile buffers are
        // still alive on the host.
        heapLog("pre-download")
        // Drop the align host scratch (~100 MB L2 caches, never read
        // again), then compact: the 150 MB download below is one
        // contiguous non-movable block, and ART counts direct buffers
        // against the Java heap — a fragmented 512 MB heap cannot place
        // it even with free bytes to spare (observed on-device).
        AlignGpu.freeLevelScratch(pinnedInit)
        System.gc()
        if (twoBand != null) timed("twoBandResolve") {
            val res = longArrayOf(numRes, denRes, tbAuxRes)
            val sizes = longArrayOf(total.toLong(), total.toLong(), 4L * outW * outH)
            fun push(pass: Int) = IntArray(32).also {
                it[2] = outW * outH; it[6] = java.lang.Float.floatToRawIntBits(config.scale)
                it[7] = java.lang.Float.floatToRawIntBits(twoBand.confK.toFloat())
                it[8] = java.lang.Float.floatToRawIntBits((config.kernelAlpha[1] + config.kernelAlpha[3]) / 2f)
                it[9] = java.lang.Float.floatToRawIntBits((config.kernelBeta[1] + config.kernelBeta[3]) / 2f)
                it[11] = pass; it[13] = outW; it[14] = outH
            }
            checkRc("tbResolve0", SrpBridge.dispatchRes(handle, spirvs.twoBandResolve!!, res, sizes, push(0), (outW * outH).toLong()))
            checkRc("tbResolve1", SrpBridge.dispatchRes(handle, spirvs.twoBandResolve, res, sizes, push(1), (outW * outH).toLong()))
            if (twoBand.phaseInvariant) {
                checkRc("tbResolve3", SrpBridge.dispatchRes(handle, spirvs.twoBandResolve, res, sizes, push(3), (outW * outH).toLong()))
                checkRc("tbResolve4", SrpBridge.dispatchRes(handle, spirvs.twoBandResolve, res, sizes, push(4), (outW * outH).toLong()))
            }
            if (twoBand.denoise) for ((ox, oy) in listOf(0 to 0, 4 to 0, 0 to 4, 4 to 4)) {
                val bpr = (outW - ox) / 8; val bpc = (outH - oy) / 8
                if (bpr <= 0 || bpc <= 0) continue
                val p = IntArray(32)
                p[11] = ox; p[12] = oy; p[13] = outW; p[14] = outH; p[15] = bpr
                p[7] = java.lang.Float.floatToRawIntBits(twoBand.grainFloor.toFloat())
                p[8] = java.lang.Float.floatToRawIntBits(kotlin.math.sqrt(twoBand.deconvStrength.coerceAtLeast(0.0) *
                    twoBand.sigmaHigh * twoBand.sigmaHigh + twoBand.opticsSigma * twoBand.opticsSigma).toFloat())
                p[9] = java.lang.Float.floatToRawIntBits(config.scale)
                p[16] = java.lang.Float.floatToRawIntBits(twoBand.gainCap.toFloat())
                // One workgroup per block; > 65535 blocks run as several row bands.
                var first = 0
                while (first < bpc) {
                    val rows = minOf(bpc - first, maxOf(1, 65535 / bpr))
                    p[12] = oy + 8 * first
                    checkRc("tbDct", SrpBridge.nativeDispatchRes(handle, spirvs.twoBandDct!!, longArrayOf(denRes, tbAuxRes),
                        longArrayOf(total.toLong(), 4L * outW * outH), p, 64, rows * bpr))
                    first += rows
                }
            }
            checkRc("tbResolve2", SrpBridge.dispatchRes(handle, spirvs.twoBandResolve, res, sizes, push(2), (outW * outH).toLong()))
        } else
        timed("divide") { checkRc("divide", SrpBridge.divideRes(handle, numRes, denRes, spirvs.divide, total)) }
        // Noise evening: num (divided) -> den (free after the divide).
        val evenRes = if (evening && evenFrames > 1 && evenSumG2 > 0.0) {
            val push = IntArray(32)
            push[0] = outW; push[1] = outH; push[2] = outW * outH
            push[6] = java.lang.Float.floatToRawIntBits(config.scale)
            push[11] = gw; push[12] = gh
            push[13] = java.lang.Float.floatToRawIntBits((evenSumG * evenSumG / evenSumG2).toFloat())
            push[14] = java.lang.Float.floatToRawIntBits(evenFrames.toFloat())
            push[15] = java.lang.Float.floatToRawIntBits((config.kernelAlpha[1] + config.kernelAlpha[3]) / 2f)
            push[16] = java.lang.Float.floatToRawIntBits((config.kernelBeta[1] + config.kernelBeta[3]) / 2f)
            push[17] = kotlin.math.round(2f * config.scale).toInt().coerceIn(2, 6)
            push[18] = java.lang.Float.floatToRawIntBits(NOISE_EVEN_RANGE)
            timed("noiseEven") { checkRc("noiseEven", SrpBridge.dispatchRes(handle, spirvs.noiseEven!!,
                longArrayOf(numRes, denRes, evenAccRes), longArrayOf(total.toLong(), total.toLong(), 4L * gw * gh),
                push, (outW * outH).toLong())) }
            denRes
        } else numRes
        greenFit?.solveResident(evenRes, if (evenRes == numRes) denRes else numRes,
            config.kernelAlpha[1], config.kernelBeta[1], pedestal, requireNotNull(config.colourDiff))
        val outBuf = output?.also {
            require(it.capacity() >= total * java.lang.Float.BYTES) { "VulkanPipeline: output buffer too small" }
            it.clear()
        } ?: SrpBridge.directFloats(total)
        timed("downloadRes") { checkRc("downloadRes", SrpBridge.downloadResident(handle, evenRes, outBuf, total.toLong())) }
        outBuf.rewind()
        // Lateral CA: fit on the merged (co-sited) colour-difference output;
        // warp on the GPU copy and download again only when it is visible.
        if (config.lateralCa && config.bayer && config.colourDiff != null && spirvs.caWarp != null) {
            val ca = timed("lateralCaFit") { LateralCa.estimate(outBuf.asFloatBuffer(), outW, outH, config.scale) }
            val shift = ca.cornerShift(outW, outH)
            println("sr-pipeline lateralCa kR=%.6f kB=%.6f nccR=%.3f nccB=%.3f points=%d cornerShift=%.2fpx".format(
                ca.kR, ca.kB, ca.nccR, ca.nccB, ca.points, shift))
            if (shift >= LateralCa.MIN_CORNER_SHIFT_PX) {
            val dst = if (evenRes == numRes) denRes else numRes
            val push = IntArray(32)
            push[0] = outW; push[1] = outH; push[2] = outW * outH
            push[6] = java.lang.Float.floatToRawIntBits(config.scale)
            push[7] = java.lang.Float.floatToRawIntBits(ca.kR)
            push[8] = java.lang.Float.floatToRawIntBits(ca.kB)
            push[11] = w; push[12] = h
            timed("caWarp") { checkRc("caWarp", SrpBridge.dispatchRes(handle, spirvs.caWarp!!,
                longArrayOf(evenRes, dst), longArrayOf(total.toLong(), total.toLong()), push, (outW * outH).toLong())) }
            outBuf.clear()
            timed("downloadRes") { checkRc("downloadRes", SrpBridge.downloadResident(handle, dst, outBuf, total.toLong())) }
            outBuf.rewind()
            }
        }
        config.colourDiff?.takeIf { config.bayer }?.let { cd ->
            timed("colourDiffReconstruct") { ColourDiff.reconstruct(outBuf.asFloatBuffer(), total / 3, cd[0], cd[1]) }
            outBuf.rewind()
            if (pedestal > 0f) {
                timed("blackPedestal") { BlackPedestal.apply(outBuf.asFloatBuffer(), total, pedestal) }
                outBuf.rewind()
            }
        }
        dumpClock()
        return outBuf
        } finally {
            AlignGpu.freeLevelSpectra(pinnedInit, handle)
            SrpBridge.freeResident(handle, meansRes)
            SrpBridge.freeResident(handle, varsRes)
            SrpBridge.freeResident(handle, numRes)
            SrpBridge.freeResident(handle, denRes)
            if (evenRRes != 0L) SrpBridge.freeResident(handle, evenRRes)
            if (tbAuxRes != 0L) SrpBridge.freeResident(handle, tbAuxRes)
            if (tbRobRes != 0L) SrpBridge.freeResident(handle, tbRobRes)
            if (tbRobTmp != 0L) SrpBridge.freeResident(handle, tbRobTmp)
            if (tbFlowRes != 0L) SrpBridge.freeResident(handle, tbFlowRes)
            if (evenAccRes != 0L) SrpBridge.freeResident(handle, evenAccRes)
            SrpBridge.freeResident(handle, frameRes)
            if (kernPreRes != 0L) SrpBridge.freeResident(handle, kernPreRes)
            if (kernGreyRes != 0L) SrpBridge.freeResident(handle, kernGreyRes)
            if (kernGradsRes != 0L) SrpBridge.freeResident(handle, kernGradsRes)
            if (kernCovRes != 0L) SrpBridge.freeResident(handle, kernCovRes)
            if (polynomialLutRes != 0L) SrpBridge.freeResident(handle, polynomialLutRes)
            if (hfRes != 0L) SrpBridge.freeResident(handle, hfRes)
            SrpBridge.freeResident(handle, sRes)
        }
    }
}
