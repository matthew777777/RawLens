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
    private val lensProfiles: Boolean = false,
    private val setStatus: (String) -> Unit,
) {
    /** Serialized settings held while the export picker is open. */
    private var pendingBytes: ByteArray? = null

    fun export() {
        val bytes = try {
            val stores = (if (lensProfiles) LensProfilesBackup.stores else SettingsBackup.KNOWN_STORES)
                .associateWith { name -> activity.getSharedPreferences(name, Context.MODE_PRIVATE).all }
            if (lensProfiles) LensProfilesBackup.encode(stores)
            else SettingsBackup.toJson(stores).toByteArray(Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            setStatus(if (lensProfiles) "LENS PROFILE EXPORT FAILED" else "SETTINGS EXPORT FAILED")
            return
        }
        pendingBytes = bytes
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (lensProfiles) "application/zip" else "application/json"
                putExtra(Intent.EXTRA_TITLE, if (lensProfiles) "rawlens-lens-profiles-$stamp.zip" else "rawlens-settings-$stamp.json")
            }
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, exportRequestCode)
        } catch (_: Exception) {
            pendingBytes = null
            setStatus("FILE PICKER UNAVAILABLE")
        }
    }

    fun import() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, if (lensProfiles) arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                    else arrayOf("application/json", "text/plain"))
            }
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, importRequestCode)
        } catch (_: Exception) {
            setStatus("FILE PICKER UNAVAILABLE")
        }
    }

    fun onExportResult(resultCode: Int, data: Intent?) {
        val bytes = pendingBytes
        pendingBytes = null
        val uri = data?.data
        if (resultCode == Activity.RESULT_OK && uri != null && bytes != null) {
            try {
                activity.contentResolver.openOutputStream(uri)?.use {
                    it.write(bytes)
                } ?: throw IOException("cannot open export file")
                setStatus(if (lensProfiles) "LENS PROFILES EXPORTED" else "SETTINGS EXPORTED")
            } catch (_: Exception) {
                setStatus(if (lensProfiles) "LENS PROFILE EXPORT FAILED" else "SETTINGS EXPORT FAILED")
            }
        } else if (resultCode == Activity.RESULT_OK) {
            setStatus(if (lensProfiles) "LENS PROFILE EXPORT FAILED" else "SETTINGS EXPORT FAILED")
        }
    }

    fun onImportResult(resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode == Activity.RESULT_OK && uri != null) {
            try {
                if (lensProfiles) {
                    val stores = activity.contentResolver.openInputStream(uri)?.use(LensProfilesBackup::decode)
                        ?: throw IOException("cannot open profiles")
                    RawLensDialogBuilder(activity)
                        .setTitle("Replace lens profiles?")
                        .setMessage("Replace all current lens profiles, vendor keys and saved presets, DNG calibration overrides, PROGRAM exposure profiles, virtual lenses, RAW size/format choices and saved lens selection with this ZIP? Camera IDs must match this device. The app will reload to apply the imported profiles.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Replace and reload") { _, _ ->
                            try {
                                apply(stores)
                                activity.recreate()
                            } catch (_: Exception) {
                                setStatus("LENS PROFILE IMPORT FAILED")
                            }
                        }.show()
                } else {
                    val count = apply(SettingsBackup.fromJson(read(uri)))
                    promptRestart(count)
                }
            } catch (_: Exception) {
                setStatus(if (lensProfiles) "LENS PROFILE IMPORT FAILED" else "SETTINGS IMPORT FAILED")
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
        if (!lensProfiles) return writeStores(stores)
        val original = LensProfilesBackup.stores.associateWith { name ->
            activity.getSharedPreferences(name, Context.MODE_PRIVATE).all
                .filterValues { it != null }.mapValues { it.value!! }
                .filterKeys { name != SettingsBackup.STORE_MAIN || it in LensProfilesBackup.selectionKeys }
        }
        return try {
            writeStores(stores)
        } catch (failure: Exception) {
            try { writeStores(original) } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private fun writeStores(stores: Map<String, Map<String, Any>>): Int {
        var count = 0
        for ((name, entries) in stores) {
            if (name !in (if (lensProfiles) LensProfilesBackup.stores else SettingsBackup.KNOWN_STORES)) continue
            val prefs = activity.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (lensProfiles && name == SettingsBackup.STORE_MAIN) {
                LensProfilesBackup.selectionKeys.forEach { editor.remove(it) }
            } else editor.clear()
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
        RawLensDialogBuilder(activity)
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
