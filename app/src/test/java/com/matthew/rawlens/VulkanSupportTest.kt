// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class VulkanSupportTest {
    @Test fun `vulkan 1_1_0 is the version floor`() {
        assertTrue(VulkanSupport.isVersionSupported(0x00401000))
        assertFalse(VulkanSupport.isVersionSupported(0x00400000))
        assertFalse(VulkanSupport.isVersionSupported(0x00400FFF))
    }

    @Test fun `newer vulkan versions pass the floor`() {
        assertTrue(VulkanSupport.isVersionSupported(0x00402000))
        assertTrue(VulkanSupport.isVersionSupported(0x00403000))
    }

    @Test fun `packed versions render as major_minor_patch`() {
        assertEquals("1.1.0", VulkanSupport.versionString(0x00401000))
        assertEquals("1.2.189", VulkanSupport.versionString(0x004020BD))
    }

    @Test fun `probe fails on missing or blank diagnostics`() {
        assertFalse(VulkanSupport.isProbeOk(null))
        assertFalse(VulkanSupport.isProbeOk(""))
        assertFalse(VulkanSupport.isProbeOk("   "))
    }

    @Test fun `probe fails when any stage reports unsupported`() {
        assertFalse(VulkanSupport.isProbeOk("Unsupported: vkEnumerateInstanceVersion failed (1)"))
        assertFalse(VulkanSupport.isProbeOk("Loader 1.3.0; unsupported: Vulkan 1.1 is required"))
        assertFalse(VulkanSupport.isProbeOk("device X; UNSUPPORTED: no Vulkan physical device"))
    }

    @Test fun `probe passes on a clean diagnostics string`() {
        assertTrue(
            VulkanSupport.isProbeOk(
                "Loader 1.3.0; device Adreno API 1.3.0; " +
                    "AHardwareBuffer extension available; import succeeded without CPU mapping"
            )
        )
    }

    @Test fun `missing level feature fails first`() {
        val verdict = VulkanSupport.check(
            hasLevelFeature = false,
            packedVersion = 0x00403000,
            probeDiagnostics = "Loader 1.3.0; import succeeded",
        )
        assertEquals(VulkanSupport.Verdict.Unsupported("no-vulkan-level"), verdict)
    }

    @Test fun `missing version fails before the probe runs`() {
        val verdict = VulkanSupport.check(
            hasLevelFeature = true,
            packedVersion = null,
            probeDiagnostics = null,
        )
        assertEquals(VulkanSupport.Verdict.Unsupported("no-vulkan-version"), verdict)
    }

    @Test fun `low version names the device version`() {
        val verdict = VulkanSupport.check(
            hasLevelFeature = true,
            packedVersion = 0x00400000,
            probeDiagnostics = "Loader 1.0.0; import succeeded",
        )
        assertEquals(VulkanSupport.Verdict.Unsupported("vulkan-1.0.0-below-1.1"), verdict)
    }

    @Test fun `failed probe fails after version passes`() {
        val verdict = VulkanSupport.check(
            hasLevelFeature = true,
            packedVersion = 0x00401000,
            probeDiagnostics = "Loader 1.1.0; unsupported: no Vulkan physical device",
        )
        assertEquals(VulkanSupport.Verdict.Unsupported("probe-failed"), verdict)
    }

    @Test fun `supported needs level plus version plus probe`() {
        assertEquals(
            VulkanSupport.Verdict.Supported,
            VulkanSupport.check(
                hasLevelFeature = true,
                packedVersion = 0x00401000,
                probeDiagnostics = "Loader 1.1.0; device X API 1.1.0; import succeeded",
            )
        )
    }

    @Test fun `supported verdict has no user message`() {
        assertNull(VulkanSupport.userMessage(VulkanSupport.Verdict.Supported))
    }

    @Test fun `every failure names its stage for the user`() {
        val level = VulkanSupport.userMessage(VulkanSupport.Verdict.Unsupported("no-vulkan-level"))
        assertNotNull(level)
        assertTrue("Vulkan" in level!! && "DNG" in level)
        val version = VulkanSupport.userMessage(VulkanSupport.Verdict.Unsupported("no-vulkan-version"))
        assertNotNull(version)
        assertTrue("Vulkan" in version!! && "DNG" in version)
        val probe = VulkanSupport.userMessage(VulkanSupport.Verdict.Unsupported("probe-failed"))
        assertNotNull(probe)
        assertTrue("probe" in probe!! && "DNG" in probe)
    }

    @Test fun `low version message names the device version`() {
        val message = VulkanSupport.userMessage(
            VulkanSupport.Verdict.Unsupported("vulkan-1.0.0-below-1.1")
        )
        assertNotNull(message)
        assertTrue("1.0.0" in message!! && "1.1" in message)
    }

    @Test fun `half-float buffers need android 13`() {
        assertFalse(VulkanSupport.isFp16BufferSupported(30))
        assertFalse(VulkanSupport.isFp16BufferSupported(32))
        assertTrue(VulkanSupport.isFp16BufferSupported(33))
        assertTrue(VulkanSupport.isFp16BufferSupported(35))
    }
}
