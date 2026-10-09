// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.io.File

/**
 * On-disk layout for the persisted Vulkan pipeline caches (one file per
 * host; each host owns its own VkDevice, so cache *objects* cannot be
 * shared — the sharing is the warmed bytes across runs). Pure file math,
 * JVM-testable; native code creates the directories on save.
 */
object VulkanPipelineCache {
    const val DIR_NAME = "vulkan_pipeline_cache"
    const val HOST_VF = "vf"
    const val HOST_GALOSH = "galosh"
    const val HOST_HDRPLUS = "hdrplus"

    private val HOSTS = setOf(HOST_VF, HOST_GALOSH, HOST_HDRPLUS)

    /** `<cacheDir>/vulkan_pipeline_cache` (not created). */
    fun dir(cacheDir: File): File = File(cacheDir, DIR_NAME)

    /** `<cacheDir>/vulkan_pipeline_cache/<host>.bin` (not created). */
    fun file(cacheDir: File, host: String): File {
        require(host in HOSTS) { "unknown pipeline-cache host: $host" }
        return File(dir(cacheDir), "$host.bin")
    }

    /** Absolute path string of [file], for the native setters. */
    fun pathFor(cacheDir: File, host: String): String = file(cacheDir, host).absolutePath
}
