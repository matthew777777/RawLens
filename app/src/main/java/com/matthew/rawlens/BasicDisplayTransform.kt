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

    fun decode(encoded: Float): Float = if (encoded <= 0.04045f) encoded / 12.92f
        else ((encoded + 0.055f) / 1.055f).pow(2.4f)
}
