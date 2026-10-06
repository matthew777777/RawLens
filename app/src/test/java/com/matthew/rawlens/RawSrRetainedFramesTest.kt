// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RawSrRetainedFramesTest {
    @Test fun `plane copy preserves bytes capacity position and limit`() {
        val src = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder())
        for (i in 0 until 1024) src.put(i, (i * 31).toByte())
        src.position(128)
        src.limit(900)
        val copy = copyPlaneByteBuffer(src)
        assertTrue(copy.isDirect)
        assertEquals(ByteOrder.nativeOrder(), copy.order())
        assertEquals(1024, copy.capacity())
        assertEquals(128, copy.position())
        assertEquals(900, copy.limit())
        val a = src.duplicate().apply { clear() }
        val b = copy.duplicate().apply { clear() }
        assertEquals(a, b)
    }

    @Test fun `plane copy is independent of the source`() {
        val src = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        for (i in 0 until 64) src.put(i, i.toByte())
        val copy = copyPlaneByteBuffer(src)
        copy.put(0, 99.toByte())
        assertEquals(0.toByte(), src.get(0))
        // Source position/limit are untouched by the copy.
        assertEquals(0, src.position())
        assertEquals(64, src.limit())
    }

    @Test fun `plane copy handles camera plane convention`() {
        // Camera planes arrive position 0, limit capacity.
        val src = ByteBuffer.allocateDirect(25 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until src.capacity()) src.put(i, (i % 251).toByte())
        val copy = copyPlaneByteBuffer(src)
        assertEquals(0, copy.position())
        assertEquals(src.capacity(), copy.limit())
        val a = ByteArray(src.capacity())
        val b = ByteArray(copy.capacity())
        src.duplicate().apply { clear() }.get(a)
        copy.duplicate().apply { clear() }.get(b)
        assertArrayEquals(a, b)
    }
}
