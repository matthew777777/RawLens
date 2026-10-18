// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// CPU reference for RawTherapee "Color propagation" highlight reconstruction
// (HLRecovery_inpaint, blend-estimation head). Op order mirrors upstream float
// arithmetic so JVM results are bit-identical to the C++ oracle except for
// sqrtf, which the JVM evaluates in double precision (<= 1 ulp, see below).
//
// Upstream: https://github.com/Beep6581/RawTherapee/blob/dev/rtengine/hilite_recon.cc
// (branch dev, fetched 2026-10-16; local verbatim copy for line parity:
// references/RawTherapee/hilite_recon.cc).
// Ported lines: thresholds hlite_recon.cc:340-404, blend estimation :979-1052,
// helpers SQR/LIM01/intp from rtengine/rt_math.h.
// Original copyright: (c) 2008-2011 Emil Martinec, (c) 2019 Ingo Weyrich, GPL-3.0-or-later.

package com.matthew.rawlens

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** RT highlight-reconstruction thresholds (hilite_recon.cc:390-404). */
data class RtHlThresholds(
    val factor: FloatArray,
    val thresh: FloatArray,
    val maxF: FloatArray,
    val whitePt: Float,
    val clipPt: Float,
    val medPt: Float,
    val blendPt: Float,
    val medFactor: FloatArray
)

/**
 * Line-by-line port of the HLRecovery_inpaint head (hilite_recon.cc:340-404).
 *
 * [chmax]/[clmax] are per-channel measured maximum and clipping levels in raw
 * code units. Returns thresholds RT derives before touching any pixel.
 */
object RtColorPropagation {
    private const val THRESH_PCT = 0.25f
    private const val MAX_PCT = 0.95f
    private const val EPSILON = 0.00001f
    private const val BLEND_THRESH = 1.0f

    // Trans/itrans matrices, hilite_recon.cc:325-334, values verbatim.
    private val TRANS = arrayOf(
        floatArrayOf(1f, 1f, 1f),
        floatArrayOf(1.7320508f, -1.7320508f, 0f),
        floatArrayOf(-1f, -1f, 2f)
    )
    private val ITRANS = arrayOf(
        floatArrayOf(1f, 0.8660254f, -0.5f),
        floatArrayOf(1f, -0.8660254f, -0.5f),
        floatArrayOf(1f, 0f, 1f)
    )

    fun computeThresholds(chmax: FloatArray, clmax: FloatArray): RtHlThresholds {
        require(chmax.size == 3 && clmax.size == 3)
        val factor = FloatArray(3) { c -> chmax[c] / clmax[c] }
        val minFactor = min(factor[0], min(factor[1], factor[2]))
        if (minFactor > 1f) {
            for (c in 0..2) factor[c] /= minFactor
            var maxPos = 0
            var maxValNew = 0f
            for (c in 0..2) {
                val v = chmax[c] / factor[c]
                if (v > maxValNew) {
                    maxValNew = v
                    maxPos = c
                }
            }
            val clipFactor = clmax[maxPos] / maxValNew
            if (clipFactor < MAX_PCT) {
                for (c in 0..2) factor[c] *= (MAX_PCT / clipFactor)
            }
        } else {
            factor[0] = 1f
            factor[1] = 1f
            factor[2] = 1f
        }
        val thresh = FloatArray(3) { c -> chmax[c] * THRESH_PCT / factor[c] }
        val maxF = FloatArray(3) { c -> chmax[c] * MAX_PCT / factor[c] }
        val whitePt = max(maxF[0], max(maxF[1], maxF[2]))
        val clipPt = min(maxF[0], min(maxF[1], maxF[2]))
        val medPt = maxF[0] + maxF[1] + maxF[2] - whitePt - clipPt
        val blendPt = BLEND_THRESH * clipPt
        val medFactor = FloatArray(3) { c -> max(1.0f, maxF[c] / medPt) / -blendPt }
        return RtHlThresholds(factor, thresh, maxF, whitePt, clipPt, medPt, blendPt, medFactor)
    }

    /**
     * Per-pixel "modified HLRecovery_blend" estimation (hilite_recon.cc:979-1052).
     *
     * Returns rgb_blend; channels at or below blendPt stay exactly 0 (RT leaves
     * the {}-initialized array untouched), matching upstream.
     */
    fun blendEstimate(pixel: FloatArray, t: RtHlThresholds): FloatArray {
        require(pixel.size == 3)
        val rgb = floatArrayOf(pixel[0], pixel[1], pixel[2])
        val rgbBlend = floatArrayOf(0f, 0f, 0f)
        val cam = Array(2) { FloatArray(3) }
        val lab = Array(2) { FloatArray(3) }
        val sum = FloatArray(2)
        for (c in 0..2) {
            cam[0][c] = rgb[c]
            cam[1][c] = min(cam[0][c], t.clipPt)
        }
        for (i2 in 0..1) {
            for (c in 0..2) {
                var acc = 0f
                for (j2 in 0..2) acc += TRANS[c][j2] * cam[i2][j2]
                lab[i2][c] = acc
            }
            var s = 0f
            for (c in 1..2) s += lab[i2][c] * lab[i2][c]
            sum[i2] = s
        }
        sum[0] = max(sum[0], EPSILON)
        val chratio = sqrt(sum[1] / sum[0])
        for (c in 1..2) lab[0][c] *= chratio
        for (c in 0..2) {
            var acc = 0f
            for (j2 in 0..2) acc += ITRANS[c][j2] * lab[0][j2]
            cam[0][c] = acc
        }
        for (c in 0..2) rgb[c] = cam[0][c] / 3f
        for (c in 0..2) {
            if (pixel[c] > t.blendPt) {
                val frac = (t.medFactor[c] * (pixel[c] - t.blendPt)).coerceIn(0f, 1f)
                rgbBlend[c] = frac * (rgb[c] - pixel[c]) + pixel[c]
            }
        }
        return rgbBlend
    }

    /** Lightness-correction ratio only, exposed so tests can bound sqrt drift. */
    fun lightRatio(pixel: FloatArray, t: RtHlThresholds): Float {
        require(pixel.size == 3)
        val cam = Array(2) { FloatArray(3) }
        for (c in 0..2) {
            cam[0][c] = pixel[c]
            cam[1][c] = min(pixel[c], t.clipPt)
        }
        val sum = FloatArray(2)
        for (i2 in 0..1) {
            var s = 0f
            for (c in 1..2) {
                var acc = 0f
                for (j2 in 0..2) acc += TRANS[c][j2] * cam[i2][j2]
                s += acc * acc
            }
            sum[i2] = s
        }
        sum[0] = max(sum[0], EPSILON)
        return sqrt(sum[1] / sum[0])
    }
}
