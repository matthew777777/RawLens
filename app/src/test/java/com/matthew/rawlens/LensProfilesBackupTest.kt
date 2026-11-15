// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class LensProfilesBackupTest {
    @Test fun roundTripsAllLensStoresAndVendorPayloadWithoutUnrelatedSettings() {
        val vendor = """{"request":{"vendor.int64":9223372036854775807},"session":{"vendor.mode":14}}"""
        val original = LensProfilesBackup.stores.associateWith { name ->
            when (name) {
                SettingsBackup.STORE_MAIN -> mapOf("selected_lens_ids" to setOf("0", "virtual:test"),
                    "lens_setup_complete" to true, "video_crop" to "unrelated")
                VendorKeyStore.PREFS_NAME -> mapOf("profile_0" to vendor, "preset_Custom" to vendor)
                else -> mapOf("profile_0" to "{\"value\":123}")
            }
        }
        val restored = LensProfilesBackup.decode(ByteArrayInputStream(LensProfilesBackup.encode(original)))
        assertEquals(LensProfilesBackup.stores, restored.keys)
        assertEquals(original[VendorKeyStore.PREFS_NAME], restored[VendorKeyStore.PREFS_NAME])
        assertEquals(setOf("0", "virtual:test"), restored.getValue(SettingsBackup.STORE_MAIN)["selected_lens_ids"])
        assertFalse(restored.getValue(SettingsBackup.STORE_MAIN).containsKey("video_crop"))
        original.filterKeys { it != SettingsBackup.STORE_MAIN }.forEach { (name, values) ->
            assertEquals(values, restored[name])
        }
    }

    @Test fun emptyArchiveRepresentsReplacingAllProfilesWithDefaults() {
        val restored = LensProfilesBackup.decode(ByteArrayInputStream(LensProfilesBackup.encode(emptyMap())))
        assertEquals(LensProfilesBackup.stores, restored.keys)
        assertTrue(restored.values.all { it.isEmpty() })
    }

    @Test fun rejectsIncompleteArchiveAndWrongValueTypes() {
        rejects(archive(SettingsBackup.toJson(emptyMap())))
        val values = LensProfilesBackup.stores.associateWith { emptyMap<String, Any>() }.toMutableMap()
        values[VendorKeyStore.PREFS_NAME] = mapOf("profile_0" to 12)
        rejects(archive(SettingsBackup.toJson(values)))
        values[VendorKeyStore.PREFS_NAME] = emptyMap()
        values[SettingsBackup.STORE_MAIN] = mapOf("video_crop" to "unrelated")
        rejects(archive(SettingsBackup.toJson(values)))
    }

    @Test fun rejectsInvalidExtraAndOversizedEntries() {
        rejects(byteArrayOf(1, 2, 3))
        rejects(archive("{}", "../profiles.json"))
        rejects(archive(" ".repeat(2 * 1024 * 1024 + 1)))
        rejects(archive("{}", extra = true))
    }

    private fun archive(json: String, name: String = "rawlens-lens-profiles-v1.json", extra: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use {
                it.putNextEntry(ZipEntry(name)); it.write(json.toByteArray()); it.closeEntry()
                if (extra) { it.putNextEntry(ZipEntry("unexpected")); it.closeEntry() }
            }
        }.toByteArray()

    private fun rejects(bytes: ByteArray) {
        try {
            LensProfilesBackup.decode(ByteArrayInputStream(bytes))
            fail("Expected invalid archive to be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
