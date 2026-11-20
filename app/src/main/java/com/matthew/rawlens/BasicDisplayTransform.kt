// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Highlight compression adapted from Khronos PBR Neutral (2024 The Khronos Group, Inc.; Apache-2.0).
package com.matthew.rawlens

import kotlin.math.pow

/** Photographic adaptation of Khronos PBR Neutral's highlight compression.
 * No PBR Fresnel subtraction: photographic shadows and midtones remain linear.
 * Shared contract: display/agx_srgb8.glsl, VfGpuImport, Halide bgu_look.
 * See docs/rendering/basic-neutral-replacement.md for provenance and differences.
 */
internal object BasicDisplayTransform {
    fun encode(linear: Float, shoulder: Float = 1f): Float =
        encodeRgb(floatArrayOf(linear, linear, linear), shoulder)[0]

    fun encodeRgb(linear: FloatArray, shoulder: Float): FloatArray {
        val color = FloatArray(3) { linear[it].coerceAtLeast(0f) }
        val peak = maxOf(color[0], color[1], color[2])
        // Even at strength zero retain a safe shoulder; never blend HDR with clipped SDR.
        val knee = 0.76f - 0.16f * shoulder.coerceIn(0f, 1f)
        if (peak > knee) {
            val d = 1f - knee
            val mapped = 1f - d * (d / (peak + d - knee))
            val scale = mapped / peak
            val white = 1f - 1f / (1f + 0.15f * (peak - mapped))
            for (i in 0..2) {
                val c = color[i] * scale
                color[i] = c + white * (mapped - c)
            }
        }
        return FloatArray(3) { AgxDisplayTransform.srgbOetf(color[it].coerceIn(0f, 1f)) }
    }

    /**
     * [encodeRgb] without the final clip, sign-extended (VF LUT bake): below the knee a negative
     * channel stays negative (the 0..1 clamp after interpolation re-creates the clip); above the
     * knee the clamped channel is already inside 0..1. Clamping the result equals [encodeRgb].
     */
    fun encodeRgbUnclipped(linear: FloatArray, shoulder: Float): FloatArray {
        val color = linear.copyOf()
        val peak = maxOf(color[0], color[1], color[2], 0f)
        val knee = 0.76f - 0.16f * shoulder.coerceIn(0f, 1f)
        if (peak > knee) {
            val d = 1f - knee
            val mapped = 1f - d * (d / (peak + d - knee))
            val scale = mapped / peak
            val white = 1f - 1f / (1f + 0.15f * (peak - mapped))
            for (i in 0..2) { val c = kotlin.math.max(color[i], 0f) * scale; color[i] = c + white * (mapped - c) }
        }
        return FloatArray(3) { signedOetf(color[it]) }
    }

    /** sRGB OETF extended as an odd function and past 1 (monotonic, so a 0..1 clamp after equals a linear clamp). */
    fun signedOetf(x: Float): Float {
        val a = kotlin.math.abs(x)
        val e = if (a <= 0.0031308f) 12.92f * a else 1.055f * a.pow(1f / 2.4f) - 0.055f
        return if (x < 0f) -e else e
    }

    fun decode(encoded: Float): Float = if (encoded <= 0.04045f) encoded / 12.92f
        else ((encoded + 0.055f) / 1.055f).pow(2.4f)
}
