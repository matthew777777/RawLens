// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.floor

/**
 * Prompt 5C experimental Mosaic SR: direct CFA-target reconstruction.
 *
 * This is NOT a re-mosaicing of merged RGB. Each target CFA site accumulates
 * only aligned same-colour source observations, routed by sensor colour
 * ([BayerPattern.colorAt]) exactly like [RawSrBayerMerge], and weighted by the
 * validated model: reference-anchored flow ([RawSrAlignmentField]), kernel
 * precision ([RawSrKernelCovariance.MatrixField]), and per-frame robustness
 * ([RawSrRobustness.FrameRobustness]). Reference merges last with r = 1;
 * sites with no support fall back to the reference-only value; Rc folds
 * through [RawSrRobustness.accumulate] unchanged.
 *
 * Target grid: linear scale [LINEAR_SCALE] (≈√2, so a ~12 MP source becomes a
 * ~24 MP target by AREA, not 2× width and height), rounded to the nearest even
 * grid to preserve the 2×2 Bayer phase and the source aspect ratio. The target
 * phase is the source phase at the shared origin (crop-origin aware).
 *
 * Scalar Double accumulation over flat arrays, mirroring the oracle. The RGB
 * merge stays the JPEG path; this reconstruction feeds only the Mosaic SR DNG.
 */
object MosaicSrReconstructor {
    /** √2 linear scale: target area ≈ 2× source area. */
    const val LINEAR_SCALE = 1.4142135623730951
    const val ALGORITHM_VERSION = "RawLens-MosaicSr/5C"
    const val EPS = 1e-8

    data class TargetGrid(
        val width: Int,
        val height: Int,
        /** Target 2×2 phase: source phase at the shared (crop) origin. */
        val pattern: BayerPattern,
        val scale: Double
    )

    data class MosaicSrResult(
        val width: Int,
        val height: Int,
        val pattern: BayerPattern,
        /** One float sample per target CFA site, unclamped like the oracle. */
        val cfa: FloatArray,
        /** Accumulated weight per site (denominator). */
        val weight: FloatArray,
        /** Contributing tap count per site. */
        val taps: IntArray,
        val rc: RawSrRobustness.RcField,
        val oob: IntArray,
        /** True where no support existed and the reference-only value was used. */
        val fallback: BooleanArray
    )

    /**
     * Even target grid at [LINEAR_SCALE], preserving aspect ratio. Rounding is
     * floor-to-even (largest even grid at or below dim×s), so every target
     * site maps strictly inside the source frame — no site is born
     * out-of-bounds. The grid carries the source phase at the shared (crop)
     * origin, so odd crop origins shift the target phase exactly like the
     * source.
     */
    fun planTarget(
        sourceWidth: Int,
        sourceHeight: Int,
        sensorPattern: BayerPattern = BayerPattern.RGGB,
        sensorLeft: Int = 0,
        sensorTop: Int = 0
    ): TargetGrid {
        require(sourceWidth >= 2 && sourceHeight >= 2 && sourceWidth % 2 == 0 && sourceHeight % 2 == 0) {
            "Mosaic SR needs an even source crop of at least 2x2"
        }
        // Floor to even: 2×floor(dim×s/2). A ~12 MP source lands at ~2× area.
        val width = (floorToEven(sourceWidth * LINEAR_SCALE)).coerceAtLeast(2)
        val height = (floorToEven(sourceHeight * LINEAR_SCALE)).coerceAtLeast(2)
        return TargetGrid(width, height, sensorPattern.shifted(sensorLeft, sensorTop), LINEAR_SCALE)
    }

    private fun floorToEven(value: Double): Int {
        val half = floor(value / 2.0).toInt()
        return half * 2
    }

    /**
     * Reconstruct [reference] + [moving] onto the target grid. [targetPattern]
     * defaults to the source phase at the shared origin; pass an explicit
     * phase only to test phase selection. Frames reuse
     * [RawSrBayerMerge.MergeFrame] so the validated model inputs transfer
     * unchanged.
     */
    fun reconstruct(
        reference: RawSrBayerMerge.MergeFrame,
        moving: List<RawSrBayerMerge.MergeFrame>,
        targetPattern: BayerPattern? = null,
        referenceOnly: Boolean = false,
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null
    ): MosaicSrResult {
        val frames = if (referenceOnly) emptyList() else moving
        val width = reference.width
        val height = reference.height
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) {
            "Mosaic SR needs an even source crop of at least 2x2"
        }
        val quadsW = width / 2
        val quadsH = height / 2
        fun checkFrame(frame: RawSrBayerMerge.MergeFrame, label: String) {
            require(frame.width == width && frame.height == height) {
                "$label dimensions do not match $width x $height"
            }
            require(frame.samples.size == width * height) { "$label samples truncated" }
            require(frame.precision.width == quadsW && frame.precision.height == quadsH) {
                "$label precision grid must match the quad grid"
            }
        }
        checkFrame(reference, "Reference")
        frames.forEachIndexed { index, frame ->
            checkFrame(frame, "Moving $index")
            require(frame.flow != null && frame.robustness != null) { "Moving $index needs flow and robustness" }
            require(frame.flow.imageWidth == quadsW && frame.flow.imageHeight == quadsH) {
                "Moving $index flow grid must match the quad grid"
            }
            require(frame.robustness.width == quadsW && frame.robustness.height == quadsH) {
                "Moving $index robustness grid must match the quad grid"
            }
        }
        // The target grid shares the source origin: its (0,0) site carries the
        // source phase at (sensorLeft, sensorTop), including odd crop origins.
        val plan = planTarget(
            width, height, reference.sensorPattern, reference.sensorLeft, reference.sensorTop
        )
        val pattern = targetPattern ?: plan.pattern
        val outW = plan.width
        val outH = plan.height
        val pixels = outW * outH
        // Single-precision accumulators (Stacker parity: references run
        // float): halves eager heap and streaming temp with no gate-level
        // impact. Arithmetic stays Double and narrows on store, identically
        // on both paths, so eager/streaming agreement is still bitwise.
        val num = FloatArray(pixels)
        val den = FloatArray(pixels)
        val refNum = FloatArray(pixels)
        val refDen = FloatArray(pixels)
        // Burst-nearest totals backing the fallback path (same delta-kernel
        // rule as the RGB merge; the kernel totals above are untouched).
        val nearNum = FloatArray(pixels)
        val nearDen = FloatArray(pixels)
        val nearRefNum = FloatArray(pixels)
        val nearRefDen = FloatArray(pixels)
        val taps = IntArray(pixels)
        val oob = IntArray(pixels)
        var rowsDone = 0
        val numBuf = FloatBuffer.wrap(num)
        val denBuf = FloatBuffer.wrap(den)
        val nearNumBuf = FloatBuffer.wrap(nearNum)
        val nearDenBuf = FloatBuffer.wrap(nearDen)
        // Canonical accumulation order (mirrors streaming exactly — ref
        // contributions first, then moving frames in order — so the two
        // paths agree bitwise even though every store narrows to float).
        accumulateFrame(reference, null, null, pattern,
            FloatBuffer.wrap(refNum), FloatBuffer.wrap(refDen),
            FloatBuffer.wrap(nearRefNum), FloatBuffer.wrap(nearRefDen), taps, oob,
            width, height, quadsW, outW, outH, true)
        for (i in num.indices) {
            num[i] += refNum[i]
            den[i] += refDen[i]
            nearNum[i] += nearRefNum[i]
            nearDen[i] += nearRefDen[i]
        }
        for (frame in frames) {
            accumulateFrame(frame, frame.flow!!, frame.robustness!!, pattern,
                numBuf, denBuf, nearNumBuf, nearDenBuf, taps, oob,
                width, height, quadsW, outW, outH, false)
            rowsDone += outH
            if (isCancelled?.invoke(rowsDone) == true) throw CancellationException("Mosaic SR cancelled")
        }
        var rc = RawSrRobustness.RcField(quadsW, quadsH, FloatArray(quadsW * quadsH))
        for (frame in frames) {
            val robust = frame.robustness!!
            val sanitized = FloatArray(robust.r.size) { i ->
                val v = robust.r[i]
                if (v.isFinite()) v else 0f
            }
            rc = RawSrRobustness.accumulate(
                rc, RawSrRobustness.FrameRobustness(robust.width, robust.height, sanitized, robust.flags))
        }
        val cfa = FloatArray(pixels)
        val weight = FloatArray(pixels)
        val fallback = BooleanArray(pixels)
        // Accumulated-robustness overwrite (merge contract §9, mirrors the
        // packed oracle): a target site reads support from its source quad —
        // the same quad whose robustness fed accumulation. Below MIN_SUPPORT
        // it joins the fallback set instead of keeping a ghost-prone kernel
        // blend. Inert with no moving frames, where the output already equals
        // the reference.
        val overwrite = frames.isNotEmpty()
        for (p in 0 until pixels) {
            val qx = p % outW
            val qy = p / outW
            val quadX = (((qx + 0.5) / LINEAR_SCALE) / 2.0).toInt().coerceIn(0, quadsW - 1)
            val quadY = (((qy + 0.5) / LINEAR_SCALE) / 2.0).toInt().coerceIn(0, quadsH - 1)
            val unsupported =
                overwrite && rc.values[quadY * quadsW + quadX] < RawSrBayerMerge.MIN_SUPPORT
            // Censored-site direct: the reference site tap itself is clipped,
            // so its true value is unknown-but-bright; the honest output is
            // the measured tap (a mosaic site carries one colour only, so no
            // triple-white applies here). Same expression the streaming pass
            // captures during accumulation — bitwise-identical.
            val siteTap = refSiteTap(reference, pattern, qx, qy, width, height)
            val siteWhite = siteTap >= RawSrBayerMerge.SATURATED_REF_GUARD
            // Accumulator reads widen to Double at the decision boundary (one
            // narrowing on store, identically on both paths).
            val denD = den[p].toDouble()
            val rcQuad = rc.values[quadY * quadsW + quadX].toDouble()
            val value = if (siteWhite) {
                fallback[p] = true
                siteTap
            } else if (!unsupported && denD > EPS) {
                num[p] / maxOf(denD, EPS)
            } else {
                fallback[p] = true
                // Burst-nearest fallback: average the nearest matching-phase
                // samples across the burst instead of the reference-only
                // value; nested ref-only fallback where even the nearest
                // path has no support, where the reference sample is
                // clipped (saturation guard, mirrors the oracle packed path:
                // clipped sites carry no trustworthy signal), where the
                // kernel is blind (fully censored window), or where the burst
                // carries no moving support at all (shared-weight quotient
                // stays achromatic where per-channel picks would straddle).
                val refValue = refNum[p] / maxOf(refDen[p].toDouble(), EPS)
                val nd = nearDen[p].toDouble()
                if (refValue >= RawSrBayerMerge.SATURATED_REF_GUARD) refValue
                else if (refDen[p].toDouble() <= EPS && nd > EPS) nearNum[p] / maxOf(nd, EPS)
                else if (rcQuad <= EPS) refValue
                else if (nd > EPS) nearNum[p] / maxOf(nd, EPS)
                else refValue
            }
            cfa[p] = if (value.isFinite()) value.toFloat() else 0f
            weight[p] = den[p].toFloat().let { if (it.isFinite()) it else 0f }
        }
        // rc accumulated above over `frames` (empty under referenceOnly,
        // hence already zero there); nothing left to reset.
        return MosaicSrResult(outW, outH, pattern, cfa, weight, taps, rc, oob, fallback)
    }

    /**
     * Lean production result: only what the Mosaic SR DNG saver consumes
     * (width/height/pattern/cfa). Weight, taps, Rc, oob and the fallback mask
     * are QA diagnostics of the List path; the file carries CFA samples only.
     */
    data class StreamingMosaic(
        val width: Int,
        val height: Int,
        val pattern: BayerPattern,
        val cfa: FloatArray,
        val acceptedFrames: Int
    )

    /**
     * Memory-bound production reconstruct: same math as [reconstruct] (same
     * accumulation order, same double operations — bitwise-identical CFA), but
     * frames stream one at a time (accumulate-then-drop, ~150MB peak per
     * full-res frame instead of ~150MB × burst size) and the six double
     * accumulators (kernel pair, reference pair, burst-nearest pair) live in
     * memory-mapped temp files (~0.6GB at full res), costing zero Dalvik
     * heap. Heap peak is ~415MB of a 512MB heap. Since the support-overwrite
     * adoption the file set is seven: the six pixel-sized pairs plus one
     * quad-sized support (Rc) accumulator driving the overwrite.
     *
     * The reference frame is built once via [buildReference], accumulated
     * first, then dropped; moving frames arrive via [moving] and are released
     * as consumed, so the sequence must be single-use. With no surviving
     * moving frame this throws [MergeUnavailableException] exactly like the
     * eager chain, and the caller falls back to the reference DNG.
     */
    fun reconstructStreaming(
        geometry: RawSrMergeJob.ReferenceGeometry,
        moving: Sequence<RawSrBayerMerge.MergeFrame>,
        buildReference: () -> RawSrBayerMerge.MergeFrame,
        targetPattern: BayerPattern? = null,
        tempDir: File,
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null
    ): StreamingMosaic {
        val width = geometry.width
        val height = geometry.height
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) {
            "Mosaic SR needs an even source crop of at least 2x2"
        }
        val quadsW = width / 2
        val quadsH = height / 2
        fun checkFrame(frame: RawSrBayerMerge.MergeFrame, label: String) {
            require(frame.width == width && frame.height == height) {
                "$label dimensions do not match $width x $height"
            }
            require(frame.samples.size == width * height) { "$label samples truncated" }
            require(frame.precision.width == quadsW && frame.precision.height == quadsH) {
                "$label precision grid must match the quad grid"
            }
        }
        val plan = planTarget(width, height, geometry.pattern, geometry.left, geometry.top)
        val pattern = targetPattern ?: plan.pattern
        val outW = plan.width
        val outH = plan.height
        val pixels = outW * outH
        // Six pixel-sized pairs plus one quad-sized support accumulator: the
        // streaming path tracks Rc exactly like the eager path (plain
        // per-quad sums, cf. RawSrRobustness.accumulate) so the
        // accumulated-robustness overwrite decides identically. One heap
        // float array (~50MB at full res, freed with the result) captures
        // the reference site taps for the censored-site rule — the reference
        // frame itself is dropped before the finalizer.
        val siteTaps = FloatArray(pixels) { Float.NaN }
        return useMappedFloats(tempDir, IntArray(6) { pixels } + quadsW * quadsH) { acc ->
            val num = acc[0]
            val den = acc[1]
            val refNum = acc[2]
            val refDen = acc[3]
            val nearNum = acc[4]
            val nearDen = acc[5]
            val rcAcc = acc[6]
            var rowsDone = 0
            // Built once, accumulated first, then released before any moving
            // frame materializes: nulling the only production reference keeps
            // the 100MB reference buffers collectible during the stream.
            var reference: RawSrBayerMerge.MergeFrame? = buildReference()
            val ref = requireNotNull(reference) { "Reference frame missing" }
            checkFrame(ref, "Reference")
            accumulateFrame(ref, null, null, pattern,
                refNum, refDen, nearNum, nearDen, null, null,
                width, height, quadsW, outW, outH, true,
                siteTaps = FloatBuffer.wrap(siteTaps))
            reference = null
            // Canonical order (mirrors eager): the reference pass already ran
            // above, so its pair joins the shared accumulators before any
            // moving frame — bitwise-identical sequencing on both paths.
            for (i in 0 until pixels) {
                num.put(i, num.get(i) + refNum.get(i))
                den.put(i, den.get(i) + refDen.get(i))
                // No near add: the reference pass accumulates its nearest
                // contribution directly into the shared near pair above.
            }
            var accepted = 0
            for (frame in moving) {
                checkFrame(frame, "Moving $accepted")
                require(frame.flow != null && frame.robustness != null) {
                    "Moving $accepted needs flow and robustness"
                }
                require(frame.flow.imageWidth == quadsW && frame.flow.imageHeight == quadsH) {
                    "Moving $accepted flow grid must match the quad grid"
                }
                require(frame.robustness.width == quadsW && frame.robustness.height == quadsH) {
                    "Moving $accepted robustness grid must match the quad grid"
                }
                accumulateFrame(frame, frame.flow, frame.robustness, pattern,
                    num, den, nearNum, nearDen, null, null,
                    width, height, quadsW, outW, outH, false)
                val support = frame.robustness.r
                for (q in support.indices) {
                    val v = support[q]
                    rcAcc.put(q, rcAcc.get(q) + (if (v.isFinite()) v else 0f))
                }
                accepted++
                rowsDone += outH
                if (isCancelled?.invoke(rowsDone) == true) throw CancellationException("Mosaic SR cancelled")
            }
            if (accepted == 0) {
                throw MergeUnavailableException("Mosaic stream kept no moving frame after alignment")
            }
            val cfa = FloatArray(pixels)
            // Accumulated-robustness overwrite (merge contract §9, mirrors
            // the eager path and the packed oracle): accepted >= 1 here (the
            // empty stream throws above), so the rule is unconditionally
            // active. StreamingMosaic carries no mask by design (DNG saver
            // consumes CFA only); the overwrite still routes values through
            // the fallback branch.
            for (p in 0 until pixels) {
                val quadX = (((p % outW + 0.5) / LINEAR_SCALE) / 2.0).toInt().coerceIn(0, quadsW - 1)
                val quadY = (((p / outW + 0.5) / LINEAR_SCALE) / 2.0).toInt().coerceIn(0, quadsH - 1)
                val unsupported = rcAcc.get(quadY * quadsW + quadX) < RawSrBayerMerge.MIN_SUPPORT
                val d = den.get(p).toDouble()
                // Censored-site direct (mirrors the eager path): the captured
                // site tap is clipped, so the honest output is the measured
                // tap. NaN where colours disagreed — never >= guard.
                val siteTap = siteTaps[p].toDouble()
                val siteWhite = siteTap >= RawSrBayerMerge.SATURATED_REF_GUARD
                val rcQuad = rcAcc.get(quadY * quadsW + quadX).toDouble()
                val value = if (siteWhite) {
                    siteTap
                } else if (!unsupported && d > EPS) {
                    num.get(p) / maxOf(d, EPS)
                } else {
                    // Burst-nearest fallback (mirrors the eager path): the
                    // nearest matching-phase average across the burst, else
                    // the reference-only value; clipped reference samples
                    // keep the reference value (saturation guard, mirrors
                    // the oracle packed path); kernel-blind or zero-support
                    // sites resolve through the reference quotient.
                    val nd = nearDen.get(p).toDouble()
                    val refDenD = refDen.get(p).toDouble()
                    val refValue = refNum.get(p) / maxOf(refDenD, EPS)
                    if (refValue >= RawSrBayerMerge.SATURATED_REF_GUARD) refValue
                    else if (refDenD <= EPS && nd > EPS) nearNum.get(p) / maxOf(nd, EPS)
                    else if (rcQuad <= EPS) refValue
                    else if (nd > EPS) nearNum.get(p) / maxOf(nd, EPS)
                    else refValue
                }
                cfa[p] = if (value.isFinite()) value.toFloat() else 0f
            }
            StreamingMosaic(outW, outH, pattern, cfa, accepted)
        }
    }

    /**
     * Off-heap single-precision accumulators backed by temp files. One
     * buffer per entry of [sizes]; files are deleted on return or failure.
     */
    private inline fun <T> useMappedFloats(
        dir: File,
        sizes: IntArray,
        block: (List<FloatBuffer>) -> T
    ): T {
        val byteSizes = sizes.map { it.toLong() * java.lang.Float.BYTES }
        val total = byteSizes.sum()
        if (dir.usableSpace < total + (64L shl 20)) {
            throw MergeUnavailableException(
                "Insufficient temp space for mosaic accumulators " +
                    "(need ${total / (1L shl 20)}MB)"
            )
        }
        val files = sizes.indices.map { i -> File.createTempFile("mosaic-acc$i-", ".bin", dir) }
        try {
            val mappings = files.mapIndexed { i, file ->
                val raf = RandomAccessFile(file, "rw")
                try {
                    raf.setLength(byteSizes[i])
                    raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, byteSizes[i]).asFloatBuffer()
                } finally {
                    runCatching { raf.close() }
                }
            }
            return block(mappings)
        } finally {
            files.forEach { runCatching { if (!it.delete()) it.deleteOnExit() } }
        }
    }

    private fun accumulateFrame(
        frame: RawSrBayerMerge.MergeFrame,
        flow: RawSrAlignmentField?,
        robust: RawSrRobustness.FrameRobustness?,
        pattern: BayerPattern,
        num: FloatBuffer,
        den: FloatBuffer,
        nearNum: FloatBuffer,
        nearDen: FloatBuffer,
        taps: IntArray?,
        oob: IntArray?,
        width: Int,
        height: Int,
        quadsW: Int,
        outW: Int,
        outH: Int,
        isReference: Boolean,
        onRow: (() -> Boolean)? = null,
        // Streaming-only: reference-pass site taps for the censored-site
        // rule. The reference frame is dropped before the streaming
        // finalizer, so its direct site samples are captured here; the eager
        // path recomputes the identical expression inline (reference alive),
        // keeping both bitwise-identical. NaN where the floor tap's colour
        // mismatches the site colour (no direct sample exists there).
        siteTaps: FloatBuffer? = null
    ) {
        val samples = frame.samples
        val precision = frame.precision.values
        val guideW = frame.precision.width
        val guideH = frame.precision.height
        val scaleX = guideW.toDouble() / width
        val scaleY = guideH.toDouble() / height
        // Row-sharded across the shared worker pool: shards cover disjoint
        // output rows with the serial per-pixel code untouched, so the
        // accumulation is bitwise-identical at any worker count. The
        // per-pixel precision scratch is shard-local (was: one DoubleArray
        // allocation per pixel, a GC hotspot at full res).
        RawSrWorkers.forEachShard(outH) { y0, y1 ->
            val scratch = DoubleArray(4)
            for (qy in y0 until y1) {
                if (onRow?.invoke() == true) throw CancellationException("Mosaic SR cancelled")
                for (qx in 0 until outW) {
                val p = qy * outW + qx
                val siteColor = pattern.colorAt(qx, qy)
                // Reference-source position of the site center (corner coords),
                // plus the validated flow displacement in quad pixels.
                val dxQuad: Double
                val dyQuad: Double
                val r: Double
                if (isReference) {
                    dxQuad = 0.0; dyQuad = 0.0; r = 1.0
                } else {
                    val baseX = (qx + 0.5) / LINEAR_SCALE
                    val baseY = (qy + 0.5) / LINEAR_SCALE
                    val quadX = floor(baseX / 2.0).toInt()
                    val quadY = floor(baseY / 2.0).toInt()
                    // Bilinear flow (flowAtSmooth), mirroring the packed
                    // oracle and the GPU accumulate twin: nearest-tile lookup
                    // imprints the 16px quilt at tile borders. Same quad
                    // coordinate space — only the sampling changes.
                    val tile = flow!!.flowAtSmooth(quadX.toFloat(), quadY.toFloat())
                    if (!tile.dx.isFinite() || !tile.dy.isFinite()) {
                        if (oob != null) oob[p]++
                        continue
                    }
                    dxQuad = tile.dx.toDouble()
                    dyQuad = tile.dy.toDouble()
                    val raw = robust!!.r[quadY.coerceIn(0, robust.height - 1) * quadsW +
                        quadX.coerceIn(0, quadsW - 1)]
                    r = if (raw.isFinite()) raw.toDouble() else 0.0
                }
                if (r == 0.0) continue
                val sourceX = (qx + 0.5) / LINEAR_SCALE + 2.0 * dxQuad
                val sourceY = (qy + 0.5) / LINEAR_SCALE + 2.0 * dyQuad
                if (!sourceX.isFinite() || !sourceY.isFinite() ||
                    sourceX < 0.0 || sourceY < 0.0 || sourceX >= width || sourceY >= height
                ) {
                    if (oob != null) oob[p]++
                    continue
                }
                if (siteTaps != null && isReference) {
                    // Reference pass has zero shift: capture the site's
                    // direct sample (NaN where colours disagree) for the
                    // censored-site rule. Same expression the eager finalizer
                    // recomputes inline — bitwise-identical by construction.
                    siteTaps.put(p, refSiteTap(frame, pattern, qx, qy, width, height).toFloat())
                }
                // Burst-nearest accumulation (Stacker delta-kernel rule): the
                // nearest finite sample of the site colour in the
                // floor-centered 3x3 window, weighted by robustness. Nearest
                // by texel-center distance with a strictly-less update, so
                // the oy-outer/ox-inner loop order deterministically breaks
                // ties. Backs the fallback path; the kernel loop below is
                // untouched.
                accumulateNearestSite(frame, siteColor, sourceX, sourceY, r, p,
                    width, height, nearNum, nearDen)
                val interpolated = interpolatePrecision(
                    precision, guideW, guideH, sourceX * scaleX - 0.5, sourceY * scaleY - 0.5, scratch)
                if (interpolated == null) continue
                val centerX = floor(sourceX).toInt()
                val centerY = floor(sourceY).toInt()
                for (oy in -1..1) for (ox in -1..1) {
                    val tx = centerX + ox
                    val ty = centerY + oy
                    if (tx < 0 || ty < 0 || tx >= width || ty >= height) continue
                    // Same-colour gate: the tap's sensor colour must match the
                    // target site colour. Cross-colour taps never contribute —
                    // this is what makes the output a CFA, not a demosaicing.
                    val tapColor = frame.sensorPattern.colorAt(frame.sensorLeft + tx, frame.sensorTop + ty)
                    if (tapColor != siteColor) continue
                    val sample = samples[ty * width + tx].toDouble()
                    // Censored taps (>= guard) carry no trustworthy signal and
                    // must not bleed through kernel means — mirrors the packed
                    // oracle; the nearest path keeps finite-only sampling.
                    if (!sample.isFinite() || sample >= RawSrBayerMerge.SATURATED_REF_GUARD) continue
                    val distX = (tx + 0.5 - sourceX) / 2.0
                    val distY = (ty + 0.5 - sourceY) / 2.0
                    val z = interpolated[0] * distX * distX +
                        (interpolated[1] + interpolated[2]) * distX * distY +
                        interpolated[3] * distY * distY
                    if (!z.isFinite()) continue
                    val weight = exp(-0.5 * maxOf(z, 0.0))
                    if (!weight.isFinite()) continue
                    val weighted = weight * r
                    num.put(p, (num.get(p) + weighted * sample).toFloat())
                    den.put(p, (den.get(p) + weighted).toFloat())
                    if (taps != null) taps[p]++
                }
            }
        }
        }
    }

    /**
     * Burst-nearest sample accumulation for one frame at one mosaic target
     * site: the nearest finite sample whose sensor colour matches the site
     * colour, in the floor-centered 3x3 window around (sourceX, sourceY),
     * weighted by [r]. Nearest by texel-center distance squared with a
     * strictly-less update, so the oy-outer/ox-inner loop order
     * deterministically breaks ties.
     */
    private fun accumulateNearestSite(
        frame: RawSrBayerMerge.MergeFrame,
        siteColor: CfaColor,
        sourceX: Double,
        sourceY: Double,
        r: Double,
        p: Int,
        width: Int,
        height: Int,
        nearNum: FloatBuffer,
        nearDen: FloatBuffer
    ) {
        val centerX = floor(sourceX).toInt()
        val centerY = floor(sourceY).toInt()
        var best = Double.POSITIVE_INFINITY
        var bestSample = Double.NaN
        for (oy in -1..1) for (ox in -1..1) {
            val tx = centerX + ox
            val ty = centerY + oy
            if (tx < 0 || ty < 0 || tx >= width || ty >= height) continue
            if (frame.sensorPattern.colorAt(frame.sensorLeft + tx, frame.sensorTop + ty) != siteColor) continue
            val sample = frame.samples[ty * width + tx].toDouble()
            if (!sample.isFinite()) continue
            val dx = tx + 0.5 - sourceX
            val dy = ty + 0.5 - sourceY
            val d2 = dx * dx + dy * dy
            if (d2 < best) {
                best = d2
                bestSample = sample
            }
        }
        if (bestSample.isFinite()) {
            nearNum.put(p, (nearNum.get(p) + r * bestSample).toFloat())
            nearDen.put(p, (nearDen.get(p) + r).toFloat())
        }
    }

    /**
     * Reference-pass direct site sample for the censored-site rule: the floor
     * source tap when its sensor colour matches the target site colour, NaN
     * otherwise (no direct sample exists there). Reference passes run with
     * zero shift, so the source is the plain scaled site position. The
     * streaming fill and the eager inline recompute share this exact
     * expression (same Float32 in, same Double out).
     */
    private fun refSiteTap(
        frame: RawSrBayerMerge.MergeFrame,
        pattern: BayerPattern,
        qx: Int, qy: Int,
        width: Int, height: Int
    ): Double {
        val sourceX = (qx + 0.5) / LINEAR_SCALE
        val sourceY = (qy + 0.5) / LINEAR_SCALE
        if (!sourceX.isFinite() || !sourceY.isFinite() ||
            sourceX < 0.0 || sourceY < 0.0 || sourceX >= width || sourceY >= height
        ) return Double.NaN
        val sx = floor(sourceX).toInt()
        val sy = floor(sourceY).toInt()
        if (frame.sensorPattern.colorAt(frame.sensorLeft + sx, frame.sensorTop + sy) !=
            pattern.colorAt(qx, qy)
        ) return Double.NaN
        return frame.samples[sy * width + sx].toDouble()
    }

    private fun interpolatePrecision(
        precision: FloatArray, guideW: Int, guideH: Int, gx: Double, gy: Double,
        out: DoubleArray
    ): DoubleArray? {
        if (!gx.isFinite() || !gy.isFinite()) return null
        val cx = gx.coerceIn(0.0, (guideW - 1).toDouble())
        val cy = gy.coerceIn(0.0, (guideH - 1).toDouble())
        val x0 = floor(cx).toInt().coerceIn(0, guideW - 1)
        val y0 = floor(cy).toInt().coerceIn(0, guideH - 1)
        val x1 = minOf(x0 + 1, guideW - 1)
        val y1 = minOf(y0 + 1, guideH - 1)
        val fx = cx - x0
        val fy = cy - y0
        for (c in 0..3) {
            val v00 = precision[(y0 * guideW + x0) * 4 + c].toDouble()
            val v10 = precision[(y0 * guideW + x1) * 4 + c].toDouble()
            val v01 = precision[(y1 * guideW + x0) * 4 + c].toDouble()
            val v11 = precision[(y1 * guideW + x1) * 4 + c].toDouble()
            if (!v00.isFinite() || !v10.isFinite() || !v01.isFinite() || !v11.isFinite()) return null
            out[c] = v00 * (1.0 - fx) * (1.0 - fy) + v10 * fx * (1.0 - fy) +
                v01 * (1.0 - fx) * fy + v11 * fx * fy
        }
        return out
    }
}
