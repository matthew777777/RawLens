// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// DESKTOP HOST SHIM (sr-vulkan only). Mirrors the android.os.SharedMemory
// surface the FFT planes use so ported allocation lines compile
// byte-identical. Backed by allocateDirect, which is genuinely off-heap on
// HotSpot (unlike ART's heap-backed non-movable store), so the production
// contract — FFT planes never sit on the managed heap — holds on both
// platforms. No-op cleanup: the JVM collector owns the mapping lifetime.
package android.os

import java.nio.ByteBuffer

class SharedMemory private constructor(private val store: ByteBuffer) : java.io.Closeable {
    fun mapReadWrite(): ByteBuffer = store.duplicate()
    override fun close() {}
    companion object {
        @JvmStatic
        fun create(name: String?, size: Int): SharedMemory =
            SharedMemory(ByteBuffer.allocateDirect(size))
        @JvmStatic
        fun unmap(buffer: ByteBuffer) {}
    }
}
