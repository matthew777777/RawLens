// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer

/** Sparse first-frame diagnostics. Absolute reads never change camera/codec buffer positions. */
internal object EncodedVideoDiagnostics {
    const val COLUMNS = 8
    const val ROWS = 4
    fun shouldSample(sequence: Long) = sequence in 0L..1L

    data class Plane(val bytes: ByteBuffer, val rowStride: Int, val pixelStride: Int) {
        fun word(x: Int, y: Int): Int {
            require(rowStride > 0 && pixelStride >= 2)
            val offset = bytes.position().toLong() + y.toLong() * rowStride + x.toLong() * pixelStride
            require(offset >= bytes.position() && offset + 2 <= bytes.limit()) { "sample outside plane" }
            // Android RAW16 and P010 words are little endian, irrespective of ByteBuffer.order().
            return (bytes.get(offset.toInt()).toInt() and 255) or
                ((bytes.get(offset.toInt() + 1).toInt() and 255) shl 8)
        }

        fun describe() = "row=$rowStride px=$pixelStride pos=${bytes.position()} " +
            "limit=${bytes.limit()} capacity=${bytes.capacity()}"
    }

    /** Even coordinates, including the last complete Bayer/chroma quad. */
    fun points(width: Int, height: Int): List<Pair<Int, Int>> {
        require(width >= 2 && height >= 2)
        return (0 until ROWS).flatMap { gy ->
            (0 until COLUMNS).map { gx ->
                ((width - 2) * gx / (COLUMNS - 1) and -2) to
                    ((height - 2) * gy / (ROWS - 1) and -2)
            }
        }
    }

    fun rawQuads(plane: Plane, left: Int, top: Int, width: Int, height: Int): IntArray {
        require(left >= 0 && top >= 0)
        return points(width, height).flatMap { (x, y) ->
            listOf(plane.word(left + x, top + y), plane.word(left + x + 1, top + y),
                plane.word(left + x, top + y + 1), plane.word(left + x + 1, top + y + 1))
        }.toIntArray()
    }

    fun p010(planes: List<Plane>, width: Int, height: Int): IntArray {
        require(planes.size >= 3)
        return points(width, height).flatMap { (x, y) ->
            listOf(planes[0].word(x, y), planes[1].word(x / 2, y / 2),
                planes[2].word(x / 2, y / 2))
        }.toIntArray()
    }

    fun describeP010(words: IntArray): String =
        "lowBitsNonzero=${words.count { it and 63 != 0 }} codes10=${words.joinToString(",") { (it ushr 6).toString() }}"

    fun compare(expected: IntArray, actual: IntArray): String {
        require(expected.size == actual.size)
        return "checked=${expected.size} mismatches=${expected.indices.count { expected[it] != actual[it] }} " +
            "maxWordDelta=${expected.indices.maxOfOrNull { kotlin.math.abs(expected[it] - actual[it]) } ?: 0}"
    }
}
