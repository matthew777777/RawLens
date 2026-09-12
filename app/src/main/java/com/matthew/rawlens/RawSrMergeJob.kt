// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.opengl.GLES30
import java.nio.ByteBuffer
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

    /**
     * Memory-compact backing for a quad-grid flow field. A full-res burst
     * holds ~3.1M quads; one boxed [RawSrTileFlow] per quad costs ~125MB per
     * moving frame and OOMs the mosaic save on a 512MB heap. Flat arrays cost
     * ~40MB for the same grid. Same [List] contract: tileSize is 1, so the
     * center derives from the index ([RawSrAlignmentField.flowAt] and direct
     * indexing behave exactly as with a materialized list; each access still
     * returns a fresh [RawSrTileFlow] value).
     */
    private class QuadFlowTiles(
        private val quadsW: Int,
        private val dx: FloatArray,
        private val dy: FloatArray,
        private val residual: FloatArray,
        private val reliable: BooleanArray
    ) : AbstractList<RawSrTileFlow>() {
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
        for (qy in 0 until quadsH) for (qx in 0 until quadsW) {
            // Bilinear source: the upsampled quad field carries the same
            // smooth values a direct smooth sample would return.
            val tile = coarse.flowAtSmooth(qx.toFloat(), qy.toFloat())
            val i = qy * quadsW + qx
            dx[i] = tile.dx
            dy[i] = tile.dy
            residual[i] = tile.residual
            reliable[i] = tile.reliable
        }
        return RawSrAlignmentField(
            quadsW, quadsH, 1, quadsW, quadsH,
            QuadFlowTiles(quadsW, dx, dy, residual, reliable)
        )
    }

    /** Full-resolution float readback for merged RGBA32F textures. */
    fun readRgbaFloat(textureId: Int, width: Int, height: Int): FloatArray {
        require(textureId != 0 && width > 0 && height > 0)
        val framebuffer = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, textureId, 0
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) ==
                GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "Merged RGB framebuffer is incomplete; no merged artifact may be produced"
            }
            val bytes = ByteBuffer.allocateDirect(width * height * 4 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_FLOAT, bytes)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) {
                "Merged RGB readback failed; no merged artifact may be produced"
            }
            return FloatArray(width * height * 4).also { bytes.asFloatBuffer().get(it) }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, framebuffer, 0)
        }
    }

    data class MosaicChain(
        val reference: RawSrBayerMerge.MergeFrame,
        val moving: List<RawSrBayerMerge.MergeFrame>,
        /** Indices into the caller's frame list that survived alignment. */
        val survivorIndices: List<Int>,
        val tuning: RawSrTuning
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
        config: RawSrAlignmentConfig = RawSrAlignmentConfig(),
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null
    ): MosaicChain {
        require(inputs.size >= 2) { "Mosaic chain needs at least two frames" }
        require(referenceIndex in inputs.indices)
        val refInput = inputs[referenceIndex]
        val tuning = RawSrTuning.fromReference(refInput.packed).tuning
        val refCfa = correctedCfa(refInput)
            ?: throw MergeUnavailableException("Reference frame cannot be unpacked")
        val refGray = RawSrAlignment.bayerQuadGray(refCfa)
        val refGuide = RawSrRobustness.linearGuide(refInput.packed)
        val reference = buildReferenceFrame(refInput)
        val moving = ArrayList<RawSrBayerMerge.MergeFrame>()
        val survivors = ArrayList<Int>()
        var completed = 0
        for ((index, input) in inputs.withIndex()) {
            if (index == referenceIndex) continue
            if (isCancelled?.invoke(completed) == true) {
                throw java.util.concurrent.CancellationException("Mosaic chain cancelled")
            }
            val frame = buildMovingFrame(refGray, refGuide, tuning, config, input) ?: continue
            moving.add(frame)
            survivors.add(index)
            completed++
        }
        if (moving.isEmpty()) {
            throw MergeUnavailableException("Mosaic chain kept no moving frame after alignment")
        }
        return MosaicChain(reference, moving, survivors, tuning)
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
        val frames: Sequence<RawSrBayerMerge.MergeFrame>
    )

    fun mosaicStream(
        inputs: List<MosaicInput>,
        referenceIndex: Int,
        config: RawSrAlignmentConfig = RawSrAlignmentConfig(),
        isCancelled: ((rowsCompleted: Int) -> Boolean)? = null
    ): MosaicStream {
        require(inputs.size >= 2) { "Mosaic chain needs at least two frames" }
        require(referenceIndex in inputs.indices)
        val refInput = inputs[referenceIndex]
        val tuning = RawSrTuning.fromReference(refInput.packed).tuning
        val refCfa = correctedCfa(refInput)
            ?: throw MergeUnavailableException("Reference frame cannot be unpacked")
        val refGray = RawSrAlignment.bayerQuadGray(refCfa)
        // Built once: the guide depends only on the reference packed frame,
        // so recomputing it per moving frame below would repeat a full-res
        // pass for identical values.
        val refGuide = RawSrRobustness.linearGuide(refInput.packed)
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
                val frame = buildMovingFrame(refGray, refGuide, tuning, config, input)
                if (frame != null) {
                    yield(frame)
                    completed++
                }
            }
        }
        return MosaicStream(geometry, tuning, inputs.size, frames)
    }

    /**
     * Reference merge frame: unpack, lens-shading correction, precision. No
     * flow or robustness (the reference anchors both). Shared by the eager
     * chain and by the streaming path's one-shot reference accumulation.
     */
    fun buildReferenceFrame(input: MosaicInput): RawSrBayerMerge.MergeFrame {
        val tuning = RawSrTuning.fromReference(input.packed).tuning
        val cfa = correctedCfa(input)
            ?: throw MergeUnavailableException("Reference frame cannot be unpacked")
        return mergeFrame(cfa, tuning, flow = null, robustness = null)
    }

    /**
     * Per-frame chain step shared by the eager and streaming paths: unpack,
     * align-or-reject, robustness, precision. Null means the frame is rejected
     * (same gates as [mosaicChain]).
     */
    private fun buildMovingFrame(
        refGray: RawSrGrayImage,
        refGuide: RawSrRobustness.LinearGuide,
        tuning: RawSrTuning,
        config: RawSrAlignmentConfig,
        input: MosaicInput
    ): RawSrBayerMerge.MergeFrame? {
        // Per-stage wall clock for production-save diagnosis (one line per
        // frame; Debug-gated so release logcat stays quiet).
        val t0 = android.os.SystemClock.elapsedRealtime()
        val cfa = correctedCfa(input) ?: return null
        val tUnpack = android.os.SystemClock.elapsedRealtime()
        val gray = RawSrAlignment.bayerQuadGray(cfa)
        val coarse = runCatching { RawSrAlignment.align(refGray, gray, config) }.getOrNull()
            ?: return null
        if (coarse.tiles.none { it.reliable }) return null
        val tAlign = android.os.SystemClock.elapsedRealtime()
        val flow = upsampleFlowToQuads(coarse, cfa.width / 2, cfa.height / 2)
        val robustness = runCatching {
            RawSrRobustness.evaluate(
                refGuide, RawSrRobustness.linearGuide(input.packed), flow, tuning, config
            )
        }.getOrNull() ?: return null
        val tRobust = android.os.SystemClock.elapsedRealtime()
        val frame = mergeFrame(cfa, tuning, flow, robustness, gray)
        if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
            android.util.Log.d(TAG, "mosaic frame ${cfa.width}x${cfa.height}" +
                " unpack=${tUnpack - t0}ms align=${tAlign - tUnpack}ms" +
                " robust=${tRobust - tAlign}ms precision=${android.os.SystemClock.elapsedRealtime() - tRobust}ms")
        }
        return frame
    }

    /** Merge-consumed frame (unpack + lens shading); internal so the merge-debug payload dumps identical bytes. */
    internal fun correctedCfa(input: MosaicInput): UnpackedRawCfa? = runCatching {
        val base = unpack(input.packed)
        if (input.metadata.lensShadingAlreadyApplied) base
        else requireNotNull(
            RawPreDemosaicPipeline.process(base, input.metadata, PreDemosaicSettings()).cfa
        ) { "Lens-shading correction produced no CFA" }
    }.getOrNull()

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
        gray: RawSrGrayImage = RawSrAlignment.bayerQuadGray(cfa)
    ): RawSrBayerMerge.MergeFrame {
        return RawSrBayerMerge.MergeFrame(
            width = cfa.width,
            height = cfa.height,
            samples = cfa.values,
            sensorPattern = cfa.pattern,
            sensorLeft = cfa.sensorCropLeft,
            sensorTop = cfa.sensorCropTop,
            precision = RawSrKernelCovariance.precision(gray, tuning),
            flow = flow,
            robustness = robustness
        )
    }
}
