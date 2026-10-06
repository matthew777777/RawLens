// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Memoizes one successful lookup per camera ID.
 *
 * Camera2 route resolution costs binder calls plus, on some OEMs, JSON parsing and service
 * lookups per call; the lens switcher and zoom labels re-resolve the same IDs on the UI thread
 * on every controls publication. Only successful resolutions are cached: a null can be a
 * transient vendor response (Xiaomi returns bogus characteristics while the camera service is
 * still initializing), so rejected IDs always re-resolve instead of poisoning the cache.
 * Entries live with the owning controller; camera open failures still surface through the
 * normal unavailable path.
 */
internal class CameraIdCache<V : Any>(private val resolve: (String) -> V?) {
    private val entries = HashMap<String, V>()

    @Synchronized
    fun get(id: String): V? {
        entries[id]?.let { return it }
        val resolved = resolve(id)
        if (resolved != null) entries[id] = resolved
        return resolved
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
