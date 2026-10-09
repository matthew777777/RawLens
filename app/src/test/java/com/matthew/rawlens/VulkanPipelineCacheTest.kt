// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VulkanPipelineCacheTest {
    private val cacheDir = File("/cache")

    @Test fun `dir nests under the app cache dir`() {
        assertEquals(File("/cache/vulkan_pipeline_cache"), VulkanPipelineCache.dir(cacheDir))
    }

    @Test fun `each host resolves to its own file`() {
        assertEquals(
            File("/cache/vulkan_pipeline_cache/vf.bin"),
            VulkanPipelineCache.file(cacheDir, VulkanPipelineCache.HOST_VF)
        )
        assertEquals(
            File("/cache/vulkan_pipeline_cache/galosh.bin"),
            VulkanPipelineCache.file(cacheDir, VulkanPipelineCache.HOST_GALOSH)
        )
        assertEquals(
            File("/cache/vulkan_pipeline_cache/hdrplus.bin"),
            VulkanPipelineCache.file(cacheDir, VulkanPipelineCache.HOST_HDRPLUS)
        )
    }

    @Test fun `pathFor renders the absolute file path`() {
        assertEquals(
            "/cache/vulkan_pipeline_cache/hdrplus.bin",
            VulkanPipelineCache.pathFor(cacheDir, VulkanPipelineCache.HOST_HDRPLUS)
        )
    }

    @Test fun `unknown host is rejected`() {
        try {
            VulkanPipelineCache.file(cacheDir, "ncnn")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Contract: only first-party hosts get a cache file.
        }
    }

    @Test fun `resolving paths creates nothing on disk`() {
        val missing = File("/definitely/not/a/real/cache/dir")
        assertFalse(VulkanPipelineCache.dir(missing).exists())
        assertFalse(VulkanPipelineCache.file(missing, VulkanPipelineCache.HOST_VF).exists())
    }
}
