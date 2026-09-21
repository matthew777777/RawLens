// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure logic for the zero-copy GPU viewfinder path. No Android dependencies so the
 * eligibility rules, quad mapping and shader assembly stay unit-testable.
 *
 * GPU path: `Image.getHardwareBuffer()` → EGLImage import (see [VfEglImport]) →
 * `R16UI` texture sampled by an ESSL 3.00 fragment shader that performs the same
 * per-quad Bayer fetch + normalize as [RawPreviewSampler.copy], then shares the exact
 * WB/CCM + Reinhard/AgX-lite tail with the CPU path (WYSIWYG parity by construction).
 */
internal object VfGpuImport {
    /** Consecutive GPU failures before the session sticks to the CPU fallback. */
    const val MAX_CONSECUTIVE_FAILURES = 3
    /** Preview-EV grid long edge in VF texels: at most 32×32 sampled quads. */
    const val PREVIEW_GRID = 32
    /** Preview-EV scratch capacity: 32×32 quads × 4 sites. */
    const val MAX_PREVIEW_SAMPLES = PREVIEW_GRID * PREVIEW_GRID * 4

    data class Eligibility(val eligible: Boolean, val reason: String)

    /**
     * Zero-copy needs a packed 16-bit layout (1:1 texel mapping for `texelFetch`),
     * an ES3 context (integer samplers are ESSL 3.00 only) and the native EGL import
     * library. Anything else keeps the CPU sampler.
     */
    fun checkEligible(
        pixelStride: Int,
        rowStride: Int,
        width: Int,
        glEs3: Boolean,
        nativeAvailable: Boolean,
        gpuDisabledForSession: Boolean
    ): Eligibility = when {
        gpuDisabledForSession -> Eligibility(false, "session-disabled")
        !nativeAvailable -> Eligibility(false, "native-unavailable")
        !glEs3 -> Eligibility(false, "es2")
        pixelStride != 2 -> Eligibility(false, "pixel-stride=$pixelStride")
        rowStride != width * 2 -> Eligibility(false, "row-stride=$rowStride")
        else -> Eligibility(true, "ok")
    }

    /**
     * Quad-local (dx, dy) per canonical output position, mirroring the CPU sampler:
     * output channel `c` reads sensor site `channels[c]` at `(channel % 2, channel / 2)`.
     * Returns 8 ints: dx0, dy0, dx1, dy1, dx2, dy2, dx3, dy3.
     */
    fun quadOffsets(channels: IntArray): IntArray {
        require(channels.size == 4)
        return IntArray(8) { i ->
            val channel = channels[i / 2]
            if (i % 2 == 0) channel % 2 else channel / 2
        }
    }

    /**
     * Live adaptive-exposure estimate for the JPEG VF preview: the same
     * [AdaptiveDevelopmentExposure] percentile statistic the saved JPEG uses,
     * sampled on a coarse quad grid (≤32×32 quads × 4 sites) so a 2 Hz refresh
     * costs well under a millisecond on the camera thread. Returns NaN when the
     * plane cannot cover the sampled quads; the caller then keeps the last EV.
     */
    fun estimatePreviewCorrectionEv(
        source: ByteBuffer, rowStride: Int, pixelStride: Int,
        left: Int, top: Int, width: Int, height: Int, step: Int,
        levels: FloatArray, white: Float, samples: FloatArray
    ): Double {
        require(left % 2 == 0 && top % 2 == 0 && step >= 2 && step % 2 == 0)
        require(width > 0 && height > 0 && levels.size == 4)
        require(samples.size >= MAX_PREVIEW_SAMPLES)
        // Bounds-check the last visited sample up front (same convention as the
        // CPU sampler): a short plane yields NaN, never an over-read.
        val lastX = left + (width - 1) * step + 1
        val lastY = top + (height - 1) * step + 1
        if (lastX < 0 || lastY < 0) return Double.NaN
        val input = source.duplicate().order(ByteOrder.nativeOrder())
        val base = input.position()
        if (pixelStride == 2 && rowStride % 2 == 0) {
            val shortsPerRow = rowStride / 2
            if (lastY.toLong() * shortsPerRow + lastX + 1 > input.asShortBuffer().capacity()) {
                return Double.NaN
            }
        } else if (lastY.toLong() * rowStride + lastX.toLong() * pixelStride + 2 > input.limit() - base) {
            return Double.NaN
        }
        val stride = maxOf(1, (maxOf(width, height) + PREVIEW_GRID - 1) / PREVIEW_GRID)
        val invRange = FloatArray(4) { i -> 1f / (white - levels[i]).coerceAtLeast(1f) }
        var count = 0
        if (pixelStride == 2 && rowStride % 2 == 0) {
            val shorts = input.asShortBuffer()
            val shortsPerRow = rowStride / 2
            var y = 0
            while (y < height && count < samples.size) {
                var x = 0
                val quadTop = top + y * step
                while (x < width && count < samples.size) {
                    val quadLeft = left + x * step
                    for (dy in 0..1) for (dx in 0..1) {
                        val code = shorts.get((quadTop + dy) * shortsPerRow + quadLeft + dx).toInt() and 0xffff
                        // left/top/step are even, so sensor parity == quad-local parity.
                        val n = (code - levels[dy * 2 + dx]) * invRange[dy * 2 + dx]
                        if (n.isFinite() && n > AdaptiveDevelopmentExposure.SHADOW_FLOOR) {
                            samples[count++] = n
                        }
                    }
                    x += stride
                }
                y += stride
            }
        } else {
            var y = 0
            while (y < height && count < samples.size) {
                var x = 0
                val quadTop = top + y * step
                while (x < width && count < samples.size) {
                    val quadLeft = left + x * step
                    for (dy in 0..1) for (dx in 0..1) {
                        val code = input.getShort(base + (quadTop + dy) * rowStride + (quadLeft + dx) * pixelStride).toInt() and 0xffff
                        val n = (code - levels[dy * 2 + dx]) * invRange[dy * 2 + dx]
                        if (n.isFinite() && n > AdaptiveDevelopmentExposure.SHADOW_FLOOR) {
                            samples[count++] = n
                        }
                    }
                    x += stride
                }
                y += stride
            }
        }
        return AdaptiveDevelopmentExposure.analyzeSamples(samples, count).correctionEv
    }

    // Shared AgX-lite helpers + tonemap tail. Both fragment shaders are composed from
    // these pieces so the CPU and GPU paths can never drift apart. The tail takes the
    // already-fetched normalized superpixel `b` and the framebuffer out-variable name.
    // AgX matrices are pinned to assets/shaders/display/agx_srgb8.glsl. GLSL mat3()
    // fills column-major, so each literal triple is one COLUMN (a row-ordered
    // literal transposes the matrix: the outset then tints neutrals blue).
    private const val TONEMAP_HELPERS =
        "mat3 agxInset() { return mat3(0.856627153315983, 0.137318972929847, 0.111898212999950, 0.095121240538159, 0.761241990602591, 0.076799418603190, 0.048251606145858, 0.101439036467562, 0.811302368396859); }" +
            "mat3 agxOutset() { return mat3(1.127100581814437, -0.141329763498438, -0.141329763498438, -0.110606643096603, 1.157823702216272, -0.110606643096603, -0.016493938717835, -0.016493938717834, 1.251936406595040); }" +
            "mat3 rec2020ToSrgb() { return mat3(1.6604910021, -0.1245504745, -0.0181507634, -0.5876411388, 1.1328998971, -0.1005788980, -0.0728498633, -0.0083494226, 1.1187296614); }" +
            "vec3 agxSigmoid(vec3 x) { vec3 x2 = x * x; vec3 x4 = x2 * x2;" +
            " return -17.86 * x4 * x2 * x + 78.01 * x4 * x2 - 126.7 * x4 * x + 92.06 * x4" +
            " - 28.72 * x2 * x + 4.361 * x2 - 0.1718 * x + vec3(0.002857); }" +
            "float gamutScale(float d, float a) { if (abs(d) < 0.000001) return 1.0;" +
            " return d > 0.0 ? (1.0 - a) / d : -a / d; }" +
            "vec3 srgbOetf(vec3 l) { vec3 lo = 12.92 * l;" +
            " vec3 hi = 1.055 * pow(l, vec3(1.0 / 2.4)) - vec3(0.055);" +
            " return mix(hi, lo, vec3(lessThanEqual(l, vec3(0.0031308)))); }"

    private const val AGX_UNIFORMS =
        "uniform int u_jpeg;" +
            "uniform float u_agxContrast; uniform float u_agxSaturation; uniform float u_agxPurity;" +
            "uniform float u_agxHue; uniform float u_agxShadowEv; uniform float u_agxHighlightEv;" +
            "uniform float u_agxGamut; uniform float u_exposureEv;"

    private const val TONEMAP_TAIL_TEMPLATE =
        " vec3 rgb = max(color * vec3(b.r, (b.g + b.b) * 0.5, b.a), vec3(0.0));" +
            " if (u_jpeg == 0) { rgb = rgb / (vec3(1.0) + rgb);" +
            "  @OUT@ = vec4(pow(rgb, vec3(1.0 / 2.2)), 1.0); return; }" +
            " rgb *= exp2(u_exposureEv);" +
            " vec3 v = agxInset() * rgb;" +
            " v = log2(max(v, vec3(0.0001)));" +
            " float evRange = u_agxShadowEv + u_agxHighlightEv;" +
            " float pivot = u_agxShadowEv / evRange;" +
            " v = clamp((v - vec3(-2.473931188 - u_agxShadowEv)) / vec3(evRange), vec3(0.0), vec3(1.0));" +
            " v = clamp(vec3(pivot) + (v - vec3(pivot)) * u_agxContrast, vec3(0.0), vec3(1.0));" +
            " v = agxSigmoid(v);" +
            " v = mix(v, agxOutset() * v, u_agxPurity);" +
            " v = pow(max(v, vec3(0.0)), vec3(2.2));" +
            " vec3 lumaW = vec3(0.2627, 0.6780, 0.0593);" +
            " float mappedLuma = dot(v, lumaW); float sceneLuma = dot(rgb, lumaW);" +
            " float ratio = (sceneLuma > 0.000001) ? mappedLuma / max(sceneLuma, 0.000001) : 1.0;" +
            " v = mix(v, rgb * ratio, u_agxHue);" +
            " float g = dot(v, lumaW); v = vec3(g) + u_agxSaturation * (v - vec3(g));" +
            " v = rec2020ToSrgb() * v;" +
            " float anchor = clamp(dot(v, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);" +
            " vec3 delta = v - vec3(anchor);" +
            " float sc = min(gamutScale(delta.r, anchor), min(gamutScale(delta.g, anchor), gamutScale(delta.b, anchor)));" +
            " sc = mix(1.0, clamp(sc, 0.0, 1.0), u_agxGamut);" +
            " v = vec3(anchor) + sc * delta;" +
            " @OUT@ = vec4(srgbOetf(clamp(v, 0.0, 1.0)), 1.0); }"

    /** ESSL 1.00 fragment shader for the CPU-sampled RGBA path. */
    fun cpuFragmentShader(): String =
        "precision mediump float;" +
            "varying vec2 tex; uniform sampler2D raw; uniform vec4 gains; uniform mat3 color;" +
            AGX_UNIFORMS +
            TONEMAP_HELPERS +
            "void main() { vec4 b = texture2D(raw, tex) * gains;" +
            TONEMAP_TAIL_TEMPLATE.replace("@OUT@", "gl_FragColor")

    /** ESSL 3.00 vertex shader for the GPU path (highp varyings for exact quad math). */
    fun gpuVertexShader(): String =
        "#version 300 es\n" +
            "in vec2 position; in vec2 uv; out highp vec2 tex;" +
            "void main(){ tex = uv; gl_Position = vec4(position, 0., 1.); }"

    /**
     * ESSL 3.00 fragment shader for the zero-copy path. Each display texel fetches its
     * Bayer quad from the imported `R16UI` texture with the same channel mapping and
     * black/white normalization as [RawPreviewSampler.copy] (in float, without the
     * 8-bit quantization), then runs the shared tonemap tail. `highp` only for
     * unpack/normalize and quad coordinates; CCM/gamma/AgX-lite stay `mediump`.
     */
    fun gpuFragmentShader(): String =
        "#version 300 es\n" +
            "precision mediump float; precision highp int;" +
            "uniform highp usampler2D u_bayer;" +
            "uniform ivec2 u_quadBase; uniform ivec2 u_frameSize; uniform int u_step;" +
            "uniform ivec4 u_chans; uniform highp vec4 u_black; uniform highp vec4 u_invRange;" +
            "uniform vec4 gains; uniform mat3 color;" +
            AGX_UNIFORMS +
            "in highp vec2 tex; out vec4 fragColor;" +
            TONEMAP_HELPERS +
            "highp float vfFetch(highp ivec2 q, int ch) {" +
            " highp ivec2 p = q + ivec2(ch & 1, (ch >> 1) & 1);" +
            " highp float code = float(texelFetch(u_bayer, p, 0).r);" +
            " highp float blk = ch == 0 ? u_black.x : ch == 1 ? u_black.y : ch == 2 ? u_black.z : u_black.w;" +
            " highp float inv = ch == 0 ? u_invRange.x : ch == 1 ? u_invRange.y : ch == 2 ? u_invRange.z : u_invRange.w;" +
            " return clamp((code - blk) * inv, 0.0, 1.0); }" +
            "void main() { highp ivec2 q = u_quadBase + ivec2(floor(tex * vec2(u_frameSize))) * u_step;" +
            " highp vec4 b = vec4(vfFetch(q, u_chans.x), vfFetch(q, u_chans.y), vfFetch(q, u_chans.z), vfFetch(q, u_chans.w)) * gains;" +
            TONEMAP_TAIL_TEMPLATE.replace("@OUT@", "fragColor")
}
