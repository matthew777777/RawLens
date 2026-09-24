// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host-side contract for the native NEON fallback sampler. Argument validation
 * runs in Kotlin before the JNI call, so every rejection below is exercisable
 * without the .so; pixel parity lives in [VfCpuNeonInstrumentedTest] on device.
 */
class VfCpuNeonTest {
    private fun direct(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    @Test fun `result codes describe every documented outcome`() {
        assertEquals("ok", VfCpuNeon.describe(VfCpuNeon.OK))
        assertEquals("bad-argument", VfCpuNeon.describe(VfCpuNeon.BAD_ARGUMENT))
        assertEquals("overflow", VfCpuNeon.describe(VfCpuNeon.OVERFLOW))
        assertEquals("not-direct", VfCpuNeon.describe(VfCpuNeon.NOT_DIRECT))
        assertEquals("code-99", VfCpuNeon.describe(99))
    }

    @Test fun `unaligned quad geometry is rejected`() {
        val src = direct(64)
        val dst = direct(64)
        for ((left, top, step) in listOf(
            Triple(1, 0, 2), Triple(0, 1, 2), Triple(0, 0, 3), Triple(0, 0, 0)
        )) {
            try {
                VfCpuNeon.copy(
                    src, 8, 2, left, top, 1, 1, step,
                    intArrayOf(0, 1, 2, 3), FloatArray(4), 1023f, dst
                )
                fail("left=$left top=$top step=$step must throw")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun `bad extents and strides are rejected`() {
        val src = direct(64)
        val dst = direct(64)
        fun attempt(width: Int, height: Int, rowStride: Int, pixelStride: Int) {
            VfCpuNeon.copy(
                src, rowStride, pixelStride, 0, 0, width, height, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), 1023f, dst
            )
        }
        for ((w, h) in listOf(0 to 1, 1 to 0, 1081 to 1, 1 to 1081)) {
            try {
                attempt(w, h, 8, 2)
                fail("${w}x$h must throw")
            } catch (_: IllegalArgumentException) {
            }
        }
        for ((row, px) in listOf(0 to 2, 8 to 0, -8 to 2)) {
            try {
                attempt(1, 1, row, px)
                fail("row=$row px=$px must throw")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun `bad channels levels and white are rejected`() {
        val src = direct(64)
        val dst = direct(64)
        fun attempt(channels: IntArray, black: FloatArray, white: Float) {
            VfCpuNeon.copy(src, 8, 2, 0, 0, 1, 1, 2, channels, black, white, dst)
        }
        try {
            attempt(intArrayOf(0, 1, 2), FloatArray(4), 1023f)
            fail("short channels must throw")
        } catch (_: IllegalArgumentException) {
        }
        try {
            attempt(intArrayOf(0, 1, 2, 4), FloatArray(4), 1023f)
            fail("site 4 must throw")
        } catch (_: IllegalArgumentException) {
        }
        try {
            attempt(intArrayOf(0, 1, 2, 3), FloatArray(4), 0f)
            fail("white=0 must throw")
        } catch (_: IllegalArgumentException) {
        }
        try {
            attempt(intArrayOf(0, 1, 2, 3), floatArrayOf(0f, 0f, Float.NaN, 0f), 1023f)
            fail("NaN black must throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `heap buffers are rejected without touching native code`() {
        val heap = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder())
        try {
            VfCpuNeon.copy(
                heap, 8, 2, 0, 0, 1, 1, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), 1023f, direct(4)
            )
            fail("heap source must throw")
        } catch (_: IllegalArgumentException) {
        }
        try {
            VfCpuNeon.copy(
                direct(64), 8, 2, 0, 0, 1, 1, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), 1023f, heap
            )
            fail("heap destination must throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `short buffers are rejected`() {
        try {
            VfCpuNeon.copy(
                direct(64), 8, 2, 0, 0, 1, 1, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), 1023f, direct(3)
            )
            fail("3-byte destination must throw")
        } catch (_: IllegalArgumentException) {
        }
        try {
            // Packed 2x2 quad needs 2 rows x rowStride bytes.
            VfCpuNeon.copy(
                direct(7), 8, 2, 0, 0, 1, 1, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), 1023f, direct(4)
            )
            fail("7-byte source must throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `valid arguments reach the native boundary`() {
        // On the host the .so is absent, so fully valid arguments must fail
        // with "native unavailable" — proving validation itself passed.
        assumeTrue("native lib unexpectedly present on host", !VfCpuNeon.available)
        val src = direct(64)
        val dst = direct(4)
        try {
            VfCpuNeon.copy(
                src, 8, 2, 0, 0, 1, 1, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), 1023f, dst
            )
            fail("missing native lib must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unavailable"))
        }
    }

    @Test fun `maximum 1080 edge passes validation`() {
        assumeTrue("native lib unexpectedly present on host", !VfCpuNeon.available)
        // 1080x1 packed row, step 2: last sample x=2159, 2 rows of 2160 shorts.
        val src = direct(2160 * 2 * 2)
        val dst = direct(1080 * 4)
        try {
            VfCpuNeon.copy(
                src, 2160 * 2, 2, 0, 0, 1080, 1, 2,
                intArrayOf(0, 1, 2, 3), FloatArray(4), 1023f, dst
            )
            fail("missing native lib must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unavailable"))
        }
    }
}
