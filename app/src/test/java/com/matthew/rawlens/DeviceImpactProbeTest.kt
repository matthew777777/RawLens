// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the device-citizenship probe logic that must stay exact: frame
 * classification behind `PERF[jank]`, trim-level names, `/proc` tick parsing
 * and CPU math behind `PERF[heartbeat]`. The `/proc` readers and the
 * Choreographer/Looper hooks need a device and stay out of unit tests.
 */
class DeviceImpactProbeTest {
    @Test fun `frame classes honor jank and frozen thresholds`() {
        assertEquals(DeviceImpactProbe.FrameClass.OK, DeviceImpactProbe.classifyFrame(0))
        assertEquals(DeviceImpactProbe.FrameClass.OK, DeviceImpactProbe.classifyFrame(16))
        assertEquals(DeviceImpactProbe.FrameClass.OK, DeviceImpactProbe.classifyFrame(31))
        assertEquals(DeviceImpactProbe.FrameClass.JANKY, DeviceImpactProbe.classifyFrame(32))
        assertEquals(DeviceImpactProbe.FrameClass.JANKY, DeviceImpactProbe.classifyFrame(699))
        assertEquals(DeviceImpactProbe.FrameClass.FROZEN, DeviceImpactProbe.classifyFrame(700))
        assertEquals(DeviceImpactProbe.FrameClass.FROZEN, DeviceImpactProbe.classifyFrame(5000))
    }

    @Test fun `trim names cover every level`() {
        assertEquals("UI_HIDDEN", DeviceImpactProbe.trimName(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals("BACKGROUND", DeviceImpactProbe.trimName(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        // Deprecated TRIM_MEMORY_* constants are matched by literal value
        // in trimName; pin the platform values (60/80/10/5/15) here too.
        assertEquals("MODERATE", DeviceImpactProbe.trimName(60))
        assertEquals("COMPLETE", DeviceImpactProbe.trimName(80))
        assertEquals("RUNNING_LOW", DeviceImpactProbe.trimName(10))
        assertEquals("RUNNING_MODERATE", DeviceImpactProbe.trimName(5))
        assertEquals("RUNNING_CRITICAL", DeviceImpactProbe.trimName(15))
        assertEquals("level=999", DeviceImpactProbe.trimName(999))
    }

    @Test fun `stat ticks sum utime and stime past a spaced comm`() {
        val stat = "1234 (my process (x)) R 0 0 0 0 0 0 0 0 0 0 100 200 0 0"
        assertEquals(300L, DeviceImpactProbe.parseStatTicks(stat))
    }

    @Test fun `stat ticks reject malformed input`() {
        assertNull(DeviceImpactProbe.parseStatTicks(""))
        assertNull(DeviceImpactProbe.parseStatTicks("1234 (x) R 1 2"))
        assertNull(DeviceImpactProbe.parseStatTicks("1234 (x) R 0 0 0 0 0 0 0 0 0 0 nope 200"))
    }

    @Test fun `cpu percent normalizes by cores`() {
        assertEquals(10.0, DeviceImpactProbe.cpuPercent(10, 1000, 1), 1e-9)
        assertEquals(10.0, DeviceImpactProbe.cpuPercent(80, 1000, 8), 1e-9)
        assertEquals(100.0, DeviceImpactProbe.cpuPercent(100, 1000, 1), 1e-9)
        assertEquals(0.0, DeviceImpactProbe.cpuPercent(100, 0, 8), 0.0)
        assertEquals(0.0, DeviceImpactProbe.cpuPercent(-5, 1000, 8), 0.0)
        assertEquals(0.0, DeviceImpactProbe.cpuPercent(100, 1000, 0), 0.0)
    }

    @Test fun `main busyness is dispatch over elapsed`() {
        assertEquals(25.0, DeviceImpactProbe.mainBusyPercent(250, 1000), 1e-9)
        assertEquals(0.0, DeviceImpactProbe.mainBusyPercent(250, 0), 0.0)
        assertEquals(0.0, DeviceImpactProbe.mainBusyPercent(-1, 1000), 0.0)
    }

    @Test fun `slow key strips identity hashes`() {
        val line = ">>>>> Dispatching to Handler (android.view.ViewRootImpl\$ViewRootHandler) {145a6f2} com.mat@1a2b3c null: 0"
        val key = DeviceImpactProbe.slowKey(line)
        assertTrue("hash survives: $key", '{' !in key && '@' !in key)
        assertTrue(key.startsWith(">>>>> Dispatching"))
        // Same message, different instance => same bucket.
        val other = line.replace("145a6f2", "999ffff").replace("1a2b3c", "9f9f9f")
        assertEquals(key, DeviceImpactProbe.slowKey(other))
    }

    @Test fun `top threads sort by growth and skip new or idle tids`() {
        val prev = mapOf(
            1L to ("a" to 100L),
            2L to ("b" to 100L),
            3L to ("c" to 100L)
        )
        val cur = mapOf(
            1L to ("a" to 110L),
            2L to ("b" to 150L),
            3L to ("c" to 100L),
            4L to ("new" to 999L)
        )
        val top = DeviceImpactProbe.topByDelta(prev, cur, 5)
        assertEquals(2, top.size)
        assertEquals(Triple(2L, "b", 50L), top[0])
        assertEquals(Triple(1L, "a", 10L), top[1])
        assertTrue(top.none { it.first == 4L })
    }
}
