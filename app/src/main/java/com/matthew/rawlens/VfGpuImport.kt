// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens
// Highlight compression adapted from Khronos PBR Neutral (2024 The Khronos Group, Inc.; Apache-2.0).

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
        weights: FloatArray? = null,
        lens: LensShadingModel? = null,
        pattern: BayerPattern? = null,
        sensorOriginX: Int = 0,
        sensorOriginY: Int = 0,
        tuning: AdaptiveExposureTuning = AdaptiveExposureTuning(),
        /** One sensor frame (always, for the VF): mirror the still develop's singleFrame rules. */
        singleFrame: Boolean = true
    ): Double {
        require(left % 2 == 0 && top % 2 == 0 && step >= 2 && step % 2 == 0)
        require(width > 0 && height > 0 && levels.size == 4)
        require(samples.size >= MAX_PREVIEW_SAMPLES)
        require(weights == null || weights.size >= MAX_PREVIEW_SAMPLES)
        // Center weighting needs the parallel weights array; without it the
        // estimate stays uniform even when the tuning arms it (documented —
        // callers wanting the subject vote pass their scratch buffer).
        val bounded = tuning.bounded()
        val center = bounded.centerWeight
        val focusX = bounded.focusX ?: 0.5f
        val focusY = bounded.focusY ?: 0.5f
        val useWeights = weights != null && center > 0f
        // Quads tile left..left+width*step, so the vote centers on the
        // visited span (same frame the save path meters).
        val spanW = width * step
        val spanH = height * step
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
                            n *= lens.gainAt(sx, sy, pattern.colorAt(sx, sy))
                        }
                        if (n.isFinite() && n > AdaptiveDevelopmentExposure.SHADOW_FLOOR) {
                            samples[count] = n
                            if (useWeights) {
                                weights[count] = AdaptiveDevelopmentExposure.spatialWeight(
                                    quadLeft + dx - left, quadTop + dy - top,
                                    spanW, spanH, center, focusX, focusY
                                )
                            }
                            count++
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
                            n *= lens.gainAt(sx, sy, pattern.colorAt(sx, sy))
                        }
                        if (n.isFinite() && n > AdaptiveDevelopmentExposure.SHADOW_FLOOR) {
                            samples[count] = n
                            if (useWeights) {
                                weights[count] = AdaptiveDevelopmentExposure.spatialWeight(
                                    quadLeft + dx - left, quadTop + dy - top,
                                    spanW, spanH, center, focusX, focusY
                                )
                            }
                            count++
                        }
                    }
                    x += stride
                }
                y += stride
            }
        }
        // Same single-frame rules as the still develop (analyzeRaw singleFrame = true):
        // clipped photosites leave the highlight guards, and the frame's own blind
        // noise fit gates the night/HDR decisions. Without them the VF metered darker
        // whenever anything clipped and brighter than the photo at night.
        val noise = if (singleFrame) {
            val shorts = if (pixelStride == 2 && rowStride % 2 == 0) input.asShortBuffer() else null
            val shortsPerRow = rowStride / 2
            // The visited span (same crop the save path's analyzeRaw meters); reads past
            // the plane return NaN, which the sampler skips.
            RawSceneNoise.sampleNormalized(spanW, spanH, AdaptiveDevelopmentExposure.CLIP_LEVEL) { cx, cy ->
                val px = left + cx
                val py = top + cy
                val index = (py and 1) * 2 + (px and 1)
                val code = if (shorts != null) {
                    val at = py.toLong() * shortsPerRow + px
                    if (at >= shorts.capacity()) return@sampleNormalized Float.NaN
                    shorts.get(at.toInt()).toInt() and 0xffff
                } else {
                    val at = base + py.toLong() * rowStride + px.toLong() * pixelStride
                    if (at + 2 > input.limit()) return@sampleNormalized Float.NaN
                    input.getShort(at.toInt()).toInt() and 0xffff
                }
                var n = (code - levels[index]) * invRange[index]
                if (applyLens) {
                    val sx = sensorOriginX + px
                    val sy = sensorOriginY + py
                    n *= lens.gainAt(sx, sy, pattern.colorAt(sx, sy))
                }
                n
            }?.let { RawSceneNoise.fit(listOf(it)) }
        } else null
        return AdaptiveDevelopmentExposure.analyzeSamples(
            samples, count, tuning, weights = if (useWeights) weights else null,
            excludeClipped = singleFrame, noise = noise
        ).correctionEv
    }

    // Shared helpers + tonemap tail. Both fragment shaders are composed from these
    // pieces so the CPU and GPU paths can never drift apart. The tail takes the
    // already-fetched lens-corrected normalized superpixel `b` (UN-gained camera RGB;
    // the RAW branch applies `gains`, the JPEG branch folds WB into `u_camToAces`)
    // and the framebuffer out-variable name. The JPEG tone pipeline itself lives in
    // the baked [VfToneLut] (stills CPU twin), not in per-pixel GLSL.
    private const val TONEMAP_HELPERS =
            "VFHP float ign(VFHP vec2 p, VFHP float ch) { return fract(52.9829189 *" +
            " fract(dot(p + ch * 16.0 + 5.588238, vec2(0.06711056, 0.00583715)))); }"

    // JPEG preview: everything after the camera matrix (tone balance, mapper, gamut, OETF) is
    // the baked [VfToneLut]; only per-frame values stay uniforms.
    private const val AGX_UNIFORMS =
        "uniform int u_jpeg; uniform float u_exposureEv;" +
            "uniform mat3 u_camToAces; uniform vec3 u_cameraWhite;"

    /** RAW VF brightness lift: the linear Reinhard preview meters ~1 EV dark, so the
     * shared tail applies +1 EV (×2.0) in RAW mode only. JPEG/AgX keeps its own
     * adaptive preview EV and is untouched. */
    const val RAW_VF_EV_GAIN = 2.0f

    private const val TONEMAP_TAIL_TEMPLATE =
        " VFHP vec3 enc;" +
            " if (u_jpeg == 0) { vec4 bg = b * gains;" +
            "  vec3 rgb = max(color * vec3(bg.r, (bg.g + bg.b) * 0.5, bg.a), vec3(0.0));" +
            "  rgb *= 2.0;" +
            "  rgb = rgb / (vec3(1.0) + rgb);" +
            "  enc = pow(rgb, vec3(1.0 / 2.2));" +
            " } else {" +
            // Preview the saved color pipeline after exposure and highlight neutralization.
            "  vec3 cam = max(vec3(b.r, (b.g + b.b) * 0.5, b.a), vec3(0.0));" +
            "  vec3 whiteB = smoothstep(vec3(0.70), vec3(0.99), cam);" +
            "  float whiteBlend = max(whiteB.r, max(whiteB.g, whiteB.b));" +
            "  cam = mix(cam, u_cameraWhite, whiteBlend);" +
            "  cam *= exp2(u_exposureEv);" +
            "  enc = vfToneLut(u_camToAces * cam);" +
            " }" +
            " enc = photoCube(enc);" +
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
        "uniform VFHP sampler2D u_lens; uniform VFHP ivec2 u_lensSize; uniform VFHP ivec4 u_lensActive;" +
            "uniform int u_applyLens; uniform VFHP ivec2 u_quadBase; uniform VFHP ivec2 u_frameSize;" +
            "uniform int u_step; uniform int u_lensGreenRow;"

    // ESSL 1.00 variant: single hardware-filtered texture2D fetch. No texelFetch
    // and no integer bitwise ops, so parity uses highp mod(). FP16 rounds
    // 2049 to 2048, selecting G-even for an odd row. ESSL 1.00 also has no
    // integer max/min overloads, so the active-array clamp stays in float.
    private const val LENS_GAIN_ES1 =
        "vec4 vfLensGain(VFHP vec2 t) {" +
            " if (u_applyLens == 0) return vec4(1.0);" +
            " VFHP ivec2 ij = ivec2(floor(t * vec2(float(u_frameSize.x), float(u_frameSize.y))));" +
            " VFHP ivec2 q = u_quadBase + ij * u_step;" +
            " VFHP float aw = max(float(u_lensActive.z - u_lensActive.x - 1), 1.0);" +
            " VFHP float ah = max(float(u_lensActive.w - u_lensActive.y - 1), 1.0);" +
            " VFHP vec2 nuv = clamp(vec2((float(q.x - u_lensActive.x)) / aw," +
            " (float(q.y - u_lensActive.y)) / ah), 0.0, 1.0);" +
            " VFHP vec2 sz = vec2(float(u_lensSize.x), float(u_lensSize.y));" +
            " VFHP vec2 uv = (nuv * (sz - vec2(1.0)) + vec2(0.5)) / sz;" +
            " vec4 g = texture2D(u_lens, uv);" +
            " VFHP float greenRow = float(q.y);" +
            " if (mod(greenRow + float(u_lensGreenRow), 2.0) != 0.0) g = vec4(g.r, g.b, g.g, g.a);" +
            " return g; }"

    // ESSL 3.00 variant: single hardware-filtered texture() fetch. Integer
    // bitwise ops are available here (unavailable in ESSL 1.00). Float math
    // uses highp for sensor geometry: mediump loses odd rows above 2048
    // and can move floor() across a quad boundary. Gains, sampler results and tonemapping retain highp precision.
    private const val LENS_GAIN_ES3 =
        "vec4 vfLensGain(VFHP vec2 t) {" +
            " if (u_applyLens == 0) return vec4(1.0);" +
            " VFHP ivec2 ij = ivec2(floor(t * vec2(u_frameSize)));" +
            " VFHP ivec2 q = u_quadBase + ij * u_step;" +
            " VFHP float aw = float(max(u_lensActive.z - u_lensActive.x - 1, 1));" +
            " VFHP float ah = float(max(u_lensActive.w - u_lensActive.y - 1, 1));" +
            " VFHP vec2 nuv = clamp(vec2(float(q.x - u_lensActive.x) / aw," +
            " float(q.y - u_lensActive.y) / ah), 0.0, 1.0);" +
            " VFHP vec2 sz = vec2(u_lensSize);" +
            " VFHP vec2 uv = (nuv * (sz - vec2(1.0)) + vec2(0.5)) / sz;" +
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
     * ESSL 1.00 fragment shader for the CPU/Vulkan RGBA16F path. Color math,
     * sampled values, geometry and dither use highp where available. Lens correction is disabled on the ES2 fallback.
     */
    fun cpuFragmentShader(es3: Boolean = false): String = if (es3) {
        "#version 300 es\n#define VFHP highp\nprecision highp float;\n" +
            "in highp vec2 tex; out vec4 fragColor; uniform highp sampler2D raw; uniform vec4 gains; uniform mat3 color;" +
            AGX_UNIFORMS + LENS_UNIFORMS_COMMON + TONEMAP_HELPERS +
            "\n#define VF_TEX texture\n" + VfToneLut.glsl + "\n" + PhotoLutGl.glsl + "\n" +
            LENS_GAIN_ES3 +
            "void main() { vec4 b = texture(raw, tex); b *= vfLensGain(tex);" +
            TONEMAP_TAIL_TEMPLATE.replace("@OUT@", "fragColor")
    } else {
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n#define VFHP highp\n#else\n#define VFHP mediump\n#endif\n" +
            "varying VFHP vec2 tex; uniform VFHP sampler2D raw; uniform vec4 gains; uniform mat3 color;" +
            AGX_UNIFORMS +
            LENS_UNIFORMS_COMMON +
            TONEMAP_HELPERS + "\n#define VF_TEX texture2D\n" + VfToneLut.glsl + "\n" +
            "vec3 photoCube(vec3 encoded) { return encoded; }\n" + LENS_GAIN_ES1 +
            "void main() { vec4 b = texture2D(raw, tex); b *= vfLensGain(tex);" +
            TONEMAP_TAIL_TEMPLATE.replace("@OUT@", "gl_FragColor")
    }

    /** ESSL 3.00 vertex shader for the GPU path (highp varyings for exact quad math). */
    fun gpuVertexShader(): String =
        "#version 300 es\n" +
            "in vec2 position; in vec2 uv; out highp vec2 tex;" +
            "void main(){ tex = uv; gl_Position = vec4(position, 0., 1.); }"

    /**
     * ESSL 3.00 fragment shader for the zero-copy path. Each display texel fetches its
     * Bayer quad from the imported `R16UI` texture with the same channel mapping and
     * Q6 black/white calibration as [VfCpuNeon.copy], preserving the sensor
     * fraction through shading and the shared highp tonemap. Only final display
     * conversion is quantized to dithered 8-bit.
     */
    fun gpuFragmentShader(): String =
        "#version 300 es\n" +
            "#define VFHP highp\n" +
            "precision highp float; precision highp int; precision highp sampler2D;" +
            "uniform highp usampler2D u_bayer;" +
            "uniform VFHP ivec2 u_quadBase; uniform VFHP ivec2 u_frameSize; uniform int u_step;" +
            "uniform ivec4 u_chans; uniform highp ivec4 u_blackQ; uniform highp ivec4 u_denQ;" +
            "uniform highp sampler2D u_lens; uniform VFHP ivec2 u_lensSize; uniform VFHP ivec4 u_lensActive;" +
            "uniform int u_applyLens; uniform int u_lensGreenRow;" +
            "uniform vec4 gains; uniform mat3 color;" +
            AGX_UNIFORMS +
            "in highp vec2 tex; out vec4 fragColor;" +
            TONEMAP_HELPERS + "\n#define VF_TEX texture\n" + VfToneLut.glsl + "\n" + PhotoLutGl.glsl + "\n" +
            LENS_GAIN_ES3 +
            // Runtime-specialized unpack: black/white for the exact RAW bit depth
            // (10/12/16-bit sensor modes all present as unpacked 16-bit codes) are
            // baked into Q6 ints on the CPU ([VfLevels]), so the shader is a single
            // straight-line fetch + integer MAD with dynamic uniform indexing and
            // no per-channel branches. All intermediates fit highp int (32-bit).
            "highp float vfFetchLinear(highp ivec2 q, int ch) {" +
            " highp ivec2 p = q + ivec2(ch & 1, (ch >> 1) & 1);" +
            " highp int code = int(texelFetch(u_bayer, p, 0).r);" +
            " highp int bq = u_blackQ[ch];" +
            " highp int dq = u_denQ[ch];" +
            " highp int num = code * 64 - bq;" +
            " if (num <= 0) return 0.0;" +
            " if (num >= dq) return 1.0;" +
            " return float(num) / float(dq); }" +
            "void main() { highp ivec2 q = u_quadBase + ivec2(floor(tex * vec2(u_frameSize))) * u_step;" +
            " highp vec4 n = vec4(vfFetchLinear(q, u_chans.x), vfFetchLinear(q, u_chans.y)," +
            " vfFetchLinear(q, u_chans.z), vfFetchLinear(q, u_chans.w));" +
            " highp vec4 bf = n;" +
            " vec4 b = vec4(bf);" +
            " b *= vfLensGain(tex);" +
            TONEMAP_TAIL_TEMPLATE.replace("@OUT@", "fragColor")
}
