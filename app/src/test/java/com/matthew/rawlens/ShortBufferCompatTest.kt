// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortBufferCompatTest {
    // Regression: ShortBuffer.get(index, dst, offset, length) compiles against
    // recent SDKs but throws NoSuchMethodError on API 30 (Redmi 9 field log),
    // killing the app on the first sampled RAW frame. The compat path must
    // match absolute-bulk semantics using API-1 calls only
    // (duplicate/position/relative-get), so it can never hit that trap again.

    private fun directShorts(vararg values: Short) =
        ByteBuffer.allocateDirect(values.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(values)
                flip()
            }

    @Test
    fun copiesSliceAtIndexWithOffset() {
        val src = directShorts(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val dst = ShortArray(8) { -1 }
        ShortBufferCompat.getBulk(src, 3, dst, 2, 4)
        assertArrayEquals(shortArrayOf(-1, -1, 3, 4, 5, 6, -1, -1), dst)
    }

    @Test
    fun leavesSourcePositionAndLimitUntouched() {
        val src = directShorts(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        src.position(2)
        src.limit(9)
        val dst = ShortArray(4)
        ShortBufferCompat.getBulk(src, 4, dst, 0, 4)
        assertArrayEquals(shortArrayOf(4, 5, 6, 7), dst)
        assertEquals(2, src.position())
        assertEquals(9, src.limit())
    }

    @Test
    fun matchesAbsoluteSingleGetsOverFullRow() {
        // Sampler-shaped fetch: a strided Bayer row copied in one bulk call
        // must equal the API-1 absolute get(int) loop, element for element.
        val width = 1024
        val shorts = ShortArray(width) { i -> ((i * 1103515245 + 12345) ushr 16).toShort() }
        val src = directShorts(*shorts)
        val dst = ShortArray(width)
        ShortBufferCompat.getBulk(src, 0, dst, 0, width)
        val expected = ShortArray(width) { i -> src.get(i) }
        assertArrayEquals(expected, dst)
        assertEquals(0, src.position())
    }
}
