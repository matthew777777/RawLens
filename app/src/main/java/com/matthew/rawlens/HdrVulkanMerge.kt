// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.res.AssetManager
import android.opengl.GLES30
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * From-scratch Vulkan HDR merge for exposure brackets (HDR, not HDR+).
 *
 * Pipeline per alternate frame, in input order like darktable (the first
 * frame wins clipped ties):
 *  1. Align: translation pre-align ([HdrBracketAligner], PhotonCamera
 *     normalize/align2 cost model) + dense FlowNet refinement
 *     ([HdrFlowNetAligner]) composed into one reference-anchored field.
 *  2. Warp: same-colour bilinear resample (`hdr/hdr_warp.glsl`, an
 *     [HdrRawMerge.sampleSmoothAt] twin that never mixes Bayer colours).
 *  3. Block stats: per-2x2-cell 3x3 max/min with darktable's border rule
 *     (`hdr/hdr_blockstats.glsl`).
 *  4. Deghost: spatial PhotonCamera-style robust blend toward the reference
 *     (`hdr/hdr_robustness.glsl`): the Hasinoff-2016/hdr-plus-swift Wiener
 *     family (`w = D / (D + strength*motion*hi*noise)`) evaluated per pixel
 *     with DNG Poisson-Gaussian noise, corr-damped strength, per-companion
 *     motion ceilings, and the longer-companion highlight discount (all via
 *     [HdrPlusRobustness], same operating points as [HdrTileDeghost]).
 *  5. Accumulate: darktable `_control_merge_hdr_process` photon weighting,
 *     envelope mask, and clipped-winner fallback
 *     (`hdr/hdr_accumulate.glsl`), white-level normalize at the end
 *     (`hdr/hdr_normalize.glsl`).
 *
 * Frequency-domain selectivity (per-bin Wiener, subpixel phase refinement,
 * deconvolution lift) stays in [HdrTileDeghost] on the legacy CPU path;
 * this pipeline trades it for full Vulkan residency. The legacy CPU merge
 * ([HdrRawMerge]) is untouched and remains the audit reference: every GPU
 * stage has a pure-CPU twin in this file, and [mergeCpu] runs the identical
 * stage order on the JVM (no Vulkan needed) for parity tests.
 *
 * HDR+ is out of scope: no `HdrPlus*` or `hdrplus/` asset is touched. The
 * shared symbols this file reads ([HdrRawMerge] calibration/photon/
 * envelope/sampling, [HdrTileDeghost] gain/mismatch/motion,
 * [HdrPlusRobustness] bracket factors) are consumed read-only.
 */
object HdrVulkanMerge {
    const val SHADER_WARP = "hdr/hdr_warp.glsl"
    const val SHADER_BLOCKSTATS = "hdr/hdr_blockstats.glsl"
    const val SHADER_ROBUSTNESS = "hdr/hdr_robustness.glsl"
    const val SHADER_ACCUMULATE = "hdr/hdr_accumulate.glsl"
    const val SHADER_NORMALIZE = "hdr/hdr_normalize.glsl"

    /** Warped-side variance inflation: resampling correlates noise (same source as [HdrTileDeghost]). */
    internal const val WARP_VARIANCE_INFLATION = 1.25f
    /** Static-tile Wiener boost ceiling (same operating point as the tile deghost path). */
    internal const val MAX_MOTION_BASE = 6f
    /** Darktable quantization margin (same value as [HdrRawMerge]). */
    internal const val QUANTIZATION_MARGIN = 3000f / 65535f
    internal const val EPS_WEIGHT = 1e-8f

    data class Options(
        val smoothMask: Boolean = true,
        val deghost: Boolean = true,
        /**
         * Wiener tuning (upstream `robustness_norm` role, same scale as
         * [HdrRawMerge.Options.wienerStrength]): mismatches collapse to the
         * reference regardless; larger keeps more alternate on matches.
         */
        val wienerStrength: Float = 8f,
        /**
         * Pixel-domain analogue of
         * [HdrRawMerge.Options.alternateDropDcRejectFrac]: an alternate whose
         * collapse-weight census reaches w >= 0.5 on more than this fraction
         * of pixels is dropped (it carries almost no alternate signal).
         * Null keeps every alternate. The reference is never dropped.
         */
        val alternateDropRejectFrac: Double? = null
    ) {
        init {
            require(wienerStrength.isFinite() && wienerStrength > 0f)
            require(alternateDropRejectFrac == null ||
                (alternateDropRejectFrac.isFinite() && alternateDropRejectFrac in 0.0..1.0))
        }
    }

    // ---- pure stage math (JVM-testable twins of the .glsl) ----

    /** Noise-profile slot for (x, y): CFA order R, Gr, Gb, B (see [CfaNoiseModel]). */
    fun noiseChannel(pattern: BayerPattern, x: Int, y: Int): Int = when (pattern.colorAt(x, y)) {
        CfaColor.RED -> 0
        CfaColor.BLUE -> 3
        CfaColor.GREEN -> if ((y and 1) == 0) 1 else 2
    }

    /** Poisson-Gaussian pixel variance (twin of the deghost helper). */
    fun pixelVariance(noise: CfaNoiseModel?, channel: Int, level: Float): Float {
        if (noise == null) {
            return CfaNoiseModel.FALLBACK_SCALE * max(level, 0f) + CfaNoiseModel.FALLBACK_OFFSET
        }
        return noise.scale[channel] * max(level, 0f) + noise.offset[channel]
    }

    /** Collapse-to-reference weight (shader `w`, [HdrTileDeghost] per-bin twin). */
    fun collapseWeight(d2: Float, noiseVar: Float, dampedStrength: Float, motion: Float, hi: Float): Float =
        (d2 / (d2 + dampedStrength * motion * hi * noiseVar)).coerceIn(0f, 1f)

    fun mismatchOf(meanAbsDiff: Float, noiseScale: Float): Float =
        HdrTileDeghost.mismatchFromStats(meanAbsDiff, noiseScale)

    fun motionGate(mismatch: Float, maxMotion: Float): Float =
        HdrTileDeghost.motionNorm(mismatch, maxMotion)

    fun highlightOf(factor: Float, movPixel: Float): Float =
        HdrPlusRobustness.highlightsNorm(factor, ((movPixel - 0.5f) / 0.49f).coerceIn(0f, 1f))

    /**
     * One robust-blend pixel (shader main twin). Returns (kept, w): the
     * mov-domain alternate pulled toward the reference, and the
     * collapse weight for the drop census.
     */
    fun robustPixel(
        ref: Float,
        mov: Float,
        channel: Int,
        noiseSRef: FloatArray,
        noiseORef: FloatArray,
        noiseSMov: FloatArray,
        noiseOMov: FloatArray,
        gain: Float,
        refScale: Float,
        dampedStrength: Float,
        maxMotion: Float,
        factor: Float
    ): Pair<Float, Float> {
        if (!ref.isFinite() || !mov.isFinite()) return mov to 1f
        val g = max(gain, 1e-9f)
        val matched = mov * g
        val d = ref - matched
        val varR = noiseSRef[channel] * max(ref, 0f) + noiseORef[channel]
        val varM = (noiseSMov[channel] * max(mov / g, 0f) + noiseOMov[channel]) *
            g * g * WARP_VARIANCE_INFLATION
        val noise = max(varR + varM, 1e-12f)
        val sigma = sqrt(0.5f * (varR + varM) + 1e-12f)
        val mismatch = mismatchOf(abs(d), sigma)
        val motion = motionGate(mismatch, maxMotion)
        val motionReject = ((mismatch - 0.3f) / 0.3f).coerceIn(0f, 1f)
        val hi = highlightOf(factor, mov)
        val d2 = d * d
        val w = max(collapseWeight(d2, noise, dampedStrength, motion, hi), motionReject)
        return (w * ref * refScale + (1f - w) * mov) to w
    }

    /**
     * Warp tap twin of `hdr_warp` ([HdrRawMerge.sampleSmoothAt] with a
     * zero-displacement fallback for non-finite flow: the GPU path cannot
     * throw, so the CPU twin matches the shader instead of the strict
     * sampler. Identical to [HdrRawMerge.sampleSmooth] on finite flow.
     */
    /**
     * Flow sample with the shader's contract: non-finite displacements fall
     * back to zero instead of throwing (the shared sampler requires finite
     * flow for arbitrary fields, so the require is caught here).
     */
    private fun flowIntoOrZero(flow: HdrFlowField, x: Int, y: Int, tmp: FloatArray, scratch: FloatArray) {
        try {
            HdrRawMerge.sampleFlowInto(flow, x, y, tmp, scratch)
        } catch (_: IllegalArgumentException) {
            tmp[0] = 0f
            tmp[1] = 0f
            return
        }
        if (!tmp[0].isFinite()) tmp[0] = 0f
        if (!tmp[1].isFinite()) tmp[1] = 0f
    }

    fun warpPixel(cfa: UnpackedRawCfa, flow: HdrFlowField?, x: Int, y: Int): Float {
        if (flow == null) return cfa.values[y * cfa.width + x]
        val tmp = FloatArray(2)
        val scratch = FloatArray(2)
        flowIntoOrZero(flow, x, y, tmp, scratch)
        return HdrRawMerge.sampleSmoothAt(cfa, tmp[0], tmp[1], x, y)
    }

    /** Block extremes twin of `hdr_blockstats` (darktable border rule). */
    fun blockGrid(registered: FloatArray, width: Int, height: Int): Pair<FloatArray, FloatArray> {
        val cw = width / 2
        val ch = height / 2
        val blockMax = FloatArray(cw * ch)
        val blockMin = FloatArray(cw * ch) { Float.MAX_VALUE }
        for (cy in 0 until ch) for (cx in 0 until cw) {
            val ox = cx * 2
            val oy = cy * 2
            if (ox >= width - 2 || oy >= height - 2) continue
            var mx = 0f
            var mn = Float.MAX_VALUE
            for (dy in 0..2) for (dx in 0..2) {
                val v = registered[(oy + dy) * width + ox + dx]
                mx = max(mx, v)
                mn = min(mn, v)
            }
            blockMax[cy * cw + cx] = mx
            blockMin[cy * cw + cx] = mn
        }
        return blockMax to blockMin
    }

    /**
     * One accumulate pixel (`hdr_accumulate` twin, darktable branch logic).
     * Returns (newPix, newWt) given the previous accumulators.
     */
    fun accumulatePixel(
        reg: Float,
        cellMax: Float,
        cellMin: Float,
        envMax: Float,
        interior: Boolean,
        smoothMask: Boolean,
        prevP: Float,
        prevW: Float,
        cal: Float,
        photon: Float,
        white: Float
    ): Pair<Float, Float> {
        var weight = photon
        if (interior) {
            val m = if (smoothMask) envMax else cellMax
            weight *= EPS_WEIGHT + HdrRawMerge.envelope(m + QUANTIZATION_MARGIN)
        }
        if (cellMax + QUANTIZATION_MARGIN >= 1f) {
            if (prevW <= 0f && (prevW == 0f || cellMin < -prevW)) {
                val p = if (cellMin + QUANTIZATION_MARGIN >= 1f) 1f else reg * cal / white
                return p to -cellMin
            }
            return prevP to prevW
        }
        val baseP = if (prevW <= 0f) 0f else prevP
        val baseW = if (prevW <= 0f) 0f else prevW
        return (baseP + weight * reg * cal) to (baseW + weight)
    }

    fun normalizePixel(pix: Float, wt: Float, white: Float): Float =
        if (wt > 0f) max(0f, pix / (wt * white)) else pix

    /** Bilinear tap of the block-max grid (envelope weight twin). */
    fun sampleBlockMax(blockMax: FloatArray, cw: Int, ch: Int, x: Int, y: Int): Float {
        val gx = (x / 2f - 0.5f).coerceIn(0f, (cw - 1).toFloat())
        val gy = (y / 2f - 0.5f).coerceIn(0f, (ch - 1).toFloat())
        val x0 = gx.toInt().coerceIn(0, cw - 1)
        val y0 = gy.toInt().coerceIn(0, ch - 1)
        val x1 = min(x0 + 1, cw - 1)
        val y1 = min(y0 + 1, ch - 1)
        val fx = (gx - x0).coerceIn(0f, 1f)
        val fy = (gy - y0).coerceIn(0f, 1f)
        val a = blockMax[y0 * cw + x0]
        val b = blockMax[y0 * cw + x1]
        val c = blockMax[y1 * cw + x0]
        val d = blockMax[y1 * cw + x1]
        return (a * (1f - fx) + b * fx) * (1f - fy) + (c * (1f - fx) + d * fx) * fy
    }

    // ---- CPU reference pipeline (same stages, same order as the GPU path) ----

    /**
     * All-at-once CPU merge over materialized frames. Production bracket
     * saves prefer the streaming [CpuSession] (same math, one alternate
     * live at a time).
     */
    fun mergeCpu(
        frames: List<HdrMergeFrame>,
        referenceIndex: Int = frames.size / 2,
        options: Options = Options(),
        onAlternateDropped: ((index: Int, rejectFrac: Double) -> Unit)? = null
    ): UnpackedRawCfa {
        require(frames.size >= 2) { "HDR merge requires at least two exposures" }
        require(referenceIndex in frames.indices)
        val session = beginCpuSession(
            frames[referenceIndex],
            frames.map {
                HdrRawMerge.calibration(it.exposureTimeNanos, it.sensitivityIso, it.aperture, it.focalLength)
            },
            frames.map {
                HdrRawMerge.photonCount(it.exposureTimeNanos, it.sensitivityIso, it.aperture, it.focalLength)
            },
            referenceIndex, frames.size, options, onAlternateDropped
        )
        frames.forEachIndexed { k, frame -> session.accumulate(k, frame) }
        return session.finish()
    }

    /**
     * Incremental CPU merge session: the exact math of [mergeCpu], but
     * frames accumulate one at a time so the caller can unpack, align,
     * merge, and release each alternate instead of holding every unpacked
     * frame plus the accumulators at once. Accumulate in input order (each
     * index exactly once): like darktable, the first frame wins clipped
     * ties, so order is part of the output.
     */
    class CpuSession internal constructor(
        val reference: HdrMergeFrame,
        val referenceIndex: Int,
        val frameCount: Int,
        cals: List<Float>,
        photons: List<Float>,
        private val options: Options,
        private val onAlternateDropped: ((index: Int, rejectFrac: Double) -> Unit)?
    ) {
        val width: Int = reference.cfa.width
        val height: Int = reference.cfa.height
        private val count = width * height
        private val cw = width / 2
        private val ch = height / 2
        private val calList = cals.toList()
        private val photonList = photons.toList()
        private val white = calList.maxOrNull() ?: 1f
        private val factors: List<Float> = calList.map {
            HdrPlusRobustness.exposureFactorFromCalibrations(calList[referenceIndex], it)
        }
        private val pixels = FloatArray(count)
        private val weights = FloatArray(count)
        private val warpBuf = FloatArray(count)
        private val keptBuf = FloatArray(count)
        private val wBuf = FloatArray(count)
        private var accumulated = 0

        /** Warp, deghost, and photon-weight frame [index] (call in order). */
        fun accumulate(index: Int, frame: HdrMergeFrame) {
            require(index in 0 until frameCount) {
                "Frame index $index outside 0..${frameCount - 1}"
            }
            require(index == accumulated) {
                "Frames must accumulate in input order (expected $accumulated, got $index)"
            }
            checkFrameMatches(frame, index)
            accumulated++
            HdrPools.runStriped(height, 8) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until width) {
                    warpBuf[y * width + x] = warpPixel(frame.cfa, frame.flow, x, y)
                }
            }
            // Saturation belongs to the observed exposure: extremes come
            // from the warped frame, before deghosting.
            val (blockMax, blockMin) = blockGrid(warpBuf, width, height)
            var registered: FloatArray = warpBuf
            if (index != referenceIndex && options.deghost) {
                val rejectFrac = robustDeghostCpu(
                    reference, frame, warpBuf, keptBuf, wBuf,
                    factors, options.wienerStrength
                )
                val dropFrac = options.alternateDropRejectFrac
                if (dropFrac != null && rejectFrac > dropFrac) {
                    onAlternateDropped?.invoke(index, rejectFrac)
                    return
                }
                registered = keptBuf
            }
            val cal = calList[index]
            val photon = photonList[index]
            HdrPools.runStriped(height, 8) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until width) {
                    val i = y * width + x
                    val cell = (y / 2) * cw + x / 2
                    val interior = (x and -2) < width - 2 && (y and -2) < height - 2
                    val envMax = if (options.smoothMask && interior) {
                        sampleBlockMax(blockMax, cw, ch, x, y)
                    } else blockMax[cell]
                    val (p, w) = accumulatePixel(
                        registered[i], blockMax[cell], blockMin[cell], envMax,
                        interior, options.smoothMask, pixels[i], weights[i],
                        cal, photon, white
                    )
                    pixels[i] = p
                    weights[i] = w
                }
            }
        }

        /**
         * Normalizes the accumulators into the merged CFA. Every index must
         * have been accumulated exactly once (dropped alternates count:
         * they were consumed, they just contributed no photons).
         */
        fun finish(): UnpackedRawCfa {
            require(accumulated == frameCount) {
                "Merge finished after $accumulated of $frameCount frames"
            }
            for (i in pixels.indices) pixels[i] = normalizePixel(pixels[i], weights[i], white)
            return reference.cfa.copy(values = pixels)
        }

        private fun checkFrameMatches(frame: HdrMergeFrame, k: Int) {
            val ref = reference.cfa
            require(frame.cfa.width == ref.width && frame.cfa.height == ref.height &&
                frame.cfa.pattern == ref.pattern) {
                "HDR frames must have identical dimensions and CFA phase"
            }
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            for (v in frame.cfa.values) {
                if (v < min) min = v
                if (v > max) max = v
            }
            require(min >= -0.5f && max <= 1.5f) {
                "HDR frame $k looks un-normalized (range $min..$max, expected ~0..1)"
            }
        }
    }

    /**
     * Opens a streaming CPU merge over [frameCount] frames with [reference]
     * as the geometric anchor.
     */
    fun beginCpuSession(
        reference: HdrMergeFrame,
        calibrations: List<Float>,
        photonWeights: List<Float>,
        referenceIndex: Int,
        frameCount: Int = calibrations.size,
        options: Options = Options(),
        onAlternateDropped: ((index: Int, rejectFrac: Double) -> Unit)? = null
    ): CpuSession {
        require(frameCount >= 2) { "HDR merge requires at least two exposures" }
        require(referenceIndex in 0 until frameCount)
        require(calibrations.size == frameCount && photonWeights.size == frameCount) {
            "Calibration lists must cover all $frameCount frames"
        }
        return CpuSession(
            reference, referenceIndex, frameCount,
            calibrations, photonWeights, options, onAlternateDropped
        )
    }

    /**
     * Spatial robust deghost on CPU (shader twin). Returns the collapse
     * census: the fraction of pixels with w >= 0.5.
     */
    internal fun robustDeghostCpu(
        reference: HdrMergeFrame,
        moving: HdrMergeFrame,
        warped: FloatArray,
        keptOut: FloatArray,
        wOut: FloatArray,
        factors: List<Float>,
        strength: Float
    ): Double {
        val width = reference.cfa.width
        val height = reference.cfa.height
        val refVals = reference.cfa.values
        val pattern = reference.cfa.pattern
        val gain = HdrTileDeghost.matchGain(reference, moving.copy(cfa = moving.cfa.copy(values = warped)))
        val exposureGain = HdrRawMerge.calibration(moving) / HdrRawMerge.calibration(reference)
        val factor = (1f / exposureGain).coerceIn(1f / 1024f, 1024f)
        val uniform = HdrPlusRobustness.isUniform(factors)
        val damped = strength * HdrPlusRobustness.strengthDamping(factors)
        val maxMotion = HdrPlusRobustness.maxMotionForFactor(MAX_MOTION_BASE, factor, uniform)
        val refScale = gain / exposureGain
        val refNoise = reference.noiseModel
        val movNoise = moving.noiseModel
        val sRef = FloatArray(4) { refNoise?.scale?.get(it) ?: CfaNoiseModel.FALLBACK_SCALE }
        val oRef = FloatArray(4) { refNoise?.offset?.get(it) ?: CfaNoiseModel.FALLBACK_OFFSET }
        val sMov = FloatArray(4) { movNoise?.scale?.get(it) ?: CfaNoiseModel.FALLBACK_SCALE }
        val oMov = FloatArray(4) { movNoise?.offset?.get(it) ?: CfaNoiseModel.FALLBACK_OFFSET }
        val lock = Any()
        var rejected = 0L
        HdrPools.runStriped(height, 8) { y0, y1 ->
            var localRejected = 0L
            for (y in y0 until y1) for (x in 0 until width) {
                val i = y * width + x
                val ch = noiseChannel(pattern, x, y)
                val (kept, w) = robustPixel(
                    refVals[i], warped[i], ch, sRef, oRef, sMov, oMov,
                    gain, refScale, damped, maxMotion, factor
                )
                keptOut[i] = kept
                wOut[i] = w
                if (w >= 0.5f) localRejected++
            }
            synchronized(lock) {
                rejected += localRejected
            }
        }
        val n = width.toLong() * height
        return rejected.toDouble() / n
    }

    // ---- Vulkan path (same stages dispatched to the hdr/*.glsl passes) ----

    /**
     * Borrowed-device GPU session: [VulkanHub.Handle] lifetime stays with
     * the caller; programs, images, and staging release on [close].
     * Confine to one thread like the other Vulkan orchestrators.
     */
    internal class GpuContext(vk: VulkanHub.Handle, assets: AssetManager) : Closeable {
        private val programs = VkProgramCache(vk, assets)
        private val arena = VkArena(vk)
        private val uploads = UploadBuffers()
        val session = VkSession(vk, programs, uploads)

        fun texture(w: Int, h: Int, format: Int): VkImage = arena.texture(w, h, format)
        fun release(image: VkImage) = arena.release(image)

        override fun close() {
            try {
                programs.close()
            } finally {
                uploads.close()
                arena.close()
            }
        }
    }

    /** Bayer quad colour slots in R, Gr, Gb, B order for the `u_fc` uniform. */
    internal fun fcSlots(pattern: BayerPattern): IntArray =
        IntArray(4) { q ->
            val qx = q and 1
            val qy = (q shr 1) and 1
            when (pattern.colorAt(qx, qy)) {
                CfaColor.RED -> 0
                CfaColor.BLUE -> 3
                CfaColor.GREEN -> if (qy == 0) 1 else 2
            }
        }

    /**
     * Incremental GPU merge session: the exact dispatches of [mergeVulkan],
     * but frames accumulate one at a time so the caller can unpack, align,
     * merge, and release each alternate instead of holding every unpacked
     * frame at once. The session owns its images (not the borrowed
     * [gpu]); [close] releases them. Accumulate in input order.
     */
    internal class GpuSession internal constructor(
        private val gpu: GpuContext,
        val reference: HdrMergeFrame,
        val referenceIndex: Int,
        val frameCount: Int,
        cals: List<Float>,
        photons: List<Float>,
        private val options: Options,
        private val onAlternateDropped: ((index: Int, rejectFrac: Double) -> Unit)?
    ) : Closeable {
        val width: Int = reference.cfa.width
        val height: Int = reference.cfa.height
        private val count = width * height
        private val cw = width / 2
        private val ch = height / 2
        private val calList = cals.toList()
        private val photonList = photons.toList()
        private val white = calList.maxOrNull() ?: 1f
        private val factors: List<Float> = calList.map {
            HdrPlusRobustness.exposureFactorFromCalibrations(calList[referenceIndex], it)
        }
        private val fc = fcSlots(reference.cfa.pattern)
        private val refNoise = reference.noiseModel
        private val sRef = FloatArray(4) { refNoise?.scale?.get(it) ?: CfaNoiseModel.FALLBACK_SCALE }
        private val oRef = FloatArray(4) { refNoise?.offset?.get(it) ?: CfaNoiseModel.FALLBACK_OFFSET }
        private val pass = gpu.session
        private val r32f = GLES30.GL_R32F
        private val refImg = gpu.texture(width, height, r32f)
        private val accAPix = gpu.texture(width, height, r32f)
        private val accAWt = gpu.texture(width, height, r32f)
        private val accBPix = gpu.texture(width, height, r32f)
        private val accBWt = gpu.texture(width, height, r32f)
        private var accRPix = accAPix
        private var accRWt = accAWt
        private var accWPix = accBPix
        private var accWWt = accBWt
        private val warpIn = gpu.texture(width, height, r32f)
        private val warpOut = gpu.texture(width, height, r32f)
        private val maxImg = gpu.texture(cw, ch, r32f)
        private val minImg = gpu.texture(cw, ch, r32f)
        private val keptImg = gpu.texture(width, height, r32f)
        private val wImg = gpu.texture(width, height, r32f)
        private val flowImg = gpu.texture(width, height, GLES30.GL_RGBA32F)
        private val outImg = gpu.texture(width, height, r32f)
        private var accumulated = 0
        private var closed = false

        init {
            refImg.uploadR32f(reference.cfa.values, pass.uploads)
            accAPix.uploadR32f(FloatArray(count), pass.uploads)
            accAWt.uploadR32f(FloatArray(count), pass.uploads)
        }

        /** Warp, deghost, and photon-weight frame [index] (call in order). */
        fun accumulate(index: Int, frame: HdrMergeFrame) {
            require(index in 0 until frameCount) {
                "Frame index $index outside 0..${frameCount - 1}"
            }
            require(index == accumulated) {
                "Frames must accumulate in input order (expected $accumulated, got $index)"
            }
            check(!closed) { "GpuSession is closed" }
            checkFrameMatches(frame, index)
            accumulated++
                        // Warp into warpOut (never aliased: warpIn is only
                        // ever sampled). Identity frames upload straight
                        // into warpOut with no dispatch.
                        val warped = warpOut
                        if (frame.flow == null) {
                            warpOut.uploadR32f(frame.cfa.values, pass.uploads)
                        } else {
                            warpIn.uploadR32f(frame.cfa.values, pass.uploads)
                            flowImg.uploadRgba32f(
                                denseFlow(frame.flow, width, height), pass.uploads
                            )
                            pass.pass(SHADER_WARP) {
                                sampler("u_cfa", warpIn)
                                sampler("u_flow", flowImg)
                                ivec2("u_size", width, height)
                                image(0, warpOut, r32f)
                                dispatch(width, height, 8, 8)
                            }
                        }
                        pass.pass(SHADER_BLOCKSTATS) {
                                sampler("u_reg", warped)
                                ivec2("u_cells", cw, ch)
                                ivec2("u_full", width, height)
                                image(0, maxImg, r32f)
                                image(1, minImg, r32f)
                                dispatch(cw, ch, 8, 8)
                            }
                            var accReg: VkImage = warped
                            if (index != referenceIndex && options.deghost) {
                                val exposureGain = calList[index] / calList[referenceIndex]
                                val factor =
                                    (1f / exposureGain).coerceIn(1f / 1024f, 1024f)
                                val uniform = HdrPlusRobustness.isUniform(factors)
                                val damped = options.wienerStrength *
                                    HdrPlusRobustness.strengthDamping(factors)
                                val maxMotion = HdrPlusRobustness.maxMotionForFactor(
                                    MAX_MOTION_BASE, factor, uniform
                                )
                                val movNoise = frame.noiseModel
                                // Gain match runs on the unwarped samples
                                // here (the warped field lives on the GPU;
                                // the median over midtones is robust to the
                                // residual shift, same argument the matcher
                                // itself uses for ghosts). The CPU path uses
                                // warped samples like the tile path.
                                val gain = HdrTileDeghost.matchGain(reference, frame)
                                val refScale = gain / exposureGain
                                pass.pass(SHADER_ROBUSTNESS) {
                                    sampler("u_ref", refImg)
                                    sampler("u_mov", warped)
                                    ivec2("u_size", width, height)
                                    ivec4("u_fc", fc)
                                    vec4("u_noiseSRef", sRef)
                                    vec4("u_noiseORef", oRef)
                                    vec4(
                                        "u_noiseSMov",
                                        FloatArray(4) {
                                            movNoise?.scale?.get(it)
                                                ?: CfaNoiseModel.FALLBACK_SCALE
                                        }
                                    )
                                    vec4(
                                        "u_noiseOMov",
                                        FloatArray(4) {
                                            movNoise?.offset?.get(it)
                                                ?: CfaNoiseModel.FALLBACK_OFFSET
                                        }
                                    )
                                    float("u_gain", gain)
                                    float("u_refScale", refScale)
                                    float("u_strength", damped)
                                    float("u_maxMotion", maxMotion)
                                    float("u_factor", factor)
                                    image(0, keptImg, r32f)
                                    image(1, wImg, r32f)
                                    dispatch(width, height, 8, 8)
                                }
                                accReg = keptImg
                            }
                            pass.pass(SHADER_ACCUMULATE) {
                                sampler("u_reg", accReg)
                                sampler("u_bmax", maxImg)
                                sampler("u_bmin", minImg)
                                sampler("u_accPix", accRPix)
                                sampler("u_accWt", accRWt)
                                ivec2("u_size", width, height)
                                ivec2("u_cells", cw, ch)
                                float("u_cal", calList[index])
                                float("u_photon", photonList[index])
                                float("u_white", white)
                                integer("u_smooth", if (options.smoothMask) 1 else 0)
                                image(0, accWPix, r32f)
                                image(1, accWWt, r32f)
                                dispatch(width, height, 8, 8)
                            }
                            val dropFrac = options.alternateDropRejectFrac
                            if (index != referenceIndex && options.deghost && dropFrac != null) {
                                // Census runs on the collapse weights; a
                                // dropped alternate skips accumulation by
                                // keeping the previous accumulators.
                                var rej = 0L
                                for (w in wImg.downloadR32f()) if (w >= 0.5f) rej++
                                val frac = rej.toDouble() / count
                                if (frac > dropFrac) {
                                    onAlternateDropped?.invoke(index, frac)
                                    return
                                }
                            }
                            // Swap accumulator roles: the next frame reads
                            // what this one wrote. Handles, not bytes.
                            val tp = accRPix
                            accRPix = accWPix
                            accWPix = tp
                            val tw = accRWt
                            accRWt = accWWt
                            accWWt = tw
        }

        /**
         * Normalizes the accumulators into the merged CFA. Every index must
         * have been accumulated exactly once (dropped alternates count).
         */
        fun finish(): UnpackedRawCfa {
            require(accumulated == frameCount) {
                "Merge finished after $accumulated of $frameCount frames"
            }
            check(!closed) { "GpuSession is closed" }
            pass.pass(SHADER_NORMALIZE) {
                sampler("u_accPix", accRPix)
                sampler("u_accWt", accRWt)
                ivec2("u_size", width, height)
                float("u_white", white)
                image(0, outImg, r32f)
                dispatch(width, height, 8, 8)
            }
            return reference.cfa.copy(values = outImg.downloadR32f())
        }

        private fun checkFrameMatches(frame: HdrMergeFrame, k: Int) {
            val ref = reference.cfa
            require(frame.cfa.width == ref.width && frame.cfa.height == ref.height &&
                frame.cfa.pattern == ref.pattern) {
                "HDR frames must have identical dimensions and CFA phase"
            }
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            for (v in frame.cfa.values) {
                if (v < min) min = v
                if (v > max) max = v
            }
            require(min >= -0.5f && max <= 1.5f) {
                "HDR frame $k looks un-normalized (range $min..$max, expected ~0..1)"
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            gpu.release(flowImg)
            gpu.release(warpIn)
            gpu.release(warpOut)
            gpu.release(maxImg)
            gpu.release(minImg)
            gpu.release(keptImg)
            gpu.release(wImg)
            gpu.release(accAPix)
            gpu.release(accAWt)
            gpu.release(accBPix)
            gpu.release(accBWt)
            gpu.release(refImg)
            gpu.release(outImg)
        }
    }

    /**
     * Opens a streaming GPU merge over [frameCount] frames. The caller owns
     * [gpu] (close it after the session); the session owns its images.
     * Throws on allocation/upload/dispatch failure: callers fall back to
     * [beginCpuSession]. If [beginGpuSession] itself throws, no session
     * exists and only [gpu] needs closing.
     */
    internal fun beginGpuSession(
        gpu: GpuContext,
        reference: HdrMergeFrame,
        calibrations: List<Float>,
        photonWeights: List<Float>,
        referenceIndex: Int,
        frameCount: Int = calibrations.size,
        options: Options = Options(),
        onAlternateDropped: ((index: Int, rejectFrac: Double) -> Unit)? = null
    ): GpuSession {
        require(frameCount >= 2) { "HDR merge requires at least two exposures" }
        require(referenceIndex in 0 until frameCount)
        require(calibrations.size == frameCount && photonWeights.size == frameCount) {
            "Calibration lists must cover all $frameCount frames"
        }
        return GpuSession(
            gpu, reference, referenceIndex, frameCount,
            calibrations, photonWeights, options, onAlternateDropped
        )
    }

    /**
     * Full merge on Vulkan over materialized frames. Identical stage order
     * and math to [mergeCpu]; throws on dispatch failure (callers fall back
     * to [mergeCpu]).
     */
    internal fun mergeVulkan(
        frames: List<HdrMergeFrame>,
        referenceIndex: Int = frames.size / 2,
        options: Options = Options(),
        gpu: GpuContext,
        onAlternateDropped: ((index: Int, rejectFrac: Double) -> Unit)? = null
    ): UnpackedRawCfa {
        require(frames.size >= 2) { "HDR merge requires at least two exposures" }
        require(referenceIndex in frames.indices)
        beginGpuSession(
            gpu,
            frames[referenceIndex],
            frames.map {
                HdrRawMerge.calibration(it.exposureTimeNanos, it.sensitivityIso, it.aperture, it.focalLength)
            },
            frames.map {
                HdrRawMerge.photonCount(it.exposureTimeNanos, it.sensitivityIso, it.aperture, it.focalLength)
            },
            referenceIndex, frames.size, options, onAlternateDropped
        ).use { session ->
            frames.forEachIndexed { k, frame -> session.accumulate(k, frame) }
            return session.finish()
        }
    }

    /**
     * Opens a GPU compute context, or null when Vulkan is unavailable (the
     * caller runs the CPU session instead). Never throws.
     */
    internal fun openGpu(assets: AssetManager): GpuContext? = try {
        GpuContext(VulkanHub.open(), assets)
    } catch (_: Throwable) {
        null
    }

    /** Builds a dense RGBA flow texture payload (dx, dy, 0, 0) per pixel. */
    internal fun denseFlow(flow: HdrFlowField, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height * 4)
        HdrPools.runStriped(height, 8) { y0, y1 ->
            val tmp = FloatArray(2)
            val scratch = FloatArray(2)
            for (y in y0 until y1) for (x in 0 until width) {
                flowIntoOrZero(flow, x, y, tmp, scratch)
                val b = (y * width + x) * 4
                out[b] = tmp[0]
                out[b + 1] = tmp[1]
            }
        }
        return out
    }

    /**
     * Entry point: [gpu] selects the Vulkan dispatches; null runs the
     * CPU reference ([mergeCpu]). Vulkan failures must be caught by the
     * caller, which falls back to [mergeCpu].
     */
    internal fun merge(
        frames: List<HdrMergeFrame>,
        referenceIndex: Int = frames.size / 2,
        options: Options = Options(),
        gpu: GpuContext? = null,
        onAlternateDropped: ((index: Int, rejectFrac: Double) -> Unit)? = null
    ): UnpackedRawCfa =
        if (gpu == null) mergeCpu(frames, referenceIndex, options, onAlternateDropped)
        else mergeVulkan(frames, referenceIndex, options, gpu, onAlternateDropped)
}
