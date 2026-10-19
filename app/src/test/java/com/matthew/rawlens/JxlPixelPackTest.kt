// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JxlPixelPackTest {
    @Test
    fun halfBitsToFloatKnownValues() {
        assertEquals(0f, JxlPixelPack.halfBitsToFloat(0x0000), 0f)
        assertEquals(1f, JxlPixelPack.halfBitsToFloat(0x3C00), 0f)
        assertEquals(0.5f, JxlPixelPack.halfBitsToFloat(0x3800), 0f)
        assertEquals(-1f, JxlPixelPack.halfBitsToFloat(0xBC00), 0f)
        assertEquals(65504f, JxlPixelPack.halfBitsToFloat(0x7BFF), 0f)
        assertEquals(
            Float.POSITIVE_INFINITY,
            JxlPixelPack.halfBitsToFloat(0x7C00),
            0f
        )
    }

    @Test
    fun floatToHalfBitsRoundTrip() {
        assertEquals(0x3C00, JxlPixelPack.floatToHalfBits(1f))
        assertEquals(0x3800, JxlPixelPack.floatToHalfBits(0.5f))
        assertEquals(0xBC00, JxlPixelPack.floatToHalfBits(-1f))
        assertEquals(0x0000, JxlPixelPack.floatToHalfBits(0f))
        // Overflow saturates to max finite half instead of Inf.
        assertEquals(0x7BFF, JxlPixelPack.floatToHalfBits(100000f))
        for (value in floatArrayOf(0.1f, 0.25f, 2.5f, 100f)) {
            val roundTripped = JxlPixelPack.halfBitsToFloat(JxlPixelPack.floatToHalfBits(value))
            assertTrue(
                "half round trip of $value drifted to $roundTripped",
                kotlin.math.abs(roundTripped - value) / value < 0.001f
            )
        }
    }

    private fun code(value: Float, max: Float): Int =
        JxlPixelPack.quantizeUint16(value, max).toInt() and 0xffff

    @Test
    fun quantizeClampsAndRounds() {
        assertEquals(0, code(0f, 65535f))
        assertEquals(65535, code(1f, 65535f))
        assertEquals(32768, code(0.5f, 65535f))
        assertEquals(16384, code(0.25f, 65535f))
        // Above-white shoulder and NaN never wrap.
        assertEquals(255, code(1.7f, 255f))
        assertEquals(0, code(Float.NaN, 255f))
        assertEquals(0, code(-0.5f, 255f))
    }

    @Test
    fun packRgb16BitDropsAlpha() {
        // Two pixels: (1.0, 0.5, 0.25, a=1.0) and black.
        val input = ByteBuffer
            .allocateDirect(2 * 4 * 2)
            .order(ByteOrder.nativeOrder())
        for (half in intArrayOf(0x3C00, 0x3800, 0x3400, 0x3C00, 0, 0, 0, 0)) {
            input.putShort(half.toShort())
        }
        input.flip()
        val packed = JxlPixelPack.packRgb(
            input, 2, 1, JxlOutputSettings(bitDepth = JxlBitDepth.BIT_16)
        )
        val out = packed.order(ByteOrder.nativeOrder())
        assertEquals(2 * 3 * 2, out.remaining())
        assertEquals(65535.toShort(), out.short)
        assertEquals(32768.toShort(), out.short)
        assertEquals(16384.toShort(), out.short)
        assertEquals(0.toShort(), out.short)
        assertEquals(0.toShort(), out.short)
        assertEquals(0.toShort(), out.short)
    }

    @Test
    fun packRgbHalfFloatRidesRawFloat() {
        // darktable parity: the encoder owns quantization, so 16-bit float
        // (like every non-16u depth) is fed raw display-referred floats.
        val input = ByteBuffer
            .allocateDirect(4 * 2)
            .order(ByteOrder.nativeOrder())
        for (half in intArrayOf(0x3C00, 0x3800, 0x3400, 0x3C00)) {
            input.putShort(half.toShort())
        }
        input.flip()
        val packed = JxlPixelPack.packRgb(
            input, 1, 1,
            JxlOutputSettings(
                bitDepth = JxlBitDepth.BIT_16,
                pixelType = JxlPixelType.HALF_FLOAT
            )
        )
        val out = packed.order(ByteOrder.nativeOrder())
        assertEquals(3 * 4, out.remaining())
        assertEquals(1f, out.float, 0f)
        assertEquals(0.5f, out.float, 0f)
        assertEquals(0.25f, out.float, 0f)
    }

    @Test
    fun packRgbIntegerDepthRidesRawFloat() {
        // A UINT16 input would be misread as full-range 16-bit by libjxl
        // (verified: 1023 arrived as 16 for 10-bit output), so integer
        // depths below 16-bit ride raw floats; above-white stays unclamped
        // for the encoder to own.
        val input = ByteBuffer
            .allocateDirect(4 * 2)
            .order(ByteOrder.nativeOrder())
        for (half in intArrayOf(0x3C00, 0x3800, 0x3E00, 0x3C00)) {
            input.putShort(half.toShort())
        }
        input.flip()
        val packed = JxlPixelPack.packRgb(
            input, 1, 1, JxlOutputSettings(bitDepth = JxlBitDepth.BIT_10)
        )
        val out = packed.order(ByteOrder.nativeOrder())
        assertEquals(3 * 4, out.remaining())
        assertEquals(1f, out.float, 0f)
        assertEquals(0.5f, out.float, 0f)
        assertEquals(1.5f, out.float, 0f)
    }

    @Test
    fun scopeRgba8DownsamplesAndForcesOpaque() {
        // One pixel: (1.0, 0.5, 0.0, half-alpha 0.0 must not leak through).
        val input = ByteBuffer
            .allocateDirect(4 * 2)
            .order(ByteOrder.nativeOrder())
        for (half in intArrayOf(0x3C00, 0x3800, 0x0000, 0x0000)) {
            input.putShort(half.toShort())
        }
        input.flip()
        val scoped = JxlPixelPack.halfRgbaToScopeRgba8(input, 1, 1)
        assertEquals(4, scoped.remaining())
        assertEquals(0xFF.toByte(), scoped.get())
        assertEquals(0x80.toByte(), scoped.get())
        assertEquals(0x00.toByte(), scoped.get())
        assertEquals(0xFF.toByte(), scoped.get())
    }

    @Test
    fun encoderFormatMappingMatchesDarktable() {
        assertEquals(0, JxlPixelPack.exponentBits(JxlOutputSettings(bitDepth = JxlBitDepth.BIT_16)))
        assertEquals(
            5,
            JxlPixelPack.exponentBits(
                JxlOutputSettings(
                    bitDepth = JxlBitDepth.BIT_16,
                    pixelType = JxlPixelType.HALF_FLOAT
                )
            )
        )
        assertEquals(8, JxlPixelPack.exponentBits(JxlOutputSettings(bitDepth = JxlBitDepth.FLOAT_32)))
        // Only 16-bit uint matches its container range; every other depth
        // rides float (libjxl would rescale depth-native integers).
        assertEquals(
            JxlPixelPack.DATA_TYPE_FLOAT,
            JxlPixelPack.dataType(JxlOutputSettings(bitDepth = JxlBitDepth.BIT_10))
        )
        assertEquals(
            JxlPixelPack.DATA_TYPE_UINT16,
            JxlPixelPack.dataType(JxlOutputSettings(bitDepth = JxlBitDepth.BIT_16))
        )
        assertEquals(
            JxlPixelPack.DATA_TYPE_FLOAT,
            JxlPixelPack.dataType(
                JxlOutputSettings(
                    bitDepth = JxlBitDepth.BIT_16,
                    pixelType = JxlPixelType.HALF_FLOAT
                )
            )
        )
        assertEquals(
            JxlPixelPack.DATA_TYPE_FLOAT,
            JxlPixelPack.dataType(JxlOutputSettings(bitDepth = JxlBitDepth.FLOAT_32))
        )
    }

    @Test
    fun pqEotfAnchorsMatchSt2084() {
        assertEquals(0f, JxlPixelPack.pqEotfNits(0f), 0f)
        assertEquals(100f, JxlPixelPack.pqEotfNits(0.5081f), 0.5f)
        assertEquals(1000f, JxlPixelPack.pqEotfNits(0.7518f), 1f)
        assertEquals(10000f, JxlPixelPack.pqEotfNits(1f), 1f)
        assertEquals(0f, JxlPixelPack.pqEotfNits(-0.5f), 0f)
    }

    @Test
    fun peakNitsReadsBrightestPackedCode() {
        val uint16 = JxlOutputSettings(
            bitDepth = JxlBitDepth.BIT_16, pixelType = JxlPixelType.UINT
        )
        // 2x1 frame: mid grey plus peak white; peak must be ~10000 nits.
        val packed = ByteBuffer.allocateDirect(2 * 3 * 2).order(ByteOrder.nativeOrder())
        repeat(3) { packed.putShort(32768.toShort()) }
        repeat(3) { packed.putShort(65535.toShort()) }
        packed.flip()
        val before = packed.position()
        assertEquals(10000f, JxlPixelPack.peakNits(packed, uint16), 1f)
        assertEquals(before, packed.position())

        // Float path: brightest sample wins, non-finite samples ignored.
        val floats = JxlOutputSettings(bitDepth = JxlBitDepth.FLOAT_32)
        val fbuf = ByteBuffer.allocateDirect(2 * 3 * 4).order(ByteOrder.nativeOrder())
        fbuf.putFloat(0.1f); fbuf.putFloat(Float.NaN); fbuf.putFloat(0.2f)
        fbuf.putFloat(0.75f); fbuf.putFloat(0.3f); fbuf.putFloat(0.4f)
        fbuf.flip()
        assertEquals(JxlPixelPack.pqEotfNits(0.75f), JxlPixelPack.peakNits(fbuf, floats), 0.01f)
    }
}
