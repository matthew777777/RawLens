// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Memoizes one nullable lookup per camera ID.
 *
 * Camera2 route resolution costs binder calls plus, on some OEMs, JSON parsing and service
 * lookups per call; the lens switcher and zoom labels re-resolve the same IDs on the UI thread
 * on every controls publication. Nulls (front lenses, non-RAW IDs) are cached too, so rejected
 * IDs never re-hit the HAL. Entries live with the owning controller; camera open failures still
 * surface through the normal unavailable path.
 */
internal class CameraIdCache<V : Any>(private val resolve: (String) -> V?) {
    private val entries = HashMap<String, V?>()

    @Synchronized
    fun get(id: String): V? {
        // Contains-check, not getOrPut: getOrPut re-invokes the loader for cached nulls.
        if (entries.containsKey(id)) return entries[id]
        val resolved = resolve(id)
        entries[id] = resolved
        return resolved
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
