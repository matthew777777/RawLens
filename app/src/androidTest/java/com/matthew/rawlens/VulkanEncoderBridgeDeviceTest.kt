// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.MediaCodecInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-A: synthetic Bayer -> Vulkan superpixel -> fenced EGL blit ->
 * HW HEVC Main10 4K. Opt-in: `-e rawlensVulkanBridge true`.
 */
@RunWith(AndroidJUnit4::class)
class VulkanEncoderBridgeDeviceTest {
    @Test
    fun bayerVulkanToMain10() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensVulkanBridge true to run the Vulkan bridge probe",
            args.getString("rawlensVulkanBridge") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        assumeTrue("Vulkan bridge unavailable", VfVulkan.available)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val spv = instrumentation.targetContext.assets
            .open("shaders/vf/vf_superpixel.spv").use { it.readBytes() }
        assumeTrue("superpixel SPIR-V missing", spv.isNotEmpty())
        val gradeSpv = instrumentation.targetContext.assets
            .open("shaders/vf/vf_loggrade.spv").use { it.readBytes() }
        assumeTrue("grade SPIR-V missing", gradeSpv.isNotEmpty())
        val f16spv = instrumentation.targetContext.assets
            .open("shaders/vf/vf_superpixel_f16.spv").use { it.readBytes() }
        assumeTrue("f16 SPIR-V missing", f16spv.isNotEmpty())
        val frames = args.getString("frames", "90").toInt().coerceIn(10, 300)
        val rep = VulkanEncoderBridge.probe(
            VulkanEncoderBridge.Params(frames = frames, spv = spv, f16spv = f16spv, gradeSpv = gradeSpv)
        )
        Log.i(
            TAG, "SPIKE vbridge enc=${rep.codecName} submitted=${rep.framesSubmitted} " +
                "outBufs=${rep.outputBuffers} bytes=${rep.outputBytes} " +
                "elapsed=${rep.elapsedMs}ms submitFps=${"%.1f".format(rep.submitFps)} " +
                "fill=${"%.1f".format(rep.fillMsAvg)}ms " +
                "compute=${"%.1f".format(rep.computeMsAvg)}ms " +
                "grade=${"%.1f".format(rep.gradeMsAvg)}ms " +
                "blit=${"%.1f".format(rep.blitMsAvg)}ms " +
                "fenceWait=${"%.1f".format(rep.fenceWaitMsAvg)}ms " +
                "fenceTimeouts=${rep.fenceTimeouts} fellBack=${rep.fellBackToFinish} " +
                "bypassLuma=${rep.bypassAvgLuma} gradedLuma=${rep.gradedAvgLuma} " +
                "readbackSkipped=${rep.readbackSkipped} " +
                "outProfile=${rep.outputProfile?.let { LogVideoProbe.profileLabel(it) }} " +
                "outFmt=[${rep.outputFormat}] err=${rep.error}"
        )
        assertTrue("vulkan bridge failed: ${rep.error}", rep.error == null)
        assertTrue("no output", rep.outputBuffers > 0 && rep.outputBytes > 0)
        assertEquals(
            "not 10-bit: ${rep.outputFormat}",
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            rep.outputProfile
        )
        // Phase-B proof: the readback sampled the graded buffer (frame 0 ran
        // bypass as baseline). Skip only when the gralloc refuses CPU_READ.
        assumeTrue("readback unsupported on this gralloc", !rep.readbackSkipped)
        val bypass = rep.bypassAvgLuma
        val graded = rep.gradedAvgLuma
        assertTrue("no bypass baseline", bypass != null)
        assertTrue("no graded sample", graded != null)
        assertTrue("bypass luma implausible: $bypass", bypass!! in 0.2f..0.8f)
        assertTrue("graded luma implausible: $graded", graded!! in 0.2f..0.7f)
        assertTrue(
            "grade curve had no effect (bypass=$bypass graded=$graded)",
            Math.abs(graded - bypass) > 0.02f
        )
    }

    companion object {
        private const val TAG = "VulkanBridge"
    }
}
