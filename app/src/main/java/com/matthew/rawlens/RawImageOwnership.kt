// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.Image
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Camera/ring/save ownership plus bounded GPU borrows of the same physical frame. */
internal class FrameLeaseRegistry<T : Any>(private val releaseNative: (T) -> Unit) {
    private class Entry(val source: Any?, val released: () -> Unit) {
        var cameraReleased = false
        var borrows = 0
    }
    private val entries = IdentityHashMap<T, Entry>()
    // Weak keys keep late duplicate completion callbacks idempotent without retaining frames.
    private val completed = java.util.WeakHashMap<T, Boolean>()

    @Synchronized fun adopt(frame: T, source: Any? = null, released: () -> Unit = {}) {
        check(!completed.containsKey(frame)) { "Cannot adopt a released camera frame" }
        if (!entries.containsKey(frame)) entries[frame] = Entry(source, released)
    }

    @Synchronized fun borrow(frame: T): AutoCloseable? {
        val entry = entries[frame] ?: return null
        if (entry.cameraReleased) return null
        entry.borrows++
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(this) {
                entry.borrows--
                finishIfReleased(frame, entry)
            }
        }
    }

    /** Returns false for images never adopted by this registry. */
    @Synchronized fun release(frame: T): Boolean {
        val entry = entries[frame] ?: return completed.containsKey(frame)
        entry.cameraReleased = true
        finishIfReleased(frame, entry)
        return true
    }

    @Synchronized fun count(source: Any): Int = entries.values.count { it.source === source }
    @Synchronized fun gpuOnlyCount(): Int = entries.values.count { it.cameraReleased && it.borrows > 0 }

    private fun finishIfReleased(frame: T, entry: Entry) {
        if (!entry.cameraReleased || entry.borrows != 0) return
        // Hold the registry lock through native close so a reader cannot retire before it.
        try { releaseNative(frame) } finally {
            entries.remove(frame)
            completed[frame] = true
            entry.released()
        }
    }
}

internal object RawImageOwnership {
    private val registry = FrameLeaseRegistry<Image> { it.close() }
    fun adopt(image: Image, reader: Any? = null, released: () -> Unit = {}) = registry.adopt(image, reader, released)
    fun borrow(image: Image): AutoCloseable? = registry.borrow(image)
    fun release(image: Image) { if (!registry.release(image)) image.close() }
    fun count(reader: Any): Int = registry.count(reader)
    fun gpuOnlyCount(): Int = registry.gpuOnlyCount()
}
