// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawPackedUnpackerTest {
    private fun packRaw10(codes: IntArray): ByteArray {
        require(codes.size % 4 == 0)
        val out = ByteArray(codes.size / 4 * 5)
        var o = 0
        var i = 0
        while (i < codes.size) {
            val p = IntArray(4) { codes[i + it] shr 6 }
            out[o++] = (p[0] shr 2).toByte()
            out[o++] = (p[1] shr 2).toByte()
            out[o++] = (p[2] shr 2).toByte()
            out[o++] = (p[3] shr 2).toByte()
            out[o++] = ((p[0] and 0x03) or ((p[1] and 0x03) shl 2) or
                ((p[2] and 0x03) shl 4) or ((p[3] and 0x03) shl 6)).toByte()
            i += 4
        }
        return out
    }

    private fun packRaw12(codes: IntArray): ByteArray {
        require(codes.size % 2 == 0)
        val out = ByteArray(codes.size / 2 * 3)
        var o = 0
        var i = 0
        while (i < codes.size) {
            val p0 = codes[i++] shr 4
            val p1 = codes[i++] shr 4
            out[o++] = (p0 shr 4).toByte()
            out[o++] = (p1 shr 4).toByte()
            out[o++] = ((p0 and 0x0f) or ((p1 and 0x0f) shl 4)).toByte()
        }
        return out
    }

    @Test fun `raw10 round trips bit exact`() {
        val width = 8
        val height = 2
        val codes = IntArray(width * height) { (it * 977 + 13) % 1024 shl 6 }
        val packed = packRaw10(codes)
        val decoded = RawPackedUnpacker.decodePlaneToShorts(
            ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder()),
            width, height, packed.size / height, 0, PackedRawFormat.RAW10
        )
        assertArrayEquals(codes.map(Int::toShort).toShortArray(), decoded)
    }

    @Test fun `raw12 round trips bit exact`() {
        val width = 8
        val height = 2
        val codes = IntArray(width * height) { (it * 541 + 7) % 4096 shl 4 }
        val packed = packRaw12(codes)
        val decoded = RawPackedUnpacker.decodePlaneToShorts(
            ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder()),
            width, height, packed.size / height, 0, PackedRawFormat.RAW12
        )
        assertArrayEquals(codes.map(Int::toShort).toShortArray(), decoded)
    }

    @Test fun `row padding is skipped`() {
        val width = 4
        val height = 2
        val codes = IntArray(width * height) { (it * 131 + 3) % 1024 shl 6 }
        val row = packRaw10(codes.copyOfRange(0, width)) + packRaw10(codes.copyOfRange(width, width * 2))
        val stride = 5 + 3
        val padded = ByteArray(stride * height)
        System.arraycopy(row, 0, padded, 0, 5)
        System.arraycopy(row, 5, padded, stride, 5)
        val decoded = RawPackedUnpacker.decodePlaneToShorts(
            ByteBuffer.wrap(padded), width, height, stride, 0, PackedRawFormat.RAW10
        )
        assertArrayEquals(codes.map(Int::toShort).toShortArray(), decoded)
    }

    @Test fun `odd width is refused`() {
        try {
            RawPackedUnpacker.decodePlaneToShorts(
                ByteBuffer.allocate(64), 6, 2, 16, 0, PackedRawFormat.RAW10
            )
            fail("expected refusal")
        } catch (expected: IllegalArgumentException) {
            assertEquals(true, expected.message!!.contains("multiple of 4"))
        }
    }

    @Test fun `short stride is refused`() {
        try {
            RawPackedUnpacker.decodePlaneToShorts(
                ByteBuffer.allocate(64), 8, 2, 9, 0, PackedRawFormat.RAW12
            )
            fail("expected refusal")
        } catch (expected: IllegalArgumentException) {
            assertEquals(true, expected.message!!.contains("stride"))
        }
    }

    @Test fun `truncated buffer is refused`() {
        try {
            RawPackedUnpacker.decodePlaneToShorts(
                ByteBuffer.allocate(10), 8, 2, 12, 0, PackedRawFormat.RAW12
            )
            fail("expected refusal")
        } catch (expected: IllegalArgumentException) {
            assertEquals(true, expected.message!!.contains("truncated"))
        }
    }

    private fun packRaw14(codes: IntArray, candidate: Raw14Candidate): ByteArray {
        require(codes.size % 4 == 0)
        val out = ByteArray(codes.size / 4 * 7)
        var o = 0
        var i = 0
        while (i < codes.size) {
            // Test codes are left-justified 16-bit values: drop to native
            // 14-bit before splitting top 8 and low 6 bits.
            val native = IntArray(4) { codes[i + it] shr 2 }
            val top = IntArray(4) { (native[it] shr 6) and 0xff }
            val tails = IntArray(4) { native[it] and 0x3f }
            val word = when (candidate) {
                Raw14Candidate.LSB_FIRST -> tails.indices.fold(0) { acc, k -> acc or (tails[k] shl (6 * k)) }
                Raw14Candidate.MSB_FIRST -> tails.indices.fold(0) { acc, k -> acc or (tails[k] shl (6 * (3 - k))) }
                Raw14Candidate.BYTE_SWAPPED_LSB, Raw14Candidate.BYTE_SWAPPED_MSB -> 0
            }
            val (b4, b5, b6) = when (candidate) {
                Raw14Candidate.LSB_FIRST, Raw14Candidate.MSB_FIRST ->
                    Triple(word and 0xff, (word shr 8) and 0xff, (word shr 16) and 0xff)
                Raw14Candidate.BYTE_SWAPPED_LSB -> {
                    val w = tails.indices.fold(0) { acc, k -> acc or (tails[k] shl (6 * k)) }
                    Triple((w shr 16) and 0xff, (w shr 8) and 0xff, w and 0xff)
                }
                Raw14Candidate.BYTE_SWAPPED_MSB -> {
                    val w = tails.indices.fold(0) { acc, k -> acc or (tails[k] shl (6 * (3 - k))) }
                    Triple((w shr 16) and 0xff, (w shr 8) and 0xff, w and 0xff)
                }
            }
            top.forEach { out[o++] = it.toByte() }
            out[o++] = b4.toByte()
            out[o++] = b5.toByte()
            out[o++] = b6.toByte()
            i += 4
        }
        return out
    }

    @Test fun `raw14 candidates round trip self consistently`() {
        val width = 8
        val height = 2
        val codes = IntArray(width * height) { (it * 104729 + 11) % 16384 shl 2 }
        for (candidate in Raw14Candidate.entries) {
            val packed = packRaw14(codes, candidate)
            val decoded = RawPackedUnpacker.decodeRaw14Plane(
                ByteBuffer.wrap(packed), width, height, packed.size / height, 0, candidate
            )
            assertArrayEquals(codes.map(Int::toShort).toShortArray(), decoded)
        }
    }

    @Test fun `raw14 candidates are mutually discriminative`() {
        val width = 8
        val height = 2
        val codes = IntArray(width * height) { (it * 104729 + 11) % 16384 shl 2 }
        val packed = packRaw14(codes, Raw14Candidate.LSB_FIRST)
        val variants = Raw14Candidate.entries.map {
            RawPackedUnpacker.decodeRaw14Plane(
                ByteBuffer.wrap(packed), width, height, packed.size / height, 0, it
            ).toList()
        }
        assertEquals(Raw14Candidate.entries.size, variants.distinct().size)
    }

    @Test fun `sample reader matches full decode`() {
        val width = 12
        val height = 6
        val codes = IntArray(width * height) { (it * 541 + 7) % 4096 shl 4 }
        val packed = packRaw12(codes)
        val reader = RawPackedUnpacker.PackedSampleReader(
            ByteBuffer.wrap(packed), width, height, packed.size / height, 0,
            PackedRawFormat.RAW12
        )
        for (y in 0 until height) for (x in 0 until width) {
            assertEquals(codes[y * width + x] shr 4, reader.codeAt(x, y))
        }
    }

    @Test fun `sample reader refuses bad geometry and range`() {
        val reader = RawPackedUnpacker.PackedSampleReader(
            ByteBuffer.allocate(48), 8, 4, 12, 0, PackedRawFormat.RAW12
        )
        try {
            reader.codeAt(8, 0)
            fail("expected refusal")
        } catch (expected: IllegalArgumentException) {
            assertEquals(true, expected.message!!.contains("outside"))
        }
        try {
            RawPackedUnpacker.PackedSampleReader(
                ByteBuffer.allocate(16), 8, 4, 4, 0, PackedRawFormat.RAW12
            )
            fail("expected refusal")
        } catch (expected: IllegalArgumentException) {
            assertEquals(true, expected.message!!.contains("stride"))
        }
    }

    @Test fun `match rate scores identical frames at one`() {
        val frame = ShortArray(64) { (it * 31).toShort() }
        assertEquals(1.0, RawPackedUnpacker.matchRate(frame, frame.copyOf(), 0), 0.0)
        val noisy = frame.copyOf().also { it[0] = (it[0] + 100).toShort() }
        assertEquals(63.0 / 64.0, RawPackedUnpacker.matchRate(frame, noisy, 4), 1e-9)
    }

    private fun normalization() = RawNormalization(
        BayerPattern.RGGB, listOf(64f, 64f, 64f, 64f), 1023f
    )

    @Test fun `bridge passes raw sensor through untouched`() {
        val width = 8
        val height = 4
        val shorts = ShortArray(width * height) { (it * 37 % 1000).toShort() }
        val bytes = ByteBuffer.allocateDirect(shorts.size * 2).order(ByteOrder.nativeOrder())
        bytes.asShortBuffer().put(shorts)
        bytes.rewind()
        val layout = RawPlaneLayout(width, height, width * 2, 2)
        val direct = RawSensorUnpacker.unpackNormalized(bytes, layout, normalization())
        val bridged = RawPackedUnpacker.unpackFrameNormalized(
            bytes, width, height, width * 2, 2,
            android.graphics.ImageFormat.RAW_SENSOR, normalization()
        )
        assertArrayEquals(direct.values, bridged.values, 0f)
        assertEquals(direct.width, bridged.width)
        assertEquals(direct.height, bridged.height)
    }

    @Test fun `bridge decodes packed like the manual path`() {
        val width = 8
        val height = 4
        val codes = IntArray(width * height) { (it * 977 + 13) % 1024 shl 6 }
        val packed = packRaw10(codes)
        // Tight 16-bit layout rejects stride 0, so the bridge is the only
        // entry: compare against a hand-built tight buffer instead.
        val tight = ByteBuffer.allocateDirect(codes.size * 2).order(ByteOrder.nativeOrder())
        tight.asShortBuffer().put(codes.map(Int::toShort).toShortArray())
        tight.rewind()
        val expected = RawSensorUnpacker.unpackNormalized(
            tight, RawPlaneLayout(width, height, width * 2, 2), normalization()
        )
        val bridged = RawPackedUnpacker.unpackFrameNormalized(
            ByteBuffer.wrap(packed), width, height, packed.size / height, 0,
            android.graphics.ImageFormat.RAW10, normalization()
        )
        assertArrayEquals(expected.values, bridged.values, 0f)
    }

    @Test fun `bridge refuses unknown formats`() {
        try {
            RawPackedUnpacker.unpackFrameNormalized(
                ByteBuffer.allocate(320), 8, 4, 40, 2, 12345, normalization()
            )
            fail("expected refusal")
        } catch (expected: IllegalArgumentException) {
            assertEquals(true, expected.message!!.contains("Unsupported RAW stream format"))
        }
    }
}
