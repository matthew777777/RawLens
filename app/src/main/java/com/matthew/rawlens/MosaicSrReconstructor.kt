// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CancellationException
import kotlin.math.floor

/**
 * Mosaic SR: direct CFA-target reconstruction at [RawSrMosaicScale]
 * ([RawSrMosaicScale.SR] upscales to the [LINEAR_SCALE] grid,
 * [RawSrMosaicScale.NATIVE] keeps sensor resolution), sharing the
 * reference-parity accumulation math with [RawSrBayerMerge] (Jamy-L Alg. 4:
 * nearest-tile flow lookup, nearest-quad robustness fetch with the
 * reference one-quad shift, covariance interpolation + per-pixel
 * inversion, raw-unit Gaussian weights, robustness with r_ref = 1).
 *
 * This is NOT a re-mosaicing of merged RGB. Each target CFA site accumulates
 * only aligned same-colour source observations, routed by sensor colour
 * ([BayerPattern.colorAt]); the reference merges with r = 1; sites with no
 * support divide to 0 like the reference NaN blacked downstream; Rc folds
 * through [RawSrRobustness.accumulate] unchanged.
 *
 * Target grid: linear scale [RawSrMosaicScale.SR] (≈√2, so a ~12 MP source
 * becomes a ~24 MP target by AREA, not 2× width and height), floored to the
 * even grid at or below dim×s to preserve the 2×2 Bayer phase and the
 * source aspect ratio; NATIVE reproduces the source grid exactly. The
 * target phase is the source phase at the shared origin (crop-origin
 * aware).
 *
 * Single-precision accumulation over flat arrays, mirroring the linear merge.
 * The RGB merge stays the JPEG path; this reconstruction feeds only the
 * Mosaic SR DNG.
 *
 * Chroma latch guard (deliberate deviation, shared with [RawSrBayerMerge]):
 * R/B sites merge with a 2x-wider kernel than green sites
 * ([RawSrBayerMerge.CHROMA_SIGMA_MPY]), Nyquist-matched to their 2px
 * lattices; the reference reuses the razor kernel for every site and
 * latches R/B sites onto single taps (chroma zipper: with across-edge
 * radii ~0.13 raw px a site whose taps all fall far from the source
 * divides to exactly 0, a saturated confetti dot). Green sites are bitwise
 * reference-verbatim; `chromaSigmaMpy = 1.0` restores the full reference
 * path.
 */
object MosaicSrReconstructor {
    /** √2 linear scale: target area ≈ 2× source area. */
    const val LINEAR_SCALE = 1.4142135623730951
    const val ALGORITHM_VERSION = "RawLens-MosaicSr/5K-neutral-highlights"
    /**
     * Zero-support gate (reference `utils.divide` parity, shared with
     * [RawSrBayerMerge.EPS]): sites divide to 0 only at exactly-zero weight;
     * tiny but nonzero support divides to its finite mean.
     */
    const val EPS = 0.0

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
     * Even target grid at [scale], preserving aspect ratio. Rounding is
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
        phasePattern: BayerPattern = BayerPattern.RGGB,
        scale: RawSrMosaicScale = RawSrMosaicScale.SR
    ): TargetGrid {
        require(sourceWidth >= 2 && sourceHeight >= 2 && sourceWidth % 2 == 0 && sourceHeight % 2 == 0) {
            "Mosaic SR needs an even source crop of at least 2x2"
        }
        // Shared planner: the mosaic SR grid is pixel-identical to the
        // linear SR grid by construction, never by duplicated rounding.
        val (width, height) = planSrOutputDims(sourceWidth, sourceHeight, scale.factor)
        return TargetGrid(width, height, phasePattern, scale.factor)
    }

    /**
     * Reconstruct [reference] + [moving] onto the target grid at [scale]
     * ([RawSrMosaicScale.SR] upscales to the √2 grid, NATIVE keeps sensor
     * resolution). [targetPattern] defaults to the source phase at the
     * shared origin; pass an explicit phase only to test phase selection.
     * Frames reuse [RawSrBayerMerge.MergeFrame] so the validated model
     * inputs transfer unchanged.
     */
    fun reconstruct(
        reference: RawSrBayerMerge.MergeFrame,
        moving: List<RawSrBayerMerge.MergeFrame>,
        targetPattern: BayerPattern? = null,
        referenceOnly: Boolean = false,
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null,
        scale: RawSrMosaicScale = RawSrMosaicScale.SR,
        chromaSigmaMpy: Double = RawSrBayerMerge.CHROMA_SIGMA_MPY
    ): MosaicSrResult {
        require(chromaSigmaMpy.isFinite() && chromaSigmaMpy > 0.0) {
            "chromaSigmaMpy must be finite and positive"
        }
        // Precision-domain scale for R/B sites (sigma x s <=> z / s^2);
        // green sites always use the unscaled z (bitwise reference path).
        val chromaZScale = RawSrCoreKernel.chromaZScale(chromaSigmaMpy)
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
            require(frame.covariance.width == quadsW && frame.covariance.height == quadsH) {
                "$label covariance grid must match the quad grid"
            }
        }
        checkFrame(reference, "Reference")
        frames.forEachIndexed { index, frame ->
            checkFrame(frame, "Moving $index")
            require(frame.flow != null && frame.robustness != null) { "Moving $index needs flow and robustness" }
            require(frame.flow.coversRaw(width, height)) {
                "Moving $index flow must cover the raw lattice"
            }
            require(frame.robustness.width == quadsW && frame.robustness.height == quadsH) {
                "Moving $index robustness grid must match the quad grid"
            }
        }
        // The target grid shares the source origin: its (0,0) site carries
        // the reference frame's origin-shifted phase verbatim.
        val plan = planTarget(width, height, reference.sensorPattern, scale)
        val linearScale = plan.scale
        val pattern = targetPattern ?: plan.pattern
        val outW = plan.width
        val outH = plan.height
        val pixels = outW * outH
        // Single-precision accumulators: arithmetic stays Double and narrows
        // on store, identically on both paths, so eager/streaming agreement
        // is still bitwise.
        val num = FloatArray(pixels)
        val den = FloatArray(pixels)
        val taps = IntArray(pixels)
        val oob = IntArray(pixels)
        var rowsDone = 0
        val numBuf = FloatBuffer.wrap(num)
        val denBuf = FloatBuffer.wrap(den)
        for (frame in frames) {
            accumulateFrame(frame, frame.flow!!, frame.robustness!!, pattern,
                numBuf, denBuf, taps, oob,
                width, height, quadsW, outW, outH, linearScale, false, chromaZScale)
            rowsDone += outH
            if (isCancelled?.invoke(rowsDone) == true) throw CancellationException("Mosaic SR cancelled")
        }
        // Canonical accumulation order (mirrors streaming exactly — moving
        // frames in order, then the reference — so the two paths agree
        // bitwise even though every store narrows to float).
        accumulateFrame(reference, null, null, pattern,
            numBuf, denBuf, taps, oob,
            width, height, quadsW, outW, outH, linearScale, true, chromaZScale)
        // Rc over the effective (finite-sanitized) weights, like the linear
        // merge; empty (hence zero) under referenceOnly.
        val rcValues = FloatArray(quadsW * quadsH)
        for (frame in frames) {
            RawSrCoreRobustness.accumulateRcInPlace(rcValues, frame.robustness!!.r)
        }
        val rc = RawSrRobustness.RcField(quadsW, quadsH, rcValues)
        val cfa = FloatArray(pixels)
        val weight = FloatArray(pixels)
        val fallback = BooleanArray(pixels)
        // Reference divide: plain per-site quotient, 0 at zero support.
        // Row-sharded: disjoint rows, bitwise-identical.
        RawSrWorkers.forEachShard(outH) { y0, y1 ->
            for (qy in y0 until y1) {
                for (qx in 0 until outW) {
                    val p = qy * outW + qx
                    // Accumulator reads widen to Double at the decision boundary (one
                    // narrowing on store, identically on both paths).
                    val denD = den[p].toDouble()
                    val numD = num[p].toDouble()
                    cfa[p] = RawSrCoreKernel.divide(numD, denD).toFloat()
                    if (RawSrCoreKernel.divideFallback(numD, denD)) fallback[p] = true
                    weight[p] = RawSrCoreKernel.sanitize(den[p])
                }
            }
        }
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
     * full-res frame instead of ~150MB × burst size) and the accumulators —
     * one pixel-sized kernel pair plus one quad-sized Rc plane — live in
     * memory-mapped temp files, costing zero Dalvik heap.
     *
     * Moving frames arrive via [moving] and are released as consumed, so the
     * sequence must be single-use; the reference frame is built once via
     * [buildReference] and accumulated last, then dropped. With no surviving
     * moving frame this throws [MergeUnavailableException] exactly like the
     * eager chain, and the caller falls back to the reference DNG.
     */
    fun reconstructStreaming(
        geometry: RawSrMergeJob.ReferenceGeometry,
        moving: Sequence<RawSrBayerMerge.MergeFrame>,
        buildReference: () -> RawSrBayerMerge.MergeFrame,
        targetPattern: BayerPattern? = null,
        tempDir: File,
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null,
        scale: RawSrMosaicScale = RawSrMosaicScale.SR,
        chromaSigmaMpy: Double = RawSrBayerMerge.CHROMA_SIGMA_MPY
    ): StreamingMosaic {
        require(chromaSigmaMpy.isFinite() && chromaSigmaMpy > 0.0) {
            "chromaSigmaMpy must be finite and positive"
        }
        val chromaZScale = RawSrCoreKernel.chromaZScale(chromaSigmaMpy)
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
            require(frame.covariance.width == quadsW && frame.covariance.height == quadsH) {
                "$label covariance grid must match the quad grid"
            }
        }
        val plan = planTarget(width, height, geometry.pattern, scale)
        val linearScale = plan.scale
        val pattern = targetPattern ?: plan.pattern
        val outW = plan.width
        val outH = plan.height
        val pixels = outW * outH
        // One pixel-sized kernel pair plus one quad-sized Rc plane: the
        // streaming path tracks Rc exactly like the eager path (plain
        // per-quad sums, cf. RawSrRobustness.accumulate).
        return useMappedFloats(tempDir, intArrayOf(pixels, pixels, quadsW * quadsH)) { acc ->
            val num = acc[0]
            val den = acc[1]
            val rcAcc = acc[2]
            var rowsDone = 0
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
                    require(current.flow.coversRaw(width, height)) {
                        "Moving $accepted flow must cover the raw lattice"
                    }
                    require(current.robustness.width == quadsW && current.robustness.height == quadsH) {
                        "Moving $accepted robustness grid must match the quad grid"
                    }
                    val tAcc0 = android.os.SystemClock.elapsedRealtime()
                    accumulateFrame(current, current.flow, current.robustness, pattern,
                        num, den, null, null,
                        width, height, quadsW, outW, outH, linearScale, false, chromaZScale)
                    val tAcc1 = android.os.SystemClock.elapsedRealtime()
                    val support = current.robustness.r
                    RawSrWorkers.forEachShard(support.size) { q0, q1 ->
                        for (q in q0 until q1) {
                            val v = support[q]
                            rcAcc.put(q, rcAcc.get(q) + RawSrCoreRobustness.sanitizeWeight(v))
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
            // Canonical order (mirrors eager): the reference accumulates last
            // into the shared pair. The `run` scope is load-bearing: the
            // reference MergeFrame (~100MB) must be unreachable after the
            // pass so its heap is reusable.
            run {
                val ref = buildReference().also { checkFrame(it, "Reference") }
                accumulateFrame(ref, null, null, pattern,
                    num, den, null, null,
                    width, height, quadsW, outW, outH, linearScale, true, chromaZScale)
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
            // Reference divide: plain per-site quotient, 0 at zero support.
            // Row-sharded: disjoint rows/indices.
            RawSrWorkers.forEachShard(outH) { y0, y1 ->
                for (qy in y0 until y1) {
                    for (qx in 0 until outW) {
                        val p = qy * outW + qx
                        val d = den.get(p).toDouble()
                        cfa[p] = RawSrCoreKernel.divide(num.get(p).toDouble(), d).toFloat()
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
        taps: IntArray?,
        oob: IntArray?,
        width: Int,
        height: Int,
        quadsW: Int,
        outW: Int,
        outH: Int,
        linearScale: Double,
        isReference: Boolean,
        chromaZScale: Double,
        onRow: (() -> Boolean)? = null
    ) {
        val samples = frame.samples
        val covariance = frame.covariance.values
        val guideW = frame.covariance.width
        val guideH = frame.covariance.height
        val scaleX = RawSrCoreSampling.guideScale(guideW, width)
        val scaleY = RawSrCoreSampling.guideScale(guideH, height)
        // Hoisted CFA phase tables (RED=0, GREEN=1, BLUE=2): the site gate
        // and the tap gate below index these directly instead of dispatching
        // colorAt per tap — identical routing, no enum traffic.
        val sitePhase = pattern.cellOrdinals
        val tapPhase = frame.sensorPattern.cellOrdinals
        // Per-axis coordinate LUTs: sourceCenter/robustnessSamplePos are pure
        // functions of (out, scale), so precomputing once per frame replaces
        // outW*outH divisions with outW+outH loads. Same functions, same
        // args, same doubles — bitwise-identical. flowLookupPos aliases
        // sourceCenter, so the flow lookup reuses the source LUT directly.
        val srcX = DoubleArray(outW) { x -> RawSrCoreSampling.sourceCenter(x, linearScale) }
        val srcY = DoubleArray(outH) { y -> RawSrCoreSampling.sourceCenter(y, linearScale) }
        val robX = DoubleArray(outW) { x -> RawSrCoreSampling.robustnessSamplePos(x, linearScale) }
        val robY = DoubleArray(outH) { y -> RawSrCoreSampling.robustnessSamplePos(y, linearScale) }
        // Row-sharded across the shared worker pool: shards cover disjoint
        // output rows with the serial per-pixel code untouched, so the
        // accumulation is bitwise-identical at any worker count. The
        // per-pixel scratch is shard-local (no per-pixel allocation).
        RawSrWorkers.forEachShard(outH) { y0, y1 ->
            val scratch = DoubleArray(4)
            val flowScratch = FloatArray(4)
            val tapScratch = DoubleArray(2)
            for (qy in y0 until y1) {
                if (onRow?.invoke() == true) throw CancellationException("Mosaic SR cancelled")
                val siteRow = (qy and 1) shl 1
                val sourceBaseY = srcY[qy]
                val robBaseY = robY[qy]
                for (qx in 0 until outW) {
                val p = qy * outW + qx
                val siteColor = sitePhase[siteRow or (qx and 1)]
                val sourceBaseX = srcX[qx]
                // Reference-source position of the site center, plus the
                // bilinear-blended flow displacement in raw pixels (the
                // smooth lookup below).
                val dx: Double
                val dy: Double
                val r: Double
                if (isReference) {
                    dx = 0.0; dy = 0.0; r = 1.0
                } else {
                    // Bilinear flow lookup (Sabre-style dense gather,
                    // mirroring the linear merge twin): the warp is
                    // C0-continuous, so no tile tears can form;
                    // mistakes are rejected per-pixel by r, not by
                    // flow vetoes. Non-finite corners fall back to
                    // the containing tile; a non-finite result skips
                    // the site.
                    flow!!.flowAtSmoothInto(
                        sourceBaseX.toFloat(), sourceBaseY.toFloat(), flowScratch)
                    if (!flowScratch[0].isFinite() || !flowScratch[1].isFinite()) {
                        if (oob != null) oob[p]++
                        continue
                    }
                    dx = flowScratch[0].toDouble()
                    dy = flowScratch[1].toDouble()
                    // Reference `cpu_accumulate` robustness fetch
                    // verbatim: nearest quad with the one-quad shift
                    // (min(int(lr//2-0.5))), sampled at s = b - 1.
                    r = RawSrCoreSampling.sampleRobustness(
                        robust!!.r, robust.width, robust.height,
                        robX[qx], robBaseY)
                }
                if (r == 0.0) continue
                // Reference lr_mov = lr + flow verbatim: raw-unit flow.
                val sourceX = sourceBaseX + dx
                val sourceY = sourceBaseY + dy
                if (!sourceX.isFinite() || !sourceY.isFinite() ||
                    sourceX < 0.0 || sourceY < 0.0 || sourceX >= width || sourceY >= height
                ) {
                    if (oob != null) oob[p]++
                    continue
                }
                val interpolated = RawSrCoreSampling.interpolateCovariance(
                    covariance, guideW, guideH,
                    RawSrCoreSampling.covarianceGuideCoord(sourceX, scaleX),
                    RawSrCoreSampling.covarianceGuideCoord(sourceY, scaleY),
                    scratch)
                if (interpolated == null) continue
                // One cross term per site, not per tap: same operands, same
                // sum, bitwise-identical exponent in the tap loop.
                val cross = interpolated[1] + interpolated[2]
                val centerX = floor(sourceX).toInt()
                val centerY = floor(sourceY).toInt()
                // Accumulator traffic: the streaming path backs num/den with
                // memory-mapped files, so every get/put crosses the buffer
                // boundary. One site's taps touch only that site's pair, so
                // a single load/add/store pass through float locals issues
                // the identical float additions in the identical order —
                // bitwise-identical output with one buffer round-trip per
                // site instead of one per tap.
                var numAcc = num.get(p)
                var denAcc = den.get(p)
                for (oy in -1..1) for (ox in -1..1) {
                    val tx = centerX + ox
                    val ty = centerY + oy
                    if (tx < 0 || ty < 0 || tx >= width || ty >= height) continue
                    // Same-colour gate: the tap's crop-relative colour must match
                    // the target site colour (sensorPattern is already
                    // origin-shifted; folding the origin again swaps R/B on
                    // odd crops). Cross-colour taps never contribute —
                    // this is what makes the output a CFA, not a demosaicing.
                    // Ordinal compare over the hoisted phase tables.
                    if (tapPhase[((ty and 1) shl 1) or (tx and 1)] != siteColor) continue
                    val sample = samples[ty * width + tx].toDouble()
                    // Every finite sample merges (no censor skip): the
                    // reference defines none. Non-finite taps are
                    // reference-undefined and skipped.
                    if (!sample.isFinite()) continue
                    val distX = tx + 0.5 - sourceX
                    val distY = ty + 0.5 - sourceY
                    val z = RawSrCoreKernel.tapZ(
                        interpolated[0], cross, interpolated[3], distX, distY)
                    // Chroma latch guard: R/B sites (ordinal != 1) see the
                    // widened kernel (z / s^2); green sites keep the
                    // unscaled z, so the luma lane is bitwise-identical
                    // with or without the guard. The same-colour gate
                    // above already ensures every tap matches the site.
                    if (!RawSrCoreKernel.accumulateTap(
                            z, r, sample, chromaZScale, siteColor == 1, tapScratch)
                    ) continue
                    numAcc = (numAcc + tapScratch[0]).toFloat()
                    denAcc = (denAcc + tapScratch[1]).toFloat()
                    if (taps != null) taps[p]++
                }
                num.put(p, numAcc)
                den.put(p, denAcc)
            }
        }
        }
    }

    /**
     * Narrow-axis floor for precision-space kernel fields (KernelNet A/B
     * path only; the reference-parity base path applies no clamp): no
     * kernel axis narrower than [MIN_MINOR_SIGMA] quad px. The floor value
     * is a measured tradeoff (mosaic oracle, period-4 grating): σ0.25 keeps
     * 87% texture contrast, σ0.3 keeps 73%, σ0.4 keeps 51%, σ0.5 keeps 39%;
     * step rise is 0/1/2 target px respectively ([minorAxisSigmaFloor]).
     */
    const val MIN_MINOR_SIGMA = 0.3
    /**
     * A/B override for the narrow-axis floor (tests / textured-target
     * evaluation). Default is [MIN_MINOR_SIGMA]; read per call site.
     */
    @Volatile var minorAxisSigmaFloor = MIN_MINOR_SIGMA

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

    // Sampling (robustness fetch, covariance interpolation), tap weights,
    // and the finalizer live in [RawSrCoreSampling]/[RawSrCoreKernel]; this
    // file keeps orchestration, sharding, and the streaming accumulators.
}
