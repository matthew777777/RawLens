// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TIFF field list for the pinned TinyDNG writer ([TinyDngImageWriter]):
 * tag payloads in target byte order plus the (tag, type, count) descriptor
 * triples the JNI bridge consumes. The writer owns all IFD layout (sorting,
 * offsets, strips, sub-IFDs); this builder only serializes values.
 *
 * Rational encoding is overflow-safe: numerators run on 64-bit math and the
 * fraction reduces before the int32 range check, so large magnitudes
 * (DefaultCropSize 4080x3060) encode exactly instead of clamping to
 * INT_MAX/10^6 — the clamped form is what RawSpeed rejects with "Error
 * decoding default crop size". Small values keep their exact 10^6-based
 * fractions after gcd reduction (same value, fewer bytes).
 */
class TiffFields {
    /** TIFF field types carried by merged-DNG tags. */
    companion object {
        const val BYTE = 1
        const val ASCII = 2
        const val SHORT = 3
        const val LONG = 4
        const val RATIONAL = 5
        const val UNDEFINED = 7
        const val SRATIONAL = 10
        const val DOUBLE = 12

        /** Unit size for a carried type; 0 rejects anything else. */
        fun unitSize(type: Int): Int = when (type) {
            BYTE, ASCII, UNDEFINED -> 1
            SHORT -> 2
            LONG -> 4
            RATIONAL, SRATIONAL, DOUBLE -> 8
            else -> 0
        }

        /**
         * Overflow-safe (numerator, denominator) for one rational value:
         * 10^6 precision on 64-bit math, gcd-reduced, denominator shrunk
         * (never below 1) until the numerator fits int32. Unsigned callers
         * clamp negatives to 0; NaN/Infinity fail loudly.
         */
        fun rationalPair(value: Double, signed: Boolean): Pair<Int, Int> {
            require(value.isFinite()) { "Rational value must be finite" }
            val v = if (signed) value else value.coerceAtLeast(0.0)
            var den = 1_000_000L
            var num = Math.round(v * den)
            val g = gcd(abs(num), den)
            num /= g
            den /= g
            val lo = if (signed) Int.MIN_VALUE.toLong() else 0L
            while ((num > Int.MAX_VALUE || num < lo) && den > 1) {
                // Halve the precision; round the numerator to stay nearest.
                num = Math.floorDiv(num + 1, 2)
                den = den / 2
                if (den < 1) den = 1
            }
            if (num > Int.MAX_VALUE || num < lo) {
                // Unrepresentable magnitude (denominator already 1): clamp
                // rather than emit a wrapped numerator no reader can decode.
                num = num.coerceIn(lo, Int.MAX_VALUE.toLong())
            }
            return num.toInt() to den.toInt()
        }

        private fun abs(v: Long): Long = if (v == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(v)

        private fun gcd(a: Long, b: Long): Long {
            var x = abs(a)
            var y = abs(b)
            while (y != 0L) {
                val t = x % y
                x = y
                y = t
            }
            return if (x == 0L) 1L else x
        }
    }

    val descriptors = ArrayList<Int>()
    val payloads = ArrayList<ByteArray>()

    val isEmpty: Boolean get() = payloads.isEmpty()

    fun tag(id: Int, type: Int, bytes: ByteArray) {
        val unit = unitSize(type)
        require(unit > 0) { "Unsupported TIFF field type $type" }
        require(bytes.isNotEmpty() && bytes.size % unit == 0) {
            "Field $id payload size ${bytes.size} is not a type-$type multiple"
        }
        descriptors.addAll(listOf(id, type, bytes.size / unit))
        payloads.add(bytes)
    }

    fun bytes(id: Int, values: ByteArray) = tag(id, BYTE, values.copyOf())

    fun undefined(id: Int, values: ByteArray) = tag(id, UNDEFINED, values.copyOf())

    fun shorts(id: Int, vararg values: Int) = tag(id, SHORT,
        buffer(values.size * 2).apply { values.forEach { putShort(it.toShort()) } }.array())

    fun longs(id: Int, vararg values: Int) = tag(id, LONG,
        buffer(values.size * 4).apply { values.forEach { putInt(it) } }.array())

    fun ascii(id: Int, value: String) =
        tag(id, ASCII, (value + '\u0000').toByteArray(Charsets.US_ASCII))

    fun rationals(id: Int, values: DoubleArray) = tag(id, RATIONAL,
        buffer(values.size * 8).apply {
            values.forEach { rationalPair(it, signed = false).let { (n, d) -> putInt(n).putInt(d) } }
        }.array())

    fun srationals(id: Int, values: DoubleArray) = tag(id, SRATIONAL,
        buffer(values.size * 8).apply {
            values.forEach { rationalPair(it, signed = true).let { (n, d) -> putInt(n).putInt(d) } }
        }.array())

    fun doubles(id: Int, values: DoubleArray) = tag(id, DOUBLE,
        buffer(values.size * 8).apply { values.forEach(::putDouble) }.array())

    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
