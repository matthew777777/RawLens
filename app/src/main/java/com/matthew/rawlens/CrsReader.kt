// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader hook for CRS takes (RawLens in-house container), used by the
 * offline burst merge. No CRS backend is linked yet, so [available] is
 * false and burst merge reports itself unavailable; implement the
 * reader functions below to enable it.
 *
 * Threading: one reader per merge thread; all calls below run on that
 * single thread. Negative codes become exceptions.
 */
internal object CrsReader {
    /** True once a CRS backend is linked in. */
    val available: Boolean = false

    data class TakeInfo(
        val timestamps: LongArray,
        val containerJson: String
    )

    fun open(path: String): Long {
        if (!available) throw IllegalStateException("CRS reader unavailable")
        val handle = try {
            openReader(path)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("CRS reader missing", e)
        }
        if (handle == 0L) throw IllegalStateException("ContainerReader failed to open $path")
        return handle
    }

    fun inspect(file: File): TakeInfo {
        val handle = open(file.absolutePath)
        try {
            val timestamps = frameTimestamps(handle)
                ?: throw IllegalStateException("frame index unreadable: ${file.name}")
            val container = containerMetadata(handle)
                ?: throw IllegalStateException("container metadata unreadable: ${file.name}")
            return TakeInfo(timestamps, container)
        } finally {
            closeReader(handle)
        }
    }

    /**
     * Decodes one frame's pixels (row-major uint16 Bayer) into
     * [destination] (position advanced by the pixel byte count).
     * Returns width × height.
     */
    fun decodeFrame(handle: Long, timestamp: Long, destination: ByteBuffer): Pair<Int, Int> {
        require(destination.isDirect) { "CrsReader needs a direct buffer" }
        val meta = frameMetadata(handle, timestamp)
            ?: throw IllegalStateException("frame metadata unreadable ts=$timestamp")
        val json = org.json.JSONObject(meta)
        val width = json.getInt("width")
        val height = json.getInt("height")
        val need = width.toLong() * height * 2
        require(need <= destination.remaining()) {
            "Destination holds ${destination.remaining()}, needs $need"
        }
        val dstOffset = destination.position()
        val written = decodeFrame(handle, timestamp, destination, dstOffset, destination.remaining())
        if (written < 0) throw IllegalStateException("decodeFrame failed: code=$written ts=$timestamp")
        destination.position(dstOffset + written)
        return width to height
    }

    /**
     * Visible dimensions of the take's first frame, or null when the
     * take cannot be opened. Listing helper; merge paths decode fully.
     */
    fun dimsOf(file: File): Pair<Int, Int>? {
        val handle = try {
            open(file.absolutePath)
        } catch (_: Exception) {
            return null
        }
        return try {
            val timestamps = frameTimestamps(handle) ?: return null
            val first = timestamps.firstOrNull() ?: return null
            val meta = frameMetadata(handle, first) ?: return null
            val json = org.json.JSONObject(meta)
            json.getInt("width") to json.getInt("height")
        } catch (_: Exception) {
            null
        } finally {
            runCatching { closeReader(handle) }
        }
    }

    private fun unavailable(): Nothing =
        throw UnsupportedOperationException("CRS reader not implemented yet")

    fun openReader(path: String): Long = unavailable()
    fun closeReader(handle: Long): Unit = unavailable()
    fun containerMetadata(handle: Long): String? = unavailable()
    fun frameTimestamps(handle: Long): LongArray? = unavailable()
    fun frameMetadata(handle: Long, timestamp: Long): String? = unavailable()
    fun decodeFrame(
        handle: Long, timestamp: Long, dst: ByteBuffer, dstOffset: Int, dstCapacity: Int
    ): Int = unavailable()
}
