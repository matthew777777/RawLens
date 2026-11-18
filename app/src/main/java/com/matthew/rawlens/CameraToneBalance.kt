// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Camera rendering before the reference display transforms. Does not alter RAW or exposure.
 * One RGB scale preserves chromaticity. The toe has no black pedestal and at most two
 * stops of lift; highlights at/above 0.6 are exactly unchanged. No spatial filtering.
 */
internal object CameraToneBalance {
    fun gain(peak: Float): Float {
        val x = peak.coerceAtLeast(0f)
        if (x >= .6f) return 1f
        val t = ((x - .18f) / .42f).coerceIn(0f, 1f)
        val fade = 1f - t * t * (3f - 2f * t)
        val toe = .06f / (.06f + x)
        return 1f + 3f * toe * toe * fade
    }

    fun map(rgb: FloatArray): FloatArray {
        val scale = gain(maxOf(rgb[0], rgb[1], rgb[2]))
        return FloatArray(3) { rgb[it] * scale }
    }

    val glsl = """
vec3 camera_tone_balance(vec3 rgb) {
    float peak = max(0.0, max(rgb.r, max(rgb.g, rgb.b)));
    if (peak >= 0.6) return rgb;
    float t = clamp((peak - 0.18) / 0.42, 0.0, 1.0);
    float fade = 1.0 - t * t * (3.0 - 2.0 * t);
    float toe = 0.06 / (0.06 + peak);
    return rgb * (1.0 + 3.0 * toe * toe * fade);
}
""".trimIndent()
}
