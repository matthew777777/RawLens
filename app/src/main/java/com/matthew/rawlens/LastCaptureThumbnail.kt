// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import java.util.concurrent.Executors

/**
 * Bottom-panel gallery seat: the newest finished RawLens capture (photo or
 * video under DCIM/RawLens) as a thumbnail, refreshed whenever MediaStore
 * changes. Every saver publishes through MediaStore with IS_PENDING, so one
 * observer covers DNG, developed JPEG/JXL, CRS and Direct-Log without
 * touching the capture paths. Captures exported only to a SAF tree are not
 * indexed and never show here.
 */
class LastCaptureThumbnail(
    context: Context,
    private val onChanged: (uri: Uri?, mime: String?, thumb: Bitmap?) -> Unit,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var lastUri: Uri? = null

    var uri: Uri? = null
        private set
    var mime: String? = null
        private set

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun start() {
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        refresh()
    }

    fun stop() {
        resolver.unregisterContentObserver(observer)
    }

    fun release() {
        stop()
        io.shutdownNow()
    }

    fun refresh() {
        if (io.isShutdown) return
        io.execute {
            val newest = listOfNotNull(
                newest(MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
                newest(MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
            ).maxByOrNull { it.added }
            if (newest?.uri == lastUri) return@execute
            val thumb = newest?.let {
                runCatching { resolver.loadThumbnail(it.uri, Size(THUMB_PX, THUMB_PX), null) }.getOrNull()
            }
            lastUri = newest?.uri
            main.post {
                uri = newest?.uri
                mime = newest?.mime
                onChanged(newest?.uri, newest?.mime, thumb)
            }
        }
    }

    /** Opens the newest capture in the system viewer; false when none or no app handles it. */
    fun open(context: Context): Boolean {
        val target = uri ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(target, mime ?: "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private data class Item(val uri: Uri, val mime: String?, val added: Long)

    private fun newest(collection: Uri): Item? = runCatching {
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf("DCIM/RawLens/%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            Item(ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1), c.getLong(2))
        }
    }.getOrNull()

    private companion object {
        const val THUMB_PX = 192
    }
}
