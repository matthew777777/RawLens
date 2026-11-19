// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Pointwise camera adjustments before the reference display mapper; RAW is unchanged.
 * Highlight recovery uses RapidRAW 1.7's flat-field capped stop shift, not its local base.
 * One RGB scale preserves chromaticity. Near-black protection avoids a milky noise floor.
 */
internal object CameraToneBalance {
    const val DEFAULT_SHADOW_LIFT = .2f
    const val KEY_SHADOW_LIFT = "jpeg_shadow_lift"
    const val KEY_HIGHLIGHT_RECOVERY = "jpeg_highlight_recovery"

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun gain(peak: Float, shadowLift: Float = DEFAULT_SHADOW_LIFT, highlightRecovery: Float = 0f): Float {
        val x = peak.coerceAtLeast(0f)
        val toe = .06f / (.06f + x)
        val shadow = if (x >= .6f) 1f else 1f + 3f * shadowLift.coerceIn(0f, 1f) *
            toe * toe * (1f - smooth(.18f, .6f, x)) * smooth(.001f, .008f, x)
        if (highlightRecovery <= 0f || x <= .125f) return shadow
        val distance = (ln(x.toDouble()) / ln(2.0) + 3.0).coerceAtLeast(0.0)
        val depth = distance * distance / (distance + 1.0)
        val shift = -highlightRecovery.coerceIn(0f, 1f) * (1.0 - exp(-1.6 * depth))
        return shadow * 2.0.pow(shift).toFloat()
    }

    fun map(rgb: FloatArray, settings: JpegOutputSettings = JpegOutputSettings()): FloatArray {
        val scale = gain(maxOf(rgb[0], rgb[1], rgb[2]), settings.shadowLift, settings.highlightRecovery)
        return FloatArray(3) { rgb[it] * scale }
    }

    val glsl = """
uniform vec2 u_camera_tone;
vec3 camera_tone_balance(vec3 rgb) {
    float peak = max(0.0, max(rgb.r, max(rgb.g, rgb.b)));
    float toe = 0.06 / (0.06 + peak);
    float shadow = peak >= 0.6 ? 1.0 : 1.0 + 3.0 * clamp(u_camera_tone.x,0.0,1.0) * toe * toe *
        (1.0 - smoothstep(0.18,0.6,peak)) * smoothstep(0.001,0.008,peak);
    float highlight = 1.0;
    if (u_camera_tone.y > 0.0 && peak > 0.125) {
        float distance = max(log2(peak) + 3.0,0.0);
        float depth = distance * distance / (distance + 1.0);
        highlight = exp2(-clamp(u_camera_tone.y,0.0,1.0) * (1.0 - exp(-1.6 * depth)));
    }
    return rgb * (shadow * highlight);
}
""".trimIndent()
}
