// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.FileUtils
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** Publishes a closed MCRAW; the private original survives any copy/publish failure. */
internal object RawVideoSaver {
    data class Saved(val uri: Uri, val sourceRemoved: Boolean)

    fun save(resolver: ContentResolver, source: File): Saved {
        val expected = source.length()
        require(source.isFile && expected > 0) { "Missing or empty RAW video" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/x-mcraw")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/RawLens")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create RAW video in DCIM/RawLens")
        try {
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Could not open RAW video destination")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                FileInputStream(source).use { input ->
                    // Android selects kernel-assisted copying for regular file descriptors.
                    val copied = FileUtils.copy(input.fd, output.fd)
                    if (copied != expected || source.length() != expected) {
                        throw IOException("Incomplete RAW video copy ($copied/$expected)")
                    }
                }
                output.fd.sync()
                if (descriptor.statSize != expected) throw IOException("RAW video size mismatch")
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("Could not publish RAW video")
            }
        } catch (failure: Exception) {
            try { resolver.delete(uri, null, null) } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        // Publication and durable copy succeeded. Never remove the source before this point.
        return Saved(uri, source.delete())
    }
}
