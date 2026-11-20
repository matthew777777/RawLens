// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import org.json.JSONObject

/**
 * Viewfinder lens list choices, after rawr's Lens settings: a custom order
 * for the switcher discs and a custom short name per camera route. Both
 * outlive unchecking a lens, so hiding one and showing it again keeps its
 * place and name. Blank name = the automatic zoom / focal label.
 */
internal class LensListStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    fun order(): List<String> = parseOrder(prefs.getString(KEY_ORDER, null))

    fun setOrder(ids: List<String>) {
        prefs.edit().putString(KEY_ORDER, ids.distinct().joinToString(",")).apply()
    }

    fun names(): Map<String, String> = parseNames(prefs.getString(KEY_NAMES, null))

    fun name(id: String): String? = names()[id]

    /** Blank clears the name back to the automatic label. */
    fun setName(id: String, name: String) {
        val next = names().toMutableMap()
        val clean = cleanName(name)
        if (clean == null) next.remove(id) else next[id] = clean
        prefs.edit().putString(KEY_NAMES, JSONObject(next as Map<*, *>).toString()).apply()
    }

    fun reset() {
        prefs.edit().remove(KEY_ORDER).remove(KEY_NAMES).apply()
    }

    companion object {
        const val KEY_ORDER = "lens_switcher_order"
        const val KEY_NAMES = "lens_switcher_names"

        /** Fits a switcher disc (rawr allows 4; our discs take 5 at 12 sp). */
        const val NAME_MAX_LENGTH = 5

        fun parseOrder(raw: String?): List<String> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun parseNames(raw: String?): Map<String, String> = runCatching {
            val obj = JSONObject(raw ?: return emptyMap())
            obj.keys().asSequence().mapNotNull { id ->
                cleanName(obj.optString(id))?.let { id to it }
            }.toMap()
        }.getOrDefault(emptyMap())

        fun cleanName(name: String?): String? =
            name?.trim()?.take(NAME_MAX_LENGTH)?.takeIf { it.isNotEmpty() }

        /**
         * [ids] in the saved [order]: ordered ids first in that order, then
         * every id the order doesn't know yet, keeping its incoming
         * (widest-first) position. Stale ids in [order] are ignored.
         */
        fun applyOrder(ids: List<String>, order: List<String>): List<String> {
            val rank = order.withIndex().associate { it.value to it.index }
            val known = ids.filter { it in rank }.sortedBy { rank.getValue(it) }
            return known + ids.filter { it !in rank }
        }

        /** Moves [id] one place up (-1) or down (+1) within [ids]. */
        fun move(ids: List<String>, id: String, delta: Int): List<String> {
            val from = ids.indexOf(id)
            val to = from + delta
            if (from < 0 || to !in ids.indices) return ids
            return ids.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
}
