// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.pow

/** Basic RAW display response: sRGB, brightness gamma 1.1, 75% cubic contrast.
 * Behavior reference: RapidRAW 89ed2db5, shader.wgsl display_transform.
 * The GPU implementations live in agx_srgb8.glsl and VfGpuImport.
 */
internal object BasicDisplayTransform {
    fun encode(linear: Float): Float {
        val x = AgxDisplayTransform.srgbOetf(linear.coerceIn(0f, 1f)).pow(1f / 1.1f)
        return 0.25f * x + 0.75f * x * x * (3f - 2f * x)
    }
    fun decode(encoded: Float): Float = if (encoded <= 0.04045f) encoded / 12.92f
        else ((encoded + 0.055f) / 1.055f).pow(2.4f)
}
