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

/** Publishes CRAW as a binary download; private originals survive copy/publish failures.
 * MediaStore.Video rejects this custom container on Xiaomi and other providers.
 */
internal object RawVideoSaver {
    data class Saved(val uri: Uri, val sourceRemoved: Boolean)

    /**
     * Closed takes eligible for export retry/rescue, oldest first: non-empty
     * `.craw` files in [stageDir] with a valid container footer, excluding
     * the actively recording [active] file. Unfinalized (footerless) takes
     * are never published. Pure filesystem scan, host-testable.
     */
    fun stagedTakes(stageDir: File, active: File?): List<File> {
        val files = stageDir.listFiles() ?: return emptyList()
        val activePath = active?.absolutePath
        return files.filter { file ->
            file.isFile && file.extension == "craw" && file.length() > 0 &&
                file.absolutePath != activePath && hasValidFooter(file) &&
                !BurstCrawStore.isBurstFile(file)
        }.sortedBy { it.lastModified() }
    }

    /** True when the file is a cleanly closed .CRAW take (trailer + index). */
    private fun hasValidFooter(file: File): Boolean = CrawFormat.isClosed(file)

    fun save(resolver: ContentResolver, source: File): Saved {
        val expected = source.length()
        require(source.isFile && expected > 0) { "Missing or empty RAW video" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/RawLens")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create RAW video in Download/RawLens")
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
            values.put(MediaStore.Downloads.IS_PENDING, 0)
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

    /**
     * Publishes to the selected recording folder. Standard Documents/DCIM/
     * Download parents receive a RawLens child; custom folders are used directly.
     * Deletes the staged source only after a verified durable copy like [save].
     */
    fun saveViaTree(context: Context, treeUri: Uri, source: File): Saved {
        val expected = source.length()
        require(source.isFile && expected > 0) { "Missing or empty RAW video" }
        if (!SidecarTreeAccess.hasWriteAccess(context, treeUri)) {
            throw IOException("No write access to granted folder")
        }
        val resolver = context.contentResolver
        val treePath = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (failure: Exception) {
            throw IOException("Unreadable video folder grant", failure)
        }
        // Standard parent selections receive a RawLens child.
        var dirId = treePath
        if (RecordingFolderAccess.needsRawLensChild(treePath)) {
            dirId = findOrCreateDir(resolver, treeUri, dirId, "RawLens")
        }
        val fileUri = try {
            DocumentsContract.createDocument(
                resolver, buildDocUri(treeUri, dirId),
                "application/octet-stream", source.name
            ) ?: throw IOException("Could not create RAW video in granted folder")
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Could not create RAW video in granted folder", failure)
        }
        try {
            // Kernel-assisted copy like [save]; a userspace byte pump is far
            // too slow for multi-GB takes.
            val descriptor = resolver.openFileDescriptor(fileUri, "w")
                ?: throw IOException("Could not open granted-folder destination")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                FileInputStream(source).use { input ->
                    val copied = FileUtils.copy(input.fd, output.fd)
                    if (copied != expected || source.length() != expected) {
                        throw IOException("Incomplete RAW video copy ($copied/$expected)")
                    }
                }
                output.fd.sync()
                if (descriptor.statSize != expected) throw IOException("RAW video size mismatch")
            }
        } catch (failure: Exception) {
            try { DocumentsContract.deleteDocument(resolver, fileUri) } catch (_: Exception) {
            }
            if (failure is IOException) throw failure
            throw IOException("Granted-folder RAW video copy failed", failure)
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
