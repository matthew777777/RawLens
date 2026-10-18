// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.ContentResolver
import android.provider.MediaStore
import java.io.File

/**
 * Merges-screen listing: staged `_BURST.crs` takes only, never RAW
 * video. Rows carry footer frame counts plus decoded dimensions when
 * the native reader is present; without it (or on a corrupt take) the
 * row still lists with dimensions unknown rather than disappearing.
 */
internal data class BurstTakeRow(
    val file: File,
    val frameCount: Int,
    val fileBytes: Long,
    val lastModified: Long,
    val width: Int?,
    val height: Int?
)

internal object BurstMergeTakes {
    fun loadStage(stageDir: File): List<BurstTakeRow> {
        val takes = BurstCrsStore.listTakes(stageDir)
        if (!CrsReader.available) return takes.map { it.toRow(null) }
        return takes.map { take ->
            take.toRow(runCatching { CrsReader.dimsOf(take.file) }.getOrNull())
        }
    }

    /**
     * Published burst takes in `DCIM/RawLens/Bursts`, or 0 when the
     * library is unreadable. Staged takes are the merge source; this
     * count is inventory only.
     */
    fun publishedCount(resolver: ContentResolver): Int {
        return try {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("%_BURST.crs"),
                null
            )?.use { cursor -> cursor.count } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Evenly spaced pool indices over [size] frames capped at [max]:
     * live-memory parity for offline merges (the engine input stays the
     * same size as an immediate merge). Returns all indices when the
     * take already fits.
     */
    fun evenSampleIndices(size: Int, max: Int): List<Int> {
        require(size >= 0 && max >= 0) { "Negative pool size $size/$max" }
        if (size <= max || max == 0) return (0 until size).toList()
        if (max == 1) return listOf(size / 2)
        return List(max) { k -> (k.toLong() * (size - 1) / (max - 1)).toInt() }
    }

    private fun BurstCrsStore.BurstTake.toRow(dims: Pair<Int, Int>?): BurstTakeRow =
        BurstTakeRow(file, frameCount, fileBytes, lastModified, dims?.first, dims?.second)
}
