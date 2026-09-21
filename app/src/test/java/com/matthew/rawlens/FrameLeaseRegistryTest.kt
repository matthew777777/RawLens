// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class FrameLeaseRegistryTest {
    @Test fun `ring eviction waits for GPU then releases native frame exactly once`() {
        val frame = Any(); val reader = Any()
        var closed = 0; var retired = 0
        val registry = FrameLeaseRegistry<Any> { closed++ }
        registry.adopt(frame, reader) { retired++ }
        val gpu = registry.borrow(frame)!!
        registry.release(frame)
        registry.release(frame)
        assertEquals(0, closed)
        assertEquals(1, registry.count(reader))
        assertEquals(1, registry.gpuOnlyCount())
        assertNull(registry.borrow(frame))
        gpu.close(); gpu.close()
        registry.release(frame) // late duplicate completion after the GPU borrow is gone
        assertEquals(1, closed)
        assertEquals(1, retired)
        assertEquals(0, registry.count(reader))
    }
    @Test fun `GPU completion does not release a frame still owned by saving`() {
        val frame = Any(); val reader = Any()
        var closed = 0
        val registry = FrameLeaseRegistry<Any> { closed++ }
        registry.adopt(frame, reader)
        val gpu = registry.borrow(frame)!!
        gpu.close()
        assertEquals(0, closed)
        assertEquals(1, registry.count(reader))
        registry.release(frame)
        assertEquals(1, closed)
    }
    @Test fun `pending frame dropping and stale sessions release independent reader allocations`() {
        val oldReader = Any(); val newReader = Any()
        val closed = mutableListOf<Any>()
        val registry = FrameLeaseRegistry<Any> { closed += it }
        repeat(50) {
            val old = Any(); val fresh = Any()
            registry.adopt(old, oldReader); registry.adopt(fresh, newReader)
            val queued = registry.borrow(old)!!
            val drawing = registry.borrow(fresh)!!
            registry.release(old); registry.release(fresh)
            queued.close() // superseded or invalidated pending GPU frame
            assertEquals(0, registry.count(oldReader))
            assertEquals(1, registry.count(newReader))
            drawing.close()
            assertEquals(0, registry.count(newReader))
        }
        assertEquals(100, closed.distinct().size)
        assertEquals(100, closed.size)
    }
}
