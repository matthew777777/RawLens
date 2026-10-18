package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.SrpBridge
import java.nio.ByteBuffer

/**
 * Vulkan-backend burst pipeline: the same Alg.1 as [EndToEnd.run], with
 * every proven stage dispatched on-device and Kotlin doing only packing,
 * buffering, and orchestration (the [AlignGpu] precedent).
 *
 * Per frame the sequence is: grey (CPU, FFT lowpass has no entry) ->
 * [AlignGpu] loop -> [SrpBridge.smap] + [SrpBridge.robustCompute] ->
 * GAT/decimate/kerngrad/[SrpBridge.kernelsCov] -> [SrpBridge.mergeSplat]
 * into the resident host num/den pair -> [SrpBridge.divide]. Reference
 * stats ([SrpBridge.robustInit]) and kernels are built once up front.
 * Covariance border semantics are the port's (OOB-skipped reads on the
 * grey grid), which can differ from [EndToEnd.frameKernels]' partial
 * windows on the outermost rim; the end-to-end gate is interior-quantile.
 *
 * @param reference reference raw frame `[H][W]` (`J_1`).
 * @param comparisons remaining burst frames (`J_2..J_N`) as decode
 *   thunks, consumed once in order: each frame is decoded when its turn
 *   comes and released after merging, so only the reference plus one
 *   comparison are ever live (same bytes and order as an upfront list).
 * @param config pipeline config (tile sizes, kernel/robustness params,
 * curves; cf. [EndToEnd.run]).
 * @param handle native context from `SrpBridge.nativeCreate`.
 * @param alignSpirvs align-loop modules; [spirvs] the remaining stage
 * modules.
 * @return merged RGB image as flat rows (`[H*scale]` rows of
 * `[W*scale*3]` floats) after `divide`: same values as the CPU
 * `[H][W][3]` nesting, without its 12.5M per-pixel arrays (~400MB
 * transient at full-res).
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
    )

    private fun pack(data: FloatArray): ByteBuffer {
        val b = SrpBridge.directFloats(data.size)
        b.asFloatBuffer().put(data)
        b.rewind()
        return b
    }

    private fun packImage(img: Array<FloatArray>): ByteBuffer {
        val h = img.size
        val w = img[0].size
        val b = SrpBridge.directFloats(h * w)
        val fb = b.asFloatBuffer()
        for (y in 0 until h) fb.put(img[y])
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

    /** Stage clock, enabled with `-Dsrpipeline.profile=true`. */
    private val profile = System.getProperty("srpipeline.profile", "false") == "true"
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

    fun run(
        reference: Array<FloatArray>,
        comparisons: List<() -> Array<FloatArray>>,
        config: PipelineConfig = PipelineConfig(),
        handle: Long,
        alignSpirvs: AlignGpu.Spirvs,
        spirvs: Spirvs,
    ): Array<FloatArray> {
        require(handle != 0L) { "VulkanPipeline: null context handle" }
        val h = reference.size
        val w = reference[0].size
        val tile = config.tileSizes.first()
        val loop = EndToEnd.loopConfig(config)
        val outH = (h * config.scale).toInt()
        val outW = (w * config.scale).toInt()

        // Grey: on-device FFT lowpass when the config selects it (the
        // entry is proven against the Python complex64 reference);
        // anything else stays on the CPU [Grey] path.
        fun greyOf(frame: Array<FloatArray>): Array<FloatArray> {
            if (!config.bayer || config.greyMethod != "FFT") {
                return if (config.bayer) Grey.computeGreyImages(frame, config.greyMethod) else frame
            }
            val h = frame.size
            val w = frame[0].size
            val out = SrpBridge.directFloats(h * w)
            timed("greyFft") { checkRc("greyFft", SrpBridge.greyFft(handle, packImage(frame), out,
                spirvs.fft, w, h)) }
            out.rewind()
            val flat = FloatArray(h * w)
            out.asFloatBuffer().get(flat)
            return Array(h) { y -> FloatArray(w) { x -> flat[y * w + x] } }
        }

        // 1. Reference grey + GPU alignment initialisation (grey inlined:
        // the 50MB ref grey is dead after init, like the unpinned init's
        // ~320MB of host copies, which die with the pin call; the pinned
        // copy carries stripped arrays, see pinLevelResidents).
        // 1b. Pin level residents on-device (L2 spectra + statics, one
        // upload per run, reused across frames); freed in the run
        // finally below.
        val pinnedInit = timed("alignPin") {
            AlignGpu.pinLevelResidents(
                timed("alignInit") { AlignGpu.initGpu(greyOf(reference), loop, handle, alignSpirvs.ica) },
                loop, handle, alignSpirvs)
        }

        // 2. Reference local stats (bayer 3-channel guide grid).
        val gw = w / 2
        val gh = h / 2
        val meansBuf = SrpBridge.directFloats(3 * gw * gh)
        val varsBuf = SrpBridge.directFloats(3 * gw * gh)
        if (config.robustnessEnabled) {
            timed("robustInit") { checkRc("robustInit", SrpBridge.robustInit(handle, packImage(reference),
                meansBuf, varsBuf, spirvs.robust, w, h)) }
        }

        // Shared LUT + GAT profile buffers (rewound per use).
        val lutBuf = pack(config.sigmaSqCurve + config.dSqCurve)
        val lutN = config.sigmaSqCurve.size
        val abBuf = pack(config.kernelAlpha + config.kernelBeta)

        // Run-level reusable host scratch (all shapes fixed per run):
        // kills the per-frame garbage (pack buffers, grey flat/rows,
        // kernel transients, merge/robust vectors) that otherwise peaks
        // the Java heap on full-res bursts. Every buffer is fully
        // rewritten per use; entries fence-wait, so sequential reuse is
        // aliasing-safe.
        val finest0 = pinnedInit.levels.last()
        val ftx0 = finest0.nTx
        val fty0 = finest0.nTy
        val rSize0 = if (config.bayer) gw * gh else w * h
        val frameBuf = SrpBridge.directFloats(w * h)
        val greyOut = SrpBridge.directFloats(w * h)
        val greyFlat = FloatArray(w * h)
        val greyRows = Array(h) { FloatArray(w) }
        val kernPre = SrpBridge.directFloats(w * h)
        val kernGrey = SrpBridge.directFloats((w / 2) * (h / 2))
        val kernGrads = SrpBridge.directFloats((w / 2 - 1) * (h / 2 - 1) * 2)
        val kernCov = SrpBridge.directFloats((w / 2) * (h / 2) * 4)
        val kp = config.kernelParams
        val kpBuf = pack(floatArrayOf(kp.kDetail, kp.kDenoise, kp.dTh, kp.dTr,
            kp.kStretch, kp.kShrink,
            java.lang.Float.intBitsToFloat(if (kp.selection == SelectionLaw.HARD_THRESHOLD) 0 else 1),
            java.lang.Float.intBitsToFloat(if (kp.iso) 1 else 0)))
        val mergeFlowFlat = FloatArray(ftx0 * fty0 * 2)
        val mergeFlow = SrpBridge.directFloats(ftx0 * fty0 * 2)
        val mergeR = FloatArray(rSize0)
        val mergeRDirect = SrpBridge.directFloats(rSize0)
        val smapOut = SrpBridge.directFloats(ftx0 * fty0)
        val robustR = SrpBridge.directFloats(gw * gh)

        /** Grey of `frame` into reusable buffers (same bytes as [greyOf]). */
        fun greyOfInto(
            frame: Array<FloatArray>,
            out: ByteBuffer,
            flat: FloatArray,
            rows: Array<FloatArray>,
        ): Array<FloatArray> {
            if (!config.bayer || config.greyMethod != "FFT") {
                return if (config.bayer) Grey.computeGreyImages(frame, config.greyMethod) else frame
            }
            timed("greyFft") { checkRc("greyFft", SrpBridge.greyFft(handle, packImageInto(frame, frameBuf), out,
                spirvs.fft, w, h)) }
            out.rewind()
            out.asFloatBuffer().get(flat)
            for (y in 0 until h) flat.copyInto(rows[y], 0, y * w, y * w + w)
            return rows
        }

        // 3. Per-frame kernels via entries (GAT -> decimate -> kerngrad ->
        // cov on the grey grid), cf. `EndToEnd.frameKernels` + `estimate_kernels`.
        fun frameKernelsGpu(frame: Array<FloatArray>): ByteBuffer {
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

        // 4. Accumulators (device-resident across frames: no host
        // round-trip per frame — download once after divide).
        val total = outH * outW * 3
        val numRes = SrpBridge.createResident(handle, total.toLong())
        require(numRes != 0L) { "VulkanPipeline: resident num alloc failed" }
        val denRes = SrpBridge.createResident(handle, total.toLong())
        require(denRes != 0L) { "VulkanPipeline: resident den alloc failed" }
        try {
        fun mergeFrame(frame: Array<FloatArray>, flow: Array<Array<FloatArray>>, rFlat: FloatArray) {
            val cov = frameKernelsGpu(frame)
            val ftx = flow[0].size
            val fty = flow.size
            require(ftx == ftx0 && fty == fty0) { "flow grid changed mid-run" }
            for (y in 0 until fty) for (x in 0 until ftx) {
                mergeFlowFlat[(y * ftx + x) * 2] = flow[y][x][0]
                mergeFlowFlat[(y * ftx + x) * 2 + 1] = flow[y][x][1]
            }
            timed("mergeSplat") { checkRc("mergeSplat", SrpBridge.mergeSplatRes(handle, packImageInto(frame, frameBuf),
                packInto(mergeFlowFlat, mergeFlow), cov, packInto(rFlat, mergeRDirect), numRes, denRes,
                spirvs.mergeSplat, w, h, outW, outH, tile,
                config.scale, config.bayer, ftx, fty)) }
        }

        // 5. Reference merge (zero flow on the finest tile grid, r = 1
        // over the guide grid).
        val finest = pinnedInit.levels.last()
        val zeroFlow = Array(finest.nTy) { Array(finest.nTx) { floatArrayOf(0f, 0f) } }
        val rSize = if (config.bayer) gw * gh else w * h
        mergeFrame(reference, zeroFlow, FloatArray(rSize) { 1f })

        // 6. Per comparison frame (decoded just in time, released after
        // merging — only one comparison is ever live; a prefetch worker
        // was tried and reverted: it cost wall time to CPU contention
        // and broke -Xmx512m survival with its extra live frame).
        for (frameThunk in comparisons) {
            val frame = frameThunk()
            val frameGrey = greyOfInto(frame, greyOut, greyFlat, greyRows)
            val flow = timed("alignLoop") { AlignGpu.alignLevelsGpu(pinnedInit, frameGrey, loop, handle, alignSpirvs) }
            val ftx = flow[0].size
            val fty = flow.size
            require(ftx == ftx0 && fty == fty0) { "flow grid changed mid-run" }
            val r: FloatArray = if (config.robustnessEnabled) {
                for (y in 0 until fty) for (x in 0 until ftx) {
                    mergeFlowFlat[(y * ftx + x) * 2] = flow[y][x][0]
                    mergeFlowFlat[(y * ftx + x) * 2 + 1] = flow[y][x][1]
                }
                val rp = config.robustnessParams
                timed("smap") { checkRc("smap", SrpBridge.smap(handle, packInto(mergeFlowFlat, mergeFlow), smapOut.apply { rewind() },
                    spirvs.robust, ftx, fty, rp.mt, rp.s1, rp.s2)) }
                smapOut.rewind()
                timed("robustCompute") { checkRc("robustCompute", SrpBridge.robustCompute(handle, packImageInto(frame, frameBuf),
                    meansBuf.apply { rewind() }, varsBuf.apply { rewind() },
                    packInto(mergeFlowFlat, mergeFlow), lutBuf.apply { rewind() }, smapOut, robustR.apply { rewind() },
                    spirvs.robust, w, h, tile, lutN,
                    config.robustnessParams.noiseCorrection,
                    config.robustnessParams.t, ftx, fty)) }
                robustR.rewind()
                robustR.asFloatBuffer().get(mergeR)
                mergeR
            } else {
                mergeR.fill(1f)
                mergeR
            }
            mergeFrame(frame, flow, r)
        }

        // 7. Normalise in place (on-device), download once.
        timed("divide") { checkRc("divide", SrpBridge.divideRes(handle, numRes, denRes, spirvs.divide, total)) }
        val outBuf = SrpBridge.directFloats(total)
        timed("downloadRes") { checkRc("downloadRes", SrpBridge.downloadResident(handle, numRes, outBuf, total.toLong())) }
        outBuf.rewind()
        // In-place divide ran num=num/den on-device; den stays resident.
        // The returned image bulk-reads the single downloaded num buffer
        // straight into its rows: 150MB + row objects, no per-pixel
        // arrays, no flat copy.
        val fb = outBuf.asFloatBuffer()
        val image = Array(outH) { FloatArray(outW * 3) }
        for (y in 0 until outH) fb.get(image[y])
        dumpClock()
        return image
        } finally {
            AlignGpu.freeLevelSpectra(pinnedInit, handle)
            SrpBridge.freeResident(handle, numRes)
            SrpBridge.freeResident(handle, denRes)
        }
    }
}
