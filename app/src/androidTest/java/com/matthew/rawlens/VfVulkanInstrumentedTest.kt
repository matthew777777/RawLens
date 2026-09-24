// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Vulkan viewfinder recovery: after a wedged queue or lost device,
 * [VfVulkan.reinitNative] must tear down and rebuild the device so the next
 * frame re-probes a fresh device instead of failing on a dead one forever.
 * Asserts through the real JNI (including symbol linkage), with no camera.
 */
@RunWith(AndroidJUnit4::class)
class VfVulkanInstrumentedTest {
    @Test fun reinitRebuildsDeviceAndReimports() {
        assumeTrue("vf native library unavailable", VfVulkan.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val spv = try {
            context.assets.open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
        assumeTrue("vf superpixel SPIR-V missing", spv != null)
        val init = VfVulkan.initNative(spv!!)
        assumeTrue("vulkan unavailable (${VfVulkan.describe(init)})", init == VfVulkan.OK)
        // Same export-buffer contract as RawViewfinder.runVulkanSuperpixel.
        val export = HardwareBuffer.create(
            680, 510, HardwareBuffer.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        try {
            assertEquals(
                VfVulkan.OK,
                VfVulkan.ensureOutputNative(export)
            )
            // Teardown + rebuild on the same device must succeed exactly when
            // bring-up did: identical init path, no wedged state retained.
            assertEquals(VfVulkan.OK, VfVulkan.reinitNative(spv))
            // Imports were dropped by the reinit and must rebuild on demand.
            assertEquals(VfVulkan.OK, VfVulkan.ensureOutputNative(export))
        } finally {
            export.close()
            VfVulkan.resetNative()
        }
    }

    @Test fun reinitRejectsMalformedSpirv() {
        assumeTrue("vf native library unavailable", VfVulkan.available)
        assertEquals(
            VfVulkan.BAD_ARGUMENT,
            VfVulkan.reinitNative(ByteArray(7))
        )
    }
}
