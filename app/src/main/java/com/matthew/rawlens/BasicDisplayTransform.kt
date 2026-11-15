// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.pow

/** Basic RAW display response: sRGB, brightness gamma 1.1, 75% cubic contrast.
 * Behavior reference: RapidRAW 89ed2db5, shader.wgsl display_transform.
 * The GPU implementations live in agx_srgb8.glsl and VfGpuImport.
 */
internal object BasicDisplayTransform {
    /** C1 rational shoulder: identity below 0.6, asymptote at display white.
     * Strength zero is the original RapidRAW Basic response. */
    fun compressHighlight(linear: Float, strength: Float): Float {
        if (linear <= 0.6f || strength <= 0f) return linear
        val t = linear - 0.6f
        val compressed = 0.6f + t / (1f + t / 0.4f)
        return if (strength >= 1f) compressed else linear + strength * (compressed - linear)
    }
    fun encode(linear: Float, shoulder: Float = 0f): Float {
        return encodeCompressed(compressHighlight(linear, shoulder))
    }
    fun encodeRgb(linear: FloatArray, shoulder: Float): FloatArray {
        val peak = maxOf(linear[0], linear[1], linear[2])
        val scale = if (peak > 0.6f) compressHighlight(peak, shoulder) / peak else 1f
        return FloatArray(3) { encodeCompressed(linear[it] * scale) }
    }
    private fun encodeCompressed(linear: Float): Float {
        val x = AgxDisplayTransform.srgbOetf(linear.coerceIn(0f, 1f)).pow(1f / 1.1f)
        return 0.25f * x + 0.75f * x * x * (3f - 2f * x)
    }
    fun decode(encoded: Float): Float = if (encoded <= 0.04045f) encoded / 12.92f
        else ((encoded + 0.055f) / 1.055f).pow(2.4f)
}
