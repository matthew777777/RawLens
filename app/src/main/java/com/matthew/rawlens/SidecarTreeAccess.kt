// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.IOException

/**
 * Same-folder sidecar access via the Storage Access Framework.
 *
 * Scoped storage forbids generic text files under DCIM through
 * MediaStore.Files/Downloads, so [BurstSidecar.writeFiles] falls back to
 * `Download/RawLens/<stem>`. When the user grants a document-tree Uri for
 * the photo folder (ideally `DCIM/RawLens`), this writer places
 * `burst.json` plus the `gyro` CSVs directly under `DCIM/RawLens/<stem>/`,
 * next to the DNGs, with no extra manifest permission (Play-safe).
 *
 * The tree Uri is persisted with takePersistableUriPermission and stored
 * in shared prefs; only [writeViaTree] touches the ContentResolver.
 * [relativeSegments] is pure JVM and host-testable.
 */
object SidecarTreeAccess {
    const val KEY_TREE_URI = "sidecar_tree_uri"
    private const val PREFS_NAME = "rawlens_settings"
    private const val LOG_TAG = "RawLensSidecar"

    fun savedTreeUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        return try {
            Uri.parse(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun hasWriteAccess(context: Context, treeUri: Uri? = savedTreeUri(context)): Boolean {
        if (treeUri == null) return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }
    }

    /** Short human-readable label for settings (`DCIM/RawLens`, …). */
    fun displayPath(treeUri: Uri?): String? {
        if (treeUri == null) return null
        return try {
            DocumentsContract.getTreeDocumentId(treeUri).substringAfter(':', "")
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun saveTreeUri(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Could not persist sidecar tree permission", failure)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE_URI, treeUri.toString())
            .apply()
    }

    fun clearTreeUri(context: Context) {
        val current = savedTreeUri(context)
        if (current != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    current,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_TREE_URI)
            .apply()
    }

    /**
     * Pure path mapping, host-testable. The tree root usually ends at
     * `DCIM/RawLens` (write `<stem>` directly) or `DCIM` (write
     * `RawLens/<stem>`); any other granted folder just receives `<stem>`.
     */
    fun relativeSegments(treePath: String, subfolder: String): List<String> {
        BurstSidecar.requireSubfolder(subfolder)
        return when {
            treePath.endsWith("DCIM/RawLens") || treePath.endsWith("DCIM/RawLens/") ->
                listOf(subfolder)
            treePath.endsWith("DCIM") || treePath.endsWith("DCIM/") ->
                listOf("RawLens", subfolder)
            else -> listOf(subfolder)
        }
    }

    /**
     * Writes `burst.json` plus the per-frame `gyro` CSVs under the granted
     * tree so they land next to the `DCIM/RawLens/<stem>/` DNGs. Throws IOException on failure;
     * the caller falls back to [BurstSidecar.writeFiles] (Downloads) so the
     * burst is never lost.
     */
    @Throws(IOException::class)
    fun writeViaTree(
        context: Context,
        treeUri: Uri,
        subfolder: String,
        metaJson: String,
        gyroCsvByName: Map<String, String>
    ) {
        BurstSidecar.requireSubfolder(subfolder)
        val resolver = context.contentResolver
        val treePath = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (failure: Exception) {
            throw IOException("Unreadable sidecar folder grant", failure)
        }
        try {
            var dirId = treePath
            for (segment in relativeSegments(treePath.substringAfter(':'), subfolder)) {
                dirId = findOrCreateDir(resolver, treeUri, dirId, segment)
            }
            val gyroId = findOrCreateDir(resolver, treeUri, dirId, BurstSidecar.GYRO_DIR)
            for ((csvName, csv) in gyroCsvByName) {
                val display = BurstSidecar.csvDisplayName(csvName)
                writeText(resolver, treeUri, gyroId, display, "text/csv", csv)
            }
            writeText(resolver, treeUri, dirId, BurstSidecar.META_FILE, "application/json", metaJson)
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Same-folder sidecar write failed", failure)
        }
    }

    private fun childUri(treeUri: Uri, parentId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)

    private fun docUri(treeUri: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun findChildId(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentId: String,
        displayName: String
    ): String? {
        resolver.query(
            childUri(treeUri, parentId),
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

    private fun findOrCreateDir(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentId: String,
        name: String
    ): String {
        findChildId(resolver, treeUri, parentId, name)?.let { return it }
        val created = DocumentsContract.createDocument(
            resolver, docUri(treeUri, parentId),
            DocumentsContract.Document.MIME_TYPE_DIR, name
        ) ?: throw IOException("Could not create sidecar dir $name")
        return DocumentsContract.getDocumentId(created)
    }

    private fun writeText(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentId: String,
        displayName: String,
        mime: String,
        text: String
    ) {
        var fileId = findChildId(resolver, treeUri, parentId, displayName)
        val fileUri = if (fileId != null) {
            docUri(treeUri, fileId)
        } else {
            val created = DocumentsContract.createDocument(
                resolver, docUri(treeUri, parentId), mime, displayName
            ) ?: throw IOException("Could not create sidecar file $displayName")
            created
        }
        try {
            resolver.openOutputStream(fileUri, "w")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Could not open sidecar stream $displayName")
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Could not write sidecar $displayName", failure)
        }
    }
}
