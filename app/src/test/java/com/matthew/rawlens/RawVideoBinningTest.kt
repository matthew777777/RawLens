// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVideoBinningTest {
    @Test fun binningNeedsPrefBayerCfaAndBinnableWidth() {
        for (cfa in 0..3) {
            assertTrue("cfa=$cfa", RawVideoRecorder.binningActive(true, cfa, 4080))
        }
        assertTrue(!RawVideoRecorder.binningActive(false, 0, 4080))
        assertTrue(!RawVideoRecorder.binningActive(true, null, 4080))
        assertTrue(!RawVideoRecorder.binningActive(true, 4, 4080))
        assertTrue(!RawVideoRecorder.binningActive(true, -1, 4080))
        assertTrue(!RawVideoRecorder.binningActive(true, 0, 66))
        assertTrue(RawVideoRecorder.binningActive(true, 3, 100))
    }

    @Test fun writerRejectsUnbinnableGeometryWithoutNativeLib() {
        // The Kotlin-side contract throws before the .so is touched, so these
        // run on the host JVM (no native library needed).
        val src = ByteBuffer.allocateDirect(64)
        val dst = ByteBuffer.allocateDirect(64)
        fun encode(width: Int, height: Int, cropTop: Int, cropHeight: Int, bin: Boolean, cfa: Int) =
            CrsWriter.encodeFrame(
                src, 64, width, height, width * 2, cropTop, cropHeight, dst, bin, cfa
            )
        // Binned width must stay even for the tile codec.
        assertThrows<IllegalArgumentException> { encode(66, 40, 0, 40, true, 0) }
        // Binned height must stay % 4.
        assertThrows<IllegalArgumentException> { encode(64, 8, 0, 4, true, 0) }
        // CFA routes the binning taps.
        assertThrows<IllegalArgumentException> { encode(64, 8, 0, 8, true, 4) }
        assertThrows<IllegalArgumentException> { encode(64, 8, 0, 8, false, 9) }
        // Valid geometries pass validation and fail only on the missing .so.
        assertThrows<IllegalStateException> { encode(64, 8, 0, 8, true, 0) }
        assertThrows<IllegalStateException> { encode(66, 40, 0, 40, false, 0) }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw AssertionError("expected ${T::class.java.name}, got $e")
        }
        throw AssertionError("expected ${T::class.java.name}, nothing thrown")
    }
}
