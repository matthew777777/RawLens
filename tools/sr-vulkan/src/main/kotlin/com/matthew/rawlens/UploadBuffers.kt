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

    override fun close() {
        storage = null
    }
}
