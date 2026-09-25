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
     * Memory-compact backing for a quad-grid flow field. A full-res burst
     * holds ~3.1M quads; one boxed [RawSrTileFlow] per quad costs ~125MB per
     * moving frame and OOMs the mosaic save on a 512MB heap. Flat arrays cost
     * ~40MB for the same grid. Same [List] contract: tileSize is 1, so the
     * center derives from the index ([RawSrAlignmentField.flowAt] and direct
     * indexing behave exactly as with a materialized list; each access still
     * returns a fresh [RawSrTileFlow] value).
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
     * Nearest-tile resampling of a coarse alignment field onto the quad grid.
     * The oracle consumes flow "looked up at the nearest tile", so sampling
     * the coarse field per quad preserves its contract on the grid the merge
     * inputs require (exactly quadsW × quadsH, tileSize 1).
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
                coarse.flowAtSmoothInto(qx.toFloat(), qy.toFloat(), flowScratch)
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

    data class MosaicChain(
        val reference: RawSrBayerMerge.MergeFrame,
        val moving: List<RawSrBayerMerge.MergeFrame>,
        /** Indices into the caller's frame list that survived alignment. */
        val survivorIndices: List<Int>,
        val tuning: RawSrTuning,
        /** Quad tile size of the alignment config this chain actually aligned with. */
        val alignmentTileQuads: Int
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
     */
    fun mosaicChain(
        inputs: List<MosaicInput>,
        referenceIndex: Int,
        config: RawSrAlignmentConfig? = null,
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null,
        noiseLut: RawSrNoiseLut.Lut? = null
    ): MosaicChain {
        require(inputs.size >= 2) { "Mosaic chain needs at least two frames" }
        require(referenceIndex in inputs.indices)
        val refInput = inputs[referenceIndex]
        val tuning = RawSrTuning.fromReference(refInput.packed).tuning
        // Null keeps the GPU path's contract: SNR-derived tile size (64/32/16
        // raw px -> 32/16/8 quads) instead of a fixed default.
        val activeConfig = config ?: tuning.alignmentConfig()
        val refCfa = correctedCfa(refInput)
            ?: throw MergeUnavailableException("Reference frame cannot be unpacked")
        val refHot = RawSrHotPixel.detectPacked(refInput.packed)
        // Inpaint before the gray: alignment and the guide both read these
        // samples, and a stuck tap must not steer either one.
        RawSrHotPixel.inpaintNormalized(
            refCfa.values, refHot, refCfa.width, refCfa.height, refCfa.pattern)
        val refGray = RawSrAlignment.bayerQuadGray(refCfa)
        val refGuide = RawSrRobustness.linearGuide(refInput.packed, refHot)
        val reference = buildReferenceFrame(refInput, refHot)
        val moving = ArrayList<RawSrBayerMerge.MergeFrame>()
        val survivors = ArrayList<Int>()
        var completed = 0
        for ((index, input) in inputs.withIndex()) {
            if (index == referenceIndex) continue
            if (isCancelled?.invoke(completed) == true) {
                throw java.util.concurrent.CancellationException("Mosaic chain cancelled")
            }
            val frame = buildMovingFrame(refGray, refGuide, tuning, activeConfig, input, "index $index", noiseLut)
                ?: continue
            moving.add(frame)
            survivors.add(index)
            completed++
        }
        if (moving.isEmpty()) {
            throw MergeUnavailableException("Mosaic chain kept no moving frame after alignment")
        }
        return MosaicChain(reference, moving, survivors, tuning, activeConfig.tileSize)
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
     * Reference preparation (unpack, gray, tuning, linear guide) runs eagerly;
     * moving frames build on consumption so the caller can accumulate-then-drop
     * each frame instead of retaining the whole burst (~150MB per full-res
     * frame). The reference guide (~40MB) is retained, not recomputed per
     * frame. Only [ReferenceGeometry], the 12.5MB reference gray and the guide
     * survive assembly.
     */
    data class MosaicStream(
        val geometry: ReferenceGeometry,
        val tuning: RawSrTuning,
        val selected: Int,
        val frames: Sequence<RawSrBayerMerge.MergeFrame>,
        /** Quad tile size of the alignment config this stream actually aligns with. */
        val alignmentTileQuads: Int
    )

    fun mosaicStream(
        inputs: List<MosaicInput>,
        referenceIndex: Int,
        config: RawSrAlignmentConfig? = null,
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null,
        noiseLut: RawSrNoiseLut.Lut? = null
    ): MosaicStream {
        require(inputs.size >= 2) { "Mosaic chain needs at least two frames" }
        require(referenceIndex in inputs.indices)
        val refInput = inputs[referenceIndex]
        val tuning = RawSrTuning.fromReference(refInput.packed).tuning
        // Null keeps the GPU path's contract: SNR-derived tile size (64/32/16
        // raw px -> 32/16/8 quads) instead of a fixed default.
        val activeConfig = config ?: tuning.alignmentConfig()
        val refCfa = correctedCfa(refInput)
            ?: throw MergeUnavailableException("Reference frame cannot be unpacked")
        val refHot = RawSrHotPixel.detectPacked(refInput.packed)
        RawSrHotPixel.inpaintNormalized(
            refCfa.values, refHot, refCfa.width, refCfa.height, refCfa.pattern)
        val refGray = RawSrAlignment.bayerQuadGray(refCfa)
        // Built once: the guide depends only on the reference packed frame,
        // so recomputing it per moving frame below would repeat a full-res
        // pass for identical values.
        val refGuide = RawSrRobustness.linearGuide(refInput.packed, refHot)
        val geometry = ReferenceGeometry(
            refCfa.width, refCfa.height, refCfa.pattern,
            refCfa.sensorCropLeft, refCfa.sensorCropTop
        )
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
                var frame = buildMovingFrame(refGray, refGuide, tuning, activeConfig, input, "index $index", noiseLut)
                if (frame != null) {
                    yield(frame)
                    frame = null
                    completed++
                }
            }
        }
        return MosaicStream(geometry, tuning, inputs.size, frames, activeConfig.tileSize)
    }

    /**
     * Reference merge frame: unpack, lens-shading correction, hot-pixel
     * inpaint, precision. No flow or robustness (the reference anchors
     * both). Shared by the eager chain and by the streaming path's one-shot
     * reference accumulation. A null [hotMask] is detected internally; pass
     * the chain's mask to share one detection.
     */
    fun buildReferenceFrame(input: MosaicInput, hotMask: BooleanArray? = null): RawSrBayerMerge.MergeFrame {
        val tuning = RawSrTuning.fromReference(input.packed).tuning
        val cfa = correctedCfa(input)
            ?: throw MergeUnavailableException("Reference frame cannot be unpacked")
        val mask = if (hotMask != null) {
            require(hotMask.size == cfa.width * cfa.height) { "Hot mask must cover the frame" }
            hotMask
        } else {
            RawSrHotPixel.detectPacked(input.packed)
        }
        RawSrHotPixel.inpaintNormalized(
            cfa.values, mask, cfa.width, cfa.height, cfa.pattern)
        val sigma = RawSrKernelNetAniso.sigmaFor(input.packed.noiseProfile)
        if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) logNoiseCheck(input)
        return mergeFrame(cfa, tuning, flow = null, robustness = null, kernelNetSigma = sigma).copy(highlightNeutral = RawSrHighlights.neutral(input.metadata))
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
     * Per-frame chain step shared by the eager and streaming paths: unpack,
     * align-or-reject, robustness, precision. Null means the frame is rejected
     * (same gates as [mosaicChain]); every rejection logs its stage at WARN so
     * a production "kept no moving frame" failure names its cause in logcat.
     * Only [Exception] rejects a frame: [OutOfMemoryError] and cancellation
     * propagate so the caller reports heap pressure truthfully instead of
     * misreporting it as an alignment failure.
     */
    private fun buildMovingFrame(
        refGray: RawSrGrayImage,
        refGuide: RawSrRobustness.LinearGuide,
        tuning: RawSrTuning,
        config: RawSrAlignmentConfig,
        input: MosaicInput,
        label: String,
        noiseLut: RawSrNoiseLut.Lut?
    ): RawSrBayerMerge.MergeFrame? {
        // Per-stage wall clock for production-save diagnosis (one line per
        // frame; Debug-gated so release logcat stays quiet).
        val t0 = android.os.SystemClock.elapsedRealtime()
        val cfa = correctedCfa(input)
        if (cfa == null) {
            android.util.Log.w(TAG, "mosaic frame $label rejected: unpack")
            return null
        }
        // One hot-pixel detection per frame, shared by the guide rail and
        // the sample inpaint below. Inpaint runs before the gray so
        // alignment, robustness, and the kernel means all read clean taps;
        // the rail gate still zeroes the quad's robustness weight.
        val hot = RawSrHotPixel.detectPacked(input.packed)
        RawSrHotPixel.inpaintNormalized(
            cfa.values, hot, cfa.width, cfa.height, cfa.pattern)
        val tUnpack = android.os.SystemClock.elapsedRealtime()
        val gray = RawSrAlignment.bayerQuadGray(cfa)
        val coarse = try {
            RawSrAlignment.align(refGray, gray, config)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w(TAG, "mosaic frame $label rejected: align (${failure.message})")
            return null
        }
        if (coarse.tiles.none { it.reliable }) {
            android.util.Log.w(TAG, "mosaic frame $label rejected: no reliable tile")
            return null
        }
        val tAlign = android.os.SystemClock.elapsedRealtime()
        val flow = upsampleFlowToQuads(coarse, cfa.width / 2, cfa.height / 2)
        val robustness = try {
            RawSrRobustness.evaluate(
                refGuide, RawSrRobustness.linearGuide(input.packed, hot), flow, tuning, config, noiseLut
            )
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w(TAG, "mosaic frame $label rejected: robustness (${failure.message})")
            return null
        }
        val tRobust = android.os.SystemClock.elapsedRealtime()
        // Unblocker (Sabre analogue): attenuate the frame's robustness where
        // 2x2 boxing destroyed signal variance, before the merge consumes it.
        // The green normalized coefficients come from the same reduction the
        // GPU path uploads; without a usable model every weight is 1 and the
        // bake is a no-op. One site serves the Linear oracle and the Mosaic
        // chain alike; the reference never attenuates (it defines detail).
        val greenParams = RawSrRobustness.gpuParams(input.packed)
        val unblocked = RawSrUnblocker.applyToFrame(
            robustness,
            RawSrUnblocker.computeFrame(
                gray, greenParams.alpha[1].toDouble(), greenParams.beta[1].toDouble()))
        val sigma = RawSrKernelNetAniso.sigmaFor(input.packed.noiseProfile)
        val frame = mergeFrame(cfa, tuning, flow, unblocked, gray, sigma)
        if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
            android.util.Log.d(TAG, "mosaic frame ${cfa.width}x${cfa.height}" +
                " unpack=${tUnpack - t0}ms align=${tAlign - tUnpack}ms" +
                " robust=${tRobust - tAlign}ms precision=${android.os.SystemClock.elapsedRealtime() - tRobust}ms")
        }
        return frame
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
        gray: RawSrGrayImage = RawSrAlignment.bayerQuadGray(cfa),
        kernelNetSigma: Float? = null
    ): RawSrBayerMerge.MergeFrame {
        val analytic = RawSrKernelCovariance.precision(gray, tuning)
        // KernelNet drives the anisotropic weights directly when its model is
        // ready; per-pixel (or wholesale) fallback keeps the analytic field.
        // Narrow-axis clamp: this builder serves the mosaic chain (1.4x CFA
        // target) exclusively — the 1x RGB production merge runs on the GPU
        // path — and sub-lattice across-axes ring and straddle per-channel
        // edges in ways third-party demosaics read as zipper.
        val precision = MosaicSrReconstructor.clampMinorAxis(
            if (kernelNetSigma != null)
                RawSrKernelNetAniso.precisionFor(cfa, analytic, kernelNetSigma)
            else analytic,
            MosaicSrReconstructor.minorAxisSigmaFloor
        )
        return RawSrBayerMerge.MergeFrame(
            width = cfa.width,
            height = cfa.height,
            samples = cfa.values,
            sensorPattern = cfa.pattern,
            sensorLeft = cfa.sensorCropLeft,
            sensorTop = cfa.sensorCropTop,
            precision = precision,
            flow = flow,
            robustness = robustness
        )
    }
}
