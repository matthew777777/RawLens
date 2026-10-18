// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Densely packed Bayer formats from `StreamConfigurationMap`, decoded to
 * 16-bit sample codes (left-justified) that the rest of the pipeline
 * consumes exactly like `RAW_SENSOR` planes.
 *
 * Layouts follow the `ImageFormat` documentation: RAW10 packs 4 pixels into
 * 5 bytes, RAW12 packs 2 pixels into 3 bytes (top 8 bits first, low bits
 * shared in the trailing byte). Pixel stride is always 0; rows may carry
 * padding past the packed pixels.
 *
 * RAW14 (API 37, value 44) is refused: confirmed so far is 4 pixels per
 * 7 bytes with the top 8 bits in bytes 0..3, width multiple of 4, even
 * height, stride 0 — but the published documentation references a
 * low-bits table it does not ship, so which bits of bytes 4..6 hold
 * each pixel's low 6 bits is unpublished. Implement only with a
 * same-scene parity proof against `RAW_SENSOR` on device.
 */
enum class PackedRawFormat(val bitsPerPixel: Int) {
    RAW10(10),
    RAW12(12);

    /** Packed data bytes per row, excluding padding. */
    fun packedRowBytes(width: Int): Int = width * bitsPerPixel / 8

    /** Camera2 stream value for session configuration. */
    fun toImageFormat(): Int = when (this) {
        RAW10 -> android.graphics.ImageFormat.RAW10
        RAW12 -> android.graphics.ImageFormat.RAW12
    }

    companion object {
        /** Known packed formats by Camera2 stream value; null covers `RAW_SENSOR` and anything refused. */
        fun ofInt(format: Int): PackedRawFormat? = when (format) {
            android.graphics.ImageFormat.RAW10 -> RAW10
            android.graphics.ImageFormat.RAW12 -> RAW12
            else -> null
        }
    }
}

/**
 * Totally ordered hypotheses for the unpublished RAW14 low-bits layout.
 * Bytes 0..3 of each 7-byte group hold the top 8 bits of P0..P3 (per the
 * API documentation); the hypotheses differ only in how the 24 bits of
 * bytes 4..6 map to the four 6-bit tails. Exactly one should reproduce a
 * same-scene `RAW_SENSOR` frame; the derivation probe scores all four.
 */
enum class Raw14Candidate {
    /** Tails concatenated LSB-first: P0 in bits 0..5 up to P3 in 18..23. */
    LSB_FIRST,

    /** Tails concatenated MSB-first: P0 in bits 18..23 down to P3 in 0..5. */
    MSB_FIRST,

    /** Bytes 4..6 read reversed, then LSB-first tails. */
    BYTE_SWAPPED_LSB,

    /** Bytes 4..6 read reversed, then MSB-first tails. */
    BYTE_SWAPPED_MSB
}

object RawPackedUnpacker {
    /**
     * Decodes one packed plane into tight 16-bit codes (`width * height`
     * shorts, left-justified). Reads start at the buffer's current position
     * plus `planeOffset`; rows advance by `rowStrideBytes`.
     *
     * @throws IllegalArgumentException on odd geometry, short strides, or
     * truncated buffers: callers fall back to `RAW_SENSOR` instead.
     */
    fun decodePlaneToShorts(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        planeOffset: Int = 0,
        format: PackedRawFormat,
        byteOrder: ByteOrder = ByteOrder.nativeOrder()
    ): ShortArray {
        require(width > 0 && height > 0) { "Packed plane must be non-empty" }
        require(width % 4 == 0) { "Packed RAW needs width multiple of 4" }
        val packedBytes = format.packedRowBytes(width)
        require(rowStrideBytes >= packedBytes) {
            "Packed row stride $rowStrideBytes holds only $packedBytes bytes"
        }
        val input = source.duplicate().order(byteOrder)
        val origin = input.position() + planeOffset
        val lastRow = origin + (height - 1).toLong() * rowStrideBytes + packedBytes
        require(lastRow <= input.limit().toLong()) { "Packed buffer truncated for its stride" }

        val output = ShortArray(width * height)
        return when (format) {
            PackedRawFormat.RAW10 -> decodeRaw10(input, origin, output, width, height, rowStrideBytes)
            PackedRawFormat.RAW12 -> decodeRaw12(input, origin, output, width, height, rowStrideBytes)
        }
    }

    /**
     * Sparse native-code sampler for metering loops: decodes one packed row
     * at a time and caches it, so histograms and peaking pay per visited
     * row instead of per full frame. Throws like the full entry on bad
     * geometry; out-of-range samples throw [IllegalArgumentException].
     */
    class PackedSampleReader(
        source: ByteBuffer,
        val width: Int,
        val height: Int,
        val rowStrideBytes: Int,
        planeOffset: Int = 0,
        val format: PackedRawFormat,
        byteOrder: ByteOrder = ByteOrder.nativeOrder()
    ) {
        private val input = source.duplicate().order(byteOrder)
        private val origin = input.position() + planeOffset
        private var cachedRow = -1
        private val rowCodes = ShortArray(width)

        init {
            require(width > 0 && height > 0) { "Packed plane must be non-empty" }
            require(width % 4 == 0) { "Packed RAW needs width multiple of 4" }
            val packedBytes = format.packedRowBytes(width)
            require(rowStrideBytes >= packedBytes) {
                "Packed row stride $rowStrideBytes holds only $packedBytes bytes"
            }
            val lastRow = origin + (height - 1).toLong() * rowStrideBytes + packedBytes
            require(lastRow <= input.limit().toLong()) { "Packed buffer truncated for its stride" }
        }

        /** Native (unshifted) sample code at packed-plane coordinates. */
        fun codeAt(x: Int, y: Int): Int {
            require(x in 0 until width && y in 0 until height) {
                "Packed sample ($x, $y) outside ${width}x$height"
            }
            if (y != cachedRow) {
                decodeRow(input, origin + y * rowStrideBytes, rowCodes, 0, width, format)
                cachedRow = y
            }
            return rowCodes[x].toInt() and 0xffff
        }
    }

    private fun decodeRow(
        input: ByteBuffer,
        rowBase: Int,
        output: ShortArray,
        outOffset: Int,
        width: Int,
        format: PackedRawFormat
    ) {
        var base = rowBase
        var out = outOffset
        var x = 0
        when (format) {
            // 4 pixels per 5 bytes: top 8 bits in bytes 0..3, low 2 bits
            // shared in byte 4 (P0 in bits 0..1 through P3 in bits 6..7).
            PackedRawFormat.RAW10 -> while (x < width) {
                val b0 = input.get(base).toInt() and 0xff
                val b1 = input.get(base + 1).toInt() and 0xff
                val b2 = input.get(base + 2).toInt() and 0xff
                val b3 = input.get(base + 3).toInt() and 0xff
                val shared = input.get(base + 4).toInt() and 0xff
                // Native codes (unshifted): callers left-justify.
                output[out++] = ((b0 shl 2) or (shared and 0x03)).toShort()
                output[out++] = ((b1 shl 2) or ((shared shr 2) and 0x03)).toShort()
                output[out++] = ((b2 shl 2) or ((shared shr 4) and 0x03)).toShort()
                output[out++] = ((b3 shl 2) or ((shared shr 6) and 0x03)).toShort()
                base += 5
                x += 4
            }
            // 2 pixels per 3 bytes: top 8 bits in bytes 0..1, low nibbles
            // shared in byte 2 (P0 low in bits 0..3, P1 low in bits 4..7).
            PackedRawFormat.RAW12 -> while (x < width) {
                val b0 = input.get(base).toInt() and 0xff
                val b1 = input.get(base + 1).toInt() and 0xff
                val shared = input.get(base + 2).toInt() and 0xff
                output[out++] = ((b0 shl 4) or (shared and 0x0f)).toShort()
                output[out++] = ((b1 shl 4) or ((shared shr 4) and 0x0f)).toShort()
                base += 3
                x += 2
            }
        }
    }

    private fun decodeRaw10(
        input: ByteBuffer,
        origin: Int,
        output: ShortArray,
        width: Int,
        height: Int,
        rowStrideBytes: Int
    ): ShortArray {
        var out = 0
        for (y in 0 until height) {
            decodeRow(input, origin + y * rowStrideBytes, output, out, width, PackedRawFormat.RAW10)
            // Left-justify to 16 bits like the RAW_SENSOR container.
            for (i in out until out + width) output[i] = (output[i].toInt() shl 6).toShort()
            out += width
        }
        return output
    }

    /**
     * RAW14 derivation entry: same geometry rules as the packed formats
     * (width multiple of 4, 7 bytes per 4 pixels). `shiftBits` left-justifies
     * like the main entry; pass 0 to compare native codes against a
     * same-scene `RAW_SENSOR` frame.
     *
     * @throws IllegalArgumentException on odd geometry or truncated buffers.
     */
    internal fun decodeRaw14Plane(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        planeOffset: Int = 0,
        candidate: Raw14Candidate,
        shiftBits: Int = 2,
        byteOrder: ByteOrder = ByteOrder.nativeOrder()
    ): ShortArray {
        require(width > 0 && height > 0) { "Packed plane must be non-empty" }
        require(width % 4 == 0) { "Packed RAW needs width multiple of 4" }
        val packedBytes = width / 4 * 7
        require(rowStrideBytes >= packedBytes) {
            "Packed row stride $rowStrideBytes holds only $packedBytes bytes"
        }
        val input = source.duplicate().order(byteOrder)
        val origin = input.position() + planeOffset
        val lastRow = origin + (height - 1).toLong() * rowStrideBytes + packedBytes
        require(lastRow <= input.limit().toLong()) { "Packed buffer truncated for its stride" }

        val output = ShortArray(width * height)
        var out = 0
        for (y in 0 until height) {
            var base = origin + y * rowStrideBytes
            var x = 0
            while (x < width) {
                val top = IntArray(4) { input.get(base + it).toInt() and 0xff }
                val b4 = input.get(base + 4).toInt() and 0xff
                val b5 = input.get(base + 5).toInt() and 0xff
                val b6 = input.get(base + 6).toInt() and 0xff
                val word = when (candidate) {
                    Raw14Candidate.LSB_FIRST -> b4 or (b5 shl 8) or (b6 shl 16)
                    Raw14Candidate.MSB_FIRST -> b4 or (b5 shl 8) or (b6 shl 16)
                    Raw14Candidate.BYTE_SWAPPED_LSB -> b6 or (b5 shl 8) or (b4 shl 16)
                    Raw14Candidate.BYTE_SWAPPED_MSB -> b6 or (b5 shl 8) or (b4 shl 16)
                }
                val tails = when (candidate) {
                    Raw14Candidate.LSB_FIRST, Raw14Candidate.BYTE_SWAPPED_LSB ->
                        IntArray(4) { (word shr (6 * it)) and 0x3f }
                    Raw14Candidate.MSB_FIRST, Raw14Candidate.BYTE_SWAPPED_MSB ->
                        IntArray(4) { (word shr (6 * (3 - it))) and 0x3f }
                }
                for (i in 0 until 4) {
                    output[out++] = (((top[i] shl 6) or tails[i]) shl shiftBits).toShort()
                }
                base += 7
                x += 4
            }
        }
        return output
    }

    /**
     * Format-aware unpack for retained frames. Takes raw plane geometry
     * (never a [RawPlaneLayout], which rightly refuses packed strides):
     * `RAW_SENSOR` delegates untouched; known packed formats decode to
     * tight 16-bit samples first (left-justified, so existing white-level
     * math holds) and flow through the same normalizer with the same crop
     * coordinates. Unknown formats throw: callers fall back to
     * `RAW_SENSOR` instead.
     */
    fun unpackFrameNormalized(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        format: Int,
        normalization: RawNormalization,
        sensorOriginX: Int = 0,
        sensorOriginY: Int = 0,
        crop: RawCrop = RawCrop(0, 0, width, height),
        byteOrder: ByteOrder = ByteOrder.nativeOrder()
    ): UnpackedRawCfa {
        if (format == android.graphics.ImageFormat.RAW_SENSOR) {
            return RawSensorUnpacker.unpackNormalized(
                source,
                RawPlaneLayout(width, height, rowStride, pixelStride, sensorOriginX, sensorOriginY),
                normalization, crop, byteOrder
            )
        }
        val packed = PackedRawFormat.ofInt(format)
            ?: throw IllegalArgumentException("Unsupported RAW stream format $format")
        val shorts = decodePlaneToShorts(
            source, width, height, rowStride, 0, packed, byteOrder
        )
        val bytes = ByteBuffer.allocateDirect(shorts.size * 2).order(byteOrder)
        bytes.asShortBuffer().put(shorts)
        bytes.rewind()
        val tight = RawPlaneLayout(width, height, width * 2, 2, sensorOriginX, sensorOriginY)
        return RawSensorUnpacker.unpackNormalized(bytes, tight, normalization, crop, byteOrder)
    }

    /**
     * Derivation scoring: fraction of native codes within `toleranceCodes`
     * of the reference frame. Same size required; the probe logs one rate
     * per candidate and a human declares the winner.
     */
    internal fun matchRate(a: ShortArray, b: ShortArray, toleranceCodes: Int): Double {
        require(a.size == b.size) { "Frames must match to score" }
        if (a.isEmpty()) return 0.0
        var hits = 0L
        for (i in a.indices) {
            if (kotlin.math.abs(a[i].toInt() - b[i].toInt()) <= toleranceCodes) hits++
        }
        return hits.toDouble() / a.size
    }

    private fun decodeRaw12(
        input: ByteBuffer,
        origin: Int,
        output: ShortArray,
        width: Int,
        height: Int,
        rowStrideBytes: Int
    ): ShortArray {
        var out = 0
        for (y in 0 until height) {
            decodeRow(input, origin + y * rowStrideBytes, output, out, width, PackedRawFormat.RAW12)
            // Left-justify to 16 bits like the RAW_SENSOR container.
            for (i in out until out + width) output[i] = (output[i].toInt() shl 4).toShort()
            out += width
        }
        return output
    }
}
