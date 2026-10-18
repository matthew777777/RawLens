// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal rgb8 lifecycle repro (no camera): device init, rgb8 init
 * WITHOUT grade (the warp pattern), reset (take teardown), rgb8 init
 * again. Opt-in: `-e rawlensRgb8Repro true`. Pass = both inits return
 * OK; a native crash here isolates the post-reset twin realloc path.
 */
@RunWith(AndroidJUnit4::class)
class Rgb8ReproDeviceTest {
    @Test fun reproInitResetInit() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensRgb8Repro true",
            args.getString("rawlensRgb8Repro") == "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun asset(path: String): ByteArray =
            context.assets.open(path).use { it.readBytes() }
        VfVulkan.setPipelineCachePathNative(
            VulkanPipelineCache.pathFor(context.cacheDir, VulkanPipelineCache.HOST_VF)
        )
        assertEquals(
            VfVulkan.OK,
            VfVulkan.initNative(asset("shaders/vf/vf_superpixel.spv"))
        )
        Log.i(TAG, "repro: device up")
        val rgb8 = asset("shaders/vf/vf_p010torgb8.spv")
        // Without the grade chain the allocator must fail CLEANLY
        // (DEVICE_FAILED) — several Mali drivers segfault on
        // null-layout allocation instead of erroring.
        val bare = VfRgb8Convert.initRgb8Native(rgb8)
        Log.i(TAG, "repro: init-without-grade=$bare")
        assertEquals(3, bare)
        // Grade chain first (record/warp order), then rgb8 init must work.
        assertEquals(
            VfVulkan.OK,
            VfLogGrade.initGradeNative(asset("shaders/vf/vf_loggrade.spv"))
        )
        assertEquals(
            VfVulkan.OK,
            VfLogGrade.initGradeYuvNative(asset("shaders/vf/vf_gradeyuv.spv"))
        )
        val first = VfRgb8Convert.initRgb8Native(rgb8)
        Log.i(TAG, "repro: init-with-grade=$first")
        assertEquals(VfVulkan.OK, first)
        VfVulkan.resetNative()
        Log.i(TAG, "repro: reset done")
        // Post-reset the grade chain is gone with the twins: rgb8 init
        // alone must again fail cleanly, not crash.
        val second = VfRgb8Convert.initRgb8Native(rgb8)
        Log.i(TAG, "repro: init-post-reset=$second")
        assertEquals(3, second)
    }

    companion object {
        private const val TAG = "Rgb8Repro"
    }
}
