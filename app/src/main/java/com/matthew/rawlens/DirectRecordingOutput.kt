// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException

/** One destination file, held open from before camera start through finalization. No copy. */
internal class DirectRecordingOutput private constructor(
    private val context: Context,
    val uri: Uri,
    val name: String,
    val descriptor: ParcelFileDescriptor,
    private val mediaStore: Boolean
) : Closeable {
    private var finished = false
    val fileDescriptor get() = descriptor.fileDescriptor
    fun bytes(): Long = Os.fstat(fileDescriptor).st_size
    fun freeBytes(): Long = Os.fstatvfs(fileDescriptor).let { stat ->
        val blocks = stat.f_bavail.coerceAtLeast(0)
        val unit = (if (stat.f_frsize > 0) stat.f_frsize else stat.f_bsize).coerceAtLeast(1)
        if (blocks > Long.MAX_VALUE / unit) Long.MAX_VALUE else blocks * unit
    }

    /** Only called after both encoder/container writers have stopped. Failed nonempty takes stay put. */
    @Synchronized fun finish(valid: Boolean): Boolean {
        if (finished) return false
        finished = true
        val size = runCatching { bytes() }.getOrDefault(-1L)
        return RecordingCompletion.finish(valid, size,
            sync = { Os.fsync(fileDescriptor) },
            close = { descriptor.close() },
            publish = {
                if (mediaStore) {
                    val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    if (context.contentResolver.update(uri, values, null, null) != 1) {
                        throw IOException("Could not publish recording at $uri")
                    }
                }
            },
            deleteEmpty = { deleteCreatedFile(context, uri, mediaStore) })
    }

    override fun close() { finish(false) }

    companion object {
        fun create(context: Context, name: String, mime: String): DirectRecordingOutput {
            require(name.isNotBlank() && '/' !in name && '\\' !in name)
            val app = context.applicationContext
            val resolver = app.contentResolver
            val selected = RecordingFolderAccess.savedTreeUri(app)
            val mediaStore = selected == null
            val uri = if (selected != null) {
                if (!SidecarTreeAccess.hasWriteAccess(app, selected)) {
                    throw IOException("Recording folder unavailable; reconnect drive or choose another folder")
                }
                val parent = recordingParent(app, selected)
                DocumentsContract.createDocument(resolver, parent, mime, name)
                    ?: throw IOException("Could not create recording in selected folder")
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/RawLens")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Could not create recording in Downloads/RawLens")
            }
            var descriptor: ParcelFileDescriptor? = null
            try {
                descriptor = resolver.openFileDescriptor(uri, "rw")
                    ?: throw IOException("Could not open recording destination")
                requireSeekable(descriptor)
                Os.ftruncate(descriptor.fileDescriptor, 0)
                return DirectRecordingOutput(app, uri, name, descriptor, mediaStore)
            } catch (e: Exception) {
                try { descriptor?.close() } catch (_: Exception) {}
                deleteCreatedFile(app, uri, mediaStore)
                throw IOException("Cannot record directly here: ${e.message}", e)
            }
        }

        internal fun requireSeekable(descriptor: ParcelFileDescriptor) {
            try { Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET) }
            catch (e: Exception) { throw IOException("Choose a local or USB folder that supports seeking", e) }
        }

        private fun deleteCreatedFile(context: Context, uri: Uri, mediaStore: Boolean) {
            try {
                if (mediaStore) context.contentResolver.delete(uri, null, null)
                else DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (_: Exception) { /* A disconnected drive may be unavailable. */ }
        }

        private fun recordingParent(context: Context, tree: Uri): Uri {
            val resolver = context.contentResolver
            val id = DocumentsContract.getTreeDocumentId(tree)
            if (!RecordingFolderAccess.needsRawLensChild(id)) {
                return DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "RawLens" &&
                        cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    }
                }
            }
            return DocumentsContract.createDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, id),
                DocumentsContract.Document.MIME_TYPE_DIR, "RawLens")
                ?: throw IOException("Could not create RawLens recording folder")
        }
    }
}
