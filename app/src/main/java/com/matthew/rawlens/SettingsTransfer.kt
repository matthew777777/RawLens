// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings export/import through the Storage Access Framework
 * (ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT,
 * https://developer.android.com/training/data-storage/shared/documents-files).
 * Serialization lives in [SettingsBackup]; this class owns the pickers and
 * the SharedPreferences writes. Extracted verbatim from MainActivity; the
 * host forwards its activity results to [onExportResult]/[onImportResult].
 */
class SettingsTransfer(
    private val activity: Activity,
    private val exportRequestCode: Int,
    private val importRequestCode: Int,
    private val setStatus: (String) -> Unit,
) {
    /** Serialized settings held while the export picker is open. */
    private var pendingJson: String? = null

    fun export() {
        val json = try {
            SettingsBackup.toJson(SettingsBackup.KNOWN_STORES.associateWith { name ->
                activity.getSharedPreferences(name, Context.MODE_PRIVATE).all
            })
        } catch (_: IllegalArgumentException) {
            setStatus("SETTINGS EXPORT FAILED")
            return
        }
        pendingJson = json
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "rawlens-settings-$stamp.json")
            }
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, exportRequestCode)
        } catch (_: Exception) {
            pendingJson = null
            setStatus("FILE PICKER UNAVAILABLE")
        }
    }

    fun import() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
            }
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, importRequestCode)
        } catch (_: Exception) {
            setStatus("FILE PICKER UNAVAILABLE")
        }
    }

    fun onExportResult(resultCode: Int, data: Intent?) {
        val json = pendingJson
        pendingJson = null
        val uri = data?.data
        if (resultCode == Activity.RESULT_OK && uri != null && json != null) {
            try {
                activity.contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw IOException("cannot open export file")
                setStatus("SETTINGS EXPORTED")
            } catch (_: Exception) {
                setStatus("SETTINGS EXPORT FAILED")
            }
        } else if (resultCode == Activity.RESULT_OK) {
            setStatus("SETTINGS EXPORT FAILED")
        }
    }

    fun onImportResult(resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode == Activity.RESULT_OK && uri != null) {
            try {
                val count = apply(SettingsBackup.fromJson(read(uri)))
                promptRestart(count)
            } catch (_: Exception) {
                setStatus("SETTINGS IMPORT FAILED")
            }
        }
    }

    @Throws(IOException::class)
    private fun read(uri: Uri): String {
        activity.contentResolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buf)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) throw IOException("settings file too large")
                out.write(buf, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        } ?: throw IOException("cannot open settings file")
    }

    /**
     * Replaces every known store with the backup contents. Synchronous commit:
     * the restart prompt must never outrun the write. Returns the restored
     * value count. Names come from [SettingsBackup], never the file, so no
     * crafted backup can touch another preferences file.
     */
    private fun apply(stores: Map<String, Map<String, Any>>): Int {
        var count = 0
        for ((name, entries) in stores) {
            if (name !in SettingsBackup.KNOWN_STORES) continue
            val editor = activity.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((key, value) in entries) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, value as Set<String>)
                    }
                    else -> throw IllegalArgumentException("cannot restore \"$key\"")
                }
                count++
            }
            if (!editor.commit()) throw IOException("cannot save settings")
        }
        return count
    }

    private fun promptRestart(count: Int) {
        AlertDialog.Builder(activity)
            .setTitle("Settings imported")
            .setMessage("$count values restored. Restart to apply them?")
            .setPositiveButton("Restart") { _, _ -> activity.recreate() }
            .setNegativeButton("Later") { _, _ -> setStatus("SETTINGS IMPORTED • RESTART TO APPLY") }
            .setCancelable(false)
            .show()
    }

    private companion object {
        /** Rejects absurd import files before parsing (a real backup is tens of KB). */
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    }
}
