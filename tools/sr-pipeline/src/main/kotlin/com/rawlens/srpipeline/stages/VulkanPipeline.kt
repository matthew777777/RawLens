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

    /** Stage modules beyond the align loop's own [AlignGpu.Spirvs]. */
    data class Spirvs(
        val gat: ByteArray,
        val grad: ByteArray,
        val kernels: ByteArray,
        val divide: ByteArray,
        val robust: ByteArray,
        val mergeSplat: ByteArray,
        /** fft.comp for on-device FFT lowpass grey (cf. [SrpBridge.greyFft]). */
        val fft: ByteArray,
        /** corr.comp for the resident grey interleave (cf. [SrpBridge.nativeGreyFftRes]). */
        val corr: ByteArray,
        /** kernelnet.comp, required when [PipelineConfig.kernelNet] is set. */
        val kernelNet: ByteArray? = null,
        /**
         * grey_fft.comp: mixed-radix FFT grey (true lengths, no Bluestein
         * padding or host staging). Null keeps the Bluestein chain.
         */
        val greyFft: ByteArray? = null,
    )

    /** Last KernelNet (s1, s2, rho) map of this process, for parity dumps: 3 x mapH x mapW. */
    @Volatile var lastKernelNetMap: FloatArray? = null

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
    ): ByteBuffer = SrpBridge.NativeFloats().use { scratch ->
        runScoped(reference, comparisons, config, handle, alignSpirvs, spirvs, output, scratch)
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
    ): ByteBuffer {
        require(handle != 0L) { "VulkanPipeline: null context handle" }
        // Decode the reference first: its shape sizes all run scratch.
        // The box is nulled once the ref phase below is done, so the
        // 50 MB frame dies before the comparison loop on callers that
        // release their own copy in the thunk.
        var refBox: Array<FloatArray>? = reference()
        heapLog("ref-decoded")
        val h = refBox!!.size
        val w = refBox!![0].size
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
        val kernPreRes = SrpBridge.createResident(handle, (w * h).toLong())
        require(kernPreRes != 0L) { "VulkanPipeline: resident kernPre alloc failed" }
        val kernGreyRes = SrpBridge.createResident(handle, (gw * gh).toLong())
        require(kernGreyRes != 0L) { "VulkanPipeline: resident kernGrey alloc failed" }
        val kernGradsRes = SrpBridge.createResident(handle, ((gw - 1) * (gh - 1) * 2).toLong())
        require(kernGradsRes != 0L) { "VulkanPipeline: resident kernGrads alloc failed" }
        val kernCovRes = SrpBridge.createResident(handle, (gw * gh * 4).toLong())
        require(kernCovRes != 0L) { "VulkanPipeline: resident kernCov alloc failed" }
        // KernelNet covariances (one map for the whole burst), 0 = Wronski.
        var knCovRes = 0L
        // 1. Ref phase (own scope): grey rows, init host temporaries
        // and the decoded frame all die here, before the loop. The ref
        // grey packs through a temp (dies with the block): the shared
        // pack staging is only allocated after the pin below, so it is
        // not live during the ~300 MB init window.
        val pinnedInit = run {
            // 1a. Init (own scope): the 50 MB grey rows die here, so
            // they are not live during the pin uploads below (the pin
            // needs the level arrays, never the rows). Both grey
            // staging buffers are one-shot temps (dead with the block):
            // the shared pack staging is allocated post-pin, and the
            // loop reuses the kernel scratch as grey output (see below).
            val unpinned = run {
                val rows = Array(h) { FloatArray(w) }
                val refGrey = greyOfInto(refBox!!, scratch.floats(w * h),
                    scratch.floats(w * h), rows)
                timed("alignInit") { AlignGpu.initGpu(refGrey, loop, handle, alignSpirvs.ica) }
            }
            // 1b. Pin level residents on-device (L2 spectra + statics,
            // one upload per run, reused across frames); freed in the
            // run finally below. The unpinned init's ~320MB of host
            // copies die with the pin call; the pinned copy carries
            // stripped arrays, see pinLevelResidents.
            val pinned = timed("alignPin") {
                AlignGpu.pinLevelResidents(unpinned, loop, handle, alignSpirvs)
            }
            pinned
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
        val kpBuf = pack(floatArrayOf(kp.kDetail, kp.kDenoise, kp.dTh, kp.dTr,
            kp.kStretch, kp.kShrink,
            java.lang.Float.intBitsToFloat(if (kp.selection == SelectionLaw.HARD_THRESHOLD) 0 else 1),
            java.lang.Float.intBitsToFloat(if (kp.iso) 1 else 0)))
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
        fun mergeFrame(frame: Array<FloatArray>, flow: Array<Array<FloatArray>>, rBuf: ByteBuffer) {
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
            } else {
                // KernelNet: the one reference map (computed below, before
                // the ref merge) replaces the per-frame kernel chain; mode
                // bit 1 makes the merge read it on the reference grid.
                val covRes = if (knCovRes != 0L) knCovRes else frameKernelsGpuRes()
                val mergeMode = if (knCovRes != 0L) 2 else 0
                timed("mergeSplat") { checkRc("mergeSplat", SrpBridge.nativeMergeSplatFrameRes(handle, frameRes,
                    packInto(mergeFlowFlat, mergeFlow), covRes, rBuf, numRes, denRes,
                    spirvs.mergeSplat, w, h, outW, outH, gw * gh * 4, gw * gh, tile,
                    config.scale, mergeMode, ftx, fty)) }
            }
        }

        // 4. Reference merge (zero flow on the finest tile grid, r = 1
        // over the guide grid), then release the decoded frame: it is
        // dead before the comparison loop below. The bayer legs read
        // the frame from its resident, so upload it once here (the
        // loop frames upload in the grey leg instead).
        val finest = pinnedInit.levels.last()
        val zeroFlow = Array(finest.nTy) { Array(finest.nTx) { floatArrayOf(0f, 0f) } }
        if (config.bayer) {
            timed("refUpload") { checkRc("refUpload", SrpBridge.uploadResident(handle, frameRes,
                packImageInto(refBox!!, frameBuf), (w * h).toLong())) }
        }
        config.kernelNet?.let { kn ->
            require(config.bayer) { "KernelNet kernels need the bayer path" }
            val knSpirv = requireNotNull(spirvs.kernelNet) { "KernelNet enabled without kernelnet.spv" }
            val full = kn.fullReference
            val rawW = full?.get(0)?.size ?: w
            val rawH = full?.size ?: h
            val (mw, mh) = KernelNet.mapSize(rawW, rawH)
            val temps = ArrayList<Long>()
            fun res(n: Int): Long = SrpBridge.createResident(handle, n.toLong()).also {
                require(it != 0L) { "VulkanPipeline: KernelNet resident alloc failed" }
                temps.add(it)
            }
            try {
                val rawRes = if (full == null) frameRes else res(rawW * rawH).also { r ->
                    val b = scratch.floats(rawW * rawH)
                    val fb = b.asFloatBuffer(); for (row in full) fb.put(row)
                    checkRc("kernelNet upload", SrpBridge.uploadResident(handle, r, b.apply { rewind() }, (rawW * rawH).toLong()))
                }
                val wRes = res(kn.weights.size)
                checkRc("kernelNet weights", SrpBridge.uploadResident(handle, wRes, pack(kn.weights), kn.weights.size.toLong()))
                val mapRes = res(3 * mw * mh)
                knCovRes = SrpBridge.createResident(handle, (gw * gh * 4).toLong())
                require(knCovRes != 0L) { "VulkanPipeline: KernelNet cov alloc failed" }
                timed("kernelNet") { checkRc("kernelNet", SrpBridge.nativeKernelNetCovRes(handle, rawRes, wRes,
                    kn.weights.size, knSpirv, rawW, rawH, kn.sigma, kn.sigmaScale, kn.sigmaCap,
                    kn.cropX, kn.cropY, gw, gh, knCovRes, mapRes,
                    if (kn.flipX) 1 else 0, if (kn.flipY) 1 else 0)) }
                val mapOut = SrpBridge.directFloats(3 * mw * mh)
                checkRc("kernelNet map", SrpBridge.downloadResident(handle, mapRes, mapOut, (3 * mw * mh).toLong()))
                lastKernelNetMap = FloatArray(3 * mw * mh).also { mapOut.asFloatBuffer().get(it) }
            } finally {
                for (t in temps) SrpBridge.freeResident(handle, t)
            }
        }
        mergeFrame(refBox!!, zeroFlow, packInto(onesR, mergeRDirect))
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
            val frameGrey = if (config.bayer) greyOfIntoRes(frame, loopRows)
                else greyOfInto(frame, frameBuf, kernPre, loopRows)
            val flow = timed("alignLoop") { AlignGpu.alignLevelsGpu(pinnedInit, frameGrey, loop, handle, alignSpirvs, pyramidReuse) }
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
                        if (config.robustnessParams.noiseCorrection) 1 else 0,
                        java.lang.Float.floatToIntBits(config.robustnessParams.t), ftx, fty,
                        hfRes, java.lang.Float.floatToIntBits(rp.s1))) }
                }
                mergeRDirect
            } else {
                packInto(onesR, mergeRDirect)
            }
            mergeFrame(frame, flow, rBuf)
        }
        for (frameThunk in comparisons) {
            frameStep(frameThunk)
            // Memory-tight discipline (cf. `memoryTight`): everything
            // above is dead here; collect before the next frame so GC
            // timing cannot decide survival at the 512 MB ceiling.
            if (config.memoryTight) System.gc()
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
        timed("divide") { checkRc("divide", SrpBridge.divideRes(handle, numRes, denRes, spirvs.divide, total)) }
        val outBuf = output?.also {
            require(it.capacity() >= total * java.lang.Float.BYTES) { "VulkanPipeline: output buffer too small" }
            it.clear()
        } ?: SrpBridge.directFloats(total)
        timed("downloadRes") { checkRc("downloadRes", SrpBridge.downloadResident(handle, numRes, outBuf, total.toLong())) }
        outBuf.rewind()
        dumpClock()
        return outBuf
        } finally {
            AlignGpu.freeLevelSpectra(pinnedInit, handle)
            SrpBridge.freeResident(handle, meansRes)
            SrpBridge.freeResident(handle, varsRes)
            SrpBridge.freeResident(handle, numRes)
            SrpBridge.freeResident(handle, denRes)
            SrpBridge.freeResident(handle, frameRes)
            SrpBridge.freeResident(handle, kernPreRes)
            SrpBridge.freeResident(handle, kernGreyRes)
            SrpBridge.freeResident(handle, kernGradsRes)
            SrpBridge.freeResident(handle, kernCovRes)
            if (knCovRes != 0L) SrpBridge.freeResident(handle, knCovRes)
            if (hfRes != 0L) SrpBridge.freeResident(handle, hfRes)
            SrpBridge.freeResident(handle, sRes)
        }
    }
}
