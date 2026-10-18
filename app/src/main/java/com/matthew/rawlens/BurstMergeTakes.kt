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

/** A published `_BURST.crs` from the shared library (no local path). */
internal data class PublishedTake(
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val uri: android.net.Uri
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
     * Published burst takes (`DCIM/RawLens/Bursts`, SAF fallback alike),
     * oldest first. These have no local path, so the merge copies the
     * selected one back into the stage dir first ([restage]).
     */
    fun loadPublished(
        resolver: ContentResolver,
        uriFor: (Long) -> android.net.Uri = { id ->
            android.net.Uri.withAppendedPath(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()
            )
        }
    ): List<PublishedTake> {
        return try {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED
                ),
                "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("%_BURST.crs"),
                "${MediaStore.Video.Media.DATE_MODIFIED} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                if (idCol < 0 || nameCol < 0) return emptyList()
                val out = ArrayList<PublishedTake>()
                while (cursor.moveToNext()) {
                    val name = runCatching { cursor.getString(nameCol) }.getOrNull()
                        ?: continue
                    if (!name.uppercase(java.util.Locale.US).endsWith("_BURST.CRS")) continue
                    val id = runCatching { cursor.getLong(idCol) }.getOrNull() ?: continue
                    val size = if (sizeCol >= 0) {
                        runCatching { cursor.getLong(sizeCol) }.getOrDefault(0L)
                    } else 0L
                    val modifiedSecs = if (dateCol >= 0) {
                        runCatching { cursor.getLong(dateCol) }.getOrDefault(0L)
                    } else 0L
                    out += PublishedTake(
                        displayName = name,
                        sizeBytes = size,
                        lastModified = modifiedSecs * 1000,
                        uri = uriFor(id)
                    )
                }
                out
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Copies a published take back into [stageDir] so the file-based
     * reader and merge can use it. Reuses an identical staged copy when
     * one already exists. The published original is kept.
     */
    fun restage(resolver: ContentResolver, published: PublishedTake, stageDir: File): File {
        require(published.displayName.uppercase(java.util.Locale.US).endsWith("_BURST.CRS")) {
            "Not a burst take: ${published.displayName}"
        }
        if (!stageDir.exists() && !stageDir.mkdirs()) {
            throw java.io.IOException("Could not create burst stage")
        }
        val staged = File(stageDir, published.displayName)
        if (staged.isFile && published.sizeBytes > 0 && staged.length() == published.sizeBytes &&
            BurstCrsStore.listTakes(stageDir).any { it.file.absolutePath == staged.absolutePath }
        ) {
            return staged
        }
        (resolver.openInputStream(published.uri)
            ?: throw java.io.IOException("Could not open ${published.displayName}")).use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        }
        if (!staged.isFile || staged.length() <= 0) {
            throw java.io.IOException("Restage produced an empty file")
        }
        return staged
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
