// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Camera-thread metadata history. Missing timestamps never fall back to a newer frame. */
internal class MeteringResultWindow<T>(private val capacity: Int = 16) {
    init { require(capacity > 0) }
    private val results = LinkedHashMap<Long, T>()
    fun put(timestamp: Long, result: T) {
        results[timestamp] = result
        while (results.size > capacity) results.remove(results.keys.first())
    }
    operator fun get(timestamp: Long): T? = results[timestamp]
    fun clear() = results.clear()
}
