// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.pow

/** Basic tone mapper: RapidRAW v1.7.0 (Furka engine) `display_transform` for RAW input,
 * src-tauri/src/shaders/shader.wgsl:
 *
 *     srgb = linear_to_srgb(clamp(c, 0, 1))
 *     srgb = pow(srgb, 1 / 1.1)                       // BRIGHTNESS_GAMMA
 *     out  = mix(srgb, smoothstep-cubic(srgb), 0.75)  // CONTRAST_MIX
 *
 * With every Furka tone slider at its default (exposure, contrast, highlights, shadows,
 * whites, blacks = 0) the rest of upstream's tone stage is an exact identity, so this is
 * the complete default RapidRAW look.
 *
 * RawLens adaptation: upstream has no shoulder and clips scene values above 1. The
 * optional [compressHighlight] runs on scene linear BEFORE the upstream curve, so
 * strength 0 is bit-exact RapidRAW. GPU copies: agx_srgb8.glsl / final_display.glsl
 * `basic_encoded` and VfGpuImport (viewfinder); keep all four identical.
 */
internal object BasicDisplayTransform {
    const val BRIGHTNESS_GAMMA = 1.1f
    const val CONTRAST_MIX = 0.75f
    private const val SHOULDER_KNEE = 0.6f
    private const val SHOULDER_RANGE = 0.4f

    /** C1 rational shoulder: identity below the knee, asymptote at display white. */
    fun compressHighlight(linear: Float, strength: Float): Float {
        if (linear <= SHOULDER_KNEE || strength <= 0f) return linear
        val t = linear - SHOULDER_KNEE
        val compressed = SHOULDER_KNEE + t / (1f + t / SHOULDER_RANGE)
        return if (strength >= 1f) compressed else linear + strength * (compressed - linear)
    }

    /** Upstream display_transform (RAW branch) of one channel. */
    fun rapidRaw(linear: Float): Float {
        val srgb = AgxDisplayTransform.srgbOetf(linear.coerceIn(0f, 1f)).pow(1f / BRIGHTNESS_GAMMA)
        val contrast = srgb * srgb * (3f - 2f * srgb)
        return srgb + CONTRAST_MIX * (contrast - srgb)
    }

    fun encode(linear: Float, shoulder: Float = 0f): Float = rapidRaw(compressHighlight(linear, shoulder))

    /** Ratio-preserving shoulder on the RGB peak, then the per-channel upstream curve. */
    fun encodeRgb(linear: FloatArray, shoulder: Float): FloatArray {
        val peak = maxOf(linear[0], linear[1], linear[2])
        val scale = if (peak > SHOULDER_KNEE) compressHighlight(peak, shoulder) / peak else 1f
        return FloatArray(3) { rapidRaw(linear[it] * scale) }
    }

    /** sRGB EOTF; how RawLens recovers display-linear values (gain map, HDR) from Basic codes. */
    fun decode(encoded: Float): Float = if (encoded <= 0.04045f) encoded / 12.92f
        else ((encoded + 0.055f) / 1.055f).pow(2.4f)
}
