// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Pointwise camera adjustments before the reference display mapper; RAW is unchanged.
 * Highlight recovery is RapidRAW 1.7's highlight_shift (shader.wgsl, slider = -100 * recovery):
 * a log-luma pull pivoted at 2^-3 that holds the white point (white_level = log2 headroom,
 * non-AgX ceiling -0.1 EV) and neutralises pulled low-saturation near-whites. Pointwise:
 * RapidRAW's local detail base is not ported. Near-black protection avoids a milky noise floor.
 */
internal object CameraToneBalance {
    const val DEFAULT_SHADOW_LIFT = 0f
    const val KEY_SHADOW_LIFT = "jpeg_shadow_lift"
    const val KEY_HIGHLIGHT_RECOVERY = "jpeg_highlight_recovery"

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun gain(peak: Float, shadowLift: Float = DEFAULT_SHADOW_LIFT, highlightRecovery: Float = 0f, whiteLevel: Float = 0f): Float {
        val x = peak.coerceAtLeast(0f)
        val toe = .06f / (.06f + x)
        val shadow = if (x >= .6f) 1f else 1f + 3f * shadowLift.coerceIn(0f, 1f) *
            toe * toe * (1f - smooth(.18f, .6f, x)) * smooth(.001f, .008f, x)
        return shadow * highlightGain(x * shadow, highlightRecovery, whiteLevel)
    }

    private fun ramp(x: Double, knee: Double): Double { val u = maxOf(x, 0.0); return u * u / (u + knee) }
    private fun lift(amount: Double, depth: Double): Double {
        val rate = 1.5; val cap = if (amount < 0) 1.6 else 1.3
        return amount * cap * (1.0 - exp(-rate * depth / cap))
    }

    /** RapidRAW 1.7 highlight_shift as a linear gain for log2 level [luma]. */
    fun highlightGain(luma: Float, highlightRecovery: Float, whiteLevel: Float = 0f): Float {
        if (highlightRecovery <= 0f || luma <= 1e-6f) return 1f
        val amount = (1.2 * highlightRecovery.coerceIn(0f, 1f)).coerceAtMost(1.1)
        val level = ln(luma.toDouble()) / ln(2.0)
        var shift = lift(amount, ramp(level + 3.0, 1.0))
        if (shift > 0.0) {
            val atWhite = lift(amount, ramp(whiteLevel + 3.0, 1.0))
            val pinned = ((whiteLevel + 0.1) / maxOf(atWhite, 1e-6)).coerceIn(0.0, 1.0)
            val dull = (-0.1 - whiteLevel).coerceIn(0.0, 1.0)
            val t = ((level - (whiteLevel - 1.5)) / 1.5).coerceIn(0.0, 1.0)
            shift *= 1.0 + (maxOf(pinned, dull) - 1.0) * t * t * (3 - 2 * t)
        }
        return 2.0.pow(-shift).toFloat()
    }

    fun map(rgb: FloatArray, settings: JpegOutputSettings = JpegOutputSettings()): FloatArray {
        val scale = gain(maxOf(rgb[0], rgb[1], rgb[2]), settings.shadowLift, settings.highlightRecovery,
            (kotlin.math.ln(settings.highlightHeadroom.toDouble()) / kotlin.math.ln(2.0)).toFloat())
        return FloatArray(3) { rgb[it] * scale }
    }

    val glsl = ToneLook.glsl + "\n" + """
uniform vec2 u_camera_tone;
float rr_ramp(float x, float k) { float u = max(x, 0.0); return u * u / (u + k); }
float rr_lift(float a, float depth) { return a * 1.3 * (1.0 - exp(-1.5 * depth / 1.3)); }
vec3 camera_tone_balance(vec3 rgb) {
    float peak = max(0.0, max(rgb.r, max(rgb.g, rgb.b)));
    float toe = 0.06 / (0.06 + peak);
    float shadow = peak >= 0.6 ? 1.0 : 1.0 + 3.0 * clamp(u_camera_tone.x,0.0,1.0) * toe * toe *
        (1.0 - smoothstep(0.18,0.6,peak)) * smoothstep(0.001,0.008,peak);
    rgb *= shadow;
    float amount = min(1.2 * clamp(u_camera_tone.y, 0.0, 1.0), 1.1);
    float luma = dot(max(rgb, vec3(0.0)), vec3(0.2722287, 0.6740818, 0.0536895));
    if (amount <= 0.0 || luma <= 1e-6) return rgb;
    // RapidRAW 1.7 highlight_shift + near-white neutralisation (pointwise).
    float white = u_tone_look[1].w;
    float level = log2(luma);
    float shift = rr_lift(amount, rr_ramp(level + 3.0, 1.0));
    float atWhite = rr_lift(amount, rr_ramp(white + 3.0, 1.0));
    float hold = max(clamp((white + 0.1) / max(atWhite, 1e-6), 0.0, 1.0), clamp(-0.1 - white, 0.0, 1.0));
    shift *= mix(1.0, hold, smoothstep(white - 1.5, white, level));
    float gain = exp2(-shift);
    float top = max(rgb.r, max(rgb.g, rgb.b));
    float sat = (top - min(rgb.r, min(rgb.g, rgb.b))) / max(top, 1e-6);
    float neutral = smoothstep(-1.0, 1.0, level) * min(shift, 1.0) * 0.6 * (1.0 - smoothstep(0.25, 0.6, sat));
    return mix(rgb * gain, vec3(luma * gain), neutral);
}
""".trimIndent()
}
