// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.util.Log

/** Per-lens vendor-key storage. Mirrors [DngMetadataOverrideStore] keying. */
class VendorKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val loaded = object : LinkedHashMap<String, Pair<String?, VendorKeyProfile>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String?, VendorKeyProfile>>): Boolean = size > 64
    }

    @Synchronized fun get(cameraId: String?): VendorKeyProfile =
        cameraId?.let { readProfile(key(it)) } ?: VendorKeyProfile()

    @Synchronized private fun readProfile(key: String): VendorKeyProfile {
        val json = try {
            preferences.getString(key, null)
        } catch (failure: ClassCastException) {
            Log.w("VendorKeyStore", "Invalid stored profile type $key", failure)
            return VendorKeyProfile()
        }
        loaded[key]?.takeIf { it.first == json }?.let { return it.second }
        val profile = try {
            VendorKeyProfile.fromJson(json)
        } catch (failure: IllegalArgumentException) {
            Log.w("VendorKeyStore", "Invalid stored profile $key; leaving it on disk for recovery", failure)
            VendorKeyProfile()
        }
        loaded[key] = json to profile
        return profile
    }

    fun hasProfile(cameraId: String?): Boolean =
        cameraId != null && preferences.contains(key(cameraId))

    @Synchronized fun save(cameraId: String, profile: VendorKeyProfile) {
        profile.validate()
        preferences.edit().putString(key(cameraId), profile.toJson()).apply()
    }

    @Synchronized fun clear(cameraId: String) = preferences.edit().remove(key(cameraId)).apply()

    /** Named user presets, shareable across lenses. Never deleted by lens reset. */
    fun listPresets(): List<String> = preferences.all.keys
        .filter { it.startsWith(PRESET_PREFIX) }
        .map { it.removePrefix(PRESET_PREFIX) }
        .filter { runCatching { cleanPresetName(it) }.isSuccess }
        .sorted()

    fun getPreset(name: String): VendorKeyProfile = readProfile(presetKey(cleanPresetName(name)))

    /** Read/modify/write under one lock; callers never save a stale dialog snapshot. */
    @Synchronized fun update(cameraId: String, edit: (VendorKeyProfile) -> VendorKeyProfile): VendorKeyProfile {
        val updated = edit(get(cameraId))
        save(cameraId, updated)
        return updated
    }

    fun savePreset(name: String, profile: VendorKeyProfile) {
        profile.validate()
        preferences.edit().putString(presetKey(cleanPresetName(name)), profile.toJson()).apply()
    }

    fun deletePreset(name: String) {
        preferences.edit().remove(presetKey(cleanPresetName(name))).apply()
    }

    private fun key(cameraId: String) = "profile_$cameraId"

    private fun presetKey(name: String) = "$PRESET_PREFIX$name"

    companion object {
        const val PREFS_NAME = "rawlens_vendor_keys"
        const val PRESET_PREFIX = "preset_"
        const val MAX_PRESET_NAME_CHARS = 64
        private val PRESET_NAME_CHARS = Regex("[A-Za-z0-9 _-]+")

        /** Pure + host-tested: trims and validates a user preset name. */
        fun cleanPresetName(name: String): String {
            val clean = name.trim()
            require(clean.isNotEmpty()) { "Preset name must not be blank" }
            require(clean.length <= MAX_PRESET_NAME_CHARS) { "Preset name too long" }
            require(PRESET_NAME_CHARS.matches(clean)) { "Preset name uses unsupported characters" }
            return clean
        }
    }
}

