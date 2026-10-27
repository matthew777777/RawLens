// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.net.Uri
import android.system.Os
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Append capture directories without relocating image data, opcodes, calibration,
 * previews, or unknown vendor tags. The TIFF header switches to the new IFD last.
 * Works for both DngCreator and TinyDNG, including JPEG XL strips.
 */
internal object DngCaptureMetadata {
    fun apply(context: Context, uri: Uri, metadata: RawFrameMetadata, timeMillis: Long, gps: GpsLocation?) {
        context.contentResolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
            val fd = descriptor.fileDescriptor
            append(Os.fstat(fd).st_size, { offset, count ->
                ByteArray(count).also { bytes ->
                    var done = 0
                    while (done < count) {
                        val n = Os.pread(fd, bytes, done, count - done, offset + done)
                        require(n > 0) { "Truncated DNG metadata" }
                        done += n
                    }
                }
            }, { offset, bytes ->
                var done = 0
                while (done < bytes.size) {
                    val n = Os.pwrite(fd, bytes, done, bytes.size - done, offset + done)
                    require(n > 0) { "Could not append DNG metadata" }
                    done += n
                }
            }, metadata, timeMillis, gps)
        }
    }

    internal fun append(
        originalSize: Long,
        read: (Long, Int) -> ByteArray,
        write: (Long, ByteArray) -> Unit,
        metadata: RawFrameMetadata,
        timeMillis: Long,
        gps: GpsLocation?
    ) {
        require(originalSize in 8..0xffff0000L) { "Unsupported DNG size" }
        val header = read(0, 8)
        val order = when (header.take(2)) {
            listOf(73.toByte(), 73.toByte()) -> ByteOrder.LITTLE_ENDIAN
            listOf(77.toByte(), 77.toByte()) -> ByteOrder.BIG_ENDIAN
            else -> error("Invalid TIFF byte order")
        }
        fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(order)
        require(buffer(header).getShort(2).toInt() == 42) { "Only classic TIFF is supported" }
        fun uint(b: ByteBuffer, offset: Int) = b.getInt(offset).toLong() and 0xffffffffL
        data class Directory(val entries: MutableMap<Int, ByteArray>, val next: Int)
        fun directory(offset: Long): Directory {
            if (offset == 0L) return Directory(sortedMapOf(), 0)
            require(offset in 8..originalSize - 2)
            val count = buffer(read(offset, 2)).short.toInt() and 65535
            require(count <= 4096 && offset + 6 + count * 12L <= originalSize)
            val bytes = read(offset + 2, count * 12 + 4)
            val entries = sortedMapOf<Int, ByteArray>()
            repeat(count) { i ->
                val entry = bytes.copyOfRange(i * 12, i * 12 + 12)
                val tag = buffer(entry).short.toInt() and 65535
                require(entries.put(tag, entry) == null) { "Duplicate TIFF tag $tag" }
            }
            return Directory(entries, buffer(bytes).getInt(count * 12))
        }
        val root = directory(uint(buffer(header), 4))
        var end = (originalSize + 1) and -2L
        fun appendBytes(bytes: ByteArray): Long {
            val start = end
            require(start + bytes.size < 0xffffffffL)
            write(start, bytes)
            end = (start + bytes.size + 1) and -2L
            return start
        }
        fun merge(dir: Directory, fields: TiffFields) {
            fields.payloads.forEachIndexed { i, source ->
                val tag = fields.descriptors[i * 3]
                val type = fields.descriptors[i * 3 + 1]
                val count = fields.descriptors[i * 3 + 2]
                val bytes = source.copyOf()
                // Rational payloads are pairs of 32-bit integers, not 64-bit scalars.
                val unit = when (type) { 3 -> 2; 4, 5, 10 -> 4; 12 -> 8; else -> 1 }
                if (order == ByteOrder.BIG_ENDIAN && unit > 1) {
                    for (j in bytes.indices step unit) bytes.reverse(j, j + unit)
                }
                val entry = ByteBuffer.allocate(12).order(order)
                    .putShort(tag.toShort()).putShort(type.toShort()).putInt(count)
                if (bytes.size <= 4) entry.put(bytes) else entry.putInt(appendBytes(bytes).toInt())
                dir.entries[tag] = entry.array()
            }
        }
        fun emit(dir: Directory): Long {
            require(dir.entries.size <= 65535)
            val out = ByteBuffer.allocate(6 + dir.entries.size * 12).order(order)
            out.putShort(dir.entries.size.toShort())
            dir.entries.toSortedMap().values.forEach { out.put(it) }
            out.putInt(dir.next)
            return appendBytes(out.array())
        }
        fun subdirectory(tag: Int, fields: TiffFields?) {
            if (fields == null || fields.isEmpty) return
            val old = root.entries[tag]?.let {
                val b = buffer(it)
                require(b.getShort(2).toInt() == 4 && b.getInt(4) == 1)
                uint(b, 8)
            } ?: 0L
            val dir = directory(old)
            merge(dir, fields)
            merge(root, TiffFields().apply { longs(tag, emit(dir).toInt()) })
        }
        subdirectory(34665, DngExifDirectory.fields(metadata, timeMillis))
        subdirectory(34853, gps?.let(GpsTiffDirectory::fields))
        merge(root, TiffFields().apply {
            ascii(306, DngExifDirectory.dateTime(timeMillis))
            ascii(305, "RawLens")
            // This names the embedded Camera2 matrices, not a vendor look/profile.
            if (50721 in root.entries && 50936 !in root.entries) {
                ascii(50936, "RawLens Camera2 calibration")
            }
        })
        val newRoot = emit(root)
        write(4, ByteBuffer.allocate(4).order(order).putInt(newRoot.toInt()).array())
    }
}
