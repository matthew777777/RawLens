// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Bounded, single-entry archive; nothing is extracted to the filesystem. */
internal object LensProfilesBackup {
    val stores = setOf(
        SettingsBackup.STORE_MAIN, DngMetadataOverrideStore.PREFS_NAME,
        ProgramAeProfileStore.PREFS_NAME, VendorKeyStore.PREFS_NAME,
        VirtualLensStore.PREFS_NAME, RawSizePreferenceStore.PREFS_NAME,
        RawFormatPreferenceStore.PREFS_NAME
    )
    val selectionKeys = setOf("selected_lens_ids", "lens_setup_complete")
    private const val ENTRY = "rawlens-lens-profiles-v1.json"
    private const val LIMIT = 2 * 1024 * 1024

    fun encode(values: Map<String, Map<String, *>>): ByteArray {
        val filtered = stores.associateWith { name ->
            val entries = values[name].orEmpty()
            if (name == SettingsBackup.STORE_MAIN) entries.filterKeys { it in selectionKeys } else entries
        }
        val json = SettingsBackup.toJson(filtered).toByteArray(Charsets.UTF_8)
        require(json.size <= LIMIT) { "Lens profiles are too large" }
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(ENTRY))
                zip.write(json)
                zip.closeEntry()
            }
        }.toByteArray()
    }

    fun decode(input: InputStream): Map<String, Map<String, Any>> = ZipInputStream(input).use { zip ->
        require(zip.nextEntry?.name == ENTRY) { "Not a RawLens lens profile ZIP" }
        val bytes = zip.readNBytesCompat(LIMIT + 1)
        require(bytes.size <= LIMIT) { "Lens profiles are too large" }
        zip.closeEntry()
        require(zip.nextEntry == null) { "Unexpected ZIP entries" }
        val values = SettingsBackup.fromJson(bytes.toString(Charsets.UTF_8), stores)
        require(values.keys == stores) { "Incomplete lens profile archive" }
        values.forEach { (name, entries) ->
            entries.forEach { (key, value) ->
                require(if (name == SettingsBackup.STORE_MAIN) {
                    when (key) {
                        "selected_lens_ids" -> value is Set<*>
                        "lens_setup_complete" -> value is Boolean
                        else -> false
                    }
                } else value is String) { "Invalid lens profile value" }
            }
        }
        values
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
