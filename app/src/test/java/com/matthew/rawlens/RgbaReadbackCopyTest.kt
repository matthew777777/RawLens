// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class RgbaReadbackCopyTest {
    @Test fun rowCopyPreservesBitsAndDestinationBoundaries() {
        val bits = intArrayOf(0, Int.MIN_VALUE, 0x7fc01234, 0x7f800000,
            0xff800000.toInt(), 1, 0x3f800000, 0xbf800000.toInt())
        val width = 7
        val rows = 3
        val source = ByteBuffer.allocateDirect(width * rows * 16)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        repeat(width * rows * 4) { source.put(Float.fromBits(bits[it % bits.size])) }
        source.flip()
        val destination = FloatArray(width * rows * 3 + 4) { 42f }
        RawSrMergeJob.copyRgbaRowsToRgb(source, width, rows, destination, 2)
        repeat(width * rows) { pixel ->
            repeat(3) { channel ->
                assertEquals(bits[(pixel * 4 + channel) % bits.size],
                    destination[2 + pixel * 3 + channel].toRawBits())
            }
        }
        for (i in intArrayOf(0, 1, destination.size - 2, destination.size - 1)) {
            assertEquals(42f.toRawBits(), destination[i].toRawBits())
        }
        assertEquals(width * rows * 4, source.position())
    }
}
