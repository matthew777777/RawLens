// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Retains existing mode settings, LUT selections and crash markers across the namespace update. */
internal object EncodedVideoSettingsMigration {
    // Historical on-disk namespace identifier, kept as migration data rather
    // than as a retired API/name in the current source namespace.
    private val previousNamespace = byteArrayOf(0x64, 0x69, 0x72, 0x65, 0x63, 0x74, 0x5f, 0x6c, 0x6f, 0x67)
        .toString(Charsets.US_ASCII)
    private const val CURRENT = "encoded_video"

    private fun currentKey(key: String): String {
        if (!key.startsWith(previousNamespace + "_")) return key
        val suffix = key.removePrefix(previousNamespace + "_")
        return CURRENT + "_" + if (suffix == "video") "enabled" else suffix
    }

    /** Current values win when both generations are present. Also used on backup import. */
    fun <T> normalizeEntries(entries: Map<String, T>): Map<String, T> = buildMap {
        entries.forEach { (key, value) -> if (currentKey(key) == key) put(key, value) }
        entries.forEach { (key, value) -> if (currentKey(key) != key) putIfAbsent(currentKey(key), value) }
    }

    fun migrate(context: Context) {
        val main = context.getSharedPreferences(SettingsBackup.STORE_MAIN, Context.MODE_PRIVATE)
        val before = main.all
        val normalized = normalizeEntries(before)
        if (normalized != before) write(main, normalized)

        val previousStore = previousNamespace + "_monitor"
        val previousFile = File(context.applicationInfo.dataDir, "shared_prefs/$previousStore.xml")
        if (previousFile.exists() || File(previousFile.path + ".bak").exists()) {
            val old = context.getSharedPreferences(previousStore, Context.MODE_PRIVATE)
            val current = context.getSharedPreferences(CURRENT + "_monitor", Context.MODE_PRIVATE)
            write(current, old.all + current.all)
            // Delete only after the destination has been synchronously committed.
            context.deleteSharedPreferences(previousStore)
        }

        val previousMarker = File(context.filesDir, previousNamespace.replace("_", "") + "_breadcrumb.txt")
        val currentMarker = File(context.filesDir, CURRENT + "_breadcrumb.txt")
        if (previousMarker.exists() && !currentMarker.exists()) {
            check(previousMarker.renameTo(currentMarker)) { "Could not migrate recording crash marker" }
        }
    }

    private fun write(preferences: SharedPreferences, entries: Map<String, *>) {
        val editor = preferences.edit().clear()
        entries.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    require(value.all { it is String })
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                null -> editor.remove(key)
                else -> error("Unsupported settings value for $key")
            }
        }
        check(editor.commit()) { "Could not commit migrated video settings" }
    }
}
