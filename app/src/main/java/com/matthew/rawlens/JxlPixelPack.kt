// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Pure-Kotlin pixel packing for the JPEG XL encoder bridge.
 *
 * The GPU develop stage produces RGBA half-float pixels (row-major,
 * top-down: buffer row 0 is the image top row, matching the existing
 * RGBA8 JPEG readback which needs no vertical flip). This object drops
 * alpha and repacks to the 3-channel sample layout libjxl expects.
 *
 * Buffer semantics follow darktable (`src/imageio/format/jxl.c`), which
 * always feeds `JXL_TYPE_FLOAT`: libjxl interprets a `JXL_TYPE_UINT16`
 * input as full-range 16-bit and rescales it to `bits_per_sample`, so
 * feeding depth-native integers (e.g. 0..1023 for 10-bit) would collapse
 * the range (verified: 1023 arrived as 16). Integer depths are therefore
 * fed as `JXL_TYPE_FLOAT` code values in [0, 1]; only 16-bit uint, whose
 * container range matches the output depth exactly, uses `JXL_TYPE_UINT16`.
 * `bits_per_sample` / `exponent_bits_per_sample` still describe the
 * on-wire depth, so decoders see the selected bit depth.
 *
 * Kept free of Android dependencies so the conversions are JVM-testable.
 */
object JxlPixelPack {

    // JxlDataType ordinals from jxl/types.h; mirrored to avoid pulling the
    // native headers into the Kotlin facade.
    const val DATA_TYPE_FLOAT = 0
    const val DATA_TYPE_FLOAT16 = 1
    const val DATA_TYPE_UINT16 = 3

    /** libjxl `exponent_bits_per_sample` for each sample kind. */
    fun exponentBits(settings: JxlOutputSettings): Int = when {
        settings.bitDepth == JxlBitDepth.FLOAT_32 -> 8
        settings.bitDepth == JxlBitDepth.BIT_16 &&
            settings.pixelType == JxlPixelType.HALF_FLOAT -> 5
        else -> 0
    }

    /** JxlDataType for the encoder input buffer (see class notes). */
    fun dataType(settings: JxlOutputSettings): Int =
        if (settings.bitDepth == JxlBitDepth.BIT_16 &&
            settings.pixelType == JxlPixelType.UINT
        ) {
            DATA_TYPE_UINT16
        } else {
            DATA_TYPE_FLOAT
        }

    /**
     * Packs RGBA half-float pixels into a direct 3-channel buffer for
     * `JxlEncoderAddImageFrame`. [rgbaHalf] holds width*height*4 half
     * samples in native order, row-major top-down.
     */
    fun packRgb(rgbaHalf: ByteBuffer, width: Int, height: Int, settings: JxlOutputSettings): ByteBuffer {
        val resolved = settings.resolved()
        require(width > 0 && height > 0) { "Invalid JXL frame size ${width}x$height" }
        require(rgbaHalf.remaining() >= width * height * 8) {
            "RGBA half-float input too small: ${rgbaHalf.remaining()} bytes"
        }
        val pixels = width * height
        val output = ByteBuffer
            .allocateDirect(pixels * 3 * bytesPerSample(resolved))
            .order(ByteOrder.nativeOrder())
        val source = rgbaHalf.duplicate().order(ByteOrder.nativeOrder())
        if (dataType(resolved) == DATA_TYPE_UINT16) {
            // 16-bit uint only: container range matches the output depth.
            repeat(pixels) {
                repeat(3) {
                    val linear = halfBitsToFloat(source.short.toInt() and 0xffff)
                    output.putShort(quantizeUint16(linear, 65535f))
                }
                source.short // drop alpha
            }
        } else {
            // Every other depth rides raw display-referred floats (darktable
            // parity: no client-side quantization — the encoder maps [0, 1]
            // to bits_per_sample itself and owns above-white handling,
            // exactly like darktable's float pipe buffer).
            repeat(pixels) {
                repeat(3) {
                    output.putFloat(halfBitsToFloat(source.short.toInt() and 0xffff))
                }
                source.short // drop alpha
            }
        }
        output.flip()
        return output
    }

    private fun bytesPerSample(settings: JxlOutputSettings): Int =
        if (dataType(settings) == DATA_TYPE_FLOAT) 4 else 2

    /**
     * Downsamples RGBA half-float pixels to 8-bit RGBA bytes for the
     * saved-frame scope overlay (same convention as the JPEG readback: GLES
     * RGBA order feeds `Bitmap.copyPixelsFromBuffer` directly). Scope-only:
     * the saved JXL keeps full precision. Alpha is forced opaque.
     */
    fun halfRgbaToScopeRgba8(rgbaHalf: ByteBuffer, width: Int, height: Int): ByteBuffer {
        require(width > 0 && height > 0) { "Invalid JXL frame size ${width}x$height" }
        require(rgbaHalf.remaining() >= width * height * 8) {
            "RGBA half-float input too small: ${rgbaHalf.remaining()} bytes"
        }
        val output = ByteBuffer
            .allocateDirect(width * height * 4)
            .order(ByteOrder.nativeOrder())
        val source = rgbaHalf.duplicate().order(ByteOrder.nativeOrder())
        repeat(width * height) {
            repeat(3) {
                val linear = halfBitsToFloat(source.short.toInt() and 0xffff)
                output.put(quantizeUint16(linear, 255f).toByte())
            }
            source.short // drop half alpha
            output.put(0xFF.toByte())
        }
        output.flip()
        return output
    }

    /**
     * Content peak for PQ stills (darktable's MaxCLL): the brightest packed
     * code converted to absolute nits via the ST2084 EOTF. The encoder tags
     * this as the container mastering peak so viewers don't tone map for a
     * 10000-nit peak the image never reaches. Reads [rgb] without disturbing
     * its position; 0 when the frame is black.
     */
    fun peakNits(rgb: ByteBuffer, settings: JxlOutputSettings): Float {
        val resolved = settings.resolved()
        val src = rgb.duplicate().order(ByteOrder.nativeOrder())
        var maxCode = 0f
        if (dataType(resolved) == DATA_TYPE_UINT16) {
            while (src.remaining() >= 2) {
                val code = (src.short.toInt() and 0xffff) / 65535f
                if (code > maxCode) maxCode = code
            }
        } else {
            while (src.remaining() >= 4) {
                val code = src.float
                if (code.isFinite() && code > maxCode) maxCode = code
            }
        }
        return pqEotfNits(maxCode)
    }

    /**
     * ST2084 electro-optical transfer: PQ code [0, 1] to absolute nits
     * [0, 10000]. Inverse of the shader's `pq_oetf`; anchors at
     * PQ(100 nits) = 0.5081 and PQ(1000 nits) = 0.7518.
     */
    fun pqEotfNits(code: Float): Float {
        val c = code.coerceIn(0f, 1f).toDouble()
        if (c <= 0.0) return 0f
        val cp = c.pow(1.0 / 78.84375)
        val y = ((cp - 0.8359375) / (18.8515625 - 18.6875 * cp)).pow(1.0 / 0.15930176)
        return (y * 10000.0).toFloat()
    }

    /**
     * Quantizes display-referred light [0, 1] to an unsigned code value.
     * NaN maps to black; above-white (specular shoulder extends past 1.0)
     * saturates at peak white instead of wrapping.
     */
    fun quantizeUint16(value: Float, maxCodeValue: Float): Short {
        val clamped = when {
            value.isNaN() || value <= 0f -> 0f
            value >= 1f -> 1f
            else -> value
        }
        return (clamped * maxCodeValue + 0.5f).toInt().coerceIn(0, maxCodeValue.toInt()).toShort()
    }

    /** IEEE 754 binary16 bits -> Float. Handles normals, subnormals, Inf/NaN. */
    fun halfBitsToFloat(bits: Int): Float {
        val h = bits and 0xffff
        val sign = (h ushr 15) and 1
        val exp = (h ushr 10) and 0x1f
        val mant = h and 0x3ff
        val fbits = when (exp) {
            0 -> {
                if (mant == 0) {
                    sign shl 31
                } else {
                    // Subnormal: renormalize.
                    var m = mant
                    var e = -14
                    while ((m and 0x400) == 0) {
                        m = m shl 1
                        e--
                    }
                    m = m and 0x3ff
                    (sign shl 31) or ((e + 127) shl 23) or (m shl 13)
                }
            }
            0x1f -> (sign shl 31) or (0xff shl 23) or (mant shl 13)
            else -> (sign shl 31) or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return Float.fromBits(fbits)
    }

    /** Float -> IEEE 754 binary16 bits, round-to-nearest-even. */
    fun floatToHalfBits(value: Float): Int {
        val bits = value.toBits()
        val sign = (bits ushr 16) and 0x8000
        val exp = ((bits ushr 23) and 0xff) - 112
        val mant = bits and 0x7fffff
        return when {
            exp >= 31 -> sign or 0x7bff // overflow saturates to max finite half
            exp <= 0 -> {
                if (exp < -10) {
                    sign // underflow to signed zero
                } else {
                    // Subnormal half (with round-to-nearest-even).
                    val shifted = mant or 0x800000
                    val shift = 14 - exp
                    var half = shifted ushr shift
                    val remainder = shifted and ((1 shl shift) - 1)
                    val halfway = 1 shl (shift - 1)
                    if (remainder > halfway || (remainder == halfway && (half and 1) != 0)) {
                        half++
                    }
                    sign or half
                }
            }
            else -> {
                var half = sign or (exp shl 10) or (mant ushr 13)
                // Round-to-nearest-even on the dropped 13 bits.
                val remainder = mant and 0x1fff
                if (remainder > 0x1000 || (remainder == 0x1000 && (half and 1) != 0)) {
                    half++
                    // Rounding may carry into the exponent (e.g. 0.9999 -> 1.0);
                    // the increment handles it, but a carry past 0x7bff must
                    // saturate instead of becoming Inf.
                    if ((half and 0x7fff) >= 0x7c00) half = sign or 0x7bff
                }
                half
            }
        }
    }
}
