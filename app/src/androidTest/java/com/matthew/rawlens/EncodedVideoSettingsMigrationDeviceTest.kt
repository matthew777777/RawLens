// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EncodedVideoSettingsMigrationDeviceTest {
    @Test fun persistedModeLutAndCrashMarkerSurviveNamespaceUpdate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = byteArrayOf(100, 105, 114, 101, 99, 116, 95, 108, 111, 103).toString(Charsets.US_ASCII)
        val names = listOf(SettingsBackup.STORE_MAIN, "${previous}_monitor", "encoded_video_monitor")
        val backups = names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        val markerNames = listOf(previous.replace("_", "") + "_breadcrumb.txt", "encoded_video_breadcrumb.txt")
        val markers = markerNames.associateWith { name -> File(context.filesDir, name).takeIf { it.exists() }?.readBytes() }
        try {
            val main = context.getSharedPreferences(SettingsBackup.STORE_MAIN, Context.MODE_PRIVATE)
            assertTrue(main.edit().clear().putBoolean("${previous}_video", true)
                .putInt("${previous}_fps", 24).putInt("encoded_video_fps", 30)
                .putString("${previous}_profile", "SLOG3").putFloat("${previous}_ev_v2", .75f).commit())
            assertTrue(context.getSharedPreferences("encoded_video_monitor", Context.MODE_PRIVATE).edit().clear().commit())
            assertTrue(context.getSharedPreferences("${previous}_monitor", Context.MODE_PRIVATE).edit().clear()
                .putString("selected", "fixture.cube").putBoolean("bake_recording", true)
                .putFloat("intensity_fixture.cube", .6f).commit())
            File(context.filesDir, markerNames[1]).delete()
            File(context.filesDir, markerNames[0]).writeText("123 first-frame")

            EncodedVideoSettingsMigration.migrate(context)

            assertTrue(main.getBoolean("encoded_video_enabled", false))
            assertEquals(30, main.getInt("encoded_video_fps", 0))
            assertEquals("SLOG3", main.getString("encoded_video_profile", null))
            assertEquals(.75f, main.getFloat("encoded_video_ev_v2", 0f), 0f)
            assertFalse(main.all.keys.any { it.startsWith(previous) })
            val library = EncodedVideoLutLibrary(context)
            assertEquals("fixture.cube", library.selected)
            assertTrue(library.bakeIntoRecording)
            assertEquals(.6f, library.intensity, 0f)
            assertEquals("123 first-frame", File(context.filesDir, markerNames[1]).readText())
            assertFalse(File(context.filesDir, markerNames[0]).exists())
            EncodedVideoSettingsMigration.migrate(context)
            assertEquals(30, main.getInt("encoded_video_fps", 0))
        } finally {
            backups.forEach { (name, entries) ->
                restore(context.getSharedPreferences(name, Context.MODE_PRIVATE), entries)
            }
            markers.forEach { (name, content) ->
                val file = File(context.filesDir, name)
                if (content == null) file.delete() else file.writeBytes(content)
            }
        }
    }

    private fun restore(preferences: SharedPreferences, entries: Map<String, *>) {
        val editor = preferences.edit().clear()
        entries.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
            }
        }
        check(editor.commit())
    }
}
