package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.SrpBridge
import com.rawlens.srpipeline.SrpBridge.SAMPLED
import com.rawlens.srpipeline.SrpBridge.STORAGE
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Mobile path of [VulkanPipeline.run] for the app recipe (1 context, Bayer,
 * colour differences, FIR grey, Wronski kernels): same stages and formulas,
 * reorganised for Mali and Adreno.
 *
 * - Everything after alignment is recorded into one command buffer per frame
 *   and submitted without waiting; a worker decodes the next frame straight
 *   into that frame's mapped upload buffer meanwhile (two frame slots).
 * - Robustness, kernels, colour differences, the green fit and noise evening
 *   read each other's device buffers: no readback or re-upload.
 * - Per-frame inputs live in images: the texture unit serves the gathers, which
 *   otherwise bind the load/store unit on Mali (merge 43 -> 6 cycles per pixel).
 * - Scratch is aliased between stages whose lifetimes do not overlap; batches
 *   run in order with a barrier before every dispatch.
 */
internal class MobileMerge(
    private val handle: Long,
    private val config: PipelineConfig,
    private val spirvs: VulkanPipeline.Spirvs,
    private val mobile: VulkanPipeline.MobileSpirvs,
    private val greenFit: GreenNormalTile?,
    private val w: Int,
    private val h: Int,
    private val frames: Int,
) : AutoCloseable {
    companion object {
        /** Set `-Dsrpipeline.mobile=false` for the staged path, `srpipeline.sync=true` to fence every dispatch. */
        private val enabled get() = System.getProperty("srpipeline.mobile", "true") == "true"

        fun supports(c: PipelineConfig, s: VulkanPipeline.Spirvs): Boolean =
            enabled && s.mobile != null && s.greyFir != null && s.pyrDown != null && c.bayer && c.colourDiff != null &&
                c.twoBand == null && !c.mobilePolynomial && c.continuousMotion && c.detailReconstruction &&
                c.robustnessEnabled && c.kernelPreprocessing == "gat" && c.frameEqualize == 0f &&
                c.alignmentPreprocessing == null && c.factors.first() == 1 && c.greyMethod.startsWith("FIR") &&
                c.run { !alignV2 && flowUpscaleMode == "bilinear" && edgeExtendFlow &&
                    EndToEnd.loopConfig(this).let { l -> l.metrics.indices.all { i ->
                        l.searchRadii[i] <= (if (l.metrics[i] == BlockMetric.L1) 1 else 4) && l.tileSizes[i] % (if (l.metrics[i] == BlockMetric.L1) 4 else 2) == 0 } } } &&
                c.kernelParams.selection != SelectionLaw.PAPER && !c.kernelParams.paperAnisotropy
    }

    private val sync = System.getProperty("srpipeline.sync", "false") == "true"
    private val profile = System.getProperty("srpipeline.profile", "false") == "true"
    private val clock = LinkedHashMap<String, Long>()
    private val owned = ArrayList<Long>()
    private val mapped = ArrayList<Long>()
    private val images = ArrayList<Long>()

    private fun <T> timed(what: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try { return block() } finally { synchronized(clock) { clock[what] = (clock[what] ?: 0L) + System.nanoTime() - t0 } }
    }
    private fun ok(what: String, rc: Int) = require(rc == 0) { "MobileMerge: $what failed with status $rc" }
    private fun own(r: Long): Long { require(r != 0L) { "MobileMerge: buffer allocation failed" }; owned.add(r); return r }
    private fun res(n: Long) = own(SrpBridge.createResident(handle, n))
    private fun host(n: Long) = own(SrpBridge.nativeCreateHostResident(handle, n))
    private fun mapOf(r: Long, n: Long): ByteBuffer {
        val b = requireNotNull(SrpBridge.nativeMapResident(handle, r, n)) { "MobileMerge: map failed" }
        mapped.add(r)
        return b.order(ByteOrder.nativeOrder())
    }

    private val scale = config.scale
    private val outW = (w * scale).toInt()
    private val outH = (h * scale).toInt()
    private val px = w.toLong() * h
    private val gw = w / 2
    private val gh = h / 2
    private val n = gw.toLong() * gh
    private val outPx = outW.toLong() * outH
    private val total = 3 * outPx
    private val tile = config.tileSizes.first()
    private val loop = EndToEnd.loopConfig(config)
    private val wb = config.colourDiff!!
    private val rp = config.robustnessParams

    private val frameRes = LongArray(2) { res(px) }
    private val frameMap = Array(2) { mapOf(frameRes[it], px) }
    private fun image(iw: Int, ih: Int, channels: Int) = SrpBridge.nativeCreateImage(handle, iw, ih, channels).also {
        require(it != 0L) { "MobileMerge: image allocation failed" }; images.add(it) }
    private fun padded(v: Int) = (v + loop.tileSize - 1) / loop.tileSize * loop.tileSize
    /** Coarse-first level shapes (rows, cols) of the circularly padded frame. */
    private val shapes = Resample.pyramidShapes(padded(h), padded(w), loop.factors, loop.binomialPyramid)
    private val levels = loop.factors.size
    /** Raw image of the frame being merged and of the next one, already aligning. */
    private val rawImg = LongArray(2) { image(w, h, 1) }
    /** Colour differences; before that the FIR grey and pyramid temporary. */
    private val cdImg = image(padded(w), padded(h), 1)
    private val refLevels = shapes.map { (lh, lw) -> image(lw, lh, 1) }
    private val movLevels = shapes.map { (lh, lw) -> image(lw, lh, 1) }
    private fun tileOf(p: Int) = loop.tileSizes[levels - 1 - p]
    private fun tilesX(p: Int) = shapes[p].second / tileOf(p)
    private fun tilesY(p: Int) = shapes[p].first / tileOf(p)
    private val ftx = tilesX(levels - 1)
    private val fty = tilesY(levels - 1)
    private val flowN = 2L * ftx * fty
    private val hessRes = LongArray(levels) { res(4L * tilesX(it) * tilesY(it)) }
    /** Flow of the coarser levels; the finest is [flowRes]. */
    private val coarseFlow = LongArray(levels - 1) { res(2L * tilesX(it) * tilesY(it)) }
    private val tieRes = res((0 until levels).maxOf { tilesX(it).toLong() * tilesY(it) })
    private val flowRes = LongArray(2) { res(flowN) }
    private val flowMap = Array(2) { mapOf(flowRes[it], flowN) }
    private val fieldImg = image(ftx, fty, 4)
    /** Green at every sensor site (directional fill); during alignment the finest moving level. */
    private val greenImg = movLevels.last()
    /** Kernel covariances; before that the frame's local means. */
    private val covImg = image(gw, gh, 4)
    private val refImg = image(gw, gh, 4)
    private val kernGrey = image(gw, gh, 1)
    private val rTmp = image(gw, gh, 1)
    private val ghost = image(gw, gh, 1)
    private val work = image(gw, gh, 1)
    private val rImg = image(gw, gh, 1)
    /** S.3 map of the reference (unused without S.3). */
    private val hfImg = if (rp.s3) image(gw, gh, 1) else kernGrey
    private val numRes = host(total)
    private val denRes = host(total)
    private val evening = config.noiseEvening && spirvs.noiseEven != null
    private val evenAcc = if (evening) res(4 * n) else 0L
    private val lutN = config.sigmaSqCurve.size
    private val lutRes = upload(config.sigmaSqCurve + config.dSqCurve)
    private val abRes = upload(config.kernelAlpha + config.kernelBeta)
    private val kpRes = config.kernelParams.let { kp ->
        upload(floatArrayOf(kp.kDetail, kp.kDenoise, kp.dTh, kp.dTr, kp.kStretch, kp.kShrink,
            Float.fromBits(if (kp.selection == SelectionLaw.HARD_THRESHOLD) 0 else 1),
            Float.fromBits(if (kp.iso) 1 else 0), kp.kMin, kp.weakEdgeNoise, kp.flatSigma))
    }
    private val firRadius = when (config.greyMethod) { "FIR", "FIR7" -> 7; "FIR11" -> 11; else -> 15 }
    private val firTaps = Grey.halfBandTaps(firRadius).let { t ->
        FloatArray((firRadius + 1) / 2 + 1) { i -> if (i == 0) t[0] else t[2 * i - 1] }
    }
    private val greenWeights = LumaFrameWeights.forBurst(frames, config.grainRetention)
    private var evenSumG = 0.0
    private var evenSumG2 = 0.0

    private fun upload(v: FloatArray): Long {
        val r = res(v.size.toLong())
        val b = SrpBridge.directFloats(v.size)
        b.asFloatBuffer().put(v)
        ok("upload", SrpBridge.uploadResident(handle, r, b, v.size.toLong()))
        return r
    }

    /** One push word per (index, value); indices >= 11 are the shaders' pad words. */
    private fun push(vararg kv: Pair<Int, Any>) = IntArray(32).also { p ->
        for ((i, v) in kv) p[i] = if (v is Float) v.toRawBits() else v as Int
    }
    private fun run(what: String, spirv: ByteArray, b: LongArray, sizes: LongArray, push: IntArray, items: Long) {
        timed("gpu.$what") { ok(what, SrpBridge.dispatchRes(handle, spirv, b, sizes, push, items)) }
    }
    private fun begin(slot: Int) { if (!sync) ok("batch begin", SrpBridge.nativeBatchBegin(handle, slot)) }
    private fun submit() { if (!sync) ok("batch submit", SrpBridge.nativeBatchSubmit(handle)) }
    private fun waitFor(slot: Int) { if (!sync) ok("batch wait", SrpBridge.nativeBatchWait(handle, slot)) }

    private fun run2d(what: String, b: LongArray, sizes: LongArray, push: IntArray, gridW: Int, gridH: Int) {
        timed("gpu.$what") { ok(what, SrpBridge.dispatch2d(handle, mobile[what], b, sizes, push, gridW, gridH)) }
    }

    /** Raw image and finest grey level (with the aligner's circular padding) of frame [slot]. */
    private fun rawAndGrey(slot: Int, level0: Long) {
        run2d("raw_image", longArrayOf(frameRes[slot], rawImg[slot]), longArrayOf(px, STORAGE), push(0 to w, 1 to h), w, h)
        for (mode in 0..1) {
            val p = push(0 to w, 1 to h, 3 to padded(w), 4 to padded(h), 10 to mode, 11 to firRadius)
            for ((j, t) in firTaps.withIndex()) p[12 + j] = t.toRawBits()
            run2d("grey_fir_img", if (mode == 0) longArrayOf(rawImg[slot], cdImg) else longArrayOf(cdImg, level0),
                longArrayOf(SAMPLED, STORAGE), p, padded(w), padded(h))
        }
    }

    /** Grey and all pyramid levels of frame [slot] into [into] (coarse first). */
    private fun pyramid(slot: Int, into: List<Long>) {
        rawAndGrey(slot, into.last())
        for (p in levels - 2 downTo 0) {
            val f = loop.factors[levels - 1 - p]
            val (oh, ow) = shapes[p]
            if (f == 1) { run2d("pyr_img", longArrayOf(into[p + 1], into[p]), longArrayOf(SAMPLED, STORAGE),
                push(0 to ow, 1 to oh, 10 to 1, 17 to 1, 18 to 1, 19 to 1f), ow, oh); continue }
            val k = Resample.pyramidKernel(f, loop.binomialPyramid)
            for (mode in 1..2) {
                val width = if (mode == 1) shapes[p + 1].second else ow
                val c = push(0 to width, 1 to oh, 10 to mode, 17 to f, 18 to k.size)
                for (i in 0..k.size / 2) c[19 + i] = k[k.size / 2 + i].toRawBits()
                run2d("pyr_img", if (mode == 1) longArrayOf(into[p + 1], cdImg) else longArrayOf(cdImg, into[p]),
                    longArrayOf(SAMPLED, STORAGE), c, width, oh)
            }
        }
    }

    private fun tiles(what: String, b: LongArray, sizes: LongArray, push: IntArray, count: Int) = timed("gpu.$what") {
        ok(what, SrpBridge.nativeDispatchRes(handle, mobile[what], b, sizes, push, 64, minOf(count, 65535))) }

    /** Coarse-to-fine block matching and ICA (AlignGpu.alignLevelsGpuResident), all on the GPU. */
    private fun align(slot: Int) {
        for (p in 0 until levels) {
            val c = levels - 1 - p
            val (lh, lw) = shapes[p]
            val nx = tilesX(p); val ny = tilesY(p); val count = nx * ny; val ts = tileOf(p)
            val flow = if (c == 0) flowRes[slot] else coarseFlow[p]
            val flowSize = 2L * count
            if (p == 0) run2d("flow_up", longArrayOf(flow, flow), longArrayOf(flowSize, flowSize), push(0 to nx, 1 to ny, 10 to 1), nx, ny)
            else {
                val f = loop.factors[c + 1]
                run2d("flow_up", longArrayOf(coarseFlow[p - 1], flow), longArrayOf(2L * tilesX(p - 1) * tilesY(p - 1), flowSize),
                    push(0 to nx, 1 to ny, 7 to f.toFloat(), 11 to tilesX(p - 1), 12 to tilesY(p - 1),
                        13 to f / (ts / tileOf(p - 1))), nx, ny)
            }
            val l1 = loop.metrics[c] == BlockMetric.L1
            val radius = loop.searchRadii[c]
            val match = push(0 to lw, 1 to lh, 3 to count, 5 to ts, 12 to (if (l1) radius else nx), 13 to (if (l1) nx else radius))
            for (pass in if (l1) listOf("align_l1_img", "align_l1_tie") else listOf("align_l2_img", if (ts <= 32) "align_l2_tie" else "align_l2_tie64"))
                tiles(pass, longArrayOf(refLevels[p], movLevels[p], flow, tieRes),
                    longArrayOf(SAMPLED, SAMPLED, flowSize, count.toLong()), match, count)
            val clip = if (ts == 64 || !loop.icaClip) 32767f else radius.toFloat()
            tiles("ica_img", longArrayOf(refLevels[p], movLevels[p], hessRes[p], flow),
                longArrayOf(SAMPLED, SAMPLED, 4L * count, flowSize),
                push(0 to lw, 1 to lh, 3 to count, 5 to ts, 12 to nx, 13 to clip, 14 to loop.nIter), count)
        }
    }

    private fun window(src: Long, dst: Long, axis: Int, kind: Int, taps: Int, span: Int, ramp: Boolean = false, cap: Long = 0L, gain: Float = 1f) =
        run2d("rob_window", longArrayOf(src, dst, if (cap != 0L) cap else src), longArrayOf(SAMPLED, STORAGE, SAMPLED),
            push(0 to gw, 1 to gh, 7 to gain, 11 to axis, 12 to kind, 13 to taps, 14 to span,
                15 to (if (ramp) 1 else 0), 16 to (if (cap != 0L) 1 else 0)), gw, gh)

    private fun motionField(slot: Int) = run2d("motion_field", longArrayOf(flowRes[slot], fieldImg),
        longArrayOf(2L * ftx * fty, STORAGE), push(0 to ftx, 1 to fty, 11 to rp.mt, 12 to rp.s1, 13 to rp.s2), ftx, fty)

    private fun robustness(slot: Int, blur: Float) {
        run2d("rob_means", longArrayOf(rawImg[slot], covImg), longArrayOf(SAMPLED, STORAGE), push(0 to gw, 1 to gh), gw, gh)
        val noiseA = VulkanPipeline.GHOST_VISIBILITY * (config.kernelAlpha[1] + config.kernelAlpha[3]) / 2f / frames
        val noiseB = VulkanPipeline.GHOST_VISIBILITY * (config.kernelBeta[1] + config.kernelBeta[3]) / 2f / frames
        run2d("rob_test", longArrayOf(covImg, refImg, fieldImg, lutRes, hfImg, rTmp, ghost),
            longArrayOf(SAMPLED, SAMPLED, SAMPLED, 2L * lutN, SAMPLED, STORAGE, STORAGE),
            push(0 to gw, 1 to gh, 5 to tile / 2, 11 to lutN, 12 to rp.t, 13 to (if (rp.s3) 1 else 0), 14 to rp.s1,
                15 to (if (rp.noiseCorrection) 1 else 0), 16 to ftx, 17 to fty,
                18 to (if (config.ghostGrow) 1 else 0), 19 to noiseA, 20 to noiseB), gw, gh)
        if (config.ghostGrow) {
            window(ghost, work, 0, 0, 7, 3)
            window(work, ghost, 1, 0, 7, 3)
            // Max over +-dilate as a 7-tap pass plus taps at most 7 apart.
            val dilate = 3 + maxOf(1, tile / 2)
            val taps = maxOf(2, (2 * dilate + 1 + 6) / 7)
            for (axis in 0..1) {
                window(ghost, work, axis, 1, 7, 3, ramp = axis == 0)
                window(work, ghost, axis, 1, taps, dilate - 3)
            }
        }
        window(rTmp, work, 0, 2, 5, 2)
        window(work, rImg, 1, 2, 5, 2, cap = if (config.ghostGrow) ghost else 0L, gain = blur)
    }

    private fun merge(index: Int) {
        val raw = rawImg[index and 1]
        run2d("kern_pre", longArrayOf(raw, kernGrey, abRes), longArrayOf(SAMPLED, STORAGE, 8), push(0 to w, 1 to h), gw, gh)
        run2d("kern_cov", longArrayOf(kernGrey, covImg, kpRes), longArrayOf(SAMPLED, STORAGE, 11), push(0 to gw, 1 to gh), gw, gh)
        run2d("colour_diff", longArrayOf(raw, cdImg, greenImg), longArrayOf(SAMPLED, STORAGE, STORAGE), push(0 to w, 1 to h, 7 to wb[0], 8 to wb[1]), w, h)
        greenFit?.let { timed("gpu.greenFit") { it.recordImages(mobile["green_gather"], cdImg, fieldImg, rImg) } }
        val greenW = greenWeights[index]
        // A unit-weight reference keeps its own narrow green gather; the first frame initialises the accumulators.
        val ref = index == 0 && greenW == 1f
        run2d(if (ref) "merge_app_ref" else "merge_app",
            if (ref) longArrayOf(cdImg, cdImg, covImg, rImg, numRes, denRes, greenImg) else longArrayOf(cdImg, fieldImg, covImg, rImg, numRes, denRes, greenImg),
            longArrayOf(SAMPLED, SAMPLED, SAMPLED, SAMPLED, total, total, SAMPLED),
            push(0 to w, 1 to h, 5 to tile, 6 to scale, 7 to config.chromaScale, 8 to greenW, 10 to (if (index == 0) 1 else 0),
                12 to ftx, 13 to outW, 14 to outH), outW, outH)
        if (evening) {
            run2d("noise_acc_img", longArrayOf(rImg, evenAcc), longArrayOf(SAMPLED, 4 * n),
                push(0 to gw, 1 to gh, 8 to greenW, 10 to (if (index == 0) 1 else 0)), gw, gh)
            evenSumG += greenW; evenSumG2 += greenW.toDouble() * greenW
        }
    }

    private fun pack(rows: Array<FloatArray>, buf: ByteBuffer) {
        java.util.stream.IntStream.range(0, 4).parallel().forEach { part ->
            val fb = buf.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (y in h * part / 4 until h * (part + 1) / 4) { fb.position(y * w); fb.put(rows[y]) }
        }
    }

    private fun writeFloats(file: java.io.File, v: FloatArray) =
        file.writeBytes(ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putFloat(it) } }.array())

    private fun setFlow(slot: Int, flat: FloatArray) {
        flowMap[slot].duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().put(flat)
    }

    private class Ready(val blur: Float)

    fun run(reference: Array<FloatArray>, comparisons: List<(ByteBuffer) -> Array<FloatArray>>,
            staging: ByteBuffer, output: () -> ByteBuffer): ByteBuffer {
        val start = System.nanoTime()
        var refRows: Array<FloatArray>? = reference
        val sharpA = (config.kernelAlpha[1] + config.kernelAlpha[3]) / 2f
        val sharpB = (config.kernelBeta[1] + config.kernelBeta[3]) / 2f
        val pool = Executors.newFixedThreadPool(3)
        var result: ByteBuffer? = null
        try {
            val refFuture = refRows!!.let { r ->
                val ped = pool.submit(Callable { if (config.blackPedestal) timed("cpu.pedestal") { BlackPedestal.estimate(r, config.kernelAlpha, config.kernelBeta) } else null })
                val energy = pool.submit(Callable { if (config.blurGamma > 0f) timed("cpu.sharpness") { FrameSharpness.energy(r, sharpA, sharpB) } else 0.0 })
                ped to energy
            }
            // Frame f (1-based) owns upload buffer, raw image and batches f & 1; its upload buffer is
            // free again once the alignment of frame f - 2 is done.
            fun prepare(f: Int) = pool.submit(Callable {
                val rows = timed("cpu.decode") { comparisons[f - 1](staging) }
                val energy = if (config.blurGamma > 0f) timed("cpu.sharpness") { FrameSharpness.energy(rows, sharpA, sharpB) } else 0.0
                timed("cpu.pack") { pack(rows, frameMap[f and 1]) }
                val ref = refFuture.second.get()
                val blur = if (ref > 0.0) FrameSharpness.weight(energy, ref, config.blurGamma).also {
                    println("sr-pipeline frame sharpness %.3f of reference -> weight %.3f".format(energy / ref, it)) } else 1f
                Ready(blur)
            })
            val first = if (comparisons.isEmpty()) null else prepare(1)
            timed("cpu.pack") { pack(refRows!!, frameMap[0]) }
            refRows = null
            begin(2)
            pyramid(0, refLevels)
            for (p in 0 until levels) run("hess_img", mobile["hess_img"], longArrayOf(refLevels[p], hessRes[p]),
                longArrayOf(SAMPLED, 4L * tilesX(p) * tilesY(p)),
                push(0 to shapes[p].second, 1 to shapes[p].first, 3 to tilesX(p) * tilesY(p), 5 to tileOf(p), 12 to tilesX(p)),
                tilesX(p).toLong() * tilesY(p))
            submit()
            begin(0)
            run2d("ref_stats", longArrayOf(rawImg[0], refImg), longArrayOf(SAMPLED, STORAGE), push(0 to gw, 1 to gh), gw, gh)
            if (rp.s3) run2d("ref_hf", longArrayOf(refImg, hfImg), longArrayOf(SAMPLED, STORAGE),
                push(0 to gw, 1 to gh, 15 to rp.s3Rho, 16 to rp.s3K,
                    17 to config.kernelAlpha[0], 18 to config.kernelAlpha[1], 19 to config.kernelAlpha[2],
                    20 to config.kernelBeta[0], 21 to config.kernelBeta[1], 22 to config.kernelBeta[2]), gw, gh)
            setFlow(0, FloatArray(flowN.toInt()))
            motionField(0)
            window(work, rImg, 0, 3, 0, 0)   // r = 1
            merge(0)
            submit()
            fun alignAsync(f: Int) {
                begin(2 + (f and 1))
                pyramid(f and 1, movLevels)
                align(f and 1)
                submit()
            }
            val last = comparisons.size
            var current = if (last > 0) timed("cpu.waitDecode") { first!!.get() } else null
            if (last > 0) alignAsync(1)
            // Structure-tensor confidence for the motion field.
            timed("cpu.refWait") { waitFor(2) }
            var next = if (last > 1) prepare(2) else null
            val hess = FloatArray(4 * ftx * fty).also { out ->
                val b = SrpBridge.directFloats(out.size)
                ok("motion confidence", SrpBridge.downloadResident(handle, hessRes[levels - 1], b, out.size.toLong()))
                b.asFloatBuffer().get(out)
            }
            val field = MotionField(ftx, fty, hess)
            for (f in 1..last) {
                val slot = f and 1
                timed("align") { waitFor(slot + 2) }
                val flow = FloatArray(flowN.toInt()).also { flowMap[slot].duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().get(it) }
                val dump = VulkanPipeline.dumpDir
                dump?.let { writeFloats(java.io.File(it, "pre_%02d.f32".format(f - 1)), flow) }
                val blur = current!!.blur
                // Queue the next alignment first: it keeps the GPU busy while the CPU regularises this flow.
                if (f < last) {
                    current = timed("cpu.waitDecode") { next!!.get() }
                    next = if (f + 2 <= last) prepare(f + 2) else null
                    alignAsync(f + 1)
                }
                timed("cpu.motionField") { field.regularizeNative(flow, rp.mt) }
                setFlow(slot, flow)
                begin(slot)
                motionField(slot)
                robustness(slot, blur)
                merge(f)
                submit()
                dump?.let { dir ->
                    // Same files as the staged path: regularised flow and the robustness map before the blur weight.
                    writeFloats(java.io.File(dir, "flow_%02d_%dx%d.f32".format(f - 1, ftx, fty)), flow)
                    waitFor(slot)
                    val r = res(n)
                    begin(slot); run2d("img_dump", longArrayOf(rImg, r), longArrayOf(SAMPLED, n), push(0 to gw, 1 to gh, 7 to 1f / blur), gw, gh); submit(); waitFor(slot)
                    val b = SrpBridge.directFloats(n.toInt())
                    ok("dump", SrpBridge.downloadResident(handle, r, b, n))
                    writeFloats(java.io.File(dir, "r_%02d_%dx%d.f32".format(f - 1, gw, gh)), FloatArray(n.toInt()).also { b.asFloatBuffer().get(it) })
                }
            }
            timed("cpu.finalWait") { for (s in 0..3) waitFor(s) }
            val ped = refFuture.first.get()?.also {
                println("sr-pipeline black pedestal %.5f (R %.5f G %.5f B %.5f, se %.6f, %d blocks, neutral %b)".format(
                    it.pedestal, it.r, it.g, it.b, it.sigma, it.blocks, it.neutral)) }?.pedestal ?: 0f
            result = finish(ped, output)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
        }
        if (profile) {
            println("sr-pipeline mobile %.0f ms%s".format((System.nanoTime() - start) / 1e6, if (sync) " (sync)" else ""))
            for ((k, v) in clock.entries.sortedByDescending { it.value }) println("  %.1f %s".format(v / 1e6, k))
        }
        return result!!
    }

    /**
     * Frees every image and resident except [keep] once the GPU is idle. The merge's peak is the
     * frame loop; releasing its working set before the output exists keeps the output (12 B per
     * output pixel, 48 B per sensor pixel at 2x) out of that peak. Numerics are unchanged.
     */
    private fun releaseAllBut(keep: Long) {
        for (s in 0..3) SrpBridge.nativeBatchWait(handle, s)
        for (r in mapped) if (r != keep) SrpBridge.nativeUnmapResident(handle, r)
        mapped.retainAll { it == keep }
        for (i in images) if (i != keep) SrpBridge.nativeFreeImage(handle, i)
        images.retainAll { it == keep }
        for (r in owned) if (r != keep) SrpBridge.freeResident(handle, r)
        owned.retainAll { it == keep }
    }

    private fun finish(pedestal: Float, output: () -> ByteBuffer): ByteBuffer {
        begin(0)
        run("mfsr_divide", mobile["mfsr_divide"], longArrayOf(numRes, denRes), longArrayOf(total, total), push(2 to total.toInt()), total)
        var img = numRes
        if (evening && frames > 1 && evenSumG2 > 0.0) {
            run("noiseEven", spirvs.noiseEven!!, longArrayOf(numRes, denRes, evenAcc), longArrayOf(total, total, 4 * n),
                push(0 to outW, 1 to outH, 2 to outPx.toInt(), 6 to scale, 11 to gw, 12 to gh,
                    13 to (evenSumG * evenSumG / evenSumG2).toFloat(), 14 to frames.toFloat(),
                    15 to (config.kernelAlpha[1] + config.kernelAlpha[3]) / 2f, 16 to (config.kernelBeta[1] + config.kernelBeta[3]) / 2f,
                    17 to kotlin.math.round(2f * scale).toInt().coerceIn(2, 6), 18 to VulkanPipeline.NOISE_EVEN_RANGE), outPx)
            img = denRes
        }
        greenFit?.let { timed("greenSolve") { it.solveResident(img, if (img == numRes) denRes else numRes,
            config.kernelAlpha[1], config.kernelBeta[1], pedestal, wb) } }
        submit()
        timed("cpu.solveWait") { waitFor(0) }
        begin(1)
        if (config.lateralCa && spirvs.caWarp != null) {
            val f = timed("cpu.lateralCa") { requireNotNull(SrpBridge.nativeLateralCaFit(handle, img, outW, outH, scale)) }
            val ca = LateralCa.Fit(f[0], f[1], f[2], f[3], f[4].toInt())
            val shift = ca.cornerShift(outW, outH)
            println("sr-pipeline lateralCa kR=%.6f kB=%.6f nccR=%.3f nccB=%.3f points=%d cornerShift=%.2fpx".format(
                ca.kR, ca.kB, ca.nccR, ca.nccB, ca.points, shift))
            if (shift >= LateralCa.MIN_CORNER_SHIFT_PX) {
                val dst = if (img == numRes) denRes else numRes
                run("caWarp", spirvs.caWarp, longArrayOf(img, dst), longArrayOf(total, total),
                    push(0 to outW, 1 to outH, 2 to outPx.toInt(), 6 to scale, 7 to ca.kR, 8 to ca.kB, 11 to w, 12 to h), outPx)
                img = dst
            }
        }
        run("finish", mobile["mfsr_finish"], longArrayOf(img), longArrayOf(total),
            push(0 to outW, 1 to outH, 2 to outPx.toInt(), 7 to wb[0], 8 to wb[1], 9 to maxOf(0f, pedestal)), outPx)
        submit()
        timed("cpu.finishWait") { waitFor(1) }
        releaseAllBut(img)
        val out = output()
        timed("download") { ok("download", SrpBridge.downloadResident(handle, img, out, total)) }
        out.rewind()
        return out
    }

    override fun close() {
        for (s in 0..3) SrpBridge.nativeBatchWait(handle, s)
        for (r in mapped) SrpBridge.nativeUnmapResident(handle, r)
        for (i in images) SrpBridge.nativeFreeImage(handle, i)
        for (r in owned) SrpBridge.freeResident(handle, r)
    }
}
