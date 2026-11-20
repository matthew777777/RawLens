// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Viewfinder JPEG-preview tone LUT.
 *
 * The VF used to run the whole still tone pipeline on every display pixel: tone balance, then
 * AgX, Sigmoid, ACES 2.0, Basic or OpenDRT, then the gamut step and the OETF. That über-shader
 * used 63 work registers and filled the uniform file on Mali, and ran about 2.7k instructions
 * on Adreno. Now the same pipeline is baked once per settings change, using the stills CPU twin
 * ([AgxDisplayTransform]), into an N^3 LUT addressed by a per-channel log2 shaper of scene
 * ACEScg (after preview exposure), addressed by log2 intensity (r+g+b) and chromaticity (r/sum, b/sum).
 * The per-pixel cost is the same for every mapper: one log2, one divide and 2 bilinear fetches. Because the bake uses the stills CPU twin, the preview matches the saved
 * JPEG's tone by construction.
 *
 * Tone curves are smooth along log intensity and hue/purity processing is smooth in chromaticity,
 * which a per-channel RGB grid is not (the display matrix mixes channels with large negative weights).
 * Atlas layout: width SIZE_C*SIZE_I, height SIZE_C; texel (i*SIZE_C + x, y) holds sign-extended
 * sRGB/P3 *encoded* RGB before the final clip.
 */
internal object VfToneLut {
    /** Log-intensity slices (manual mix axis) and chromaticity grid (hardware bilinear). */
    const val SIZE_I = 48
    const val SIZE_C = 48
    /** Shaper range in log2 of r+g+b (scene ACEScg); x + 2^LO maps LO..HI to 0..1, 0 -> 0. */
    const val LOG_LO = -13f
    const val LOG_HI = 7f
    private val EPS = Math.pow(2.0, LOG_LO.toDouble()).toFloat()
    private val LN2 = ln(2.0)

    fun shaper(x: Float): Float =
        ((ln((max(x, 0f) + EPS).toDouble()) / LN2).toFloat() - LOG_LO) / (LOG_HI - LOG_LO)

    fun inverseShaper(s: Float): Float =
        (Math.pow(2.0, (LOG_LO + s * (LOG_HI - LOG_LO)).toDouble()) - EPS).toFloat()

    /** LUT coordinates in 0..1: intensity shaper, r/sum, b/sum. */
    fun coords(acescg: FloatArray): FloatArray {
        val r = max(acescg[0], 0f); val g = max(acescg[1], 0f); val b = max(acescg[2], 0f)
        val sum = r + g + b
        if (sum <= 1e-12f) return floatArrayOf(0f, 1f / 3f, 1f / 3f)
        return floatArrayOf(shaper(sum), r / sum, b / sum)
    }

    /** Grid node -> scene ACEScg (chromaticities outside the triangle are projected onto it). */
    fun node(si: Float, x: Float, y: Float): FloatArray {
        val sum = inverseShaper(si)
        var cx = x; var cy = y
        if (cx + cy > 1f) { val k = cx + cy; cx /= k; cy /= k }
        return floatArrayOf(cx * sum, (1f - cx - cy) * sum, cy * sum)
    }

    /** Encoded display RGB for one scene ACEScg value: exactly the stills SDR path before dither. */
    fun encode(acescg: FloatArray, settings: JpegOutputSettings): FloatArray {
        val r = settings.resolvedForPlatform()
        val linear = if (r.displayP3) AgxDisplayTransform.acescgToOutputLinearDisplayP3(acescg, r)
            else AgxDisplayTransform.acescgToOutputLinearSrgb(acescg, r)
        val clipped = AgxDisplayTransform.finalGamutClip(FloatArray(3) { if (linear[it].isFinite()) linear[it] else 0f })
        return FloatArray(3) { AgxDisplayTransform.srgbOetf(clipped[it]) }
    }

    /**
     * Value stored in the LUT: the same pipeline *before* the final 0..1 clip, as a sign-extended
     * OETF. The clip kink then happens after interpolation (lookup clamps), so out-of-gamut
     * colours next to the gamut boundary are not smeared. Needs a signed (RGBA16F) atlas.
     */
    fun bakeValue(acescg: FloatArray, settings: JpegOutputSettings): FloatArray =
        bakeValue(acescg, AgxDisplayTransform.Prepared(settings))

    fun bakeValue(acescg: FloatArray, p: AgxDisplayTransform.Prepared): FloatArray {
        if (p.settings.toneMapper == PhotoToneMapper.BASIC)
            return BasicDisplayTransform.encodeRgbUnclipped(p.basicOutput(acescg), p.settings.highlightShoulder)
        val linear = p.outputLinear(acescg)
        return FloatArray(3) { if (linear[it].isFinite()) BasicDisplayTransform.signedOetf(linear[it].coerceIn(-4f, 64f)) else 0f }
    }

    /** RGBA float atlas: width SIZE_C*SIZE_I (slice i, column x), height SIZE_C (row y). */
    fun bake(settings: JpegOutputSettings, sizeI: Int = SIZE_I, sizeC: Int = SIZE_C, parallel: Boolean = false): FloatArray {
        val r = AgxDisplayTransform.Prepared(settings)
        val out = FloatArray(sizeI * sizeC * sizeC * 4)
        // Slices write disjoint texels and every stage is pure, so slices can run concurrently.
        val slice = java.util.function.IntConsumer { i ->
            for (y in 0 until sizeC) for (x in 0 until sizeC) {
                val e = bakeValue(node(i / (sizeI - 1f), x / (sizeC - 1f), y / (sizeC - 1f)), r)
                val o = (y * sizeC * sizeI + i * sizeC + x) * 4
                out[o] = e[0]; out[o + 1] = e[1]; out[o + 2] = e[2]; out[o + 3] = 1f
            }
        }
        val range = java.util.stream.IntStream.range(0, sizeI)
        (if (parallel) range.parallel() else range).forEach(slice)
        return out
    }

    /** CPU twin of [glsl]'s vfToneLut (trilinear), for parity tests. */
    fun lookup(lut: FloatArray, acescg: FloatArray, sizeI: Int = SIZE_I, sizeC: Int = SIZE_C): FloatArray {
        val c = coords(acescg)
        val pi = c[0].coerceIn(0f, 1f) * (sizeI - 1); val px = c[1].coerceIn(0f, 1f) * (sizeC - 1); val py = c[2].coerceIn(0f, 1f) * (sizeC - 1)
        val i0 = min(floor(pi).toInt(), sizeI - 2); val x0 = min(floor(px).toInt(), sizeC - 2); val y0 = min(floor(py).toInt(), sizeC - 2)
        val fi = pi - i0; val fx = px - x0; val fy = py - y0
        val out = FloatArray(3)
        for (ch in 0..2) {
            var acc = 0f
            for (k in 0..7) {
                val di = k and 1; val dx = (k shr 1) and 1; val dy = (k shr 2) and 1
                val w = (if (di == 1) fi else 1 - fi) * (if (dx == 1) fx else 1 - fx) * (if (dy == 1) fy else 1 - fy)
                acc += w * lut[((y0 + dy) * sizeC * sizeI + (i0 + di) * sizeC + x0 + dx) * 4 + ch]
            }
            out[ch] = acc.coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * GLSL (ESSL 1.00 and 3.00) lookup. Requires `uniform VFHP sampler2D u_toneLut;` and
     * linear filtering, and a `VF_TEX` macro (texture2D or texture). One shaper per channel, two hardware-bilinear fetches and one mix.
     */
    val glsl: String = """
uniform VFHP sampler2D u_toneLut;
vec3 vfToneLut(vec3 aces) {
    const float NI = ${SIZE_I}.0;
    const float NC = ${SIZE_C}.0;
    vec3 a = max(aces, vec3(0.0));
    float sum = a.r + a.g + a.b;
    vec2 ch = sum > 1e-12 ? vec2(a.r, a.b) / sum : vec2(1.0 / 3.0);
    float si = clamp((log2(sum + ${EPS}) - (${LOG_LO})) * ${1f / (LOG_HI - LOG_LO)}, 0.0, 1.0) * (NI - 1.0);
    float i0 = min(floor(si), NI - 2.0);
    float fi = si - i0;
    vec2 uv = clamp(ch, 0.0, 1.0) * (NC - 1.0) + 0.5;
    vec3 c0 = VF_TEX(u_toneLut, vec2((uv.x + i0 * NC) / (NC * NI), uv.y / NC)).rgb;
    vec3 c1 = VF_TEX(u_toneLut, vec2((uv.x + (i0 + 1.0) * NC) / (NC * NI), uv.y / NC)).rgb;
    return clamp(mix(c0, c1, fi), 0.0, 1.0);
}
""".trimIndent()
}
