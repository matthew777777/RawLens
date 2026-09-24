// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-FileCopyrightText: 2010-2026 darktable developers
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Dense displacement from reference coordinates into a moving frame, in full RAW pixels. */
fun interface HdrFlowField {
    fun displacement(x: Int, y: Int): Pair<Float, Float>
}

/** Flow with allocation-free sampling (dense FlowNet grid). */
internal interface FastFlow : HdrFlowField {
    fun sampleInto(x: Int, y: Int, out: FloatArray)
}

/** Constant displacement (translation pre-align). Allocation-free sampling. */
class TranslationFlow(val dx: Float, val dy: Float) : HdrFlowField {
    override fun displacement(x: Int, y: Int): Pair<Float, Float> = dx to dy
}

/** Sum of two fields (coarse shift + dense residual). Folds constants. */
class CombinedFlow(val first: HdrFlowField, val second: HdrFlowField) : HdrFlowField {
    override fun displacement(x: Int, y: Int): Pair<Float, Float> {
        val (ax, ay) = first.displacement(x, y)
        val (bx, by) = second.displacement(x, y)
        return (ax + bx) to (ay + by)
    }
}

data class HdrMergeFrame(
    val cfa: UnpackedRawCfa,
    val exposureTimeNanos: Long,
    val sensitivityIso: Int,
    val aperture: Float = 1f,
    /** Reference-to-this-frame flow. Null means identity (the reference frame). */
    val flow: HdrFlowField? = null,
    val focalLength: Float = 1f,
    /**
     * Normalized Poisson-Gaussian noise model for robust alignment/deghost
     * weighting. Null selects a data-driven MAD fallback (see
     * [HdrTileDeghost]); callers with DNG metadata should pass
     * `CfaNoiseModel.from(metadata.noiseProfile)`.
     */
    val noiseModel: CfaNoiseModel? = null
) {
    init {
        require(exposureTimeNanos > 0L)
        require(sensitivityIso > 0)
        require(aperture.isFinite() && aperture > 0f)
        require(focalLength.isFinite() && focalLength > 0f)
        require(cfa.values.size == Math.multiplyExact(cfa.width, cfa.height))
        require(cfa.values.all(Float::isFinite))
    }
}

/**
 * RAW exposure-bracket radiance merge.
 *
 * Core math is darktable's `_control_merge_hdr_process()` + `_envelope()` (control_jobs.c):
 * per-frame aperture-area/ISO/exposure calibration, photon-count weighting, 3x3-block
 * saturation envelope, clipped-pixel fallback with negative-weight bookkeeping, and final
 * white-level normalization. [mergeExact] is the verbatim port used for audit/tests.
 *
 * [merge] (production) keeps that structure but fixes known artifact sources:
 *  1. Teeth edges: darktable evaluates one max/min per 2px block origin, so the saturation
 *     mask steps every 2px. The envelope *weight* rides a bilinearly interpolated block
 *     maximum, so shadow/highlight blending is C0-continuous (no 2px stair) — but the
 *     clip *branch* and the clipped-winner bookkeeping stay block-exact like darktable,
 *     so adjacent pixels never pick different fallback winners (no contour lines).
 *  2. Jagged warp: the old nearest-CFA-cell warp snapped flow to 2px steps. Warp is now
 *     same-colour bilinear (only taps of the destination pixel's own Bayer colour), so
 *     sub-pixel flow from translation pre-align / FlowNet stays smooth and never mixes
 *     R/G/B.
 *  3. Ghosts/misalignment/blur (HDR+ deghost): each warped non-reference frame first
 *     passes through [HdrTileDeghost], a pairwise frequency-domain Wiener merge toward
 *     the reference. Bins that match keep the alternate (photon-weighted denoise
 *     downstream); bins broken by motion blur, ghosts, or misregistration collapse to
 *     the sharp reference instead of smearing. Highlights clipped in the reference are
 *     still rescued by the short exposure via darktable's fallback path, which the
 *     tile blend preserves (clipped tiles have no matchable structure).
 */
object HdrRawMerge {
    internal const val EPS_WEIGHT = 1e-8f
    internal const val QUANTIZATION_MARGIN = 3000f / 65535f
    /** Darktable fallbacks when EXIF aperture/focal length are missing (fisheye assumption). */
    const val FALLBACK_APERTURE = 22f
    const val FALLBACK_FOCAL_LENGTH = 8f

    data class Options(
        val smoothMask: Boolean = true,
        val deghost: Boolean = true,
        /**
         * HDR+ Wiener strength for the [HdrTileDeghost] pre-pass. Mismatched
         * bins collapse to the reference regardless; [wienerStrength] sets
         * how much alternate frame survives on matched bins (reference
         * weight near `1 / (1 + strength)`): larger keeps more alternate
         * (cleaner, less ghost-robust), smaller more reference.
         */
        val wienerStrength: Float = 8f,
        /**
         * Per-channel deghost tile edge: [HdrTileDeghost.TILE] (8, default)
         * or [HdrTileDeghost.TILE_LARGE] (16, fallback for heavy blur).
         */
        val deghostTileSize: Int = HdrTileDeghost.TILE
    )

    fun merge(
        frames: List<HdrMergeFrame>,
        referenceIndex: Int = frames.size / 2,
        options: Options = Options()
    ): UnpackedRawCfa {
        checkFrames(frames, referenceIndex)
        if (!options.smoothMask && !options.deghost) return mergeExact(frames, referenceIndex)
        val reference = frames[referenceIndex].cfa
        val width = reference.width
        val height = reference.height
        val count = width * height

        val cals = frames.map { calibration(it) }
        val photons = frames.map { photonCount(it) }
        var whiteLevel = cals.maxOrNull() ?: 1f

        val pixels = FloatArray(count)
        val weights = FloatArray(count)
        // Reused across frames (sequential loop): avoids re-allocating and
        // zeroing ~48MB warp + ~2x12MB block buffers per frame. Values fed to
        // the accumulate loop are identical; only allocation churn is removed.
        val warpBuf = FloatArray(count)
        val cw = width / 2
        val ch = height / 2
        val blockMax = FloatArray(cw * ch)
        val blockMin = FloatArray(cw * ch)

        // Accumulate in input order like darktable: the first frame wins clipped
        // ties. The geometric reference only defines flow coordinates, never order.
        // Row strips run on the shared pool (disjoint writes, deterministic output).
        for ((k, frame) in frames.withIndex()) {
            // Identity frames read the source directly: no 48MB copy.
            var registered: FloatArray
            val flow = frame.flow
            if (flow == null) {
                registered = frame.cfa.values
            } else {
                registered = warpBuf
                parallelRows(height) { y0, y1 ->
                    val tmp = FloatArray(2)
                    val scratch = FloatArray(2)
                    for (y in y0 until y1) {
                        var i = y * width
                        for (x in 0 until width) {
                            registered[i] = sampleSmoothFast(frame.cfa, flow, x, y, tmp, scratch)
                            i++
                        }
                    }
                }
            }
            // Same 3x3 block extremes as darktable, evaluated per 2x2 cell with
            // darktable's border rule (cells past width/height-2 contribute no
            // envelope). The clip branch and fallback bookkeeping below use
            // these block-exact values verbatim, so every pixel of a cell
            // agrees on the winner; only the envelope weight rides the
            // interpolated maximum for C0-continuous blending.
            // Buffers are reused across frames: reset to the same fresh-array
            // defaults (0 / MAX_VALUE) so border cells match exactly.
            blockMax.fill(0f)
            blockMin.fill(Float.MAX_VALUE)
            parallelRows(ch) { cy0, cy1 ->
                for (cy in cy0 until cy1) for (cx in 0 until cw) {
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
            }
            // Saturation belongs to the observed exposure, not the Wiener
            // reconstruction: filtering can pull a clipped channel below white.
            // Keep the original extrema for highlight eligibility and weighting.
            if (k != referenceIndex && options.deghost) {
                // HDR+ robust pre-pass: collapse blurred/ghosted/misregistered
                // bins to the reference before photon weighting (which would
                // otherwise trust a motion-blurred long exposure most).
                registered = HdrTileDeghost.deghost(
                    frames[referenceIndex], frame,
                    frame.cfa.copy(values = registered),
                    options.wienerStrength,
                    options.deghostTileSize
                ).values
            }
            val cal = cals[k]
            val photon = photons[k]
            parallelRows(height) { y0, y1 ->
                val mm = FloatArray(2)
                for (y in y0 until y1) for (x in 0 until width) {
                    val i = y * width + x
                    val sample = registered[i]
                    val cell = (y / 2) * cw + x / 2
                    val cellMax = blockMax[cell]
                    val cellMin = blockMin[cell]
                    val interior = (x and -2) < width - 2 && (y and -2) < height - 2
                    var weight = photon
                    if (interior) {
                        val envMax = if (options.smoothMask) {
                            sampleBlockSmoothInto(blockMax, blockMin, cw, ch, x, y, mm)
                            mm[0]
                        } else cellMax
                        weight *= EPS_WEIGHT + envelope(envMax + QUANTIZATION_MARGIN)
                    }
                    if (cellMax + QUANTIZATION_MARGIN >= 1f) {
                        if (weights[i] <= 0f && (weights[i] == 0f || cellMin < -weights[i])) {
                            pixels[i] = if (cellMin + QUANTIZATION_MARGIN >= 1f) 1f
                            else sample * cal / whiteLevel
                            weights[i] = -cellMin
                        }
                    } else {
                        if (weights[i] <= 0f) {
                            pixels[i] = 0f
                            weights[i] = 0f
                        }
                        pixels[i] += weight * sample * cal
                        weights[i] += weight
                    }
                }
            }
        }
        for (i in pixels.indices) if (weights[i] > 0f)
            pixels[i] = max(0f, pixels[i] / (weights[i] * whiteLevel))
        return reference.copy(values = pixels)
    }

    /**
     * Verbatim darktable port: frames accumulate in input order (darktable has no
     * reference concept — the first frame seeds geometry and wins clipped ties),
     * including border behaviour (no envelope off-image) and negative-weight
     * clipped bookkeeping. Flow fields still map reference coordinates into each
     * frame; warping is order-independent, only accumulation sequence matters.
     */
    fun mergeExact(frames: List<HdrMergeFrame>, referenceIndex: Int = frames.size / 2): UnpackedRawCfa {
        checkFrames(frames, referenceIndex)
        val reference = frames[referenceIndex].cfa
        val width = reference.width
        val height = reference.height
        val count = width * height
        val pixels = FloatArray(count)
        val weights = FloatArray(count)
        var whiteLevel = 0f
        val cals = frames.map { calibration(it) }
        val photons = frames.map { photonCount(it) }
        for (c in cals) whiteLevel = max(whiteLevel, c)
        for ((k, frame) in frames.withIndex()) {
            val cal = cals[k]
            val photon = photons[k]
            val registered = FloatArray(count) { i ->
                sampleCfaSafe(frame.cfa, frame.flow, i % width, i / width)
            }
            for (y in 0 until height) for (x in 0 until width) {
                val i = y * width + x
                val sample = registered[i]
                val qx = x and -2
                val qy = y and -2
                var maximum = 0f
                var minimum = Float.MAX_VALUE
                if (qx < width - 2 && qy < height - 2) {
                    for (dy in 0..2) for (dx in 0..2) {
                        val v = registered[(qy + dy) * width + qx + dx]
                        maximum = max(maximum, v)
                        minimum = min(minimum, v)
                    }
                }
                var weight = photon
                if (minimum != Float.MAX_VALUE) weight *= EPS_WEIGHT + envelope(maximum + QUANTIZATION_MARGIN)
                if (maximum + QUANTIZATION_MARGIN >= 1f) {
                    if (weights[i] <= 0f && (weights[i] == 0f || minimum < -weights[i])) {
                        pixels[i] = if (minimum + QUANTIZATION_MARGIN >= 1f) 1f else sample * cal / whiteLevel
                        weights[i] = -minimum
                    }
                } else {
                    if (weights[i] <= 0f) { pixels[i] = 0f; weights[i] = 0f }
                    pixels[i] += weight * sample * cal
                    weights[i] += weight
                }
            }
        }
        for (i in pixels.indices) if (weights[i] > 0f)
            pixels[i] = max(0f, pixels[i] / (weights[i] * whiteLevel))
        return reference.copy(values = pixels)
    }

    // ---- darktable calibration (control_jobs.c) ----

    internal fun calibration(frame: HdrMergeFrame): Float {
        val apertureArea = Math.PI.toFloat() * sq(0.5f * frame.focalLength / frame.aperture)
        val seconds = frame.exposureTimeNanos * 1e-9f
        return 100f / (apertureArea * seconds * frame.sensitivityIso)
    }

    internal fun photonCount(frame: HdrMergeFrame): Float {
        val apertureArea = Math.PI.toFloat() * sq(0.5f * frame.focalLength / frame.aperture)
        val seconds = frame.exposureTimeNanos * 1e-9f
        return 100f * apertureArea * seconds / frame.sensitivityIso
    }

    // ---- sampling ----

    /**
     * Legacy 2px-quantized nearest warp (kept for [mergeExact] parity and old tests).
     * Preserves Bayer colour by construction; prefer [sampleSmooth] for production.
     */
    internal fun sampleCfaSafe(cfa: UnpackedRawCfa, flow: HdrFlowField?, x: Int, y: Int): Float {
        if (flow == null) return cfa.values[y * cfa.width + x]
        val (dx, dy) = flow.displacement(x, y)
        require(dx.isFinite() && dy.isFinite()) { "Invalid FlowNet displacement" }
        val sx = (x.toFloat() + 2f * kotlin.math.round(dx / 2f))
            .coerceIn((x and 1).toFloat(), (cfa.width - 2 + (x and 1)).toFloat()).toInt()
        val sy = (y.toFloat() + 2f * kotlin.math.round(dy / 2f))
            .coerceIn((y and 1).toFloat(), (cfa.height - 2 + (y and 1)).toFloat()).toInt()
        return cfa.values[sy * cfa.width + sx]
    }

    /**
     * CFA-safe smooth warp: same-colour bilinear. Only lattice sites carrying this
     * pixel's own Bayer colour contribute, so R/G/B never mix while sub-pixel flow
     * stays smooth (no 2px teeth).
     */
    internal fun sampleSmooth(cfa: UnpackedRawCfa, flow: HdrFlowField?, x: Int, y: Int): Float {
        if (flow == null) return cfa.values[y * cfa.width + x]
        val tmp = FloatArray(2)
        val scratch = FloatArray(2)
        sampleFlowInto(flow, x, y, tmp, scratch)
        return sampleSmoothAt(cfa, tmp[0], tmp[1], x, y)
    }

    /** Hot-loop warp tap: caller supplies thread-local [tmp]/[scratch]. */
    internal fun sampleSmoothFast(
        cfa: UnpackedRawCfa, flow: HdrFlowField?, x: Int, y: Int,
        tmp: FloatArray, scratch: FloatArray
    ): Float {
        if (flow == null) return cfa.values[y * cfa.width + x]
        sampleFlowInto(flow, x, y, tmp, scratch)
        return sampleSmoothAt(cfa, tmp[0], tmp[1], x, y)
    }

    /**
     * Allocation-free warp tap: displacement lands in [tmp] (and [scratch] for
     * combined fields) with zero per-pixel objects on production flow types.
     */
    internal fun sampleFlowInto(
        flow: HdrFlowField, x: Int, y: Int, tmp: FloatArray, scratch: FloatArray
    ) {
        when (flow) {
            is TranslationFlow -> {
                tmp[0] = flow.dx
                tmp[1] = flow.dy
            }
            is CombinedFlow -> {
                sampleFlowInto(flow.first, x, y, tmp, scratch)
                sampleFlowInto(flow.second, x, y, scratch, tmp)
                tmp[0] += scratch[0]
                tmp[1] += scratch[1]
            }
            else -> {
                val fast = flow as? FastFlow
                if (fast != null) {
                    fast.sampleInto(x, y, tmp)
                } else {
                    val (dx, dy) = flow.displacement(x, y)
                    require(dx.isFinite() && dy.isFinite()) { "Invalid flow displacement" }
                    tmp[0] = dx
                    tmp[1] = dy
                }
            }
        }
    }
    /** Same-colour bilinear tap at an explicit displacement. */
    internal fun sampleSmoothAt(cfa: UnpackedRawCfa, dx: Float, dy: Float, x: Int, y: Int): Float {
        require(dx.isFinite() && dy.isFinite()) { "Invalid flow displacement" }
        val w = cfa.width
        val h = cfa.height
        val px = (x + dx).coerceIn(0f, w - 1f)
        val py = (y + dy).coerceIn(0f, h - 1f)
        val pxParity = x and 1
        val pyParity = y and 1
        var x0 = px.toInt()
        if ((x0 and 1) != pxParity) x0 -= 1
        var y0 = py.toInt()
        if ((y0 and 1) != pyParity) y0 -= 1
        x0 = x0.coerceIn(pxParity, w - 2 + pxParity)
        y0 = y0.coerceIn(pyParity, h - 2 + pyParity)
        val x1 = min(x0 + 2, w - 1 - ((w - 1 - pxParity) and 1))
        val y1 = min(y0 + 2, h - 1 - ((h - 1 - pyParity) and 1))
        val fx = ((px - x0) / 2f).coerceIn(0f, 1f)
        val fy = ((py - y0) / 2f).coerceIn(0f, 1f)
        val a = cfa.values[y0 * w + x0]
        val b = cfa.values[y0 * w + x1]
        val c = cfa.values[y1 * w + x0]
        val d = cfa.values[y1 * w + x1]
        return (a * (1f - fx) + b * fx) * (1f - fy) + (c * (1f - fx) + d * fx) * fy
    }

    internal fun sampleBlockSmooth(
        blockMax: FloatArray, blockMin: FloatArray, cw: Int, ch: Int, x: Int, y: Int
    ): Pair<Float, Float> {
        val mm = FloatArray(2)
        sampleBlockSmoothInto(blockMax, blockMin, cw, ch, x, y, mm)
        return mm[0] to mm[1]
    }

    /** Allocation-free mask tap for the hot accumulate loop. */
    internal fun sampleBlockSmoothInto(
        blockMax: FloatArray, blockMin: FloatArray, cw: Int, ch: Int,
        x: Int, y: Int, out: FloatArray
    ) {
        val gx = (x / 2f - 0.5f).coerceIn(0f, (cw - 1).toFloat())
        val gy = (y / 2f - 0.5f).coerceIn(0f, (ch - 1).toFloat())
        out[0] = sampleGrid(blockMax, cw, ch, gx, gy)
        out[1] = sampleGrid(blockMin, cw, ch, gx, gy)
    }

    private fun sampleGrid(g: FloatArray, cw: Int, ch: Int, gx: Float, gy: Float): Float {
        val x0 = gx.toInt().coerceIn(0, cw - 1)
        val y0 = gy.toInt().coerceIn(0, ch - 1)
        val x1 = min(x0 + 1, cw - 1)
        val y1 = min(y0 + 1, ch - 1)
        val fx = (gx - x0).coerceIn(0f, 1f)
        val fy = (gy - y0).coerceIn(0f, 1f)
        val a = g[y0 * cw + x0]
        val b = g[y0 * cw + x1]
        val c = g[y1 * cw + x0]
        val d = g[y1 * cw + x1]
        return (a * (1f - fx) + b * fx) * (1f - fy) + (c * (1f - fx) + d * fx) * fy
    }

    internal fun envelope(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            1f - sq(abs(x / 0.5f - 1f))
        } else {
            val t = (1f - x) / 0.5f
            3f * t * t - 2f * t * t * t
        }
    }

    /**
     * Static row-strip fan-out over the shared pool (max 8 threads). Strips write
     * disjoint rows, so output is bit-deterministic regardless of thread count.
     */
    private fun parallelRows(height: Int, block: (y0: Int, y1: Int) -> Unit) =
        HdrPools.runStriped(height, 8, block)

    private fun checkFrames(frames: List<HdrMergeFrame>, referenceIndex: Int) {        require(frames.size >= 2) { "HDR merge requires at least two exposures" }
        require(referenceIndex in frames.indices)
        val reference = frames[referenceIndex].cfa
        reference.requireAmazeCompatible()
        require(frames.all {
            it.cfa.width == reference.width && it.cfa.height == reference.height &&
                it.cfa.pattern == reference.pattern &&
                it.cfa.sensorCropLeft == reference.sensorCropLeft &&
                it.cfa.sensorCropTop == reference.sensorCropTop
        }) { "HDR frames must have identical dimensions, crop, and CFA phase" }
        // Normalized-domain guard: inputs must be sensor black/white
        // normalized (roughly [0,1] with small negative noise overshoot),
        // never raw digital numbers. Catches callers feeding un-normalized
        // CFA (e.g. double black subtraction or missing white scaling),
        // which would silently corrupt calibration/photon weighting.
        // Geometry uses frames[referenceIndex] (middle exposure by default
        // in RawCameraController); accumulation stays input-ordered like
        // darktable, so the first frame still wins clipped ties.
        for ((k, frame) in frames.withIndex()) {
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

    private fun sq(x: Float) = x * x
}
