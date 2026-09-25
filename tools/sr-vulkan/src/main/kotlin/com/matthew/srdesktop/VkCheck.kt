// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.srdesktop

import com.matthew.rawlens.SrVulkan
import kotlin.system.exitProcess

/**
 * Driver proof for the unified Vulkan backend: initializes the native
 * `srvulkan` library, reports every physical device, and creates real SR
 * shader modules from the packaged SPIR-V. Run via either CLI:
 * `mosaic-desktop vkcheck` / `linear-sr-desktop vkcheck`.
 */
object VkCheck {
    private val PROBE_SHADERS = listOf("clear_reference.spv", "merge_accumulate.spv")

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run()
        } catch (failure: Exception) {
            System.err.println("vkcheck: FAILED: ${failure.message}")
            exitProcess(1)
        }
    }

    fun run() {
        SrVulkan.load()
        SrVulkan.open().use { vk ->
            println("vkcheck: ${vk.deviceInfo()}")
            for (i in 0 until vk.deviceCount()) {
                println("vkcheck: device[$i]=${vk.deviceName(i)}")
            }
            for (name in PROBE_SHADERS) {
                val bytes = VkCheck::class.java.getResourceAsStream("/spirv/$name")?.readBytes()
                    ?: throw IllegalStateException(
                        "missing /spirv/$name (build with :tools:sr-vulkan:compileShaders)"
                    )
                val module = vk.loadModule(bytes)
                try {
                    println("vkcheck: module $name ok (handle=$module, ${bytes.size} bytes)")
                } finally {
                    vk.destroyModule(module)
                }
            }
        }
        println("vkcheck: OK")
    }
}
