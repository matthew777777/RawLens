// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cinematic RAW Studio (.CRAW) container constants and the closed-take check
 * used by staging/rescue (spec: CinematicRAWStudio-Spec/SPEC.md section 1).
 *
 * A take starts with a 16-byte header (`CRAW`, u16 major, u16 minor, ...) and a
 * cleanly closed take ends with an INDX chunk plus a 16-byte trailer
 * (u64 INDX offset, `CRAWEND\0`). A take without a trailer is a crashed
 * recording: readers recover it, staging never publishes it.
 */
internal object CrawFormat {
    const val MAGIC = "CRAW"
    const val VERSION_MAJOR = 0
    const val HEADER_SIZE = 16
    const val TRAILER_SIZE = 16
    const val TRAILER_MAGIC = "CRAWEND\u0000"
    private const val CHUNK_HEADER = 16
    private const val INDEX_ENTRY = 24

    /** Frame count of a cleanly closed take (valid header, trailer and INDX), else null. */
    fun closedFrameCount(file: File): Int? {
        val length = file.length()
        if (length < HEADER_SIZE + CHUNK_HEADER + 4 + TRAILER_SIZE) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = read(raf, 0, HEADER_SIZE) ?: return null
                if (ascii(head, 4) != MAGIC || head.getShort(4).toInt() != VERSION_MAJOR) return null
                val tail = read(raf, length - TRAILER_SIZE, TRAILER_SIZE) ?: return null
                val indexOffset = tail.long
                if (ascii(tail, 8, 8) != TRAILER_MAGIC) return null
                if (indexOffset < HEADER_SIZE || indexOffset > length - TRAILER_SIZE - CHUNK_HEADER - 4) return null
                val chunk = read(raf, indexOffset, CHUNK_HEADER + 4) ?: return null
                if (ascii(chunk, 4) != "INDX") return null
                val size = chunk.getLong(8)
                val count = chunk.getInt(CHUNK_HEADER)
                if (count < 0 || size != 4L + count.toLong() * INDEX_ENTRY ||
                    indexOffset + CHUNK_HEADER + size + 4 != length - TRAILER_SIZE) return null
                val entries = read(raf, indexOffset + CHUNK_HEADER + 4, count * INDEX_ENTRY) ?: return null
                var frames = 0
                for (i in 0 until count) if (ascii(entries, 4, i * INDEX_ENTRY) == "FRAM") frames++
                frames.takeIf { it > 0 }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isClosed(file: File): Boolean = closedFrameCount(file) != null

    /**
     * Upper bound of one codec payload for a [width] x [height] frame: the
     * encoder stores a tile raw whenever coding would not be smaller, so a
     * payload never exceeds 2 bytes/sample plus headers and the tile table.
     */
    fun payloadBound(width: Int, height: Int): Int {
        val tiles = ((width / 2 + 127) / 128).toLong() * ((height / 2 + 127) / 128)
        return (width.toLong() * height * 2 + tiles * 9 + 65536)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** Minimal closed take with [frames] indexed FRAM entries (host tests and fixtures). */
    internal fun closedStub(frames: Int, prefix: ByteArray = ByteArray(0)): ByteArray {
        val index = ByteBuffer.allocate(4 + frames * INDEX_ENTRY).order(ByteOrder.LITTLE_ENDIAN)
        index.putInt(frames)
        repeat(frames) { index.put("FRAM".toByteArray(Charsets.US_ASCII)).putInt(0).putLong(HEADER_SIZE.toLong()).putLong(it.toLong()) }
        val indexOffset = HEADER_SIZE + prefix.size
        val out = ByteBuffer.allocate(indexOffset + CHUNK_HEADER + index.capacity() + 4 + TRAILER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.put(MAGIC.toByteArray(Charsets.US_ASCII)).putShort(VERSION_MAJOR.toShort()).putShort(2)
            .putInt(0).putInt(0)
        out.put(prefix)
        out.put("INDX".toByteArray(Charsets.US_ASCII)).putInt(0).putLong(index.capacity().toLong())
        out.put(index.array()).putInt(0)
        out.putLong(indexOffset.toLong()).put(TRAILER_MAGIC.toByteArray(Charsets.US_ASCII))
        return out.array()
    }

    private fun read(raf: RandomAccessFile, offset: Long, bytes: Int): ByteBuffer? {
        if (bytes < 0) return null
        val buf = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        raf.channel.position(offset)
        while (buf.hasRemaining()) if (raf.channel.read(buf) < 0) return null
        buf.flip()
        return buf
    }

    private fun ascii(buf: ByteBuffer, length: Int, at: Int = 0): String =
        String(ByteArray(length) { buf.get(at + it) }, Charsets.US_ASCII)
}
