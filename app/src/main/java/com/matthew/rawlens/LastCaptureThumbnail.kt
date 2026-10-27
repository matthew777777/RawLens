// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.util.concurrent.Executors

/**
 * Bottom-panel gallery seat: the newest finished RawLens capture (photo or
 * video under DCIM/RawLens) as a thumbnail, refreshed whenever MediaStore
 * changes. Every saver publishes through MediaStore with IS_PENDING, so one
 * observer covers DNG, developed JPEG/JXL, CRAW and Direct-Log without
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
    private var lastHadThumb = false

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
            // Newest first across photos and videos; show the first one that
            // actually yields a picture. A capture with no thumbnail (a DNG the
            // platform thumbnailer skips, an CRAW) never turns the seat grey.
            val candidates = (recent(MediaStore.Images.Media.EXTERNAL_CONTENT_URI) +
                recent(MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
                .sortedByDescending { it.added }
            if (candidates.firstOrNull()?.uri == lastUri && lastHadThumb) return@execute
            var shown: Item? = null
            var thumb: Bitmap? = null
            for (item in candidates) {
                thumb = loadPreview(item)
                if (thumb != null) {
                    shown = item
                    break
                }
            }
            if (shown == null) shown = candidates.firstOrNull()
            lastUri = candidates.firstOrNull()?.uri
            lastHadThumb = thumb != null
            main.post {
                uri = shown?.uri
                mime = shown?.mime
                onChanged(shown?.uri, shown?.mime, thumb)
            }
        }
    }

    /**
     * Platform thumbnail, falling back to a downscaled full decode (DNG via
     * ImageDecoder), turned upright from the capture's EXIF orientation:
     * DNG thumbnails come back in sensor (landscape) order.
     */
    private fun loadPreview(item: Item): Bitmap? {
        val isImage = item.mime?.startsWith("image/") == true
        val platform = runCatching { resolver.loadThumbnail(item.uri, Size(THUMB_PX, THUMB_PX), null) }.getOrNull()
        val bitmap = platform ?: if (isImage) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
                    val scale = maxOf(1, minOf(info.size.width, info.size.height) / THUMB_PX)
                    decoder.setTargetSize(info.size.width / scale, info.size.height / scale)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        } else null
        bitmap ?: return null
        if (!isImage) return bitmap
        val degrees = orientationDegrees(item.uri)
        // A source already turned upright (JPEG thumbnails usually are) is
        // left alone: 90/270 only rotate a still-landscape bitmap, 180 only
        // applies to the formats the platform passes through raw (DNG).
        val rotate = when (degrees) {
            90, 270 -> bitmap.width > bitmap.height
            180 -> item.mime != "image/jpeg"
            else -> false
        }
        if (!rotate) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun orientationDegrees(uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).rotationDegrees
        } ?: 0
    }.getOrDefault(0)

    /** Opens the newest capture in the system viewer; false when none or no app handles it. */
    fun open(context: Context): Boolean {
        val target = uri ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(target, mime ?: "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private data class Item(val uri: Uri, val mime: String?, val added: Long)

    private fun recent(collection: Uri): List<Item> = runCatching {
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf("DCIM/RawLens%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC",
        )?.use { c ->
            val items = ArrayList<Item>()
            while (items.size < CANDIDATES && c.moveToNext()) {
                items += Item(ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1), c.getLong(2))
            }
            items
        }
    }.getOrNull().orEmpty()

    private companion object {
        const val THUMB_PX = 192
        /** Newest items per collection tried before giving up on a preview. */
        const val CANDIDATES = 8
    }
}
