// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Pure logic for the zero-copy GPU viewfinder path. No Android dependencies so the
 * eligibility rules, quad mapping and shader assembly stay unit-testable.
 *
 * GPU path: `Image.getHardwareBuffer()` → EGLImage import (see [VfEglImport]) →
 * `R16UI` texture sampled by an ESSL 3.00 fragment shader that performs the same
 * per-quad Bayer fetch + normalize as [VfCpuNeon.copy], then shares the exact
 * Reinhard/AgX tail with the CPU path (WYSIWYG parity by construction).
 */
internal object VfGpuImport {
    /** Consecutive GPU failures before the session sticks to the NEON fallback. */
    const val MAX_CONSECUTIVE_FAILURES = 3
    /** First Vulkan device-recovery attempt after the session latch. */
    const val VULKAN_RECOVER_INITIAL_DELAY_MS = 5000L
    /** Recovery backoff cap: a dead GPU costs one cheap reinit attempt per 30 s. */
    const val VULKAN_RECOVER_MAX_DELAY_MS = 30000L
    /** Preview-EV grid long edge in VF texels: at most 32×32 sampled quads. */
    const val PREVIEW_GRID = 32
    /** Preview-EV scratch capacity: 32×32 quads × 4 sites. */
    const val MAX_PREVIEW_SAMPLES = PREVIEW_GRID * PREVIEW_GRID * 4

    data class Eligibility(val eligible: Boolean, val reason: String)

    /**
     * Vulkan recovery backoff: double the delay per failed reinit, capped so a
     * permanently dead GPU is re-probed every 30 s. The viewfinder resets to
     * [VULKAN_RECOVER_INITIAL_DELAY_MS] on the next rendered Vulkan frame, so a
     * recovered device never inherits a stale backoff.
     */
    fun nextVulkanRecoverDelayMs(currentDelayMs: Long): Long {
        require(currentDelayMs > 0) { "Recovery delay must stay positive" }
        return (currentDelayMs * 2).coerceAtMost(VULKAN_RECOVER_MAX_DELAY_MS)
    }

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
     * Quad-relative row (0/1) of the canonical Gr site: the lens-shading G-even
     * channel covers greens on even sensor rows, so the shader's G-even/G-odd
     * swap must test (quadRow + this) parity. RGGB/GRBG carry Gr on row 0,
     * GBRG/BGGR on row 1 (Gb always takes the other row).
     */
    fun lensGreenRow(channels: IntArray): Int {
        require(channels.size == 4)
        return channels[1] / 2
    }

    /** Per-frame preview smoothing time constant: 95% settled in ~540 ms. */
    const val PREVIEW_SMOOTH_TAU_MS = 180f

    /**
     * One exponential-approach step of [current] toward [target] over [dtMs].
     * Alpha derives from wall time so ramps hold constant speed across frame
     * rates; snaps exactly once within 1/256 so values settle, never asymptote.
     */
    fun smoothToward(current: Float, target: Float, dtMs: Long, tauMs: Float = PREVIEW_SMOOTH_TAU_MS): Float {
        if (current == target || tauMs <= 0f) return target
        if (dtMs <= 0L) return current
        val alpha = (1.0 - exp(-dtMs.toDouble() / tauMs.toDouble())).toFloat().coerceIn(0f, 1f)
        val next = current + (target - current) * alpha
        return if (kotlin.math.abs(target - next) < 1f / 256f) target else next
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
        levels: FloatArray, white: Float, samples: FloatArray,
        lens: LensShadingModel? = null,
        pattern: BayerPattern? = null,
        sensorOriginX: Int = 0,
        sensorOriginY: Int = 0,
        tuning: AdaptiveExposureTuning = AdaptiveExposureTuning()
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
        // Mirror the save path (AdaptiveDevelopmentExposure.analyzeRaw): when a lens
        // map is present and not already applied, the same per-site gain multiplies
        // the normalized sample. Null/identity keeps the legacy behavior bit-exact.
        val applyLens = lens != null && pattern != null && !lens.alreadyApplied
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
                        var n = (code - levels[dy * 2 + dx]) * invRange[dy * 2 + dx]
                        if (applyLens) {
                            val sx = sensorOriginX + quadLeft + dx
                            val sy = sensorOriginY + quadTop + dy
                            n *= lens!!.gainAt(sx, sy, pattern!!.colorAt(sx, sy))
                        }
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
                        var n = (code - levels[dy * 2 + dx]) * invRange[dy * 2 + dx]
                        if (applyLens) {
                            val sx = sensorOriginX + quadLeft + dx
                            val sy = sensorOriginY + quadTop + dy
                            n *= lens!!.gainAt(sx, sy, pattern!!.colorAt(sx, sy))
                        }
                        if (n.isFinite() && n > AdaptiveDevelopmentExposure.SHADOW_FLOOR) {
                            samples[count++] = n
                        }
                    }
                    x += stride
                }
                y += stride
            }
        }
        return AdaptiveDevelopmentExposure.analyzeSamples(samples, count, tuning).correctionEv
    }

    // Shared AgX helpers + tonemap tail. Both fragment shaders are composed from
    // these pieces so the CPU and GPU paths can never drift apart. The tail takes the
    // already-fetched lens-corrected normalized superpixel `b` (UN-gained camera RGB;
    // the RAW branch applies `gains`, the JPEG branch folds WB into `u_camToAces`)
    // and the framebuffer out-variable name. AgX matrices are pinned to
    // assets/shaders/display/agx_srgb8.glsl. GLSL mat3() fills column-major, so each
    // literal triple is one COLUMN (a row-ordered literal transposes the matrix:
    // the outset then tints neutrals blue).
    private const val TONEMAP_HELPERS =
        "mat3 agxInset() { return mat3(0.856627153315983, 0.137318972929847, 0.111898212999950, 0.095121240538159, 0.761241990602591, 0.076799418603190, 0.048251606145858, 0.101439036467562, 0.811302368396859); }" +
            "mat3 agxOutset() { return mat3(1.127100581814437, -0.141329763498438, -0.141329763498438, -0.110606643096603, 1.157823702216272, -0.110606643096603, -0.016493938717835, -0.016493938717834, 1.251936406595040); }" +
            "mat3 acesCgToRec2020() { return mat3(1.025877552449, -0.002232441770, -0.005013950857, -0.020020686312, 1.004568990995, -0.025282661381, -0.005775003430, -0.002349522759, 1.030082295555); }" +
            "mat3 rec2020ToSrgb() { return mat3(1.6604910021, -0.1245504745, -0.0181507634, -0.5876411388, 1.1328998971, -0.1005788980, -0.0728498633, -0.0083494226, 1.1187296614); }" +
            "mat3 rec2020ToDisplayP3() { return mat3(1.343578252570, -0.065297452837, 0.002821787226, -0.282179670449, 1.075787915784, -0.019598494598, -0.061398582051, -0.010490463088, 1.016776707234); }" +
            "vec3 agxSigmoid(vec3 x) { vec3 x2 = x * x; vec3 x4 = x2 * x2;" +
            " return -17.86 * x4 * x2 * x + 78.01 * x4 * x2 - 126.7 * x4 * x + 92.06 * x4" +
            " - 28.72 * x2 * x + 4.361 * x2 - 0.1718 * x + vec3(0.002857); }" +
            "float gamutScale(float d, float a) { if (abs(d) < 0.000001) return 1.0;" +
            " return d > 0.0 ? (1.0 - a) / d : -a / d; }" +
            "vec3 srgbOetf(vec3 l) { vec3 lo = 12.92 * l;" +
            " vec3 hi = 1.055 * pow(l, vec3(1.0 / 2.4)) - vec3(0.055);" +
            " return mix(hi, lo, vec3(lessThanEqual(l, vec3(0.0031308)))); }" +
            // Interleaved-gradient-noise dither: float-only so both ESSL versions share
            // it (ESSL 1.00 has no uint/bitwise ops for the save path's hash01).
            // Pinned to VFHP (highp where available): the hash needs >16-bit
            // intermediates at 1080p (mediump would coarsen it into banding).
            "VFHP float ign(VFHP vec2 p, VFHP float ch) { return fract(52.9829189 *" +
            " fract(dot(p + ch * 16.0 + 5.588238, vec2(0.06711056, 0.00583715)))); }"

    private const val AGX_UNIFORMS =
        "uniform int u_jpeg;" +
            "uniform float u_agxContrast; uniform float u_agxSaturation; uniform float u_agxPurity;" +
            "uniform float u_agxHue; uniform float u_agxShadowEv; uniform float u_agxHighlightEv;" +
            "uniform float u_agxGamut; uniform float u_exposureEv; uniform float u_highlightShoulder;" +
            "uniform mat3 u_camToAces; uniform vec3 u_cameraWhite; uniform int u_displayP3;"

    /** RAW VF brightness lift: the linear Reinhard preview meters ~1 EV dark, so the
     * shared tail applies +1 EV (×2.0) in RAW mode only. JPEG/AgX keeps its own
     * adaptive preview EV and is untouched. */
    const val RAW_VF_EV_GAIN = 2.0f

    private const val TONEMAP_TAIL_TEMPLATE =
        " if (u_jpeg == 0) { vec4 bg = b * gains;" +
            "  vec3 rgb = max(color * vec3(bg.r, (bg.g + bg.b) * 0.5, bg.a), vec3(0.0));" +
            "  rgb *= 2.0;" +
            "  rgb = rgb / (vec3(1.0) + rgb);" +
            "  @OUT@ = vec4(pow(rgb, vec3(1.0 / 2.2)), 1.0); return; }" +
            // JPEG: the save-path sequence on the superpixel (highlight
            // neutralize -> preview EV -> calibrated camera-to-ACEScg -> AgX ->
            // sRGB/P3 -> gamut -> clip -> OETF -> one-LSB dither). Only the
            // superpixel demosaic stands in for AMaZE detail/denoise.
            " vec3 cam = max(vec3(b.r, (b.g + b.b) * 0.5, b.a), vec3(0.0));" +
            " vec3 whiteB = smoothstep(vec3(0.70), vec3(0.99), cam);" +
            " float whiteBlend = max(whiteB.r, max(whiteB.g, whiteB.b));" +
            " cam = mix(cam, u_cameraWhite, whiteBlend);" +
            " cam *= exp2(u_exposureEv);" +
            " vec3 scene = max(acesCgToRec2020() * (u_camToAces * cam), vec3(0.0));" +
            " for (int c = 0; c < 3; ++c) { if (scene[c] > 0.9 && u_highlightShoulder > 0.0) {" +
            "  float t = (scene[c] - 0.9) / 0.8;" +
            "  float comp = 0.9 + 0.8 * (1.0 - exp(-t));" +
            "  scene[c] = mix(scene[c], comp, clamp(u_highlightShoulder, 0.0, 1.0)); } }" +
            " vec3 v = agxInset() * scene;" +
            " v = log2(max(v, vec3(1e-10)));" +
            " float evRange = u_agxShadowEv + u_agxHighlightEv;" +
            " float pivot = u_agxShadowEv / evRange;" +
            " v = clamp((v - vec3(-2.473931188 - u_agxShadowEv)) / vec3(evRange), vec3(0.0), vec3(1.0));" +
            " v = clamp(vec3(pivot) + (v - vec3(pivot)) * u_agxContrast, vec3(0.0), vec3(1.0));" +
            " v = agxSigmoid(v);" +
            " v = mix(v, agxOutset() * v, u_agxPurity);" +
            " v = pow(max(v, vec3(0.0)), vec3(2.2));" +
            " vec3 lumaW = vec3(0.2627, 0.6780, 0.0593);" +
            " float mappedLuma = dot(v, lumaW); float sceneLuma = dot(scene, lumaW);" +
            " float ratio = (sceneLuma > 1e-9) ? mappedLuma / max(sceneLuma, 1e-9) : 1.0;" +
            " v = mix(v, scene * ratio, u_agxHue);" +
            " float g = dot(v, lumaW); v = vec3(g) + u_agxSaturation * (v - vec3(g));" +
            " mat3 outMat = rec2020ToSrgb(); if (u_displayP3 != 0) { outMat = rec2020ToDisplayP3(); }" +
            " v = outMat * v;" +
            " float anchor = clamp(dot(v, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);" +
            " vec3 delta = v - vec3(anchor);" +
            " float sc = min(gamutScale(delta.r, anchor), min(gamutScale(delta.g, anchor), gamutScale(delta.b, anchor)));" +
            " sc = mix(1.0, clamp(sc, 0.0, 1.0), u_agxGamut);" +
            " v = vec3(anchor) + sc * delta;" +
            " vec3 enc = srgbOetf(clamp(v, 0.0, 1.0));" +
            " VFHP float d0 = ign(gl_FragCoord.xy, 0.0);" +
            " VFHP float d1 = ign(gl_FragCoord.xy, 1.0);" +
            " VFHP float d2 = ign(gl_FragCoord.xy, 2.0);" +
            " @OUT@ = vec4(enc.r + (d0 - 0.5) / 255.0, enc.g + (d1 - 0.5) / 255.0," +
            " enc.b + (d2 - 0.5) / 255.0, 1.0); }"

    // Zero-copy hardware-accelerated lens-shading (vignetting) correction.
    // The HAL's STATISTICS_LENS_SHADING_CORRECTION_MAP is packed once per map
    // change into a single tiny RGBA16F texture (R, G-even, G-odd, B in Camera2
    // order, Mali-friendly: 8 bytes/texel vs 16 for RGBA32F, one hardware-filtered
    // fetch per display texel). The GPU fixed-function sampler does the bilinear
    // interpolation (LINEAR + CLAMP_TO_EDGE); the shader only remaps the sensor
    // quad to the lens-map UV and swaps the green parity on odd rows. One fetch
    // per display texel at the quad origin: the map is ~17x13 over the full
    // sensor, so intra-quad variation is negligible. u_applyLens == 0 keeps the
    // identity path (map missing or HAL already applied shading). Quad geometry
    // (u_quadBase/u_frameSize/u_step) is in sensor coordinates; u_lensActive is
    // SENSOR_INFO_ACTIVE_ARRAY_SIZE.
    private const val LENS_UNIFORMS_COMMON =
        "uniform sampler2D u_lens; uniform ivec2 u_lensSize; uniform ivec4 u_lensActive;" +
            "uniform int u_applyLens; uniform ivec2 u_quadBase; uniform ivec2 u_frameSize;" +
            "uniform int u_step; uniform int u_lensGreenRow;"

    // ESSL 1.00 variant: single hardware-filtered texture2D fetch. No texelFetch
    // and no integer bitwise ops, so parity uses mod(). ESSL 1.00 also has no
    // integer max/min overloads, so the active-array clamp stays in float.
    private const val LENS_GAIN_ES1 =
        "vec4 vfLensGain(vec2 t) {" +
            " if (u_applyLens == 0) return vec4(1.0);" +
            " ivec2 ij = ivec2(floor(t * vec2(float(u_frameSize.x), float(u_frameSize.y))));" +
            " ivec2 q = u_quadBase + ij * u_step;" +
            " float aw = max(float(u_lensActive.z - u_lensActive.x - 1), 1.0);" +
            " float ah = max(float(u_lensActive.w - u_lensActive.y - 1), 1.0);" +
            " vec2 nuv = clamp(vec2((float(q.x - u_lensActive.x)) / aw," +
            " (float(q.y - u_lensActive.y)) / ah), 0.0, 1.0);" +
            " vec2 sz = vec2(float(u_lensSize.x), float(u_lensSize.y));" +
            " vec2 uv = (nuv * (sz - vec2(1.0)) + vec2(0.5)) / sz;" +
            " vec4 g = texture2D(u_lens, uv);" +
            " if (mod(float(q.y + u_lensGreenRow), 2.0) != 0.0) g = vec4(g.r, g.b, g.g, g.a);" +
            " return g; }"

    // ESSL 3.00 variant: single hardware-filtered texture() fetch. Integer
    // bitwise ops are available here (unavailable in ESSL 1.00). Float math
    // runs at default (mediump) precision: the ~17-cell map needs none
    // finer; quad coordinates stay highp int (mediump int is 16-bit).
    private const val LENS_GAIN_ES3 =
        "vec4 vfLensGain(vec2 t) {" +
            " if (u_applyLens == 0) return vec4(1.0);" +
            " highp ivec2 ij = ivec2(floor(t * vec2(u_frameSize)));" +
            " highp ivec2 q = u_quadBase + ij * u_step;" +
            " float aw = float(max(u_lensActive.z - u_lensActive.x - 1, 1));" +
            " float ah = float(max(u_lensActive.w - u_lensActive.y - 1, 1));" +
            " vec2 nuv = clamp(vec2(float(q.x - u_lensActive.x) / aw," +
            " float(q.y - u_lensActive.y) / ah), 0.0, 1.0);" +
            " vec2 sz = vec2(u_lensSize);" +
            " vec2 uv = (nuv * (sz - vec2(1.0)) + vec2(0.5)) / sz;" +
            " vec4 g = texture(u_lens, uv);" +
            " if (((q.y + u_lensGreenRow) & 1) != 0) g = vec4(g.r, g.b, g.g, g.a);" +
            " return g; }"

    // IEEE-754 float -> half-float bits for the RGBA16F lens pack. Branch-free
    // exponent rebias with NaN/Inf saturation; subnormals flush to zero (lens
    // gains are >= 1.0, so the preview path never exercises them).
    fun floatToHalfBits(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exp = ((bits ushr 23) and 0xff) - 112
        val mantissa = bits and 0x7fffff
        return when {
            exp >= 31 -> (sign or 0x7bff).toShort() // Inf/overflow -> max half
            exp <= 0 -> sign.toShort() // subnormal/underflow -> signed zero
            else -> (sign or (exp shl 10) or (mantissa ushr 13)).toShort()
        }
    }

    fun halfBitsToFloat(bits: Short): Float {
        val u = bits.toInt() and 0xffff
        val sign = (u and 0x8000) shl 16
        val exp = (u ushr 10) and 0x1f
        val mantissa = u and 0x3ff
        val fbits = when (exp) {
            0 -> sign // zero/subnormal -> signed zero (preview-adequate)
            31 -> sign or 0x7f800000 or (mantissa shl 13) // Inf/NaN
            else -> sign or ((exp + 112) shl 23) or (mantissa shl 13)
        }
        return Float.fromBits(fbits)
    }

    /** Pack Camera2-order lens gains (rows*cols*4 floats) into RGBA16F half bits. */
    fun packLensHalf(gains: FloatArray): ShortArray =
        ShortArray(gains.size) { floatToHalfBits(gains[it]) }

    /**
     * Identity key for a lens-shading map: size mixed with content hash. The HAL
     * map is static per session, so the VF uploads (and the camera thread
     * re-snapshots) only when this key changes — never per frame.
     */
    fun lensContentKey(cols: Int, rows: Int, gains: FloatArray): Long =
        (cols.toLong() shl 56) xor (rows.toLong() shl 48) xor
            (gains.contentHashCode().toLong() and 0xffff_ffffL)

    /**
     * Lens-map re-snapshot cadence. The HAL map is static per session, so once
     * a snapshot is cached the camera thread skips the gain-copy + content
     * hash (30 allocs/sec of GC churn) and re-verifies at 1 Hz. No cache or a
     * characteristics change (lens switch) always rechecks immediately, so a
     * late-appearing map is picked up on the next frame, never after a delay.
     */
    const val LENS_RECHECK_MS = 1000L

    fun shouldRecheckLensMap(
        nowMs: Long,
        lastMs: Long,
        sameCharacteristics: Boolean,
        hasCached: Boolean,
        intervalMs: Long = LENS_RECHECK_MS
    ): Boolean {
        if (!hasCached) return true
        if (!sameCharacteristics) return true
        return nowMs - lastMs >= intervalMs
    }

    /**
     * ESSL 1.00 fragment shader for the CPU-sampled RGBA path. Float math
     * runs mediump (fp16 on mobile): the 8-bit dithered output needs no
     * finer; only the dither hash keeps highp (VFHP) where available.
     */
    fun cpuFragmentShader(): String =
        "precision mediump float;\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n#define VFHP highp\n#else\n#define VFHP mediump\n#endif\n" +
            "varying vec2 tex; uniform sampler2D raw; uniform vec4 gains; uniform mat3 color;" +
            AGX_UNIFORMS +
            LENS_UNIFORMS_COMMON +
            TONEMAP_HELPERS +
            LENS_GAIN_ES1 +
            "void main() { vec4 b = texture2D(raw, tex); b *= vfLensGain(tex);" +
            TONEMAP_TAIL_TEMPLATE.replace("@OUT@", "gl_FragColor")

    /** ESSL 3.00 vertex shader for the GPU path (highp varyings for exact quad math). */
    fun gpuVertexShader(): String =
        "#version 300 es\n" +
            "in vec2 position; in vec2 uv; out highp vec2 tex;" +
            "void main(){ tex = uv; gl_Position = vec4(position, 0., 1.); }"

    /**
     * ESSL 3.00 fragment shader for the zero-copy path. Each display texel fetches its
     * Bayer quad from the imported `R16UI` texture with the same channel mapping and
     * Q6 fixed-point normalization as [VfCpuNeon.copy] (pure integer, exact tier
     * parity), then runs the shared tonemap tail in mediump (fp16 on mobile:
     * the 8-bit dithered output needs no finer).
     */
    fun gpuFragmentShader(): String =
        "#version 300 es\n" +
            "#define VFHP highp\n" +
            "precision mediump float; precision highp int; precision highp sampler2D;" +
            "uniform highp usampler2D u_bayer;" +
            "uniform ivec2 u_quadBase; uniform ivec2 u_frameSize; uniform int u_step;" +
            "uniform ivec4 u_chans; uniform highp ivec4 u_blackQ; uniform highp ivec4 u_denQ;" +
            "uniform mediump sampler2D u_lens; uniform ivec2 u_lensSize; uniform ivec4 u_lensActive;" +
            "uniform int u_applyLens; uniform int u_lensGreenRow;" +
            "uniform vec4 gains; uniform mat3 color;" +
            AGX_UNIFORMS +
            "in highp vec2 tex; out vec4 fragColor;" +
            TONEMAP_HELPERS +
            LENS_GAIN_ES3 +
            // Runtime-specialized unpack: black/white for the exact RAW bit depth
            // (10/12/16-bit sensor modes all present as unpacked 16-bit codes) are
            // baked into Q6 ints on the CPU ([VfLevels]), so the shader is a single
            // straight-line fetch + integer MAD with dynamic uniform indexing and
            // no per-channel branches. All intermediates fit highp int (32-bit).
            "highp int vfFetchByte(highp ivec2 q, int ch) {" +
            " highp ivec2 p = q + ivec2(ch & 1, (ch >> 1) & 1);" +
            " highp int code = int(texelFetch(u_bayer, p, 0).r);" +
            " highp int bq = u_blackQ[ch];" +
            " highp int dq = u_denQ[ch];" +
            " highp int num = code * 64 - bq;" +
            " if (num <= 0) return 0;" +
            " if (num >= dq) return 255;" +
            " return (num * 255 + dq / 2) / dq; }" +
            "void main() { highp ivec2 q = u_quadBase + ivec2(floor(tex * vec2(u_frameSize))) * u_step;" +
            " highp ivec4 n = ivec4(vfFetchByte(q, u_chans.x), vfFetchByte(q, u_chans.y)," +
            " vfFetchByte(q, u_chans.z), vfFetchByte(q, u_chans.w));" +
            " highp vec4 bf = vec4(n) / 255.0;" +
            " vec4 b = vec4(bf);" +
            " b *= vfLensGain(tex);" +
            TONEMAP_TAIL_TEMPLATE.replace("@OUT@", "fragColor")
}
