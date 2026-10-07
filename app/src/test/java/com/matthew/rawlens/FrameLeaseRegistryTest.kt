// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pins the [FrameLeaseRegistry] contract the Direct-Log record path
 * relies on: the camera ref releases up front while a GPU borrow keeps
 * the native buffer alive, and the borrow transfers across threads
 * (camera submit -> copy-worker fence) with idempotent close.
 */
class FrameLeaseRegistryTest {
    @Test
    fun borrowDefersNativeCloseUntilLeaseClose() {
        var closes = 0
        val registry = FrameLeaseRegistry<Any> { closes++ }
        val frame = Any()
        registry.adopt(frame)
        val lease = registry.borrow(frame)
        assertNotNull(lease)
        registry.release(frame)
        assertEquals("camera release must not close a borrowed frame", 0, closes)
        lease!!.close()
        assertEquals(1, closes)
    }

    @Test
    fun leaseCloseIsIdempotent() {
        var closes = 0
        val registry = FrameLeaseRegistry<Any> { closes++ }
        val frame = Any()
        registry.adopt(frame)
        val lease = registry.borrow(frame)!!
        registry.release(frame)
        lease.close()
        lease.close()
        assertEquals("double close (fence + job finally) must close once", 1, closes)
    }

    @Test
    fun borrowAfterCameraReleaseReturnsNull() {
        val registry = FrameLeaseRegistry<Any> { }
        val frame = Any()
        registry.adopt(frame)
        registry.release(frame)
        assertNull(registry.borrow(frame))
    }

    @Test
    fun everyBorrowGatesTheClose() {
        var closes = 0
        val registry = FrameLeaseRegistry<Any> { closes++ }
        val frame = Any()
        registry.adopt(frame)
        val first = registry.borrow(frame)!!
        val second = registry.borrow(frame)!!
        registry.release(frame)
        first.close()
        assertEquals(0, closes)
        second.close()
        assertEquals(1, closes)
    }

    @Test
    fun recorderProtocolTransfersOneLeaseToFenceClose() {
        // Mirrors DirectLogRecorder: adopt -> borrow -> camera release on
        // the camera thread, then the transferred lease closes on the
        // worker (early fence close + finally backstop).
        var closes = 0
        val registry = FrameLeaseRegistry<Any> { closes++ }
        val frame = Any()
        registry.adopt(frame)
        val transferred = registry.borrow(frame)
        assertNotNull(transferred)
        registry.release(frame)
        assertEquals(0, closes)
        transferred!!.close()
        transferred.close()
        assertEquals(1, closes)
    }
}
