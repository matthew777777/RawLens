// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Shared upload-scratch pool (unified phone/desktop bytes): extracted from
// the retired GLES host's nested declaration; same-package callers resolve
// it unchanged.
package com.matthew.rawlens

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** One direct client-upload allocation, grown only when a CPU fallback requires more space. */
internal class UploadBuffers : Closeable {
    private var storage: ByteBuffer? = null

    fun floats(count: Int): FloatBuffer {
        val required = count * Float.SIZE_BYTES
        var bytes = storage
        if (bytes == null || bytes.capacity() < required) {
            bytes = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
            storage = bytes
        }
        bytes.clear()
        bytes.limit(required)
        return bytes.asFloatBuffer().apply { limit(count) }
    }

    /**
     * Short view of the same pooled staging, for RAW16 code uploads: same
     * grow-only discipline as [floats], consumed via [directBytes] exactly
     * the same way. Reuses the session's staging instead of allocating one
     * ~24MB direct buffer per burst frame.
     */
    fun shorts(count: Int): java.nio.ShortBuffer {
        val required = count * Short.SIZE_BYTES
        var bytes = storage
        if (bytes == null || bytes.capacity() < required) {
            bytes = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
            storage = bytes
        }
        bytes.clear()
        bytes.limit(required)
        return bytes.asShortBuffer().apply { limit(count) }
    }

    /**
     * Direct read view of the staging filled via [floats]: the same bytes
     * the view holds, rebased so capacity equals the last requirement (the
     * JNI upload sizes by capacity, not limit). Valid until the next
     * [floats] call; the native upload consumes it synchronously (one-shot
     * submit + fence wait inside the JNI call), so no second copy is needed
     * and the pool is reusable on return.
     */
    fun directBytes(): ByteBuffer =
        requireNotNull(storage) { "Upload staging is empty" }.slice()
            .order(ByteOrder.nativeOrder())

    override fun close() {
        storage = null
    }
}
