// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-made switcher entry layered on a real camera route: same camera ID, its own
 * name, description and per-lens settings (vendor keys, RAW size/format, DNG overrides,
 * PROGRAM profile). Settings stores key it by [settingsKey] instead of the camera ID.
 */
data class VirtualLens(
    val id: String,
    val baseCameraId: String,
    /** Big text on the switcher disc, e.g. "1×". */
    val zoom: String,
    /** Small second line on the disc, e.g. "NIGHT". */
    val tag: String,
    val description: String = ""
) {
    val settingsKey: String get() = settingsKeyOf(id)
    val switcherLabel: String get() = if (tag.isBlank()) zoom else "$zoom · $tag"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("base", baseCameraId).put("zoom", zoom)
        .put("tag", tag).put("description", description)

    companion object {
        const val READOUT_PREFIX = "virtual:"
        fun settingsKeyOf(id: String) = "$READOUT_PREFIX$id"
        fun idFromReadout(readout: String?): String? =
            readout?.takeIf { it.startsWith(READOUT_PREFIX) }?.removePrefix(READOUT_PREFIX)

        fun fromJson(json: JSONObject): VirtualLens? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val base = json.optString("base").takeIf { it.isNotBlank() } ?: return null
            return VirtualLens(
                id, base,
                json.optString("zoom").ifBlank { "1×" }.take(MAX_ZOOM),
                json.optString("tag").take(MAX_TAG),
                json.optString("description").take(MAX_DESCRIPTION)
            )
        }

        const val MAX_ZOOM = 6
        const val MAX_TAG = 10
        const val MAX_DESCRIPTION = 120
    }
}

class VirtualLensStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized fun all(): List<VirtualLens> {
        val raw = preferences.getString(KEY_LENSES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { VirtualLens.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(id: String?): VirtualLens? = id?.let { wanted -> all().firstOrNull { it.id == wanted } }

    @Synchronized fun save(lens: VirtualLens) {
        val list = all().toMutableList()
        val at = list.indexOfFirst { it.id == lens.id }
        if (at >= 0) list[at] = lens else list += lens
        write(list)
    }

    @Synchronized fun delete(id: String) {
        write(all().filterNot { it.id == id })
        if (activeId() == id) setActive(null)
    }

    /** The virtual lens currently chosen in the switcher, or null for the plain route. */
    fun activeId(): String? = preferences.getString(KEY_ACTIVE, null)

    fun setActive(id: String?) {
        preferences.edit().apply { if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id) }.apply()
    }

    /** Active virtual lens when it sits on [cameraId]. */
    fun activeOn(cameraId: String?): VirtualLens? =
        get(activeId())?.takeIf { cameraId != null && it.baseCameraId == cameraId }

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36)

    private fun write(list: List<VirtualLens>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_LENSES, array.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "rawlens_virtual_lenses"
        private const val KEY_LENSES = "lenses"
        private const val KEY_ACTIVE = "active"
    }
}
