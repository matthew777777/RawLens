// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign

/** ACES 2.0, 100 nit SDR output, baked using OpenColorIO (see tools/aces2).
 * Signed log AP1 LUT: -0.255 .. 16777.215, 65 cubed, encoded RGB8 nodes.
 * This is a LUT approximation of the complete perceptual output transform.
 */
internal object Aces2DisplayTransform {
    const val SIZE = 65
    private val srgb by lazy { load("srgb") }
    private val p3 by lazy { load("display_p3") }
    private fun load(name: String): ByteArray =
        GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/aces2/$name.rgb.gz"))).use {
            it.readBytes().also { bytes -> check(bytes.size == SIZE * SIZE * SIZE * 3) }
        }

    fun nodes(displayP3: Boolean): ByteArray = if (displayP3) p3 else srgb

    private fun coordinate(value: Float): Float {
        val shaped = sign(value) * (ln(1.0 + abs(value) / .001) / ln(2.0))
        return ((shaped.toFloat() + 8f) * 2f).coerceIn(0f, 64f)
    }

    fun map(acescg: FloatArray, displayP3: Boolean = false): FloatArray {
        val data = nodes(displayP3)
        val pos = FloatArray(3) { coordinate(acescg[it]) }
        val low = IntArray(3) { floor(pos[it]).toInt().coerceAtMost(63) }
        val fraction = FloatArray(3) { pos[it] - low[it] }
        return FloatArray(3) { channel ->
            var encoded = 0f
            for (b in 0..1) for (g in 0..1) for (r in 0..1) {
                val index = (((low[2] + b) * SIZE + low[1] + g) * SIZE + low[0] + r) * 3 + channel
                val weight = (if (r == 0) 1f - fraction[0] else fraction[0]) *
                    (if (g == 0) 1f - fraction[1] else fraction[1]) *
                    (if (b == 0) 1f - fraction[2] else fraction[2])
                encoded += (data[index].toInt() and 255) / 255f * weight
            }
            if (encoded <= .04045f) encoded / 12.92f else ((encoded + .055f) / 1.055f).pow(2.4f)
        }
    }

    val glsl = """
uniform highp sampler2D u_aces2;
vec3 aces2_slice(vec3 p, float blue, float target) {
    vec2 tile = vec2(mod(blue, 9.0), floor(blue / 9.0));
    vec2 uv = (tile * 65.0 + p.rg + vec2(0.5) + vec2(0.0, target * 520.0)) / vec2(585.0,1040.0);
    #if __VERSION__ >= 300
    return texture(u_aces2, uv).rgb;
    #else
    return texture2D(u_aces2, uv).rgb;
    #endif
}
vec3 aces2_output(vec3 ap1, int displayP3) {
    vec3 p = clamp((sign(ap1) * log2(vec3(1.0) + abs(ap1) / 0.001) + 8.0) * 2.0, 0.0, 64.0);
    float blue = min(floor(p.b),63.0);
    vec3 encoded = mix(aces2_slice(p,blue,float(displayP3)), aces2_slice(p,blue+1.0,float(displayP3)),p.b-blue);
    return mix(encoded / 12.92, pow((encoded + 0.055) / 1.055,vec3(2.4)),step(vec3(0.04045),encoded));
}
""".trimIndent()
}
