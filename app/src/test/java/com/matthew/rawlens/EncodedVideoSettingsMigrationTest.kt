// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class EncodedVideoSettingsMigrationTest {
    private val previous = byteArrayOf(100, 105, 114, 101, 99, 116, 95, 108, 111, 103).toString(Charsets.US_ASCII)

    @Test fun settingsRetainValuesAndTypesUnderCurrentNames() {
        val migrated = EncodedVideoSettingsMigration.normalizeEntries(mapOf(
            "${previous}_video" to true, "${previous}_fps" to 24,
            "${previous}_profile" to "SLOG3", "${previous}_res" to "QHD1440",
            "${previous}_ev_v2" to -.5f, "unrelated" to setOf("a", "b")))
        assertEquals(true, migrated["encoded_video_enabled"])
        assertEquals(24, migrated["encoded_video_fps"])
        assertEquals("SLOG3", migrated["encoded_video_profile"])
        assertEquals("QHD1440", migrated["encoded_video_res"])
        assertEquals(-.5f, migrated["encoded_video_ev_v2"])
        assertEquals(setOf("a", "b"), migrated["unrelated"])
        assertFalse(migrated.keys.any { it.startsWith(previous) })
    }

    @Test fun currentChoicesWinAndMigrationIsIdempotent() {
        val migrated = EncodedVideoSettingsMigration.normalizeEntries(mapOf(
            "${previous}_fps" to 24, "encoded_video_fps" to 30,
            "${previous}_video" to true, "encoded_video_enabled" to false))
        assertEquals(mapOf("encoded_video_fps" to 30, "encoded_video_enabled" to false), migrated)
        assertEquals(migrated, EncodedVideoSettingsMigration.normalizeEntries(migrated))
    }

    @Test fun previousSettingsBackupsImportWithCurrentKeys() {
        val json = SettingsBackup.toJson(mapOf(SettingsBackup.STORE_MAIN to mapOf(
            "${previous}_video" to true, "${previous}_crop" to "SCOPE_2_39")))
        val restored = SettingsBackup.fromJson(json).getValue(SettingsBackup.STORE_MAIN)
        assertEquals(mapOf("encoded_video_enabled" to true, "encoded_video_crop" to "SCOPE_2_39"), restored)
    }
}
