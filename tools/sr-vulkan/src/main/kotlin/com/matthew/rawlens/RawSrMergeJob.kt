// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteOrder

/** Thrown when no merged artifact may be produced; the caller falls back or reports. */
class MergeUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Prompt 5D failure-table decisions. Pure CPU logic over the planner output —
 * no camera, GL, or filesystem access — so every success, partial-success,
 * fallback, and rejection path is unit-testable.
 *
 * - Fewer than two valid frames → truthful reference fallback (never a
 *   single-frame "merge").
 * - Incompatible frames are already rejected by the planner; merging continues
 *   when at least two accepted frames remain.
 * - Merge inputs are reference-first: the planner's accepted list is ascending
 *   and can hold the reference mid-list, so the decision reorders explicitly.
 */
object RawSrMergeDecisions {
    enum class Mode { MERGE, REFERENCE_FALLBACK }
    enum class FallbackReason { NO_ELIGIBLE_FRAMES, INSUFFICIENT_FRAMES }

    data class Decision(
        val mode: Mode,
        /** Index into the snapshot frame list. */
        val referenceIndex: Int,
        /** Reference-first merge order; empty unless [mode] is MERGE. */
        val mergeIndices: List<Int>,
        val rejectedCount: Int,
        val fallbackReason: FallbackReason?
    )

    /**
     * Takes plain planner outputs rather than the planner's internal Plan
     * type, so this decision core stays public and unit-testable without
     * exposing internals.
     */
    fun decide(
        accepted: List<Int>,
        rejectedCount: Int,
        reference: Int?,
        frameCount: Int
    ): Decision {
        require(frameCount >= 2) { "SR capture must hold at least two frames" }
        val referenceIndex = reference ?: frameCount / 2
        if (reference == null) {
            return Decision(Mode.REFERENCE_FALLBACK, referenceIndex, emptyList(),
                rejectedCount, FallbackReason.NO_ELIGIBLE_FRAMES)
        }
        if (accepted.size < 2) {
            return Decision(Mode.REFERENCE_FALLBACK, referenceIndex, emptyList(),
                rejectedCount, FallbackReason.INSUFFICIENT_FRAMES)
        }
        val order = listOf(reference) + accepted.filter { it != reference }
        require(order.size == accepted.size && order.toSet().size == order.size) {
            "Merge order must carry every accepted frame exactly once, reference-first"
        }
        return Decision(Mode.MERGE, referenceIndex, order, rejectedCount, null)
    }

    fun fallbackStatus(selected: Int, reason: FallbackReason): String = when (reason) {
        FallbackReason.NO_ELIGIBLE_FRAMES -> "RAW SR FALLBACK • NO ELIGIBLE FRAMES"
        FallbackReason.INSUFFICIENT_FRAMES -> "RAW SR ×$selected • REFERENCE FALLBACK"
    }

    fun mergeStatus(accepted: Int, rejected: Int, mode: RawSrDngMode): String {
        val label = "RAW SR ×$accepted • ${mode.label}"
        return if (rejected > 0) "$label • REJECTED $rejected" else label
    }
}

/**
 * Hardware-facing merge-job helpers. The controller owns threading (single
 * writer thread) and artifact saving; this object owns the CPU mosaic chain,
 * flow resampling, and float readback. Nothing here writes MediaStore.
 */
object RawSrMergeJob {
    private const val TAG = "RawLensMosaic"
    /** Band height for [readRgbaFloat]: 256 rows keep each band at ~17MB at 12MP. */
    private const val READBACK_STRIP_ROWS = 256

    /**
     * Memory-compact backing for a quad-grid flow field, serving
     * [upsampleFlowToQuads] (dormant A/B; see above). Flat arrays instead of
     * one boxed [RawSrTileFlow] per quad. Same [List] contract: tileSize is
     * 1, so the center derives from the index ([RawSrAlignmentField.flowAt]
     * and direct indexing behave exactly as with a materialized list; each
     * access still returns a fresh [RawSrTileFlow] value).
     */
    internal class QuadFlowTiles(
        private val quadsW: Int,
        private val dx: FloatArray,
        private val dy: FloatArray,
        private val residual: FloatArray,
        private val reliable: BooleanArray
    ) : AbstractList<RawSrTileFlow>(), RawSrDirectFlowTiles {
        override val size: Int get() = dx.size
        override fun get(index: Int): RawSrTileFlow {
            require(index in 0 until size) { "Quad index $index out of $size" }
            return RawSrTileFlow(
                centerX = (index % quadsW).toFloat(),
                centerY = (index / quadsW).toFloat(),
                dx = dx[index],
                dy = dy[index],
                residual = residual[index],
                reliable = reliable[index]
            )
        }
        override fun directDx(index: Int): Float = dx[index]
        override fun directDy(index: Int): Float = dy[index]
        override fun directResidual(index: Int): Float = residual[index]
        override fun directReliable(index: Int): Boolean = reliable[index]
    }

    /**
     * Bilinear resampling of a coarse alignment field onto the quad grid
     * (dormant A/B helper; the reference-parity base path consumes the
     * coarse field with bilinear sampling directly and never upsamples).
     * The coarse field speaks raw pixels (Jamy-L convention), so each quad
     * samples its center in raw coordinates (2q+1); the stored vectors stay
     * raw-unit displacements on the quad lattice (tileSize 1).
     */
    fun upsampleFlowToQuads(
        coarse: RawSrAlignmentField, quadsW: Int, quadsH: Int
    ): RawSrAlignmentField {
        require(quadsW > 0 && quadsH > 0)
        val n = quadsW * quadsH
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        val residual = FloatArray(n)
        val reliable = BooleanArray(n)
        RawSrWorkers.forEachShard(quadsH) { y0, y1 ->
            val flowScratch = FloatArray(4)
            for (qy in y0 until y1) for (qx in 0 until quadsW) {
                // Bilinear source: the upsampled quad field carries the same
                // smooth values a direct smooth sample would return.
                // Into form is bitwise-identical with no per-quad boxing.
                coarse.flowAtSmoothInto((2 * qx + 1).toFloat(), (2 * qy + 1).toFloat(), flowScratch)
                val i = qy * quadsW + qx
                dx[i] = flowScratch[0]
                dy[i] = flowScratch[1]
                residual[i] = flowScratch[2]
                reliable[i] = flowScratch[3] >= 0.5f
            }
        }
        return RawSrAlignmentField(
            quadsW, quadsH, 1, quadsW, quadsH,
            QuadFlowTiles(quadsW, dx, dy, residual, reliable)
        )
    }

    /**
     * Full-resolution float readback for merged RGBA32F images. Banded so
     * each transient band stays small (~17MB per 256-row band at 12MP);
     * only the returned array is full-frame.
     */
    fun readRgbaFloat(imageId: Int, width: Int, height: Int): FloatArray {
        require(imageId != 0 && width > 0 && height > 0)
        val out = FloatArray(Math.multiplyExact(Math.multiplyExact(width, height), 4))
        var y = 0
        while (y < height) {
            val rows = minOf(READBACK_STRIP_ROWS, height - y)
            vkDownloadRgba32fRegion(imageId, 0, y, width, rows)
                .copyInto(out, Math.multiplyExact(Math.multiplyExact(y, width), 4))
            y += rows
        }
        return out
    }

    /**
     * Memory-bounded single-band readback of a merged RGBA32F image for
     * [LinearRgbDngWriter.writeStriped]: rows `[startY, startY+rows)` land
     * as RGB triplets at `rgb[rgbOffset, rgbOffset+width*rows*3)` in
     * [MergedLinearRgb] row order, with alpha dropped at the boundary. Peak
     * transient per band is one RGBA band (~17MB for 256 rows at 12MP)
     * instead of ~380MB for a whole-frame readback. Values are
     * bitwise-identical to the corresponding slice of
     * [MergedLinearRgb.toTriplets] over [readRgbaFloat].
     */
    fun readMergedRgbStrip(
        imageId: Int,
        width: Int,
        height: Int,
        startY: Int,
        rows: Int,
        rgb: FloatArray,
        rgbOffset: Int = 0
    ) {
        require(imageId != 0 && width > 0 && height > 0)
        require(startY in 0 until height && rows > 0 && startY + rows <= height) {
            "Strip [$startY, ${startY + rows}) must lie inside 0..$height"
        }
        val samples = Math.multiplyExact(Math.multiplyExact(width, rows), 3)
        require(rgbOffset >= 0 && rgb.size - rgbOffset >= samples) {
            "RGB strip buffer too small for $rows row(s) at width $width"
        }
        val band = vkDownloadRgba32fRegion(imageId, 0, startY, width, rows)
        copyRgbaRowsToRgb(java.nio.FloatBuffer.wrap(band), width, rows, rgb, rgbOffset)
        sanitizeStrip(rgb, rgbOffset, width, startY, rows)
    }

    /**
     * Non-finite firewall for GPU readback strips: the merge shaders are
     * finite-guarded end to end, so any NaN/Inf here is driver/readback
     * corruption below the shader math (seen once in the field as a
     * whole-DNG loss in [LinearRgbDngWriter.quantize]). A corrupt texel
     * sanitizes to 0 (black, matching the merge's own zero-support value)
     * instead of nuking the save; the first hit logs its coordinates and
     * the band total so the corruption stays diagnosable. Finite strips
     * pass through untouched — same floats, same bytes. Returns the
     * sanitized sample count (0 when the strip was already finite).
     */
    internal fun sanitizeStrip(rgb: FloatArray, rgbOffset: Int, width: Int, startY: Int, rows: Int): Int {
        val samples = Math.multiplyExact(Math.multiplyExact(width, rows), 3)
        var bad = 0
        var first = -1
        val end = rgbOffset + samples
        var i = rgbOffset
        while (i < end) {
            if (!rgb[i].isFinite()) {
                if (first < 0) first = i - rgbOffset
                bad++
                rgb[i] = 0f
            }
            i++
        }
        if (bad > 0) {
            val pixel = first / 3
            val x = pixel % width
            val y = startY + pixel / width
            android.util.Log.w(TAG, "SR readback sanitized $bad non-finite sample(s)" +
                " in rows [$startY, ${startY + rows}): first at ($x, $y) channel ${first % 3}")
        }
        return bad
    }

    /**
     * Mean per-quad support (1 + Rc) from a merged R32F robustness image,
     * for the merged noise model (see RawSrMergedNoise). Banded like
     * [readMergedRgbStrip] so only one 256-row band (~3MB at 12MP quads)
     * is transient; the image itself is never retained. Throws on GPU
     * readback failure: callers fall back to the reference profile, never
     * to a fabricated scale.
     */
    fun readRcMeanSupport(imageId: Int, quadsW: Int, quadsH: Int): Double {
        require(imageId != 0 && quadsW > 0 && quadsH > 0)
        var sum = 0.0
        var y = 0
        while (y < quadsH) {
            val rows = minOf(READBACK_STRIP_ROWS, quadsH - y)
            val band = vkDownloadR32fRegion(imageId, 0, y, quadsW, rows)
            for (v in band) {
                val s = 1.0 + v.toDouble()
                sum += if (s.isFinite()) s else 1.0
            }
            y += rows
        }
        return sum / (quadsW.toLong() * quadsH)
    }

    /** Bulk-copy one row at a time, preserving float bits and omitting only alpha. */
    internal fun copyRgbaRowsToRgb(
        rgba: java.nio.FloatBuffer, width: Int, rows: Int, rgb: FloatArray, offset: Int
    ) {
        // Array-backed sources (every production band) copy straight from
        // the backing array: no per-band row temp, no per-row bulk get, same
        // floats in the same order. The position still advances past the
        // consumed quads, exactly as the row loop did.
        if (rgba.hasArray()) {
            val backing = rgba.array()
            var r = rgba.arrayOffset() + rgba.position()
            var o = offset
            repeat(Math.multiplyExact(width, rows)) {
                rgb[o++] = backing[r]
                rgb[o++] = backing[r + 1]
                rgb[o++] = backing[r + 2]
                r += 4
            }
            rgba.position(rgba.position() + Math.multiplyExact(Math.multiplyExact(width, rows), 4))
            return
        }
        val row = FloatArray(Math.multiplyExact(width, 4))
        var o = offset
        repeat(rows) {
            rgba.get(row)
            var r = 0
            repeat(width) {
                rgb[o++] = row[r]
                rgb[o++] = row[r + 1]
                rgb[o++] = row[r + 2]
                r += 4
            }
        }
    }

    /** One merge-time rejection with the measured support numbers for provenance logging. */
    data class RejectedFrame(
        val index: Int,
        val reason: String,
        val evRelative: Double?,
        val reliableFraction: Double?,
        val meanRobustness: Double?,
        val supportFraction: Double?,
        val medianFlowSpanPx: Float?
    )

    data class MosaicChain(
        val reference: RawSrBayerMerge.MergeFrame,
        val moving: List<RawSrBayerMerge.MergeFrame>,
        /** Indices into the caller's frame list that survived alignment. */
        val survivorIndices: List<Int>,
        val tuning: RawSrTuning,
        /** Raw-pixel tile size of the alignment config this chain actually aligned with. */
        val alignmentTileSize: Int,
        /** Every moving frame rejected during the chain, in input order. */
        val rejections: List<RejectedFrame> = emptyList(),
        /**
         * Measured/profile noise-sigma ratio for the reference frame (null
         * when unmeasurable or KernelNet is off): feeds
         * [RawSrKernelNetAniso.sigmaFor]'s auto-sigma correction so an
         * understated OEM profile cannot maze the learned kernels.
         */
        val noiseSigmaRatio: Float? = null
    )

    /** One mosaic input: packed plane plus its reference metadata. */
    data class MosaicInput(val packed: RawSrPackedFrame, val metadata: RawFrameMetadata)

    /**
     * CPU mosaic chain: unpack → lens-shading correction unless already
     * applied → gray → align-or-reject → robustness → precision → merge
     * frames. A moving frame that cannot be unpacked, corrected, aligned (or
     * yields no reliable tile at all), or evaluated is rejected; with no
     * surviving moving frame the chain throws [MergeUnavailableException] and
     * the caller falls back. Local motion stays inside the merge via
     * robustness and the reference-only fallback — never as a frame rejection.
     * A null [tuningOverride] resolves tuning from the reference SNR scan; a
     * fixed tuning replaces it for A/B presets.
     *
     * Frames keep analytic covariances: eager consumers orchestrate their own
     * [kernelNetSwap] (the linear CLI does, after its GAT stage so learned
     * kernels win — swapping here too would run inference twice). Only the
     * streaming path swaps internally, where consumers have no swap point.
     */
    fun mosaicChain(
        inputs: List<MosaicInput>,
        referenceIndex: Int,
        config: RawSrAlignmentConfig? = null,
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null,
        noiseLut: RawSrNoiseLut.Lut? = null,
        tuningOverride: RawSrTuning? = null,
        rejectionPolicy: RawSrFrameRejection.Policy = RawSrFrameRejection.Policy(),
        onRejected: ((RejectedFrame) -> Unit)? = null
    ): MosaicChain {
        require(inputs.size >= 2) { "Mosaic chain needs at least two frames" }
        require(referenceIndex in inputs.indices)
        val refInput = inputs[referenceIndex]
        val tuning = tuningOverride ?: RawSrTuning.fromReference(refInput.packed).tuning
        // Null keeps the SNR-derived tile size (reference update_snr_config
        // 64/32/16 raw px) instead of a fixed default.
        val activeConfig = config ?: tuning.alignmentConfig()
        val refEv = exposureValue(refInput.metadata)
        // The corrected reference CFA is built once and shared: the gray,
        // the burst-shared alignment pyramid, and the reference merge frame
        // all read it, instead of re-unpacking and re-correcting the
        // reference inside buildReferenceFrame. Jamy-L parity: no hot-pixel
        // stage — the reference defines none, and the stuck-low gate
        // misfires on thin scene lines (green-dot root cause).
        val refCfa = correctedCfa(refInput)
            ?: throw MergeUnavailableException("Reference frame cannot be unpacked")
        // Alignment grey is unshaded normalized (the reference has no lens
        // shading); the merge keeps the shaded CFA. The grey runs in RGGB
        // processing space (reference cfa_to_rggb): sensor space would
        // sample the complementary stride phase and pad the wrong end.
        val refPlain = unpack(refInput.packed)
        val refProcessing = RawSrCfaOrientation.toProcessingSpace(
            refPlain.values, refPlain.width, refPlain.height,
            RawSrCfaOrientation.forPattern(refPlain.pattern))
        val refGray = RawSrAlignment.fftGrey(refProcessing, refPlain.width, refPlain.height)
        // Burst-shared alignment levels: identical for every moving frame
        // (see alignBurst), built once instead of per frame. The reference
        // pyramid pads circularly to a tile multiple (Jamy-L init).
        val refPyramid = RawSrAlignment.pyramid(
            RawSrAlignment.circularPad(refGray, activeConfig.tileSize))
        // Reference stats are identical for every moving frame: compute once,
        // fused from packed codes (no 37MB guide beside the 75MB stats).
        val refStats = RawSrRobustness.referenceStatsFromPacked(refInput.packed)
        // Auto-sigma input for KernelNet consumers: measured once on the
        // pre-shading reference unpack (only when the model is enabled —
        // the analytic path never reads it). Like all diagnostics, it must
        // never break the merge.
        val noiseSigmaRatio = if (RawSrKernelNetAniso.enabled) {
            try {
                RawSrFrameNoiseMeter.compare(refPlain, refInput.packed.noiseProfile)?.ratio
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val reference = buildReferenceFrameFromCfa(refInput, refCfa, tuning)
        val moving = ArrayList<RawSrBayerMerge.MergeFrame>()
        val survivors = ArrayList<Int>()
        val rejections = ArrayList<RejectedFrame>()
        var completed = 0
        for ((index, input) in inputs.withIndex()) {
            if (index == referenceIndex) continue
            if (isCancelled?.invoke(completed) == true) {
                throw java.util.concurrent.CancellationException("Mosaic chain cancelled")
            }
            val outcome = buildMovingFrame(
                refPyramid, refStats, tuning, activeConfig, input, "index $index", noiseLut,
                rejectionPolicy, refEv)
            val frame = outcome.frame
            if (frame == null) {
                val rejected = RejectedFrame(index, outcome.reason ?: "unknown",
                    outcome.evRelative, outcome.reliableFraction, outcome.meanRobustness,
                    outcome.supportFraction, outcome.medianFlowSpanPx)
                rejections.add(rejected)
                onRejected?.invoke(rejected)
                continue
            }
            moving.add(frame)
            survivors.add(index)
            completed++
        }
        if (moving.isEmpty()) {
            val detail = rejections.joinToString { "index ${it.index}=${it.reason}" }
            throw MergeUnavailableException(
                "Mosaic chain kept no moving frame after alignment ($detail)")
        }
        return MosaicChain(reference, moving, survivors, tuning, activeConfig.tileSize, rejections,
            noiseSigmaRatio)
    }

    /** Exposure value proxy (exposure*ISO) for EV-relative logging; null-safe. */
    private fun exposureValue(m: RawFrameMetadata): Double =
        (m.exposureTimeNanos ?: 0).toDouble() * (m.sensitivityIso ?: 0).toDouble()

    private fun evRelative(refEv: Double, m: RawFrameMetadata): Double? {
        if (refEv <= 0.0) return null
        val ev = exposureValue(m)
        if (ev <= 0.0) return null
        return kotlin.math.log2(ev / refEv)
    }

    /** Reference geometry for the streaming path (bytes, not buffers). */
    data class ReferenceGeometry(
        val width: Int,
        val height: Int,
        val pattern: BayerPattern,
        val left: Int,
        val top: Int
    )

    /**
     * Lazy counterpart of [mosaicChain] for the memory-bound production save.
     * Reference preparation (unpack, gray, tuning, local stats) runs eagerly;
     * moving frames build on consumption so the caller can accumulate-then-drop
     * each frame instead of retaining the whole burst (~150MB per full-res
     * frame). The reference local stats are computed once and retained, not
     * recomputed per frame. [ReferenceGeometry], the corrected reference
     * CFA (~50MB) with its gray and alignment pyramid, and the stats
     * survive assembly. [tuningOverride] behaves like [mosaicChain]'s.
     */
    data class MosaicStream(
        val geometry: ReferenceGeometry,
        val tuning: RawSrTuning,
        val selected: Int,
        val frames: Sequence<RawSrBayerMerge.MergeFrame>,
        /** Raw-pixel tile size of the alignment config this stream actually aligns with. */
        val alignmentTileSize: Int,
        /**
         * Reference merge frame over the cached corrected CFA: identical to
         * `buildReferenceFrame(inputs[ref], tuning)` without re-unpacking,
         * re-correcting, re-detecting, or re-graying the reference. Invoke
         * once, at reference-accumulation time.
         */
        val referenceFrame: () -> RawSrBayerMerge.MergeFrame,
        /**
         * Measured/profile noise-sigma ratio for the reference frame (null
         * when unmeasurable or KernelNet is off): same auto-sigma input as
         * [MosaicChain.noiseSigmaRatio], for streaming consumers.
         */
        val noiseSigmaRatio: Float? = null
    )

    /**
     * Test-only stand-in for model inference in the streaming swap: when
     * non-null, [mosaicStream] routes every moving frame plus the reference
     * through it instead of the readiness-gated [kernelNetSwap], so wiring
     * tests pin the per-frame call sites without an ncnn model. Production
     * code must leave this null. Args are the frame, its sensor noise
     * profile, and the stream's measured auto-sigma ratio.
     */
    internal var kernelNetSwapForTest: ((
        frame: RawSrBayerMerge.MergeFrame,
        noiseProfile: ImmutableDoubleValues?,
        measuredRatio: Float?
    ) -> RawSrBayerMerge.MergeFrame)? = null

    /**
     * One frame's analytic→learned covariance swap: rebuilds the frame's own
     * corrected samples as CFA input (no re-unpack) and runs KernelNet at
     * [RawSrKernelNetAniso.sigmaFor] with the burst's auto-sigma ratio.
     * Returns the frame untouched when the model is off, not ready, or
     * falls back (logged at debug). Shared by the linear CLI's eager swap
     * and the streaming mosaic swap, so both paths consume one conversion.
     */
    fun kernelNetSwap(
        frame: RawSrBayerMerge.MergeFrame,
        noiseProfile: ImmutableDoubleValues?,
        label: String,
        measuredRatio: Float?
    ): RawSrBayerMerge.MergeFrame {
        val cfa = UnpackedRawCfa(
            frame.width, frame.height, frame.sensorPattern, frame.samples,
            RawCrop(0, 0, frame.width, frame.height), frame.sensorLeft, frame.sensorTop
        )
        val sigma = RawSrKernelNetAniso.sigmaFor(noiseProfile, measuredRatio)
        val field = RawSrKernelNetAniso.precisionFor(cfa, frame.covariance, sigma)
        if (field === frame.covariance) {
            if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
                android.util.Log.d(TAG, "kernelnet $label fell back to analytic")
            }
            return frame
        }
        return frame.copy(covariance = field)
    }

    fun mosaicStream(
        inputs: List<MosaicInput>,
        referenceIndex: Int,
        config: RawSrAlignmentConfig? = null,
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null,
        noiseLut: RawSrNoiseLut.Lut? = null,
        tuningOverride: RawSrTuning? = null,
        rejectionPolicy: RawSrFrameRejection.Policy = RawSrFrameRejection.Policy(),
        onRejected: ((RejectedFrame) -> Unit)? = null
    ): MosaicStream {
        require(inputs.size >= 2) { "Mosaic chain needs at least two frames" }
        require(referenceIndex in inputs.indices)
        val refInput = inputs[referenceIndex]
        val tuning = tuningOverride ?: RawSrTuning.fromReference(refInput.packed).tuning
        // Null keeps the SNR-derived tile size (reference update_snr_config
        // 64/32/16 raw px) instead of a fixed default.
        val activeConfig = config ?: tuning.alignmentConfig()
        val refEv = exposureValue(refInput.metadata)
        // The corrected reference CFA is built once and retained for the
        // reference accumulation (~50MB CFA plus the 12MB gray beside the
        // 75MB stats — inside the streaming budget), instead of
        // re-unpacking and re-correcting the reference when it accumulates
        // last. Jamy-L parity: no hot-pixel stage (see mosaicChain).
        val refCfa = correctedCfa(refInput)
            ?: throw MergeUnavailableException("Reference frame cannot be unpacked")
        // Alignment grey is unshaded normalized (the reference has no lens
        // shading); the merge keeps the shaded CFA. The grey runs in RGGB
        // processing space (reference cfa_to_rggb): sensor space would
        // sample the complementary stride phase and pad the wrong end.
        val refPlain = unpack(refInput.packed)
        val refProcessing = RawSrCfaOrientation.toProcessingSpace(
            refPlain.values, refPlain.width, refPlain.height,
            RawSrCfaOrientation.forPattern(refPlain.pattern))
        val refGray = RawSrAlignment.fftGrey(refProcessing, refPlain.width, refPlain.height)
        // Burst-shared alignment levels: identical for every moving frame
        // (see alignBurst), built once instead of per frame. The reference
        // pyramid pads circularly to a tile multiple (Jamy-L init).
        val refPyramid = RawSrAlignment.pyramid(
            RawSrAlignment.circularPad(refGray, activeConfig.tileSize))
        val geometry = ReferenceGeometry(
            refCfa.width, refCfa.height, refCfa.pattern,
            refCfa.sensorCropLeft, refCfa.sensorCropTop
        )
        // Built once: the stats depend only on the reference packed frame,
        // so recomputing them per moving frame below would repeat full-res
        // passes for identical values. Fused from packed codes (no 37MB
        // guide beside the 75MB stats); only the stats are captured below.
        val refStats = RawSrRobustness.referenceStatsFromPacked(refInput.packed)
        // Auto-sigma input, mirroring mosaicChain: measured once on the
        // pre-shading reference unpack (only when the model is enabled).
        // Like all diagnostics, it must never break the merge.
        val noiseSigmaRatio = if (RawSrKernelNetAniso.enabled) {
            try {
                RawSrFrameNoiseMeter.compare(refPlain, refInput.packed.noiseProfile)?.ratio
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        // Frozen KernelNet decision for every frame of this stream:
        // readiness is captured once at assembly so a model that finishes
        // loading mid-burst cannot mix learned and analytic covariances
        // across frames. The test seam bypasses the gate (it stands in for
        // inference itself).
        val testSwap = kernelNetSwapForTest
        val swapKernels = testSwap != null ||
            (RawSrKernelNetAniso.enabled && RawSrKernelNetAniso.isReady())
        if (swapKernels) {
            android.util.Log.i(TAG, "mosaic stream uses KernelNet covariances" +
                " (auto-sigma ratio=${noiseSigmaRatio ?: "n/a"})")
        } else if (RawSrKernelNetAniso.enabled) {
            android.util.Log.i(TAG, "mosaic stream analytic: KernelNet model not ready")
        }
        fun maybeSwap(
            frame: RawSrBayerMerge.MergeFrame,
            noiseProfile: ImmutableDoubleValues?,
            label: String
        ): RawSrBayerMerge.MergeFrame {
            if (!swapKernels) return frame
            return testSwap?.invoke(frame, noiseProfile, noiseSigmaRatio)
                ?: kernelNetSwap(frame, noiseProfile, label, noiseSigmaRatio)
        }
        val frames = sequence {
            var completed = 0
            for ((index, input) in inputs.withIndex()) {
                if (index == referenceIndex) continue
                if (isCancelled?.invoke(completed) == true) {
                    throw java.util.concurrent.CancellationException("Mosaic chain cancelled")
                }
                // `var` + null-after-yield is load-bearing: the sequence state
                // machine keeps locals alive across `yield`, so a `val` would
                // root the ~150MB yielded frame during the NEXT frame's ~200MB
                // build (the consumer's `next()` resumes here), peaking past
                // the 512MB heap. Nulling before the next build keeps the
                // accumulate-then-drop contract (mirrors the consumer side in
                // MosaicSrReconstructor.reconstructStreaming).
                var outcome = buildMovingFrame(refPyramid, refStats, tuning, activeConfig, input,
                    "index $index", noiseLut, rejectionPolicy, refEv)
                var frame = outcome.frame
                if (frame != null) {
                    // Learned kernels before the yield: the swap only replaces
                    // the covariance field (same quad grid), so the
                    // accumulate-then-drop memory contract below is unchanged.
                    frame = maybeSwap(frame, input.packed.noiseProfile, "index $index")
                    yield(frame)
                    frame = null
                    outcome = MovingOutcome(null, null, null, null, null, null, null)
                    completed++
                } else {
                    onRejected?.invoke(RejectedFrame(index, outcome.reason ?: "unknown",
                        outcome.evRelative, outcome.reliableFraction, outcome.meanRobustness,
                        outcome.supportFraction, outcome.medianFlowSpanPx))
                }
            }
        }
        val referenceFrame: () -> RawSrBayerMerge.MergeFrame = {
            maybeSwap(buildReferenceFrameFromCfa(refInput, refCfa, tuning),
                refInput.packed.noiseProfile, "ref")
        }
        return MosaicStream(geometry, tuning, inputs.size, frames, activeConfig.tileSize, referenceFrame,
            noiseSigmaRatio)
    }

    /**
     * Reference merge frame: unpack, lens-shading correction, GAT-guide
     * kernels, precision. No flow or robustness (the reference anchors
     * both). Shared by the eager chain and by the streaming path's one-shot
     * reference accumulation. Jamy-L parity: no hot-pixel stage.
     */
    fun buildReferenceFrame(
        input: MosaicInput,
        tuning: RawSrTuning? = null
    ): RawSrBayerMerge.MergeFrame {
        // Null re-estimates from the frame (a full-frame SNR scan); callers
        // that already hold the chain tuning pass it to skip the rescan.
        val activeTuning = tuning ?: RawSrTuning.fromReference(input.packed).tuning
        val cfa = correctedCfa(input)
            ?: throw MergeUnavailableException("Reference frame cannot be unpacked")
        if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) logNoiseCheck(input)
        val guide = RawSrCovarianceGuide.guide(input.packed).gray
        return mergeFrame(cfa, activeTuning, flow = null, robustness = null, guide)
            .copy(highlightNeutral = RawSrHighlights.neutral(input.metadata))
    }

    /**
     * Reference merge frame over an already corrected CFA: identical to
     * [buildReferenceFrame] for the same tuning, minus the redundant
     * unpack and lens-shading correction. The chain/stream setups already
     * hold the CFA; rebuilding it costs a full unpack + correction per
     * save for identical bytes.
     */
    internal fun buildReferenceFrameFromCfa(
        input: MosaicInput,
        cfa: UnpackedRawCfa,
        tuning: RawSrTuning
    ): RawSrBayerMerge.MergeFrame {
        if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) logNoiseCheck(input)
        val guide = RawSrCovarianceGuide.guide(input.packed).gray
        return mergeFrame(cfa, tuning, flow = null, robustness = null, guide)
            .copy(highlightNeutral = RawSrHighlights.neutral(input.metadata))
    }

    /**
     * DEBUG-only cross-check (`adb shell setprop log.tag.RawLensMosaic DEBUG`):
     * measures the noise actually present in the pre-lens-shading reference
     * bytes and compares it against the OEM profile behind
     * [RawSrKernelNetAniso.sigmaFor].
     * A ratio well below 1.0 means the profile overstates the noise and the
     * model over-smooths. Diagnostics never break the merge.
     */
    private fun logNoiseCheck(input: MosaicInput) {
        try {
            val report = RawSrFrameNoiseMeter.compare(unpack(input.packed), input.packed.noiseProfile)
            if (report != null) android.util.Log.d(TAG, report.logLine("sr-ref"))
        } catch (_: Exception) {
            // Diagnostics never break the merge.
        }
    }

    /**
     * Per-frame outcome: a kept frame, or the rejection reason plus the
     * measured support numbers (null frame). Internal so both chain paths
     * share one gate implementation.
     */
    internal data class MovingOutcome(
        val frame: RawSrBayerMerge.MergeFrame?,
        val reason: String?,
        val evRelative: Double?,
        val reliableFraction: Double?,
        val meanRobustness: Double?,
        val supportFraction: Double?,
        val medianFlowSpanPx: Float?
    )

    /**
     * Per-frame chain step shared by the eager and streaming paths: unpack,
     * align-or-reject, robustness-or-reject, support-gate, precision. Null
     * frame means the frame is rejected (same gates as [mosaicChain]); every
     * rejection logs its stage at WARN so a production "kept no moving
     * frame" failure names its cause in logcat. Only [Exception] rejects a
     * frame: [OutOfMemoryError] and cancellation propagate so the caller
     * reports heap pressure truthfully instead of misreporting it as an
     * alignment failure.
     *
     * Gates (see [RawSrFrameRejection]): unpack, align exception, zero
     * reliable tiles, low reliable-tile fraction, robustness exception, then
     * the shared support verdict (mean R, support fraction, global-motion
     * veto). Local motion stays inside the merge via robustness and the
     * reference-only fallback — never as a frame rejection.
     */
    private fun buildMovingFrame(
        refPyramid: List<RawSrGrayImage>,
        refStats: RawSrRobustness.ReferenceStats,
        tuning: RawSrTuning,
        config: RawSrAlignmentConfig,
        input: MosaicInput,
        label: String,
        noiseLut: RawSrNoiseLut.Lut?,
        rejectionPolicy: RawSrFrameRejection.Policy = RawSrFrameRejection.Policy(),
        refEv: Double = 0.0
    ): MovingOutcome {
        fun rejected(reason: String, rel: Double? = null, mean: Double? = null,
                     support: Double? = null, span: Float? = null): MovingOutcome {
            val ev = evRelative(refEv, input.metadata)
            val evTag = if (ev == null) "" else " ev=${"%.2f".format(ev)}"
            val detail = buildString {
                if (rel != null) append(" rel=${"%.3f".format(rel)}")
                if (mean != null) append(" meanR=${"%.3f".format(mean)}")
                if (support != null) append(" support=${"%.3f".format(support)}")
                if (span != null && span.isFinite()) append(" span=${"%.2f".format(span)}q")
            }
            android.util.Log.w(TAG, "mosaic frame $label rejected: $reason$evTag$detail")
            return MovingOutcome(null, reason, ev, rel, mean, support, span)
        }
        // Per-stage wall clock for production-save diagnosis (one line per
        // frame; Debug-gated so release logcat stays quiet).
        val t0 = android.os.SystemClock.elapsedRealtime()
        val cfa = correctedCfa(input)
        if (cfa == null) {
            return rejected("unpack")
        }
        // Jamy-L parity: no hot-pixel stage (see mosaicChain). The fused
        // moving stats below read packed codes directly.
        val tUnpack = android.os.SystemClock.elapsedRealtime()
        // Alignment grey is unshaded normalized (the reference has no lens
        // shading); the merge keeps the shaded CFA. Alignment runs in RGGB
        // processing space (reference cfa_to_rggb) and the field maps back
        // to sensor space at the boundary: robustness, rejection, and the
        // merge below all see sensor space, exactly as before.
        val plain = unpack(input.packed)
        val flip = RawSrCfaOrientation.forPattern(plain.pattern)
        val processing = RawSrCfaOrientation.toProcessingSpace(
            plain.values, plain.width, plain.height, flip)
        val gray = RawSrAlignment.fftGrey(processing, plain.width, plain.height)
        val coarse = try {
            RawSrCfaOrientation.remapFieldToSensor(
                RawSrAlignment.alignPair(refPyramid, RawSrAlignment.pyramid(gray), config), flip,
                plain.width, plain.height)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return rejected("align (${failure.message})")
        }
        if (coarse.tiles.none { it.reliable }) {
            return rejected("no-reliable-tile", rel = 0.0)
        }
        val reliableFraction = RawSrFrameRejection.reliableFraction(coarse)
        if (reliableFraction < rejectionPolicy.minReliableFraction) {
            return rejected("low-reliable-frac", rel = reliableFraction)
        }
        val tAlign = android.os.SystemClock.elapsedRealtime()
        // Reference-parity base: the fine raw-lattice field feeds robustness
        // and the merge directly (raw-pixel flows, Jamy-L convention). A
        // regularization sigma upgrades to the bilateral-filtered field
        // (both consumers stay consistent by construction).
        val flowSmoothSigma = tuning.flowRegularizeSigma ?: 0.0
        val flow = if (flowSmoothSigma > 0.0) coarse.bilateralFiltered(flowSmoothSigma.toFloat()) else coarse
        val robustness = try {
            // Fused from packed codes (no 37MB guide beside the 37MB means).
            // Inside the gate so a bad frame still rejects like before.
            val movStats = RawSrRobustness.movingStatsFromPacked(input.packed)
            RawSrRobustness.evaluateWithStats(
                refStats, movStats, flow, tuning, config, noiseLut
            )
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return rejected("robustness (${failure.message})", rel = reliableFraction)
        }
        val verdict = RawSrFrameRejection.judge(flow, robustness, rejectionPolicy)
        if (!verdict.keep) {
            return rejected(verdict.rejectReason ?: "support",
                rel = verdict.reliableFraction, mean = verdict.meanRobustness,
                support = verdict.supportFraction, span = verdict.medianFlowSpanPx)
        }
        val tRobust = android.os.SystemClock.elapsedRealtime()
        val guide = RawSrCovarianceGuide.guide(input.packed).gray
        // Sabre-style unblocker fold (always on): photometric agreement
        // is blind to period-ambiguous ghosts (boxed means match at any
        // whole-period shift), so the variance-loss keep-weight caps the
        // robustness (min, never compounding) before the merge. The keep
        // verdict above judged the unfolded field, so frame selection is
        // unchanged; without a usable noise model the field rides through
        // (no model, no gate).
        val effective = try {
            val params = RawSrRobustness.gpuParams(input.packed)
            if (!params.modelValid) robustness
            else RawSrUnblocker.applyToFrameAndSpread(robustness, RawSrUnblocker.computeFrame(
                guide, params.alpha[1].toDouble(), params.beta[1].toDouble()),
                flow, RawSrRobustness.motionThresholdPx(tuning))
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Exotic CFA (non-Bayer): the coefficient reduction requires
            // two green phases, so the field rides through unfolded rather
            // than failing a frame the fused stats path would accept.
            robustness
        }
        val frame = mergeFrame(cfa, tuning, flow, effective, guide)
        if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
            val ev = evRelative(refEv, input.metadata)
            android.util.Log.d(TAG, "mosaic frame ${cfa.width}x${cfa.height}" +
                (if (ev == null) "" else " ev=${"%.2f".format(ev)}") +
                " rel=${"%.3f".format(verdict.reliableFraction)}" +
                " meanR=${"%.3f".format(verdict.meanRobustness)}" +
                " unpack=${tUnpack - t0}ms align=${tAlign - tUnpack}ms" +
                " robust=${tRobust - tAlign}ms precision=${android.os.SystemClock.elapsedRealtime() - tRobust}ms")
        }
        return MovingOutcome(frame, null, evRelative(refEv, input.metadata),
            verdict.reliableFraction, verdict.meanRobustness,
            verdict.supportFraction, verdict.medianFlowSpanPx)
    }

    /**
     * Merge-consumed frame (unpack + lens shading); internal so the
     * merge-debug payload dumps identical bytes. Only [Exception] fails
     * softly (null): [OutOfMemoryError] and cancellation propagate.
     */
    internal fun correctedCfa(input: MosaicInput): UnpackedRawCfa? = try {
        val base = unpack(input.packed)
        if (input.metadata.lensShadingAlreadyApplied) base
        else requireNotNull(
            RawPreDemosaicPipeline.process(base, input.metadata, PreDemosaicSettings()).cfa
        ) { "Lens-shading correction produced no CFA" }
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** Same bytes the merge consumes; internal so the merge-debug payload dumps identical inputs. */
    internal fun unpack(packed: RawSrPackedFrame): UnpackedRawCfa {
        // uploadInput exposes the frame's own plane, layout, crop, and
        // normalization: the same bytes the GPU path uploads, read on CPU.
        val input = packed.uploadInput()
        return RawSensorUnpacker.unpackNormalized(
            input.buffer, input.layout, input.normalization, input.crop,
            ByteOrder.nativeOrder()
        ).also { require(it.width == packed.width && it.height == packed.height) }
    }

    private fun mergeFrame(
        cfa: UnpackedRawCfa,
        tuning: RawSrTuning,
        flow: RawSrAlignmentField?,
        robustness: RawSrRobustness.FrameRobustness?,
        guide: RawSrGrayImage
    ): RawSrBayerMerge.MergeFrame {
        // Reference-parity base: the analytic kernel covariance over the
        // GAT variance-stabilized guide (Jamy-L Algs. 4-5), interpolated
        // and inverted by the merge itself. No learned stage in the base
        // path; KernelNet stays available behind its own switch for A/B
        // only.
        val covariance = RawSrKernelCovariance.covariance(guide, tuning)
        if (RawSrKernelNetAniso.zipperGates) {
            // Narrow-axis clamp (default-kernel zipper gate): sub-lattice
            // across-axes ring and straddle per-channel edges in ways
            // third-party demosaics read as zipper, so every texel's minor
            // axis floors at minorAxisSigmaFloor. Precision-space clamp
            // over a covariance field: invert, clamp, invert back, all in
            // place (the field is freshly allocated and the merge inverts
            // per-pixel anyway). KernelNet-swapped fields arrive
            // pre-clamped from the producers.
            RawSrKernelCovariance.invertFieldInPlace(covariance.values)
            MosaicSrReconstructor.clampMinorAxisInPlace(covariance.values, MosaicSrReconstructor.minorAxisSigmaFloor)
            RawSrKernelCovariance.invertFieldInPlace(covariance.values)
        }
        return RawSrBayerMerge.MergeFrame(
            width = cfa.width,
            height = cfa.height,
            samples = cfa.values,
            sensorPattern = cfa.pattern,
            sensorLeft = cfa.sensorCropLeft,
            sensorTop = cfa.sensorCropTop,
            covariance = covariance,
            flow = flow,
            robustness = robustness
        )
    }
}
