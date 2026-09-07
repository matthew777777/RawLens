// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-FileCopyrightText: 2010-2026 darktable developers
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.Build
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Streaming 32-bit IEEE-float CFA DNG writer, adapted from darktable's HDR DNG writer. */
object FloatCfaDngWriter {
    private const val BYTE = 1
    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val SRATIONAL = 10

    fun write(output: OutputStream, cfa: UnpackedRawCfa, metadata: RawFrameMetadata) {
        cfa.requireAmazeCompatible()
        require(cfa.values.size == cfa.width * cfa.height)
        require(cfa.values.all(Float::isFinite)) { "Float DNG cannot contain NaN or infinity" }

        val neutral = metadata.neutralColorPoint?.toDoubleArray()
            ?.takeIf { it.size >= 3 } ?: doubleArrayOf(1.0, 1.0, 1.0)
        val matrix = metadata.colorMatrix1?.toDoubleArray()
            ?.takeIf { it.size == 9 } ?: IDENTITY
        val cameraName = "${Build.MANUFACTURER} ${Build.MODEL} (${metadata.cameraId})"
        require(metadata.exifOrientation in 1..8)
        val entries = arrayListOf(
            Entry(254, LONG, longs(0)),
            Entry(256, LONG, longs(cfa.width)), Entry(257, LONG, longs(cfa.height)),
            Entry(258, SHORT, shorts(32)), Entry(259, SHORT, shorts(1)),
            Entry(262, SHORT, shorts(32803)),
            Entry(273, LONG, ByteArray(4), patchStripOffset = true),
            Entry(274, SHORT, shorts(metadata.exifOrientation)),
            Entry(277, SHORT, shorts(1)), Entry(278, LONG, longs(cfa.height)),
            Entry(279, LONG, longs(Math.multiplyExact(cfa.width * cfa.height, Float.SIZE_BYTES))),
            Entry(284, SHORT, shorts(1)), Entry(305, ASCII, ascii("RawLens")),
            Entry(339, SHORT, shorts(3)),
            Entry(33421, SHORT, shorts(2, 2)), Entry(33422, BYTE, cfaPattern(cfa.pattern)),
            Entry(50706, BYTE, byteArrayOf(1, 4, 0, 0)),
            Entry(50707, BYTE, byteArrayOf(1, 4, 0, 0)),
            Entry(50708, ASCII, ascii(cameraName)),
            Entry(50713, SHORT, shorts(2, 2)),
            Entry(50714, RATIONAL, rationals(doubleArrayOf(0.0, 0.0, 0.0, 0.0), signed = false)),
            Entry(50717, LONG, longs(1)),
            Entry(50721, SRATIONAL, rationals(matrix, signed = true)),
            Entry(50728, RATIONAL, rationals(neutral, signed = false)),
            Entry(50778, SHORT, shorts(metadata.referenceIlluminant1 ?: 21))
        )
        // Preserve the full camera colour calibration. Omitting calibration/forward matrices
        // while retaining AsShotNeutral can change the rendering in external RAW developers.
        fun matrixTag(tag: Int, values: ImmutableDoubleValues?) {
            values?.toDoubleArray()?.let {
                require(it.size == 9 && it.all(Double::isFinite))
                entries += Entry(tag, SRATIONAL, rationals(it, signed = true))
            }
        }
        matrixTag(50723, metadata.cameraCalibration1)
        matrixTag(50964, metadata.forwardMatrix1)
        if (metadata.colorMatrix2 != null && metadata.referenceIlluminant2 != null) {
            matrixTag(50722, metadata.colorMatrix2)
            matrixTag(50724, metadata.cameraCalibration2)
            matrixTag(50965, metadata.forwardMatrix2)
            entries += Entry(50779, SHORT, shorts(metadata.referenceIlluminant2))
        }
        entries.sortBy { it.tag }

        val ifdBytes = 2 + entries.size * 12 + 4
        var dataOffset = 8 + ifdBytes
        val externalOffsets = HashMap<Int, Int>()
        entries.forEachIndexed { index, entry ->
            if (entry.payload.size > 4) {
                externalOffsets[index] = dataOffset
                dataOffset += entry.payload.size + (entry.payload.size and 1)
            }
        }
        val stripOffset = dataOffset
        val header = ByteBuffer.allocate(stripOffset).order(ByteOrder.LITTLE_ENDIAN)
        header.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        header.putShort(entries.size.toShort())
        entries.forEachIndexed { index, entry ->
            header.putShort(entry.tag.toShort()).putShort(entry.type.toShort())
            header.putInt(entry.count)
            when {
                entry.patchStripOffset -> header.putInt(stripOffset)
                entry.payload.size <= 4 -> {
                    header.put(entry.payload)
                    repeat(4 - entry.payload.size) { header.put(0) }
                }
                else -> header.putInt(requireNotNull(externalOffsets[index]))
            }
        }
        header.putInt(0)
        entries.forEachIndexed { index, entry -> if (externalOffsets.containsKey(index)) {
            header.position(requireNotNull(externalOffsets[index]))
            header.put(entry.payload)
            if (entry.payload.size and 1 == 1) header.put(0)
        } }
        output.write(header.array())

        val row = ByteBuffer.allocate(cfa.width * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until cfa.height) {
            row.clear()
            val start = y * cfa.width
            for (x in 0 until cfa.width) row.putFloat(cfa.values[start + x].coerceAtLeast(0f))
            output.write(row.array())
        }
    }

    private data class Entry(
        val tag: Int, val type: Int, val payload: ByteArray, val patchStripOffset: Boolean = false
    ) {
        val count: Int = payload.size / when (type) {
            SHORT -> 2; LONG -> 4; RATIONAL, SRATIONAL -> 8; else -> 1
        }
    }

    private fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()
    private fun longs(vararg values: Int) = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()
    private fun ascii(value: String) = (value + '\u0000').toByteArray(Charsets.US_ASCII)
    private fun rationals(values: DoubleArray, signed: Boolean): ByteArray =
        ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { value ->
                val denominator = 1_000_000
                val numerator = (value * denominator).roundToInt()
                putInt(if (signed) numerator else numerator.coerceAtLeast(0)).putInt(denominator)
            }
        }.array()
    private fun cfaPattern(pattern: BayerPattern) = ByteArray(4) { index -> when (
        pattern.colorAt(index and 1, index shr 1)) {
        CfaColor.RED -> 0; CfaColor.GREEN -> 1; CfaColor.BLUE -> 2
    } }
    private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
}
