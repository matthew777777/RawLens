// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract

/** Recording destination is independent of photo/gyro sidecar permissions. */
internal object RecordingFolderAccess {
    private const val PREFS = "rawlens_recording_folder"
    private const val KEY = "tree_uri"

    fun savedTreeUri(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun writableTreeUri(context: Context): Uri? = savedTreeUri(context)?.takeIf {
        SidecarTreeAccess.hasWriteAccess(context, it)
    }

    fun save(context: Context, uri: Uri, resultFlags: Int): Boolean {
        val flags = resultFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) return false
        return runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            check(SidecarTreeAccess.hasWriteAccess(context, uri))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, uri.toString()).apply()
            true
        }.getOrDefault(false)
    }

    /** Selecting a standard parent puts takes in its RawLens child. */
    fun needsRawLensChild(documentId: String): Boolean =
        documentId.substringAfter(':').trimEnd('/').substringAfterLast('/') in
            setOf("Documents", "DCIM", "Download")

    fun clear(context: Context) {
        // Other features may share this grant: remove only our preference.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun pickerIntent(context: Context): Intent {
        val intent = runCatching {
            context.getSystemService(StorageManager::class.java)?.primaryStorageVolume?.createOpenDocumentTreeIntent()
        }.getOrNull() ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        return intent.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, savedTreeUri(context) ?:
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents"))
        }
    }

    /** Legacy staged-take rescue fallback. Live recording uses DirectRecordingOutput. */
    fun <T> export(preferred: (() -> T)?, fallback: () -> T): T {
        val preferredFailure = if (preferred != null) {
            try { return preferred() } catch (failure: Exception) { failure }
        } else null
        try { return fallback() } catch (failure: Exception) {
            if (preferredFailure != null) failure.addSuppressed(preferredFailure)
            throw failure
        }
    }
}
