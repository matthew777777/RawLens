// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanEncoderBridgeTest {
    @Test
    fun paramsDefaultToVulkanFed4k() {
        val p = VulkanEncoderBridge.params()
        assertEquals(3840, p.width)
        assertEquals(2160, p.height)
        assertEquals(30, p.fps)
        // Synthetic sensor is HD; the superpixel export is half-res per
        // axis and the blit stretches to the 4K encoder surface.
        assertEquals(1920, p.rawWidth)
        assertEquals(1080, p.rawHeight)
        assertNull(p.spv)
        // Phase B is on by default: the blit is a dumb copy, color lives
        // in the Vulkan grade stage.
        assertTrue(p.grade)
        assertNull(p.gradeSpv)
        assertEquals(4, p.gains.size)
        assertEquals(9, p.ccm.size)
    }
}
