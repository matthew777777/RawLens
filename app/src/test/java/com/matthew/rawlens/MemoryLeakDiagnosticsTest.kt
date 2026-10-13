// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Pins the leak-probe logic that must stay exact: the pure trend verdict behind
 * `POTENTIAL_MEMORY_LEAK` lines, and the monotonic counter accounting behind every
 * `MEM[...]` line. `sample()` itself needs a device (PSS/`/proc`), so it stays
 * out of unit tests by design.
 */
class MemoryLeakDiagnosticsTest {
    @Before fun reset() {
        MemoryLeakDiagnostics.resetForTest()
    }

    private fun point(
        pssKb: Long = 100_000L,
        nativeBytes: Long = 50_000_000L,
        textureBytes: Long = 0L,
        nativeBufBytes: Long = 0L,
        images: Long = 0L
    ) = MemoryLeakDiagnostics.TrendPoint(pssKb, nativeBytes, textureBytes, nativeBufBytes, images)

    @Test fun `short history never warns`() {
        val flat = List(5) { point() }
        assertNull(MemoryLeakDiagnostics.trendWarning(flat))
    }

    @Test fun `flat history never warns`() {
        val flat = List(6) { point() }
        assertNull(MemoryLeakDiagnostics.trendWarning(flat))
    }

    @Test fun `rising PSS past 32MB warns`() {
        val rising = List(6) { i -> point(pssKb = 100_000L + i * 10_000L) }
        assertNotNull(MemoryLeakDiagnostics.trendWarning(rising))
    }

    @Test fun `small PSS drift stays silent`() {
        val drift = List(6) { i -> point(pssKb = 100_000L + i * 100L) }
        assertNull(MemoryLeakDiagnostics.trendWarning(drift))
    }

    @Test fun `rising native heap past 24MB warns`() {
        val rising = List(6) { i -> point(nativeBytes = 50_000_000L + i * 10_000_000L) }
        assertNotNull(MemoryLeakDiagnostics.trendWarning(rising))
    }

    @Test fun `malloc-buffer growth past 24MB warns`() {
        val rising = List(6) { i -> point(nativeBufBytes = i * 10_000_000L) }
        val detail = MemoryLeakDiagnostics.trendWarning(rising)
        assertNotNull(detail)
    }

    @Test fun `gl texture growth past 16MB warns without monotonicity`() {
        val points = listOf(
            point(textureBytes = 0L),
            point(textureBytes = 20_000_000L),
            point(textureBytes = 0L),
            point(textureBytes = 20_000_000L),
            point(textureBytes = 0L),
            point(textureBytes = 20_000_000L)
        )
        assertNotNull(MemoryLeakDiagnostics.trendWarning(points))
    }

    @Test fun `two more live images warns`() {
        val rising = List(6) { i -> point(images = i.toLong()) }
        assertNotNull(MemoryLeakDiagnostics.trendWarning(rising))
    }

    @Test fun `native buffer accounting balances`() {
        MemoryLeakDiagnostics.nativeBufferAllocated(100L)
        MemoryLeakDiagnostics.nativeBufferAllocated(50L)
        MemoryLeakDiagnostics.nativeBufferReleased(100L)
        assertEquals(50L, MemoryLeakDiagnostics.nativeBufferOutstandingBytes())
        assertEquals(1L, MemoryLeakDiagnostics.countersForTest().nativeBufferCount)
    }

    @Test fun `event counters accumulate per module`() {
        MemoryLeakDiagnostics.amazeCapture()
        MemoryLeakDiagnostics.amazeCapture()
        MemoryLeakDiagnostics.hdrMerge()
        MemoryLeakDiagnostics.stillCapture()
        MemoryLeakDiagnostics.vfFrame()
        MemoryLeakDiagnostics.bguVfFrame()
        MemoryLeakDiagnostics.rawVideoFrame()
        MemoryLeakDiagnostics.directLogFrame()
        val counters = MemoryLeakDiagnostics.countersForTest()
        assertEquals(2L, counters.amaze)
        assertEquals(1L, counters.hdr)
        assertEquals(1L, counters.still)
        assertEquals(1L, counters.vf)
        assertEquals(1L, counters.bgu)
        assertEquals(1L, counters.rawVideo)
        assertEquals(1L, counters.directLog)
    }

    @Test fun `vulkan handle accounting balances`() {
        MemoryLeakDiagnostics.vulkanHandleAllocated()
        MemoryLeakDiagnostics.vulkanHandleAllocated()
        MemoryLeakDiagnostics.vulkanHandleReleased()
        assertEquals(1L, MemoryLeakDiagnostics.countersForTest().vulkanHandles)
    }

    @Test fun `graphicsKb reads the summary key`() {
        assertEquals(1_462_000L, MemoryLeakDiagnostics.graphicsKb(mapOf("summary.graphics" to "1462000")))
        assertEquals(-1L, MemoryLeakDiagnostics.graphicsKb(emptyMap()))
        assertEquals(-1L, MemoryLeakDiagnostics.graphicsKb(mapOf("summary.graphics" to "nope")))
        assertEquals(-1L, MemoryLeakDiagnostics.graphicsKb(mapOf("summary.graphics" to "-5")))
    }

    @Test fun `reset clears counters`() {
        MemoryLeakDiagnostics.amazeCapture()
        MemoryLeakDiagnostics.nativeBufferAllocated(8L)
        MemoryLeakDiagnostics.resetForTest()
        val counters = MemoryLeakDiagnostics.countersForTest()
        assertEquals(0L, counters.amaze)
        assertEquals(0L, counters.nativeBufferBytes)
        assertEquals(0L, MemoryLeakDiagnostics.nativeBufferOutstandingBytes())
    }
}
