// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import java.io.RandomAccessFile

/** Validate the actual TIFF directory before publishing, not just the worker exit status. */
internal object LapisDngValidation {
    fun validate(file: File, width: Int, height: Int) {
        RandomAccessFile(file, "r").use { input ->
            val order = input.readUnsignedShort()
            require(order == 0x4949 || order == 0x4d4d) { "Invalid DNG byte order" }
            val little = order == 0x4949
            fun short(): Int = input.readUnsignedShort().let { if (little) ((it and 255) shl 8) or (it ushr 8) else it }
            fun long(): Long = input.readInt().let { if (little) Integer.reverseBytes(it) else it }.toLong() and 0xffffffffL
            require(short() == 42) { "Expected classic TIFF DNG" }
            val first = long()
            data class Entry(val type: Int, val count: Long, val valueAt: Long, val offset: Long)
            val visited = mutableSetOf<Long>()
            fun directory(offset: Long): Map<Int, Entry> {
                require(offset in 8 until input.length() - 2 && visited.add(offset)) { "Invalid DNG directory" }
                input.seek(offset)
                val count = short()
                require(offset + 2L + count * 12L + 4 <= input.length()) { "Truncated DNG directory" }
                return buildMap {
                    repeat(count) {
                        val tag = short(); val type = short(); val n = long(); val at = input.filePointer; val value = long()
                        put(tag, Entry(type, n, at, value))
                    }
                }
            }
            fun values(entry: Entry): List<Long> {
                val size = when (entry.type) { 1 -> 1; 3 -> 2; 4 -> 4; else -> error("Unexpected DNG field type") }
                require(entry.count in 1..4096) { "Invalid DNG field count" }
                val start = if (entry.count * size <= 4) entry.valueAt else entry.offset
                require(start >= 0 && start + entry.count * size <= input.length()) { "Truncated DNG field" }
                input.seek(start)
                return List(entry.count.toInt()) { when (size) { 1 -> input.readUnsignedByte().toLong(); 2 -> short().toLong(); else -> long() } }
            }
            var raw: Map<Int, Entry>? = null
            var main: Map<Int, Entry> = emptyMap()
            fun visit(offset: Long) {
                val tags = directory(offset)
                if (offset == first) main = tags
                if (tags[262]?.let(::values) == listOf(32803L)) raw = tags
                tags[330]?.let(::values)?.forEach { if (it !in visited) visit(it) }
            }
            visit(first)
            val tags = main + requireNotNull(raw) { "DNG has no CFA image" }
            fun field(tag: Int) = values(requireNotNull(tags[tag]) { "Missing DNG field $tag" })
            fun numbers(tag: Int): List<Double> {
                val entry = requireNotNull(tags[tag]) { "Missing DNG field $tag" }
                if (entry.type !in listOf(5, 10)) return values(entry).map(Long::toDouble)
                require(entry.count in 1..32 && entry.offset + entry.count * 8 <= input.length()) { "Invalid DNG rational field" }
                input.seek(entry.offset)
                return List(entry.count.toInt()) {
                    val numerator = long().let { if (entry.type == 10) it.toInt().toDouble() else it.toDouble() }
                    val denominator = long().let { if (entry.type == 10) it.toInt().toDouble() else it.toDouble() }
                    require(denominator > 0) { "Invalid DNG rational denominator" }
                    numerator / denominator
                }
            }
            require(field(256) == listOf(width.toLong()) && field(257) == listOf(height.toLong())) { "Wrong DNG dimensions" }
            require(field(258) == listOf(16L)) { "Expected an unpacked 16-bit RAW container" }
            require(field(33421) == listOf(2L, 2L) && field(33422) == listOf(1L, 2L, 0L, 1L)) { "Expected normal GBRG CFA" }
            require(field(50717) == listOf(1023L)) { "Unverified DNG white level" }
            require(field(50713) == listOf(2L, 2L) && numbers(50714).let { it.size == 4 && it.all { v -> kotlin.math.abs(v - 64) < 0.001 } }) { "Unverified DNG black levels" }
            require(field(50829) == listOf(0L, 0L, height.toLong(), width.toLong())) { "DNG active area is not the full RAW frame" }
            require(numbers(50719) == listOf(0.0, 0.0) && numbers(50720) == listOf(width.toDouble(), height.toDouble())) { "DNG crop truncates the sensor readout" }
            require(numbers(50728).let { it.size == 3 && it.all { v -> v.isFinite() && v > 0 } }) { "Invalid DNG white balance" }
            require(numbers(50721).let { it.size == 9 && it.all(Double::isFinite) && it.any { v -> v != 0.0 } }) { "Invalid DNG color matrix" }
            require(field(259) == listOf(1L)) { "Expected lossless uncompressed profile DNG" }
            val offsets = field(273); val sizes = field(279)
            require(offsets.size == sizes.size && offsets.indices.all { offsets[it] > 0 && sizes[it] > 0 && offsets[it] + sizes[it] <= input.length() }) { "Truncated DNG image data" }
            require(sizes.sum() == width.toLong() * height * 2) { "DNG does not contain a complete RAW raster" }
        }
    }
}
