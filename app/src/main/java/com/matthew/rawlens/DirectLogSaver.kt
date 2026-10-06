// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.FileUtils
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** Publishes a closed Direct-Log MP4; mirrors [RawVideoSaver] for video/mp4. */
internal object DirectLogSaver {
    data class Saved(val uri: Uri, val sourceRemoved: Boolean)

    fun save(resolver: ContentResolver, source: File): Saved {
        val expected = source.length()
        require(source.isFile && expected > 0) { "Missing or empty Direct-Log video" }
        require(source.extension == "mp4") { "Not an MP4 take: ${source.name}" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/RawLens")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create Direct-Log video in DCIM/RawLens")
        try {
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Could not open Direct-Log destination")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                FileInputStream(source).use { input ->
                    val copied = FileUtils.copy(input.fd, output.fd)
                    if (copied != expected || source.length() != expected) {
                        throw IOException("Incomplete Direct-Log copy ($copied/$expected)")
                    }
                }
                output.fd.sync()
                if (descriptor.statSize != expected) throw IOException("Direct-Log size mismatch")
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("Could not publish Direct-Log video")
            }
        } catch (failure: Exception) {
            try { resolver.delete(uri, null, null) } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        return Saved(uri, source.delete())
    }

    /**
     * SAF fallback reusing the burst sidecar tree grant, used only when
     * MediaStore.Video rejects the file on some OEMs.
     */
    fun saveViaTree(context: Context, treeUri: Uri, source: File): Saved {
        val expected = source.length()
        require(source.isFile && expected > 0) { "Missing or empty Direct-Log video" }
        if (!SidecarTreeAccess.hasWriteAccess(context, treeUri)) {
            throw IOException("No write access to granted folder")
        }
        val resolver = context.contentResolver
        val treePath = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (failure: Exception) {
            throw IOException("Unreadable video folder grant", failure)
        }
        val suffix = treePath.substringAfter(':', "")
        var dirId = treePath
        if (suffix == "DCIM" || suffix == "DCIM/" || suffix.endsWith("/DCIM") ||
            suffix.endsWith("/DCIM/")
        ) {
            dirId = findOrCreateDir(resolver, treeUri, dirId, "RawLens")
        }
        val fileUri = try {
            DocumentsContract.createDocument(
                resolver, buildDocUri(treeUri, dirId),
                "video/mp4", source.name
            ) ?: throw IOException("Could not create Direct-Log video in granted folder")
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Could not create Direct-Log video in granted folder", failure)
        }
        try {
            val descriptor = resolver.openFileDescriptor(fileUri, "w")
                ?: throw IOException("Could not open granted-folder destination")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                FileInputStream(source).use { input ->
                    val copied = FileUtils.copy(input.fd, output.fd)
                    if (copied != expected || source.length() != expected) {
                        throw IOException("Incomplete Direct-Log copy ($copied/$expected)")
                    }
                }
                output.fd.sync()
                if (descriptor.statSize != expected) throw IOException("Direct-Log size mismatch")
            }
        } catch (failure: Exception) {
            try { DocumentsContract.deleteDocument(resolver, fileUri) } catch (_: Exception) {
            }
            if (failure is IOException) throw failure
            throw IOException("Granted-folder Direct-Log copy failed", failure)
        }
        return Saved(fileUri, source.delete())
    }

    private fun buildDocUri(treeUri: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun findOrCreateDir(
        resolver: ContentResolver,
        treeUri: Uri,
        parentId: String,
        name: String,
    ): String {
        findChildId(resolver, treeUri, parentId, name)?.let { return it }
        val created = DocumentsContract.createDocument(
            resolver, buildDocUri(treeUri, parentId),
            DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw IOException("Could not create video dir $name")
        return DocumentsContract.getDocumentId(created)
    }

    private fun findChildId(
        resolver: ContentResolver,
        treeUri: Uri,
        parentId: String,
        displayName: String,
    ): String? {
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idCol < 0 || nameCol < 0) return null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == displayName) return cursor.getString(idCol)
            }
        }
        return null
    }
}
