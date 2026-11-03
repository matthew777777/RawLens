// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/** Owns the original grade fence even when duplicating the delivery fd fails.
 * Single slot, called under PhotoRcdRenderer's monitor. A timeout preserves ownership.
 */
internal class PhotoRcdCompletion {
    private var fence = -1
    val pending: Boolean get() = fence >= 0

    fun submitted(fd: Int, duplicate: (Int) -> Int): Int {
        require(fd >= 0)
        check(!pending) { "Previous photo RCD grade still owns this slot" }
        fence = fd
        val delivery = duplicate(fd)
        check(delivery >= 0 && delivery != fd) { "Invalid delivery fence duplicate" }
        return delivery
    }

    fun complete(timeoutMs: Int, await: (Int, Int) -> Boolean, close: (Int) -> Unit): Boolean {
        require(timeoutMs >= 0)
        if (!pending) return true
        if (!await(fence, timeoutMs)) return false
        close(fence)
        fence = -1
        return true
    }
}
