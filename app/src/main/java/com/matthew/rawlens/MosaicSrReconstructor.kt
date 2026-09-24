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
    const val ALGORITHM_VERSION = "RawLens-MosaicSr/5H-neutral-highlights"
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
     * out-of-bounds. [phasePattern] is the source phase AT the shared (crop)
     * origin — i.e. an already-shifted pattern such as
     * [RawSrBayerMerge.MergeFrame.sensorPattern] — carried verbatim onto the
     * target grid. It must NOT be shifted here: shifting an already-shifted
     * pattern double-folds the phase and mislabels the target CFA (and every
     * same-colour gate reading it) on odd crop origins.
     */
    fun planTarget(
        sourceWidth: Int,
        sourceHeight: Int,
        phasePattern: BayerPattern = BayerPattern.RGGB
    ): TargetGrid {
        require(sourceWidth >= 2 && sourceHeight >= 2 && sourceWidth % 2 == 0 && sourceHeight % 2 == 0) {
            "Mosaic SR needs an even source crop of at least 2x2"
        }
        // Floor to even: 2×floor(dim×s/2). A ~12 MP source lands at ~2× area.
        val width = (floorToEven(sourceWidth * LINEAR_SCALE)).coerceAtLeast(2)
        val height = (floorToEven(sourceHeight * LINEAR_SCALE)).coerceAtLeast(2)
        return TargetGrid(width, height, phasePattern, LINEAR_SCALE)
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
        // The target grid shares the source origin: its (0,0) site carries
        // the reference frame's origin-shifted phase verbatim.
        val plan = planTarget(width, height, reference.sensorPattern)
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
        RawSrWorkers.forEachShard(num.size) { i0, i1 ->
            for (i in i0 until i1) {
                num[i] += refNum[i]
                den[i] += refDen[i]
                nearNum[i] += nearRefNum[i]
                nearDen[i] += nearRefDen[i]
            }
        }
        for (frame in frames) {
            accumulateFrame(frame, frame.flow!!, frame.robustness!!, pattern,
                numBuf, denBuf, nearNumBuf, nearDenBuf, taps, oob,
                width, height, quadsW, outW, outH, false)
            rowsDone += outH
            if (isCancelled?.invoke(rowsDone) == true) throw CancellationException("Mosaic SR cancelled")
        }
        // Fused sanitize+accumulate: one alloc and one pass per frame instead
        // of a sanitized copy plus accumulate's own copy (was: 2 allocs, 2
        // passes). Same index order, same addition — bitwise-identical.
        var rcValues = FloatArray(quadsW * quadsH)
        for (frame in frames) {
            val robust = frame.robustness!!
            val next = rcValues.copyOf()
            val r = robust.r
            for (i in next.indices) {
                val v = r[i]
                next[i] += if (v.isFinite()) v else 0f
            }
            rcValues = next
        }
        val rc = RawSrRobustness.RcField(quadsW, quadsH, rcValues)
        val cfa = FloatArray(pixels)
        val weight = FloatArray(pixels)
        val fallback = BooleanArray(pixels)
        // Accumulated-robustness overwrite (merge contract §9, mirrors the
        // packed oracle): a target site reads support from its source quad —
        // the same quad whose robustness fed accumulation. Below MIN_SUPPORT
        // it joins the fallback set instead of keeping a ghost-prone kernel
        // blend. Inert with no moving frames, where the output already equals
        // the reference. Row-sharded: disjoint rows, bitwise-identical.
        val overwrite = frames.isNotEmpty()
        RawSrWorkers.forEachShard(outH) { y0, y1 ->
            for (qy in y0 until y1) {
                // Hoisted row quad: the mapping depends on qy only through the
                // row term, but the per-pixel expression is kept verbatim.
                for (qx in 0 until outW) {
                    val p = qy * outW + qx
                    val quadX = (((qx + 0.5) / LINEAR_SCALE) / 2.0).toInt().coerceIn(0, quadsW - 1)
                    val quadY = (((qy + 0.5) / LINEAR_SCALE) / 2.0).toInt().coerceIn(0, quadsH - 1)
                    val unsupported =
                        overwrite && rc.values[quadY * quadsW + quadX] < RawSrBayerMerge.MIN_SUPPORT
                    // All target colours use the same reference neighbourhood.
                    // Resolve clipped chroma on the camera-neutral ray after
                    // normal merging, including sites with censored kernel support.
                    val highlightPeak = referenceHighlightPeak(reference, qx, qy, width, height)
                    val highlight = RawSrHighlights.amount(highlightPeak) > 0.0
                    // Accumulator reads widen to Double at the decision boundary (one
                    // narrowing on store, identically on both paths).
                    val denD = den[p].toDouble()
                    if (highlight) fallback[p] = true
                    val value = if (!unsupported && denD > EPS) {
                        num[p] / maxOf(denD, EPS)
                    } else {
                        fallback[p] = true
                        // Reference-kernel fallback first: the reference-only
                        // quotient is a full 3x3 kernel mean (smooth, honest
                        // single-frame value), while the burst-nearest below
                        // is one tap per frame (jagged on sparse distant
                        // sites, blocky where the plane never filled — the
                        // figure's resolve step only trusts filled regions).
                        // Nearest survives solely where the reference kernel
                        // itself has no support. Nested ref-only fallback
                        // where even the nearest path has no support, and the
                        // reference sample is clipped (saturation guard:
                        // clipped sites carry no trustworthy signal).
                        val refValue = refNum[p] / maxOf(refDen[p].toDouble(), EPS)
                        val refSupported = refDen[p].toDouble() > EPS
                        val nd = nearDen[p].toDouble()
                        if (refValue >= RawSrBayerMerge.SATURATED_REF_GUARD) refValue
                        else if (refSupported) refValue
                        else if (nd > EPS) nearNum[p] / maxOf(nd, EPS)
                        else refValue
                    }
                    cfa[p] = RawSrHighlights.resolve(value, highlightPeak,
                        reference.highlightNeutral[RawSrHighlights.channel(pattern.colorAt(qx, qy))]).toFloat()
                    weight[p] = den[p].toFloat().let { if (it.isFinite()) it else 0f }
                }
            }
        }
        // rc accumulated above over `frames` (empty under referenceOnly,
        // hence already zero there); nothing left to reset.
        return MosaicSrResult(outW, outH, pattern, cfa, weight, taps, rc, oob, fallback)
    }

    /**
     * Lean production result: only what the Mosaic SR DNG saver consumes
     * (width/height/pattern/cfa). Weight, taps, Rc, oob and the fallback mask
     * are QA diagnostics of the List path; the file carries CFA samples only.
     * [meanSupport] is the mean per-quad support (1 + Rc) backing the merged
     * noise model (see RawSrMergedNoise): a single scalar, not the mapped
     * accumulator, so it survives the temp-file cleanup.
     */
    data class StreamingMosaic(
        val width: Int,
        val height: Int,
        val pattern: BayerPattern,
        val cfa: FloatArray,
        val acceptedFrames: Int,
        val meanSupport: Double
    )

    /**
     * Memory-bound production reconstruct: same math as [reconstruct] (same
     * accumulation order, same double operations — bitwise-identical CFA), but
     * frames stream one at a time (accumulate-then-drop, ~150MB peak per
     * full-res frame instead of ~150MB × burst size) and the six double
     * accumulators (kernel pair, reference pair, burst-nearest pair) live in
     * memory-mapped temp files (~0.6GB at full res), costing zero Dalvik
     * heap. Heap peak is ~415MB of a 512MB heap. Since the support-overwrite
     * adoption the file set is eight: the six pixel-sized pairs, one
     * quad-sized support (Rc) accumulator driving the overwrite, plus one
     * pixel-sized reference highlight-peak capture
     * (~100MB at full res — heap allocation here OOMs the save, see the
     * highlight-plane note below).
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
        val plan = planTarget(width, height, geometry.pattern)
        val pattern = targetPattern ?: plan.pattern
        val outW = plan.width
        val outH = plan.height
        val pixels = outW * outH
        // Six pixel-sized pairs, one quad-sized support accumulator, one
        // pixel-sized highlight-peak capture: the streaming path tracks Rc exactly
        // like the eager path (plain per-quad sums, cf.
        // RawSrRobustness.accumulate) so the accumulated-robustness
        // overwrite decides identically. The highlight-peak capture (~100MB at full
        // res) MUST stay mapped: a heap FloatArray here plus the reference
        // frame plus the first moving-frame build peaks past the 512MB heap
        // and every moving frame OOMs inside the chain (swallowed as a
        // rejection), failing the save with "kept no moving frame". Mapped
        // files start zeroed; the reference pass below overwrites every site
        // (floor-to-even grids map strictly inside, so no site is born OOB),
        // and zero leaves highlight rolloff disabled.
        return useMappedFloats(tempDir, IntArray(6) { pixels } + quadsW * quadsH + pixels) { acc ->
            val num = acc[0]
            val den = acc[1]
            val refNum = acc[2]
            val refDen = acc[3]
            val nearNum = acc[4]
            val nearDen = acc[5]
            val rcAcc = acc[6]
            val highlightPeaks = acc[7]
            var rowsDone = 0
            var highlightNeutral = floatArrayOf(1f, 1f, 1f)
            // Built once, accumulated first, then released before any moving
            // frame materializes. The `run` scope is load-bearing: the
            // reference MergeFrame (~100MB of samples + precision) must be
            // unreachable — not merely nulled through one alias while another
            // (`ref`) still roots it — so the first moving-frame build can
            // reuse its heap.
            run {
                val ref = buildReference()
                checkFrame(ref, "Reference")
                highlightNeutral = ref.highlightNeutral.copyOf()
                accumulateFrame(ref, null, null, pattern,
                    refNum, refDen, nearNum, nearDen, null, null,
                    width, height, quadsW, outW, outH, true,
                    highlightPeaks = highlightPeaks)
            }
            // Canonical order (mirrors eager): the reference pass already ran
            // above, so its pair joins the shared accumulators before any
            // moving frame — bitwise-identical sequencing on both paths.
            // Sharded: indexed get/put on disjoint indices (no position use).
            RawSrWorkers.forEachShard(pixels) { i0, i1 ->
                for (i in i0 until i1) {
                    num.put(i, num.get(i) + refNum.get(i))
                    den.put(i, den.get(i) + refDen.get(i))
                    // No near add: the reference pass accumulates its nearest
                    // contribution directly into the shared near pair above.
                }
            }
            var accepted = 0
            // Explicit iterator with a nulled slot (NOT a `for` loop): the
            // next sequence element is built by `next()` while the previous
            // frame is still in scope, so a `for` loop roots ~150MB of
            // previous-frame buffers during the next frame's ~200MB build and
            // peaks past the 512MB heap. Nulling before `next()` keeps the
            // accumulate-then-drop contract the streaming path promises.
            val iterator = moving.iterator()
            while (iterator.hasNext()) {
                var frame: RawSrBayerMerge.MergeFrame? = iterator.next()
                try {
                    val current = requireNotNull(frame) { "Moving frame missing" }
                    checkFrame(current, "Moving $accepted")
                    require(current.flow != null && current.robustness != null) {
                        "Moving $accepted needs flow and robustness"
                    }
                    require(current.flow.imageWidth == quadsW && current.flow.imageHeight == quadsH) {
                        "Moving $accepted flow grid must match the quad grid"
                    }
                    require(current.robustness.width == quadsW && current.robustness.height == quadsH) {
                        "Moving $accepted robustness grid must match the quad grid"
                    }
                    val tAcc0 = android.os.SystemClock.elapsedRealtime()
                    accumulateFrame(current, current.flow, current.robustness, pattern,
                        num, den, nearNum, nearDen, null, null,
                        width, height, quadsW, outW, outH, false)
                    val tAcc1 = android.os.SystemClock.elapsedRealtime()
                    val support = current.robustness.r
                    RawSrWorkers.forEachShard(support.size) { q0, q1 ->
                        for (q in q0 until q1) {
                            val v = support[q]
                            rcAcc.put(q, rcAcc.get(q) + (if (v.isFinite()) v else 0f))
                        }
                    }
                    if (android.util.Log.isLoggable("RawLensMosaic", android.util.Log.DEBUG)) {
                        android.util.Log.d("RawLensMosaic", "mosaic stream frame $accepted" +
                            " accumulate=${tAcc1 - tAcc0}ms" +
                            " rc=${android.os.SystemClock.elapsedRealtime() - tAcc1}ms")
                    }
                } finally {
                    frame = null
                }
                accepted++
                rowsDone += outH
                if (isCancelled?.invoke(rowsDone) == true) throw CancellationException("Mosaic SR cancelled")
            }
            if (accepted == 0) {
                throw MergeUnavailableException("Mosaic stream kept no moving frame after alignment")
            }
            val cfa = FloatArray(pixels)
            // Mean support for the merged noise model: one scalar pass over
            // the mapped Rc accumulator (megabytes read once, no heap
            // retained) before the temp files are deleted with the block.
            var supportSum = 0.0
            for (q in 0 until quadsW * quadsH) {
                val v = 1.0 + rcAcc.get(q).toDouble()
                supportSum += if (v.isFinite()) v else 1.0
            }
            val meanSupport = supportSum / (quadsW * quadsH)
            // Accumulated-robustness overwrite (merge contract §9, mirrors
            // the eager path and the packed oracle): accepted >= 1 here (the
            // empty stream throws above), so the rule is unconditionally
            // active. StreamingMosaic carries no mask by design (DNG saver
            // consumes CFA only); the overwrite still routes values through
            // the fallback branch. Row-sharded: disjoint rows/indices.
            RawSrWorkers.forEachShard(outH) { y0, y1 ->
                for (qy in y0 until y1) {
                    for (qx in 0 until outW) {
                        val p = qy * outW + qx
                        val quadX = (((qx + 0.5) / LINEAR_SCALE) / 2.0).toInt().coerceIn(0, quadsW - 1)
                        val quadY = (((qy + 0.5) / LINEAR_SCALE) / 2.0).toInt().coerceIn(0, quadsH - 1)
                        val unsupported = rcAcc.get(quadY * quadsW + quadX) < RawSrBayerMerge.MIN_SUPPORT
                        val d = den.get(p).toDouble()
                        // Captured reference neighbourhood peak; the source
                        // frame has already been released by the streaming path.
                        val highlightPeak = highlightPeaks.get(p).toDouble()
                        val value = if (!unsupported && d > EPS) {
                            num.get(p) / maxOf(d, EPS)
                        } else {
                            // Reference-kernel fallback first (mirrors the eager
                            // path): the reference-only quotient is a full 3x3
                            // kernel mean, while burst-nearest is one tap per
                            // frame. Nearest survives solely where the reference
                            // kernel itself has no support; clipped reference
                            // samples keep the reference value (saturation
                            // guard).
                            val nd = nearDen.get(p).toDouble()
                            val refDenD = refDen.get(p).toDouble()
                            val refValue = refNum.get(p) / maxOf(refDenD, EPS)
                            if (refValue >= RawSrBayerMerge.SATURATED_REF_GUARD) refValue
                            else if (refDenD > EPS) refValue
                            else if (nd > EPS) nearNum.get(p) / maxOf(nd, EPS)
                            else refValue
                        }
                        cfa[p] = RawSrHighlights.resolve(value, highlightPeak,
                            highlightNeutral[RawSrHighlights.channel(pattern.colorAt(qx, qy))]).toFloat()
                    }
                }
            }
            StreamingMosaic(outW, outH, pattern, cfa, accepted, meanSupport)
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
        // Streaming-only reference neighbourhood peaks. Reuse the mapped
        // plane so the reference CFA can be released before moving frames.
        highlightPeaks: FloatBuffer? = null
    ) {
        val samples = frame.samples
        val precision = frame.precision.values
        val guideW = frame.precision.width
        val guideH = frame.precision.height
        val scaleX = guideW.toDouble() / width
        val scaleY = guideH.toDouble() / height
        // All sites in an alignment tile ask the same 3x3-neighbour question.
        // Cache it once per tile instead of scanning nine flows per output pixel.
        val motionEdges = if (!isReference) RawSrRobustness.flowDisagreementTiles(
            checkNotNull(flow), RawSrBayerMerge.MOTION_EDGE_QUAD) else null
        // Row-sharded across the shared worker pool: shards cover disjoint
        // output rows with the serial per-pixel code untouched, so the
        // accumulation is bitwise-identical at any worker count. The
        // per-pixel precision scratch is shard-local (was: one DoubleArray
        // allocation per pixel, a GC hotspot at full res).
        RawSrWorkers.forEachShard(outH) { y0, y1 ->
            val scratch = DoubleArray(4)
            val flowScratch = FloatArray(4)
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
                    // Bilinear flow (flowAtSmoothInto), mirroring the packed
                    // oracle and the GPU accumulate twin: nearest-tile lookup
                    // imprints the 16px quilt at tile borders. Same quad
                    // coordinate space — only the sampling changes. The Into
                    // form is bitwise-identical to flowAtSmooth with no per-
                    // pixel boxing (was: 6 objects per pixel per frame).
                    flow!!.flowAtSmoothInto(quadX.toFloat(), quadY.toFloat(), flowScratch)
                    if (!flowScratch[0].isFinite() || !flowScratch[1].isFinite()) {
                        if (oob != null) oob[p]++
                        continue
                    }
                    dxQuad = flowScratch[0].toDouble()
                    dyQuad = flowScratch[1].toDouble()
                    val raw = robust!!.r[quadY.coerceIn(0, robust.height - 1) * quadsW +
                        quadX.coerceIn(0, quadsW - 1)]
                    r = if (raw.isFinite()) raw.toDouble() else 0.0
                    // Motion-edge stop (mirrors the packed oracle): skip
                    // splats where neighbouring tiles demonstrably disagree —
                    // no OOB bump. Unknown tiles merge (robustness backstop).
                    val tileX = (quadX / flow.tileSize).coerceIn(0, flow.columns - 1)
                    val tileY = (quadY / flow.tileSize).coerceIn(0, flow.rows - 1)
                    if (motionEdges!![tileY * flow.columns + tileX]) continue
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
                if (highlightPeaks != null && isReference) {
                    // Same source-coordinate footprint as the eager finalizer.
                    highlightPeaks.put(p, referenceHighlightPeak(frame, qx, qy, width, height).toFloat())
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
                    // Same-colour gate: the tap's crop-relative colour must match
                    // the target site colour (sensorPattern is already
                    // origin-shifted; folding the origin again swaps R/B on
                    // odd crops). Cross-colour taps never contribute —
                    // this is what makes the output a CFA, not a demosaicing.
                    val tapColor = frame.sensorPattern.colorAt(tx, ty)
                    if (tapColor != siteColor) continue
                    val sample = samples[ty * width + tx].toDouble()
                    // Censored taps (>= guard) carry no trustworthy signal and
                    // must not bleed through kernel means — the nearest path
                    // below censors identically, so a clipped white tap can
                    // never become a fallback value either.
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
            if (frame.sensorPattern.colorAt(tx, ty) != siteColor) continue
            val sample = frame.samples[ty * width + tx].toDouble()
            // Censored taps carry no trustworthy signal: a clipped white tap
            // must not become the site's fallback value (mirrors the kernel
            // loop's censor rule above and the packed oracle's nearest rule).
            if (!sample.isFinite() || sample >= RawSrBayerMerge.SATURATED_REF_GUARD) continue
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

    /** Source-coordinate 3x3 peak, independent of target CFA phase. */
    private fun referenceHighlightPeak(
        frame: RawSrBayerMerge.MergeFrame,
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
        return RawSrHighlights.peak(frame.samples, width, height, sx, sy)
    }

    /**
     * Narrow-axis clamp for merge-bound precision fields: no kernel axis
     * narrower than [MIN_MINOR_SIGMA] quad px. Sub-lattice across-axes
     * (≈0.2) collapse each colour channel onto its own sparse taps and
     * register each colour's edge separately (~±1px inter-channel straddle
     * with non-monotonic ringing) — zipper and colour leaks on the 1x RGB
     * merge itself, and single-target-row bright lines on the 1.4x mosaic
     * target once demosaiced. The clamp widens only the narrow axis
     * (orientation and the wide axis untouched), so transitions span an
     * honest ~2 px while along-edge smoothing and flat kernels pass
     * through. Applied in [RawSrMergeJob.mergeFrame] to analytic and
     * KernelNet fields alike, and at the KernelNet producers
     * ([RawSrKernelNetAniso]) so the 1x RGB GPU path — which uploads
     * KernelNet fields verbatim — gets the same floor.
     */
    const val MIN_MINOR_SIGMA = 0.5

    fun clampMinorAxis(
        field: RawSrKernelCovariance.MatrixField,
        sigmaMin: Double = MIN_MINOR_SIGMA
    ): RawSrKernelCovariance.MatrixField {
        val values = field.values.copyOf()
        clampMinorAxisInPlace(values, sigmaMin)
        return RawSrKernelCovariance.MatrixField(field.width, field.height, values)
    }

    /**
     * In-place core of [clampMinorAxis]: clamps the packed texels of
     * [values] (4 floats per kernel, `(m00, m01, m10, m11)`) without
     * allocating, so the KernelNet scratch-reuse contract survives the
     * clamp. Per-texel reads complete before writes and texels are
     * disjoint, hence bitwise-identical to [clampMinorAxis].
     */
    fun clampMinorAxisInPlace(
        values: FloatArray,
        sigmaMin: Double = MIN_MINOR_SIGMA
    ) {
        require(sigmaMin > 0.0) { "Minor-axis floor must be positive" }
        require(values.size % 4 == 0) { "Packed field must hold 4 coefficients per texel" }
        val n = values.size / 4
        val floor = sigmaMin * sigmaMin
        // Texel-sharded: disjoint outputs with the serial math untouched,
        // so any worker count agrees bitwise.
        RawSrWorkers.forEachShard(n) { i0, i1 ->
            val eig = FloatArray(6)
            for (i in i0 until i1) {
                val o = i * 4
                val p00 = values[o].toDouble()
                val p01 = values[o + 1].toDouble()
                val p11 = values[o + 3].toDouble()
                val detP = p00 * p11 - p01 * p01
                if (!detP.isFinite() || detP <= 0.0) {
                    // Degenerate texel: isotropic floor, finite by construction.
                    val f = (1.0 / floor).toFloat()
                    values[o] = f
                    values[o + 1] = 0f
                    values[o + 2] = 0f
                    values[o + 3] = f
                    continue
                }
                // Eigendecompose Σ = P^-1, clamp, recompose P' = Σ'^-1.
                val s00 = p11 / detP
                val s01 = -p01 / detP
                val s11 = p00 / detP
                RawSrKernelCovariance.eigenInto(
                    s00.toFloat(), s01.toFloat(), s11.toFloat(), eig
                )
                val l1 = maxOf(eig[4].toDouble(), floor)
                val l2 = maxOf(eig[5].toDouble(), floor)
                if (!l1.isFinite() || !l2.isFinite()) continue
                val e1x = eig[0].toDouble()
                val e1y = eig[1].toDouble()
                val e2x = eig[2].toDouble()
                val e2y = eig[3].toDouble()
                val t00 = l1 * e1x * e1x + l2 * e2x * e2x
                val t01 = l1 * e1x * e1y + l2 * e2x * e2y
                val t11 = l1 * e1y * e1y + l2 * e2y * e2y
                val detS = l1 * l2
                if (!t00.isFinite() || !t01.isFinite() || !t11.isFinite() ||
                    !detS.isFinite() || detS <= 0.0
                ) continue
                values[o] = (t11 / detS).toFloat()
                values[o + 1] = (-t01 / detS).toFloat()
                values[o + 2] = (-t01 / detS).toFloat()
                values[o + 3] = (t00 / detS).toFloat()
            }
        }
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
