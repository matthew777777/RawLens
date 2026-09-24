// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class VfVulkanTest {
    @Test fun `device loss bypasses retries and schedules recreation`() {
        val lost = VfVulkan.describe(VfVulkan.DEVICE_LOST)
        assertEquals("device-lost", lost)
        assertTrue(VfVulkan.shouldDisableImmediately(lost))
        assertTrue(VfVulkan.shouldRecover(lost))
        assertFalse(VfVulkan.shouldDisableImmediately(VfVulkan.describe(VfVulkan.SUBMIT_FAILED)))
        assertFalse(VfVulkan.shouldDisableImmediately("input-import"))
    }

    @Test fun `only device failures schedule recreation`() {
        assertTrue(VfVulkan.shouldRecover(VfVulkan.describe(VfVulkan.SUBMIT_FAILED)))
        assertTrue(VfVulkan.shouldRecover(VfVulkan.describe(VfVulkan.DEVICE_FAILED)))
        assertFalse(VfVulkan.shouldRecover(VfVulkan.describe(VfVulkan.INPUT_IMPORT_FAILED)))
        assertFalse(VfVulkan.shouldRecover(VfVulkan.describe(VfVulkan.OUTPUT_IMPORT_FAILED)))
        assertFalse(VfVulkan.shouldRecover("egl-import"))
    }

    @Test fun `param packing matches the native push-constant layout`() {
        val (iparams, fparams) = VfVulkan.packParams(
            intArrayOf(0, 1, 2, 3), 0, 0, 680, 510, 6, 4080,
            floatArrayOf(64f, 64f, 64f, 64f), 1023f
        )
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 0, 0, 680, 510, 6, 4080), iparams)
        assertEquals(8, fparams.size)
        assertArrayEquals(floatArrayOf(64f, 64f, 64f, 64f), fparams.copyOfRange(0, 4), 0f)
        // Reciprocal normalization identical to VfCpuNeon.copy.
        val expected = 1f / (1023f - 64f)
        for (i in 4..7) assertEquals(expected, fparams[i], 1e-7f)
    }

    @Test fun `reciprocal clamps degenerate white-minus-black`() {
        val (_, fparams) = VfVulkan.packParams(
            intArrayOf(0, 1, 2, 3), 0, 0, 1, 1, 2, 4080,
            floatArrayOf(1023f, 2000f, 0f, 0f), 1023f
        )
        assertEquals(1f, fparams[4], 0f)
        assertEquals(1f, fparams[5], 0f)
    }

    @Test fun `dispatch groups cover partial tiles`() {
        assertEquals(85, VfVulkan.dispatchGroups(680))
        assertEquals(64, VfVulkan.dispatchGroups(510))
        assertEquals(1, VfVulkan.dispatchGroups(1))
        assertEquals(2, VfVulkan.dispatchGroups(9))
    }

    @Test fun `result codes describe every failure stage`() {
        assertEquals("ok", VfVulkan.describe(VfVulkan.OK))
        assertEquals("input-import", VfVulkan.describe(VfVulkan.INPUT_IMPORT_FAILED))
        assertEquals("output-import", VfVulkan.describe(VfVulkan.OUTPUT_IMPORT_FAILED))
        assertEquals("submit", VfVulkan.describe(VfVulkan.SUBMIT_FAILED))
    }
}
