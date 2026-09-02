// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * CPU golden reference for the SDR display/output boundary.
 *
 * AgX is pinned to Google Filament commit 2a8018f54d5154ceb1bf7005c6c01b13aa70e7ad,
 * filament/src/ToneMapper.cpp SHA-256 1e3212b67f2954721a4336c68fef1904204873835896e25c9ba77f9030aa42cd.
 * Filament's implementation is Apache-2.0 and expects scene-linear Rec.2020. RawLens therefore
 * applies an explicit ACEScg/AP1 D60 -> XYZ D60 -> Bradford D65 -> Rec.2020 D65 transform first.
 */
object AgxDisplayTransform {
    const val REFERENCE_COMMIT = "2a8018f54d5154ceb1bf7005c6c01b13aa70e7ad"
    const val REFERENCE_SHA256 = "1e3212b67f2954721a4336c68fef1904204873835896e25c9ba77f9030aa42cd"
    const val GPU_TOLERANCE = 3e-3f

    /** Returns output-linear sRGB before the one deliberate final display-gamut clip. */
    fun acescgToOutputLinearSrgb(acescg: FloatArray, purityBoost: Float = 1f): FloatArray {
        return acescgToOutputLinearSrgb(
            acescg,
            JpegOutputSettings(agxPurityBoost = purityBoost)
        )
    }

    fun acescgToOutputLinearSrgb(acescg: FloatArray, settings: JpegOutputSettings): FloatArray {
        return acescgToOutputLinear(acescg, REC2020_TO_SRGB, settings.resolvedForPlatform())
    }

    /** Display P3 shares D65 and the sRGB transfer curve, but has wider P3 primaries. */
    fun acescgToOutputLinearDisplayP3(acescg: FloatArray, purityBoost: Float = 1f): FloatArray {
        return acescgToOutputLinearDisplayP3(
            acescg,
            JpegOutputSettings(agxPurityBoost = purityBoost)
        )
    }

    fun acescgToOutputLinearDisplayP3(acescg: FloatArray, settings: JpegOutputSettings): FloatArray {
        return acescgToOutputLinear(acescg, REC2020_TO_DISPLAY_P3, settings.resolvedForPlatform())
    }

    private fun acescgToOutputLinear(
        acescg: FloatArray,
        outputMatrix: FloatArray,
        settings: JpegOutputSettings
    ): FloatArray {
        require(acescg.size == 3 && acescg.all(Float::isFinite))
        val sceneRec2020 = map(ACESCG_TO_REC2020_D65, acescg)
        // AgX's log domain is non-negative. This is the pinned view-transform domain guard,
        // not the final display-gamut boundary.
        val positiveScene = FloatArray(3) { max(0f, sceneRec2020[it]) }
        var value = positiveScene
        value = map(AGX_INSET, value)
        val minimumEv = MIDDLE_GRAY_LOG2 - settings.agxShadowEv
        val maximumEv = MIDDLE_GRAY_LOG2 + settings.agxHighlightEv
        val evRange = maximumEv - minimumEv
        for (channel in 0..2) {
            val logValue = log2(max(value[channel], 1e-10f))
            val normalized = ((logValue - minimumEv) / evRange).coerceIn(0f, 1f)
            val pivot = settings.agxShadowEv / evRange
            value[channel] = (pivot + (normalized - pivot) * settings.agxContrast).coerceIn(0f, 1f)
        }
        value = FloatArray(3) { contrast(value[it]) }
        value = applyLook(value, settings.agxLook)
        val outset = map(AGX_OUTSET, value)
        value = FloatArray(3) { value[it] + settings.agxPurityBoost * (outset[it] - value[it]) }
        value = FloatArray(3) { max(0f, value[it]).pow(2.2f) }
        val mappedLuma = dot(value, REC2020_LUMA)
        if (settings.agxHuePreservation > 0f) {
            val sceneLuma = dot(positiveScene, REC2020_LUMA)
            if (sceneLuma > 1e-9f) {
                val ratioMapped = FloatArray(3) { positiveScene[it] * mappedLuma / sceneLuma }
                value = FloatArray(3) {
                    value[it] + settings.agxHuePreservation * (ratioMapped[it] - value[it])
                }
            }
        }
        val gradedLuma = dot(value, REC2020_LUMA)
        value = FloatArray(3) {
            gradedLuma + settings.agxSaturation * (value[it] - gradedLuma)
        }
        value = map(outputMatrix, value)
        value = gamutCompress(value, settings.agxGamutCompression)
        require(value.all(Float::isFinite)) { "AgX produced NaN or infinity" }
        return value
    }

    private fun applyLook(value: FloatArray, look: AgxLook): FloatArray {
        if (look == AgxLook.BASE) return value
        val luma = dot(value, SRGB_LUMA)
        val slope = if (look == AgxLook.GOLDEN) floatArrayOf(1f, 0.9f, 0.5f)
            else floatArrayOf(1f, 1f, 1f)
        val power = if (look == AgxLook.GOLDEN) 0.8f else 1.35f
        val saturation = if (look == AgxLook.GOLDEN) 1.3f else 1.4f
        val adjusted = FloatArray(3) { max(0f, value[it] * slope[it]).pow(power) }
        return FloatArray(3) { luma + saturation * (adjusted[it] - luma) }
    }

    private fun gamutCompress(value: FloatArray, strength: Float): FloatArray {
        if (strength <= 0f) return value
        val anchor = dot(value, SRGB_LUMA).coerceIn(0f, 1f)
        var boundaryScale = 1f
        value.forEach { channel ->
            val delta = channel - anchor
            if (delta > 0f) boundaryScale = minOf(boundaryScale, (1f - anchor) / delta)
            else if (delta < 0f) boundaryScale = minOf(boundaryScale, -anchor / delta)
        }
        val scale = 1f + strength * (boundaryScale.coerceIn(0f, 1f) - 1f)
        return FloatArray(3) { anchor + scale * (value[it] - anchor) }
    }

    /** Exactly one final display-gamut operation: hard clip in output-linear sRGB. */
    fun finalGamutClip(linearSrgb: FloatArray): FloatArray {
        require(linearSrgb.size == 3 && linearSrgb.all(Float::isFinite))
        return FloatArray(3) { linearSrgb[it].coerceIn(0f, 1f) }
    }

    fun srgbOetf(linear: Float): Float {
        require(linear.isFinite() && linear in 0f..1f)
        return if (linear <= 0.0031308f) 12.92f * linear
        else 1.055f * linear.pow(1f / 2.4f) - 0.055f
    }

    /** Deterministic encoded-space triangular-ish hash dither, centered at zero, amplitude 1 LSB. */
    fun quantizeSrgb8(linearSrgb: FloatArray, x: Int, y: Int): ByteArray {
        val clipped = finalGamutClip(linearSrgb)
        return ByteArray(3) { channel ->
            val encoded = srgbOetf(clipped[channel])
            val dither = (hash01(x, y, channel) - 0.5f) / 255f
            ((encoded + dither) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
    }

    private fun contrast(x: Float): Float {
        val x2 = x * x
        val x4 = x2 * x2
        val x6 = x4 * x2
        return -17.86f * x6 * x + 78.01f * x6 - 126.7f * x4 * x + 92.06f * x4 -
            28.72f * x2 * x + 4.361f * x2 - 0.1718f * x + 0.002857f
    }

    private fun map(matrix: FloatArray, vector: FloatArray) = FloatArray(3) { row ->
        matrix[row * 3] * vector[0] + matrix[row * 3 + 1] * vector[1] +
            matrix[row * 3 + 2] * vector[2]
    }

    private fun dot(left: FloatArray, right: FloatArray): Float =
        left[0] * right[0] + left[1] * right[1] + left[2] * right[2]

    private fun hash01(x: Int, y: Int, channel: Int): Float {
        var bits = x * 0x1f123bb5 + y * 0x05491333 + channel * 0x68bc21eb
        bits = bits xor (bits ushr 16)
        bits *= 0x7feb352d
        bits = bits xor (bits ushr 15)
        return (bits ushr 8 and 0x00ffffff) / 16777216f
    }

    private fun log2(value: Float): Float = (ln(value.toDouble()) / LN_2).toFloat()

    private const val MIDDLE_GRAY_LOG2 = -2.473931188f
    private const val LN_2 = 0.6931471805599453
    private val REC2020_LUMA = floatArrayOf(0.2627f, 0.6780f, 0.0593f)
    private val SRGB_LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    // Mathematical row-major, column-vector convention.
    private val ACESCG_TO_REC2020_D65 = floatArrayOf(
        1.025877552449f, -0.020020686312f, -0.005775003430f,
        -0.002232441770f, 1.004568990995f, -0.002349522759f,
        -0.005013950857f, -0.025282661381f, 1.030082295555f
    )
    private val AGX_INSET = floatArrayOf(
        0.856627153315983f, 0.0951212405381588f, 0.0482516061458583f,
        0.137318972929847f, 0.761241990602591f, 0.101439036467562f,
        0.11189821299995f, 0.0767994186031903f, 0.811302368396859f
    )
    private val AGX_OUTSET = floatArrayOf(
        1.127100581814437f, -0.110606643096603f, -0.016493938717835f,
        -0.141329763498438f, 1.157823702216272f, -0.016493938717834f,
        -0.141329763498438f, -0.110606643096603f, 1.251936406595040f
    )
    private val REC2020_TO_SRGB = floatArrayOf(
        1.6604910021f, -0.5876411388f, -0.0728498633f,
        -0.1245504745f, 1.1328998971f, -0.0083494226f,
        -0.0181507634f, -0.1005788980f, 1.1187296614f
    )
    private val REC2020_TO_DISPLAY_P3 = floatArrayOf(
        1.343578252570f, -0.282179670449f, -0.061398582051f,
        -0.065297452837f, 1.075787915784f, -0.010490463088f,
        0.002821787226f, -0.019598494598f, 1.016776707234f
    )
}
