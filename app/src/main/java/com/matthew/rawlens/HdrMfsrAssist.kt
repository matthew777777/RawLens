// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max

/**
 * MFSR-assisted HDR merge support (HDR+-style hard reject).
 *
 * The darktable radiance accumulator has no notion of misalignment: every warped
 * sample receives its full envelope weight, and
 * [HdrRawMerge.suppressMotionDisagreement] only soft-blends toward the reference
 * afterwards. Around high-contrast edges (signs, lamps) the FlowNet field is smooth
 * across a true motion discontinuity, so warped moving samples come from the wrong
 * side of the edge, still get weight, and the soft blend leaves a fringe
 * ("teeth") plus blur.
 *
 * This assist reuses the MFSR machinery to turn that soft fallback into a hard
 * per-quad reject:
 * - [RawSrAlignmentField.asHdrFlow] converts tile flow (quad pixels, nearest tile,
 *   discontinuities preserved) into an [HdrFlowField] (full RAW pixels).
 * - [validateFlow] checks any flow (FlowNet dense or tile) in the
 *   exposure-compensated quad-gray domain and returns 0/1 robustness with a 5x5
 *   local minimum, so a misaligned edge quad contributes nothing and the reference
 *   stays sharp.
 * - [alignWithMfsr] is the fallback when FlowNet yields no field at all.
 */
object HdrMfsrAssist {
    private const val SATURATION_MARGIN = 3000f / 65535f

    data class AssistResult(
        val flow: HdrFlowField,
        val robustness: RawSrRobustness.FrameRobustness
    )

    /** Nearest-tile tile flow expressed as a dense full-RAW-pixel flow. No blending across tiles. */
    fun RawSrAlignmentField.asHdrFlow(): HdrFlowField {
        val field = this
        return HdrFlowField { x, y ->
            val tile = field.flowAt(x * 0.5f, y * 0.5f)
            packHdrDisplacement(tile.dx * 2f, tile.dy * 2f)
        }
    }

    /** Exposure value in the same units as HdrFlowNetAligner's proxy matching. */
    fun exposureValue(frame: HdrMergeFrame): Double =
        frame.exposureTimeNanos.toDouble() * frame.sensitivityIso /
            (frame.aperture.toDouble() * frame.aperture.toDouble())

    /** Quad gray with the frame scaled into the reference exposure; clamped to [0, 1]. */
    fun compensatedGray(cfa: UnpackedRawCfa, scale: Float): RawSrGrayImage {
        val base = RawSrAlignment.bayerQuadGray(cfa)
        if (scale == 1f) return base
        val out = FloatArray(base.values.size) { (base.values[it] * scale).coerceIn(0f, 1f) }
        return RawSrGrayImage(base.width, base.height, out)
    }

    /**
     * Per-quad 0/1 robustness for [flow] in the exposure-compensated gray domain.
     * Mirrors [HdrRawMerge.suppressMotionDisagreement] tolerances
     * (0.02 absolute + 15% relative) so the two stages agree on what counts as motion.
     * Saturated/deep-black reference quads cannot be judged and are accepted; the
     * per-pixel saturation envelope remains authoritative there. A 5x5 local minimum
     * (HDR+ Alg. 9 style) erodes isolated accepts around edges.
     *
     * When [tileField] is supplied, tiles it marks unreliable force rejection, so the
     * MFSR fallback path honors the Hessian/residual/consistency gates.
     */
    fun validateFlow(
        reference: HdrMergeFrame,
        moving: HdrMergeFrame,
        flow: HdrFlowField,
        tileField: RawSrAlignmentField? = null
    ): RawSrRobustness.FrameRobustness {
        val refCfa = reference.cfa
        val movCfa = moving.cfa
        require(refCfa.width == movCfa.width && refCfa.height == movCfa.height) {
            "HDR assist frames must have identical dimensions"
        }
        require(refCfa.width % 2 == 0 && refCfa.height % 2 == 0) {
            "HDR assist requires complete Bayer quads"
        }
        if (tileField != null) {
            require(tileField.imageWidth == refCfa.width / 2 && tileField.imageHeight == refCfa.height / 2) {
                "Tile field grid must match the quad grid"
            }
        }
        val scale = (exposureValue(reference) / exposureValue(moving)).toFloat()
        require(scale.isFinite() && scale > 0f) { "Invalid HDR exposure ratio" }
        val refGray = compensatedGray(refCfa, 1f)
        val movGray = compensatedGray(movCfa, scale)
        val width = refGray.width
        val height = refGray.height
        val raw = FloatArray(width * height)
        val flags = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val o = y * width + x
            if (tileField != null && !tileField.flowAt(x.toFloat(), y.toFloat()).reliable) {
                raw[o] = 0f
                flags[o] = RawSrRobustness.FLAG_FLOW_UNRELIABLE
                continue
            }
            val ref = refGray[x, y]
            // Clipped or deep-black reference quads permit no radiometric comparison;
            // accept and let the saturation envelope decide per pixel.
            if (!ref.isFinite() || ref >= 1f - SATURATION_MARGIN || ref < 0.02f) {
                raw[o] = 1f
                continue
            }
            val displacement = flow.displacement(x * 2, y * 2)
            val dx = hdrDisplacementX(displacement)
            val dy = hdrDisplacementY(displacement)
            if (!dx.isFinite() || !dy.isFinite()) {
                raw[o] = 0f
                flags[o] = RawSrRobustness.FLAG_INVALID_FLOW
                continue
            }
            val sx = x + dx * 0.5f
            val sy = y + dy * 0.5f
            if (sx < 0f || sy < 0f || sx >= width || sy >= height) {
                raw[o] = 0f
                flags[o] = RawSrRobustness.FLAG_OUT_OF_BOUNDS
                continue
            }
            val warped = RawSrAlignment.bilinearOrNull(movGray, sx, sy)
            if (warped == null) {
                raw[o] = 0f
                flags[o] = RawSrRobustness.FLAG_OUT_OF_BOUNDS
                continue
            }
            if (warped >= 1f - SATURATION_MARGIN) {
                raw[o] = 0f
                flags[o] = RawSrRobustness.FLAG_SATURATED
                continue
            }
            val tolerance = 0.02f + 0.15f * max(ref, 0f)
            if (abs(ref - warped) <= tolerance) {
                raw[o] = 1f
            } else {
                raw[o] = 0f
                flags[o] = RawSrRobustness.FLAG_PHOTO_CONFLICT
            }
        }
        // Local minimum over a 5x5 clamp window; flags stay own-quad.
        val r = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var minimum = Float.POSITIVE_INFINITY
            for (i in -2..2) for (j in -2..2)
                minimum = minOf(minimum, raw[((y + i).coerceIn(0, height - 1)) * width + (x + j).coerceIn(0, width - 1)])
            r[y * width + x] = minimum
        }
        return RawSrRobustness.FrameRobustness(width, height, r, flags)
    }

    /**
     * Full MFSR fallback: tile-align the exposure-compensated grays, then validate.
     * Never returns null; total failure degrades to all-rejected robustness, which the
     * merge turns into reference-dominated output instead of aborting the bracket.
     */
    fun alignWithMfsr(
        reference: HdrMergeFrame,
        moving: HdrMergeFrame,
        config: RawSrAlignmentConfig = RawSrAlignmentConfig()
    ): AssistResult {
        require(reference.cfa.width == moving.cfa.width && reference.cfa.height == moving.cfa.height) {
            "HDR assist frames must have identical dimensions"
        }
        val scale = (exposureValue(reference) / exposureValue(moving)).toFloat()
        require(scale.isFinite() && scale > 0f) { "Invalid HDR exposure ratio" }
        val field = RawSrAlignment.align(
            compensatedGray(reference.cfa, 1f),
            compensatedGray(moving.cfa, scale),
            config
        )
        val flow = field.asHdrFlow()
        return AssistResult(flow, validateFlow(reference, moving, flow, field))
    }
}
