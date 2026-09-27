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
 * Mosaic SR: direct CFA-target reconstruction at [LINEAR_SCALE], sharing the
 * reference-parity accumulation math with [RawSrBayerMerge] (Jamy-L Alg. 4:
 * nearest-tile flow, covariance interpolation + per-pixel inversion, raw-unit
 * Gaussian weights, one-quad-shifted robustness with r_ref = 1).
 *
 * This is NOT a re-mosaicing of merged RGB. Each target CFA site accumulates
 * only aligned same-colour source observations, routed by sensor colour
 * ([BayerPattern.colorAt]); the reference merges with r = 1; sites with no
 * support divide to 0 like the reference NaN blacked downstream; Rc folds
 * through [RawSrRobustness.accumulate] unchanged.
 *
 * Target grid: linear scale [LINEAR_SCALE] (≈√2, so a ~12 MP source becomes a
 * ~24 MP target by AREA, not 2× width and height), floored to the even grid
 * at or below dim×s to preserve the 2×2 Bayer phase and the source aspect
 * ratio. The target phase is the source phase at the shared origin
 * (crop-origin aware).
 *
 * Single-precision accumulation over flat arrays, mirroring the linear merge.
 * The RGB merge stays the JPEG path; this reconstruction feeds only the
 * Mosaic SR DNG.
 */
object MosaicSrReconstructor {
    /** √2 linear scale: target area ≈ 2× source area. */
    const val LINEAR_SCALE = 1.4142135623730951
    const val ALGORITHM_VERSION = "RawLens-MosaicSr/5K-neutral-highlights"
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
            require(frame.covariance.width == quadsW && frame.covariance.height == quadsH) {
                "$label covariance grid must match the quad grid"
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
                width, height, quadsW, outW, outH, false)
            rowsDone += outH
            if (isCancelled?.invoke(rowsDone) == true) throw CancellationException("Mosaic SR cancelled")
        }
        // Canonical accumulation order (mirrors streaming exactly — moving
        // frames in order, then the reference — so the two paths agree
        // bitwise even though every store narrows to float).
        accumulateFrame(reference, null, null, pattern,
            numBuf, denBuf, taps, oob,
            width, height, quadsW, outW, outH, true)
        // Rc over the effective (finite-sanitized) weights, like the linear
        // merge; empty (hence zero) under referenceOnly.
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
        // Reference divide: plain per-site quotient, 0 at zero support.
        // Row-sharded: disjoint rows, bitwise-identical.
        RawSrWorkers.forEachShard(outH) { y0, y1 ->
            for (qy in y0 until y1) {
                for (qx in 0 until outW) {
                    val p = qy * outW + qx
                    // Accumulator reads widen to Double at the decision boundary (one
                    // narrowing on store, identically on both paths).
                    val denD = den[p].toDouble()
                    val value = if (denD > EPS) {
                        num[p] / maxOf(denD, EPS)
                    } else {
                        fallback[p] = true
                        0.0
                    }
                    val finite = if (value.isFinite()) value else 0.0
                    if (!value.isFinite()) fallback[p] = true
                    cfa[p] = finite.toFloat()
                    weight[p] = den[p].toFloat().let { if (it.isFinite()) it else 0f }
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
            require(frame.covariance.width == quadsW && frame.covariance.height == quadsH) {
                "$label covariance grid must match the quad grid"
            }
        }
        val plan = planTarget(width, height, geometry.pattern)
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
                    require(current.flow.imageWidth == quadsW && current.flow.imageHeight == quadsH) {
                        "Moving $accepted flow grid must match the quad grid"
                    }
                    require(current.robustness.width == quadsW && current.robustness.height == quadsH) {
                        "Moving $accepted robustness grid must match the quad grid"
                    }
                    val tAcc0 = android.os.SystemClock.elapsedRealtime()
                    accumulateFrame(current, current.flow, current.robustness, pattern,
                        num, den, null, null,
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
            // Canonical order (mirrors eager): the reference accumulates last
            // into the shared pair. The `run` scope is load-bearing: the
            // reference MergeFrame (~100MB) must be unreachable after the
            // pass so its heap is reusable.
            run {
                val ref = buildReference()
                checkFrame(ref, "Reference")
                accumulateFrame(ref, null, null, pattern,
                    num, den, null, null,
                    width, height, quadsW, outW, outH, true)
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
                        val value = if (d > EPS) {
                            num.get(p) / maxOf(d, EPS)
                        } else {
                            0.0
                        }
                        cfa[p] = (if (value.isFinite()) value else 0.0).toFloat()
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
        isReference: Boolean,
        onRow: (() -> Boolean)? = null
    ) {
        val samples = frame.samples
        val covariance = frame.covariance.values
        val guideW = frame.covariance.width
        val guideH = frame.covariance.height
        val scaleX = guideW.toDouble() / width
        val scaleY = guideH.toDouble() / height
        // Row-sharded across the shared worker pool: shards cover disjoint
        // output rows with the serial per-pixel code untouched, so the
        // accumulation is bitwise-identical at any worker count. The
        // per-pixel scratch is shard-local (no per-pixel allocation).
        RawSrWorkers.forEachShard(outH) { y0, y1 ->
            val scratch = DoubleArray(4)
            val flowScratch = FloatArray(4)
            for (qy in y0 until y1) {
                if (onRow?.invoke() == true) throw CancellationException("Mosaic SR cancelled")
                for (qx in 0 until outW) {
                val p = qy * outW + qx
                val siteColor = pattern.colorAt(qx, qy)
                // Reference-source position of the site center, plus the
                // bilinear flow displacement in quad pixels (tile borders
                // stay inside alignment; nearest-tile lookup imprints the
                // 16px quilt at tile borders).
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
                    // Bilinear flow sampling (flowAtSmoothInto), mirroring
                    // the linear merge twin: the four surrounding tiles
                    // blend dx/dy so tile borders never quilt the mosaic.
                    // Non-finite corners fall back to the containing tile.
                    flow!!.flowAtSmoothInto(quadX.toFloat(), quadY.toFloat(), flowScratch)
                    if (!flowScratch[0].isFinite() || !flowScratch[1].isFinite()) {
                        if (oob != null) oob[p]++
                        continue
                    }
                    dxQuad = flowScratch[0].toDouble()
                    dyQuad = flowScratch[1].toDouble()
                    // Reference bayer lookup verbatim: min(int(lr//2-0.5))
                    // reads the quad one up-left (max(q-1, 0)), edge-clamped.
                    val rqX = maxOf(quadX - 1, 0).coerceIn(0, quadsW - 1)
                    val rqY = maxOf(quadY - 1, 0).coerceIn(0, robust!!.height - 1)
                    val raw = robust!!.r[rqY * quadsW + rqX]
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
                val interpolated = interpolateCovariance(
                    covariance, guideW, guideH, sourceX * scaleX - 0.5, sourceY * scaleY - 0.5, scratch)
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
                    // Every finite sample merges (no censor skip): the
                    // reference defines none. Non-finite taps are
                    // reference-undefined and skipped.
                    if (!sample.isFinite()) continue
                    val distX = tx + 0.5 - sourceX
                    val distY = ty + 0.5 - sourceY
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

    /**
     * Reference covariance interpolation + inversion (`merge.py::accumulate`),
     * mirroring the linear merge twin: sign-preserving `modf` fractions,
     * `int()` floors clipped at 0, ceilings at the far edge, row-then-column
     * lerp, then the analytic 2x2 inverse into [out]. Null skips the site.
     */
    private fun interpolateCovariance(
        covariance: FloatArray, guideW: Int, guideH: Int, gx: Double, gy: Double,
        out: DoubleArray
    ): DoubleArray? {
        if (!gx.isFinite() || !gy.isFinite()) return null
        // Sign-preserving remainder, exactly like C modf / Python math.modf.
        val fx = gx % 1.0
        val fy = gy % 1.0
        val x0 = (gx - fx).toInt().coerceAtLeast(0)
        val y0 = (gy - fy).toInt().coerceAtLeast(0)
        if (x0 >= guideW || y0 >= guideH) return null
        val x1 = minOf(x0 + 1, guideW - 1)
        val y1 = minOf(y0 + 1, guideH - 1)
        var cxx = 0.0
        var cxy = 0.0
        var cyy = 0.0
        for (c in 0..3) {
            val v00 = covariance[(y0 * guideW + x0) * 4 + c].toDouble()
            val v10 = covariance[(y0 * guideW + x1) * 4 + c].toDouble()
            val v01 = covariance[(y1 * guideW + x0) * 4 + c].toDouble()
            val v11 = covariance[(y1 * guideW + x1) * 4 + c].toDouble()
            if (!v00.isFinite() || !v10.isFinite() || !v01.isFinite() || !v11.isFinite()) return null
            val top = v00 + fx * (v10 - v00)
            val bot = v01 + fx * (v11 - v01)
            val v = top + fy * (bot - top)
            if (c == 0) cxx = v else if (c == 3) cyy = v else if (c == 1) cxy = v
        }
        val det = cxx * cyy - cxy * cxy
        if (!det.isFinite() || det <= 0.0) return null
        out[0] = cyy / det
        out[1] = -cxy / det
        out[2] = -cxy / det
        out[3] = cxx / det
        return out
    }
}
