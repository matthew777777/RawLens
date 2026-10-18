package com.rawlens.srpipeline.stages

import com.rawlens.srpipeline.SrpBridge
import java.nio.ByteBuffer

/**
 * GPU-accelerated coarse-to-fine alignment loop.
 *
 * Sequencing mirrors [alignLevelsPerLevel] exactly (same visit order, same
 * [upscaleLvl], same per-level block-matching-then-ICA contract), but every
 * compute-heavy op runs on-device through [SrpBridge]: L1 search
 * ([SrpBridge.alignL1]), L2 correlation ([SrpBridge.corrExtract] +
 * [SrpBridge.correlate] + host ±R crop + [SrpBridge.corrArgmin]), and ICA
 * Hessian build + refine ([SrpBridge.icaHessian]/[SrpBridge.icaRefine]).
 * Plumbing (padding, pyramids, gradients, tiling, flow pack/unpack, the C×C
 * crop) stays Kotlin — exact integer/permutation code shared with the CPU
 * loop, so any deviation is attributable to device math, never staging.
 *
 * Hessian note: like the port's `gpu_init_alignment`, Hessians are built
 * on-device (FMA class, ~1e-7 off the Kotlin ones); the loop envelope
 * absorbs this exactly as the port's does.
 */
object AlignGpu {

    private val prof = System.getProperty("srpipeline.profile", "false") == "true"

    /** Timing-only split; never changes values or order. */
    private inline fun <T> ptime(what: String, block: () -> T): T {
        if (!prof) return block()
        val t0 = System.nanoTime()
        val r = block()
        println("sr-prof align $what ${"%.1f".format((System.nanoTime() - t0) / 1e6)}")
        return r
    }

    /** Compiled kernels per op family. */
    data class Spirvs(
        val align: ByteArray,
        val corr: ByteArray,
        val fft: ByteArray,
        val ica: ByteArray,
    )

    /** One coarse-to-fine row, cf. `AlignmentInit` (spectra replaced). */
    data class GpuLevelInit(
        val ref: Array<FloatArray>,
        /** (nTy*nTx) reference tiles, each S*S row-major (zero halo). */
        val tiles: Array<FloatArray>,
        val gradX: Array<FloatArray>,
        val gradY: Array<FloatArray>,
        /** Packed Hessian (h00,h01,h11,0) per tile, via [SrpBridge.icaHessian]. */
        val hessPacked: FloatArray,
        val nTy: Int,
        val nTx: Int,
        val tileSize: Int,
        val radius: Int,
        /**
         * Pinned reference spectra resident (one forward FFT per level,
         * cf. [pinLevelResidents]), 0 when unpinned. L2 levels use it via
         * [SrpBridge.correlatePinned]; 0 selects the staged path.
         */
        val refSpecRes: Long = 0L,
        /**
         * Pinned level statics (uploaded once per run, cf.
         * [pinLevelResidents]), 0 when unpinned: reference image for
         * [SrpBridge.alignL1Res], plus gradients and packed Hessian for
         * [SrpBridge.icaRefineRes]. 0 on any of them selects the staged
         * path for that op.
         */
        val refRes: Long = 0L,
        val gradXRes: Long = 0L,
        val gradYRes: Long = 0L,
        val hessRes: Long = 0L,
        /** Per-level reusable host scratch (see [LevelScratch]). */
        val scratch: LevelScratch = LevelScratch(),
    )

    /** Reference-side initialisation, cf. [initAlignment]. */
    data class GpuInit(val levels: List<GpuLevelInit>)

    /**
     * Per-level reusable host scratch (exact shapes, allocated on first
     * use): eliminates the per-frame garbage (patches/full/corr arrays,
     * pack buffers) that otherwise peaks the Java heap on full-res
     * bursts. Shapes are fixed per level, so reuse is exact; every
     * buffer is fully rewritten per use (no stale reads). Entries still
     * fence-wait per dispatch, so sequential reuse is aliasing-safe.
     */
    class LevelScratch {
        private val floats = HashMap<String, FloatArray>()
        private val directs = HashMap<String, ByteBuffer>()

        /** Reusable FloatArray of exactly `n` floats. */
        fun floats(key: String, n: Int): FloatArray {
            val got = floats.getOrPut(key) { FloatArray(n) }
            require(got.size == n) { "scratch shape changed for $key" }
            return got
        }

        /** Reusable rewound direct buffer of exactly `n` floats. */
        fun direct(key: String, n: Int): ByteBuffer {
            val got = directs.getOrPut(key) { SrpBridge.directFloats(n) }
            require(got.capacity() / 4 == n) { "scratch shape changed for $key" }
            got.rewind()
            return got
        }
    }

    /**
     * Non-overlapping S×S tiles with a zero halo, cf. the `unfold` + zero
     * pad block (`alignment.py`) and the flat input of [tiledSpectra].
     * Tile count is floored (`imsize // tileSize`, remainder dropped).
     */
    fun tileLevel(
        level: Array<FloatArray>,
        tileSize: Int,
        searchRadius: Int,
    ): Array<FloatArray> {
        requireRectImage(level, "level")
        val h = level.size
        val w = level[0].size
        val nTy = h / tileSize
        val nTx = w / tileSize
        require(nTy >= 1 && nTx >= 1) { "level ${h}x$w holds no ${tileSize}px tiles" }
        val s = tileSize + 2 * searchRadius
        return Array(nTy * nTx) { t ->
            val ty = t / nTx
            val tx = t % nTx
            FloatArray(s * s) { i ->
                val y = i / s
                val x = i % s
                val ly = y - searchRadius
                val lx = x - searchRadius
                if (ly in 0 until tileSize && lx in 0 until tileSize) {
                    level[ty * tileSize + ly][tx * tileSize + lx]
                } else {
                    0f
                }
            }
        }
    }

    /**
     * Reference-side initialisation, cf. [initAlignment] (call order: pad,
     * gaussian pyramid with preprocessing, then per-level gradients +
     * tiling + on-device Hessian, coarse-to-fine index `len - i - 1`).
     */
    fun initGpu(
        refGrey: Array<FloatArray>,
        config: AlignLoopConfig,
        handle: Long,
        spirvIca: ByteArray,
    ): GpuInit {
        requireRectImage(refGrey, "refGrey")
        val n = config.factors.size
        val h = refGrey.size
        val w = refGrey[0].size
        val padH = if (h % config.tileSize == 0) 0 else config.tileSize - h % config.tileSize
        val padW = if (w % config.tileSize == 0) 0 else config.tileSize - w % config.tileSize
        // The padded copy (50 MB at full-res) is only the pyramid
        // input: scope it so it dies before the per-level builds below
        // instead of living until `initGpu` returns.
        val pyramid = run {
            val padded = circularPad(refGrey, padH, padW)
            Resample.buildGaussianPyramid(padded, config.factors, config.preprocessing)
        }
        // Gradient-upload staging reused across levels (fully rewritten
        // per use): the old per-level packImg pair churned 2x level-size
        // direct garbage per level (100 MB at the finest), which the
        // 512 MB on-device ceiling cannot absorb mid-init. Same bytes.
        // Extra capacity is fine: entries read explicit counts.
        val maxN = pyramid.last().size * pyramid.last()[0].size
        val packA = SrpBridge.directFloats(maxN)
        val packB = SrpBridge.directFloats(maxN)
        val levels = pyramid.indices.map { i ->
            val c = n - i - 1
            val tileSize = config.tileSizes[c]
            val radius = config.searchRadii[c]
            val (gradX, gradY) = sobelGradients(pyramid[i])
            // L1-metric levels never run the correlator, so their tiles
            // are dead weight (~63MB at full-res): skip the build. L2
            // levels keep tiles for the spectra pin (stripped after).
            val tiles = if (config.metrics[c] == BlockMetric.L1) emptyArray()
            else tileLevel(pyramid[i], tileSize, radius)
            val nTy = pyramid[i].size / tileSize
            val nTx = pyramid[i][0].size / tileSize
            val hessBuf = SrpBridge.directFloats(nTy * nTx * 4)
            val rc = SrpBridge.icaHessian(handle, packImgInto(gradX, packA), packImgInto(gradY, packB),
                hessBuf, spirvIca, pyramid[i][0].size, pyramid[i].size,
                nTy * nTx, tileSize, nTx)
            require(rc == 0) { "icaHessian failed with status $rc" }
            hessBuf.rewind()
            val hessPacked = FloatArray(nTy * nTx * 4)
            hessBuf.asFloatBuffer().get(hessPacked)
            GpuLevelInit(pyramid[i], tiles, gradX, gradY, hessPacked,
                nTy, nTx, tileSize, radius)
        }
        return GpuInit(levels)
    }

    /**
     * Pins each level's device residents (one upload per run, reused by
     * every frame): the L2 reference spectra (one forward FFT per level
     * via [SrpBridge.correlatePinRef], consumed through
     * [SrpBridge.correlatePinned]) plus the level statics every level
     * needs (reference image, gradients, packed Hessian, uploaded via
     * [SrpBridge.uploadResident] for [SrpBridge.alignL1Res]/
     * [SrpBridge.icaRefineRes]). L1 levels keep `refSpecRes = 0` (no
     * correlate there). On any failure, pins completed so far are freed
     * before the exception propagates.
     *
     * @return copy of [init] with levels carrying resident handles.
     */
    fun pinLevelResidents(
        init: GpuInit,
        config: AlignLoopConfig,
        handle: Long,
        spirvs: Spirvs,
    ): GpuInit {
        require(handle != 0L) { "AlignGpu: null context handle" }
        val n = config.factors.size
        require(init.levels.size == n) { "init must hold $n levels" }
        // Upload staging reused for every pin upload below (fully
        // rewritten per use): the old per-upload pack() churned one
        // level-size direct buffer per static (4x50 MB at the finest),
        // uncollectable fast enough under the 512 MB ceiling. Same bytes;
        // extra capacity is fine (entries read explicit counts).
        val maxStatic = init.levels.maxOf { it.ref.size * it.ref[0].size }
        val uploadScratch = SrpBridge.directFloats(maxStatic)
        val pinned = ArrayList<GpuLevelInit>(n)
        try {
            for (p in 0 until n) {
                val c = n - p - 1
                val lvl = init.levels[p]
                // Per-level partials: a mid-level failure must free pins
                // that never reached `pinned` (pinStatic frees only its
                // own handle on upload failure, not its siblings').
                val partial = ArrayList<Long>()
                try {
                    // One flat scratch per level, reused for the three
                    // same-shape uploads (ref, gradX, gradY): the old
                    // per-array temporaries tripled the pin-phase Eden
                    // spike (~200MB at full-res).
                    val flat = FloatArray(lvl.ref.size * lvl.ref[0].size)
                    fun flatInto(img: Array<FloatArray>): FloatArray {
                        require(img.size * img[0].size == flat.size) {
                            "pin scratch shape changed"
                        }
                        for (y in img.indices) img[y].copyInto(flat, y * img[0].size)
                        return flat
                    }
                    val refRes = pinStatic(handle, flatInto(lvl.ref), "ref", uploadScratch)
                    partial.add(refRes)
                    val gradXRes = pinStatic(handle, flatInto(lvl.gradX), "gradX", uploadScratch)
                    partial.add(gradXRes)
                    val gradYRes = pinStatic(handle, flatInto(lvl.gradY), "gradY", uploadScratch)
                    partial.add(gradYRes)
                    val hessRes = pinStatic(handle, lvl.hessPacked, "hess", uploadScratch)
                    partial.add(hessRes)
                    var cur = lvl.copy(refRes = refRes, gradXRes = gradXRes,
                        gradYRes = gradYRes, hessRes = hessRes)
                    if (config.metrics[c] == BlockMetric.L2) {
                        val tc = lvl.nTy * lvl.nTx
                        val s = lvl.tileSize + 2 * lvl.radius
                        val flat = FloatArray(tc * s * s) { i -> lvl.tiles[i / (s * s)][i % (s * s)] }
                        val res = SrpBridge.createResident(handle, 2L * tc * s * s)
                        require(res != 0L) { "pinLevelResidents: spectra alloc failed" }
                        partial.add(res)
                        val rc = ptime("correlatePinRef") {
                            SrpBridge.correlatePinRef(handle, packInto(flat, uploadScratch), res,
                                spirvs.fft, spirvs.corr, tc, s)
                        }
                        require(rc == 0) { "correlatePinRef failed with status $rc" }
                        cur = cur.copy(refSpecRes = res)
                    }
                    // Host copies are dead once residents hold the bytes:
                    // drop them (frees ~320MB retained heap at full-res).
                    // Unpinned levels keep theirs for the staged fallback;
                    // the staged branches select on the resident handles,
                    // so empty arrays on a pinned level are unreachable
                    // (and fail loudly, not silently, if ever read).
                    val stripped = if (cur.refSpecRes != 0L) {
                        cur.copy(ref = emptyArray(), tiles = emptyArray(),
                            gradX = emptyArray(), gradY = emptyArray(),
                            hessPacked = FloatArray(0))
                    } else {
                        cur.copy(ref = emptyArray(),
                            gradX = emptyArray(), gradY = emptyArray(),
                            hessPacked = FloatArray(0))
                    }
                    pinned.add(stripped)
                } catch (e: Exception) {
                    for (r in partial) SrpBridge.freeResident(handle, r)
                    throw e
                }
            }
        } catch (e: Exception) {
            freeLevelSpectra(GpuInit(pinned), handle)
            throw e
        }
        return GpuInit(pinned)
    }

    /** Creates a resident and uploads `data` once (level-static pin). */
    private fun pinStatic(handle: Long, data: FloatArray, what: String, scratch: ByteBuffer): Long {
        val res = SrpBridge.createResident(handle, data.size.toLong())
        require(res != 0L) { "pinLevelResidents: $what alloc failed" }
        try {
            val rc = ptime("uploadStatic") {
                SrpBridge.uploadResident(handle, res, packInto(data, scratch), data.size.toLong())
            }
            require(rc == 0) { "uploadResident($what) failed with status $rc" }
        } catch (e: Exception) {
            SrpBridge.freeResident(handle, res)
            throw e
        }
        return res
    }

    /** Frees every pinned resident in [init] (no-op unpinned). */
    fun freeLevelSpectra(init: GpuInit, handle: Long) {
        for (lvl in init.levels) {
            if (lvl.refSpecRes != 0L) SrpBridge.freeResident(handle, lvl.refSpecRes)
            if (lvl.refRes != 0L) SrpBridge.freeResident(handle, lvl.refRes)
            if (lvl.gradXRes != 0L) SrpBridge.freeResident(handle, lvl.gradXRes)
            if (lvl.gradYRes != 0L) SrpBridge.freeResident(handle, lvl.gradYRes)
            if (lvl.hessRes != 0L) SrpBridge.freeResident(handle, lvl.hessRes)
        }
    }

    /**
     * Coarse-to-fine loop, cf. [alignLevelsPerLevel]: same row order, same
     * [upscaleLvl], per-level block matching then ICA — all on-device.
     *
     * @return finest-level flow `[nTy][nTx][2]` (x, y), cf. [alignLevels].
     */
    fun alignLevelsGpu(
        init: GpuInit,
        movingGrey: Array<FloatArray>,
        config: AlignLoopConfig,
        handle: Long,
        spirvs: Spirvs,
    ): Array<Array<FloatArray>> {
        requireRectImage(movingGrey, "movingGrey")
        val n = config.factors.size
        require(init.levels.size == n) { "init must hold $n levels" }
        // Same circular pad as initGpu: the reference pyramid is built
        // from the padded frame, so the moving pyramid must use it too —
        // otherwise level shapes diverge whenever the frame is not an
        // exact tile multiple (full-res 3060x4080) and icaRefine (which
        // packs moving at ref size) has nothing matching to solve against.
        // Mirrors the port's gpu moving-pyramid pad; interior values are
        // unaffected (pad only feeds the downsample border band, inside
        // the e2e gate's border exclusion). No-op on exact multiples, so
        // crop runs are bit-identical before/after.
        val mh = movingGrey.size
        val mw = movingGrey[0].size
        val padH = if (mh % config.tileSize == 0) 0 else config.tileSize - mh % config.tileSize
        val padW = if (mw % config.tileSize == 0) 0 else config.tileSize - mw % config.tileSize
        val movingPyramid = Resample.buildGaussianPyramid(
            circularPad(movingGrey, padH, padW), config.factors)
        require(movingPyramid.size == n) { "moving pyramid must hold $n levels" }
        var flow: Array<Array<FloatArray>>? = null
        for (p in 0 until n) {
            val c = n - p - 1
            val lvl = init.levels[p]
            val moving = movingPyramid[p]
            flow = if (flow == null) {
                Array(lvl.nTy) { Array(lvl.nTx) { floatArrayOf(0f, 0f) } }
            } else {
                upscaleLvl(flow, intArrayOf(lvl.nTy, lvl.nTx), c, config)
            }
            val current = flow
            when (config.metrics[c]) {
                BlockMetric.L1 -> gpuL1(lvl, moving, current, handle, spirvs.align)
                BlockMetric.L2 -> gpuL2(lvl, moving, current, handle, spirvs)
            }
            gpuIca(lvl, moving, current, config, c, handle, spirvs.ica)
            flow = current
        }
        return flow ?: error("empty loop")
    }

    internal fun gpuL1(
        lvl: GpuLevelInit,
        moving: Array<FloatArray>,
        flow: Array<Array<FloatArray>>,
        handle: Long,
        spirv: ByteArray,
    ) {
        // Sized from `moving` (same shape as the ref level by the
        // circular-pad contract): `lvl.ref` is stripped post-pin.
        val w = moving[0].size
        val h = moving.size
        val sc = lvl.scratch
        val tc = lvl.nTy * lvl.nTx
        val flowBuf = packInto(flowToFlatInto(flow, sc.floats("flowFlat", tc * 2)),
            sc.direct("flow", tc * 2))
        val movBuf = packImgInto(moving, sc.direct("mov", w * h))
        // Pinned levels skip the ref upload; unpinned levels stage both.
        val rc = if (lvl.refRes != 0L) {
            ptime("alignL1Res") { SrpBridge.alignL1Res(handle, lvl.refRes, movBuf,
                flowBuf, spirv, w, h, tc, lvl.tileSize, lvl.radius,
                lvl.nTx) }
        } else {
            ptime("alignL1") { SrpBridge.alignL1(handle,
                packImgInto(lvl.ref, sc.direct("ref", w * h)), movBuf,
                flowBuf, spirv, w, h, tc, lvl.tileSize, lvl.radius,
                lvl.nTx) }
        }
        require(rc == 0) { "alignL1 failed with status $rc" }
        flowBuf.rewind()
        val flat = sc.floats("flowOut", tc * 2)
        flowBuf.asFloatBuffer().get(flat)
        flatToFlow(flat, flow)
    }

    internal fun gpuL2(
        lvl: GpuLevelInit,
        moving: Array<FloatArray>,
        flow: Array<Array<FloatArray>>,
        handle: Long,
        spirvs: Spirvs,
    ) {
        val tc = lvl.nTy * lvl.nTx
        val s = lvl.tileSize + 2 * lvl.radius
        val c = 2 * lvl.radius + 1
        val sc = lvl.scratch
        val movBuf = packImgInto(moving, sc.direct("mov", moving.size * moving[0].size))
        val flowBuf = packInto(flowToFlatInto(flow, sc.floats("flowFlat", tc * 2)),
            sc.direct("flow", tc * 2))
        // Extract moving patches around the current flow. patchBuf
        // doubles as the correlate/argmin patches input (sequential
        // fence-waited dispatches, same bytes, no download or re-pack).
        val patchBuf = sc.direct("patches", tc * s * s)
        var rc = ptime("corrExtract") { SrpBridge.corrExtract(handle, movBuf,
            flowBuf, patchBuf, spirvs.corr,
            moving.size, moving[0].size, tc, lvl.tileSize, lvl.radius, lvl.nTx) }
        require(rc == 0) { "corrExtract failed with status $rc" }
        patchBuf.rewind()
        // Correlate against the reference tiles, crop to ±R, argmin.
        // Pinned levels reuse the one-per-level ref spectra (no ref
        // upload, no ref forward); unpinned levels stage both sides.
        val corrBuf = sc.direct("corr", tc * s * s)
        rc = if (lvl.refSpecRes != 0L) {
            ptime("correlatePinned") { SrpBridge.correlatePinned(handle, patchBuf,
                lvl.refSpecRes, corrBuf, spirvs.fft, spirvs.corr, tc, s) }
        } else {
            val refFlat = FloatArray(tc * s * s) { i -> lvl.tiles[i / (s * s)][i % (s * s)] }
            ptime("correlate") { SrpBridge.correlate(handle, pack(refFlat), patchBuf,
                corrBuf, spirvs.fft, tc, s) }
        }
        require(rc == 0) { "correlate failed with status $rc" }
        corrBuf.rewind()
        val full = sc.floats("full", tc * s * s)
        corrBuf.asFloatBuffer().get(full)
        val cropped = ptime("cropCorrsHost") {
            cropCorrs(full, tc, s, c, sc.floats("cropped", tc * c * c))
        }
        val cropBuf = packInto(cropped, sc.direct("crop", tc * c * c))
        val dflowBuf = sc.direct("dflow", tc * 2)
        rc = ptime("corrArgmin") { SrpBridge.corrArgmin(handle, patchBuf, cropBuf,
            dflowBuf, spirvs.corr, tc, lvl.tileSize, lvl.radius, lvl.nTx,
            tc * s * s, tc * c * c) }
        require(rc == 0) { "corrArgmin failed with status $rc" }
        dflowBuf.rewind()
        val delta = sc.floats("delta", tc * 2)
        dflowBuf.asFloatBuffer().get(delta)
        // `align_lvl_block_matching_L2` adds the winning integer shift to
        // the full float flow, keeping the subpixel part.
        for (ty in 0 until lvl.nTy) {
            for (tx in 0 until lvl.nTx) {
                val t = (ty * lvl.nTx + tx) * 2
                flow[ty][tx][0] = flow[ty][tx][0] + delta[t]
                flow[ty][tx][1] = flow[ty][tx][1] + delta[t + 1]
            }
        }
    }

    internal fun gpuIca(
        lvl: GpuLevelInit,
        moving: Array<FloatArray>,
        flow: Array<Array<FloatArray>>,
        config: AlignLoopConfig,
        c: Int,
        handle: Long,
        spirv: ByteArray,
    ) {
        val radius = when {
            lvl.tileSize == 64 -> 32767
            config.icaClip -> config.searchRadii[c]
            else -> 32767
        }
        val sc = lvl.scratch
        val tc = lvl.nTy * lvl.nTx
        // Sized from `moving` (same shape as the ref level by the
        // circular-pad contract): `lvl.ref` is stripped post-pin.
        val w = moving[0].size
        val h = moving.size
        val flowBuf = packInto(flowToFlatInto(flow, sc.floats("flowFlat", tc * 2)),
            sc.direct("flow", tc * 2))
        val movBuf = packImgInto(moving, sc.direct("mov", w * h))
        // Pinned levels skip the four static uploads; unpinned stage all.
        val statics = lvl.refRes != 0L && lvl.gradXRes != 0L &&
            lvl.gradYRes != 0L && lvl.hessRes != 0L
        val rc = if (statics) {
            ptime("icaRefineRes") { SrpBridge.icaRefineRes(handle, lvl.refRes, movBuf,
                lvl.gradXRes, lvl.gradYRes, lvl.hessRes,
                flowBuf, spirv, w, h,
                tc, lvl.tileSize, config.nIter, radius.toFloat(),
                lvl.nTx) }
        } else {
            ptime("icaRefine") { SrpBridge.icaRefine(handle,
                packImgInto(lvl.ref, sc.direct("ref", w * h)), movBuf,
                packImgInto(lvl.gradX, sc.direct("gradX", w * h)),
                packImgInto(lvl.gradY, sc.direct("gradY", w * h)),
                packInto(lvl.hessPacked, sc.direct("hess", tc * 4)),
                flowBuf, spirv, w, h,
                tc, lvl.tileSize, config.nIter, radius.toFloat(),
                lvl.nTx) }
        }
        require(rc == 0) { "icaRefine failed with status $rc" }
        flowBuf.rewind()
        val flat = sc.floats("flowOut", tc * 2)
        flowBuf.asFloatBuffer().get(flat)
        flatToFlow(flat, flow)
    }

    /**
     * Centre C×C crop of each S×S correlation map, cf. the crop in
     * `gpu_align_lvl_l2` (`crop = (pre - 1 - C) // 2`, `+1` offset).
     */
    fun cropCorrs(
        full: FloatArray,
        tileCount: Int,
        s: Int,
        c: Int,
        out: FloatArray? = null,
    ): FloatArray {
        val crop = (s - 1 - c) / 2
        val dst = out ?: FloatArray(tileCount * c * c)
        require(dst.size == tileCount * c * c) { "cropCorrs out has wrong size" }
        for (i in dst.indices) {
            val t = i / (c * c)
            val y = (i / c) % c
            val x = i % c
            dst[i] = full[(t * s + (crop + 1 + y)) * s + (crop + 1 + x)]
        }
        return dst
    }

    /** (nTy,nTx,2) nested flow → flat row-major (x, y) pairs. */
    fun flowToFlat(flow: Array<Array<FloatArray>>): FloatArray {
        val nTy = flow.size
        val nTx = flow[0].size
        return FloatArray(nTy * nTx * 2) { i ->
            val t = i / 2
            flow[t / nTx][t % nTx][i % 2]
        }
    }

    /** [flowToFlat] into reusable `out` (same bytes, no allocation). */
    fun flowToFlatInto(flow: Array<Array<FloatArray>>, out: FloatArray): FloatArray {
        val nTy = flow.size
        val nTx = flow[0].size
        require(out.size == nTy * nTx * 2) { "flowToFlatInto out has wrong size" }
        for (i in out.indices) {
            val t = i / 2
            out[i] = flow[t / nTx][t % nTx][i % 2]
        }
        return out
    }

    /** Flat row-major (x, y) pairs → nested flow, in place. */
    fun flatToFlow(flat: FloatArray, flow: Array<Array<FloatArray>>) {
        val nTx = flow[0].size
        for (t in 0 until flow.size * nTx) {
            flow[t / nTx][t % nTx][0] = flat[t * 2]
            flow[t / nTx][t % nTx][1] = flat[t * 2 + 1]
        }
    }

    /**
     * Copies `data` into reusable direct buffer `buf` (same bytes as
     * [pack], no allocation). The caller owns `buf` (usually level
     * scratch); contents are fully rewritten per call.
     */
    private fun packInto(data: FloatArray, buf: ByteBuffer): ByteBuffer {
        buf.rewind()
        buf.asFloatBuffer().put(data)
        buf.rewind()
        return buf
    }

    /** Copies row-major `img` into reusable direct buffer `buf`. */
    private fun packImgInto(img: Array<FloatArray>, buf: ByteBuffer): ByteBuffer {
        buf.rewind()
        val fb = buf.asFloatBuffer()
        for (y in img.indices) fb.put(img[y])
        buf.rewind()
        return buf
    }

    private fun pack(data: FloatArray): ByteBuffer {
        val buf = SrpBridge.directFloats(data.size)
        buf.asFloatBuffer().put(data)
        buf.rewind()
        return buf
    }

    private fun packFlow(flow: Array<Array<FloatArray>>): ByteBuffer =
        pack(flowToFlat(flow))

    private fun packImg(img: Array<FloatArray>): ByteBuffer {
        val h = img.size
        val w = img[0].size
        val buf = SrpBridge.directFloats(h * w)
        val fb = buf.asFloatBuffer()
        for (y in 0 until h) fb.put(img[y])
        buf.rewind()
        return buf
    }
}
