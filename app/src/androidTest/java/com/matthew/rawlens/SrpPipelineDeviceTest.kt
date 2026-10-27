// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rawlens.srpipeline.SrpBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * sr-pipeline first on-device dispatch: `libsrpipeline.so` (built from
 * the shared tools/sr-pipeline sources via app CMake) runs the grey FFT
 * on the 1024 Pattern input, and the full-buffer checksums must equal
 * the desktop values to 12 digits (same shaders, same math — the values
 * `sr_device` already matched bit-identically). SPVs come from the
 * generated `sr_spv` assets (compiled by the Android build).
 *
 * Opt-in: `-e rawlensSrp true` (keeps normal runs green/fast).
 */
@RunWith(AndroidJUnit4::class)
class SrpPipelineDeviceTest {
    // Always run: a missing generated asset must fail even when GPU probes
    // are disabled. Cover both Wronski and KernelNet capture paths.
    @Test fun mfsrShadersArePackaged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val required = listOf(
            "fft", "align", "corr", "ica", "corr_direct", "ica_wg",
            "gat", "grad", "kernels", "divide", "robustness", "merge_splat",
            "grey_fft", "kernelnet"
        )
        for (name in required) {
            val bytes = context.assets.open("sr_spv/$name.spv").use { it.readBytes() }
            assertTrue("$name has a complete SPIR-V header", bytes.size >= 20 && bytes.size % 4 == 0)
            assertEquals(
                "$name SPIR-V magic", 0x07230203,
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            )
        }
    }

    @Test fun greyMatchesDesktopChecksum() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensSrp true to run the SR pipeline device probe",
            args.getString("rawlensSrp") == "true",
        )
        try {
            SrpBridge.loadLibrary()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("libsrpipeline.so unavailable: ${e.message}", false)
        }
        val handle = SrpBridge.nativeCreate()
        assumeTrue("no Vulkan compute device", handle != 0L)
        try {
            Log.i("SrpPipelineDevice", "device: ${SrpBridge.nativeDeviceInfo(handle)}")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fft = context.assets.open("sr_spv/fft.spv").use { it.readBytes() }
            val w = 1024
            val h = 1024
            val n = w * h
            // Pattern input, same formula as the sr_device harness
            // (64-bit multiply, exact float conversion).
            val inBuf = SrpBridge.directFloats(n)
            val inFb = inBuf.asFloatBuffer()
            for (i in 0 until n) {
                inFb.put((i.toULong() * 2654435761u % 1000u).toFloat() / 1000f)
            }
            inBuf.rewind()
            val outBuf = SrpBridge.directFloats(n)
            val t0 = System.nanoTime()
            val rc = SrpBridge.greyFft(handle, inBuf, outBuf, fft, w, h)
            val ms = (System.nanoTime() - t0) / 1e6
            assertEquals("greyFft status", 0, rc)
            // Checksum loop in the harness order (ascending i, Double).
            outBuf.rewind()
            val outFb = outBuf.asFloatBuffer()
            var sum = 0.0
            var wsum = 0.0
            for (i in 0 until n) {
                val v = outFb.get().toDouble()
                sum += v
                wsum += v * (i + 1)
            }
            Log.i("SrpPipelineDevice", "grey 1024 wall_ms=%.1f sum=%.6f wsum=%.6f".format(ms, sum, wsum))
            // Reference values from the desktop Kotlin-loop probe (same
            // input formula, same accumulation order): the old sr_device
            // C++ harness number for wsum (...354.69) was FMA-tainted host
            // noise, not device output — desktop recomputed with this loop
            // gives ...363.90274 exactly. 1e-12 relative would still trip
            // on FMA-level (3e-11) divergence.
            assertEquals("grey sum vs desktop", 523763.5940875411, sum, 1e-12 * abs(523763.5940875411))
            assertEquals("grey wsum vs desktop", 274603403363.90274, wsum, 1e-12 * abs(274603403363.90274))
        } finally {
            SrpBridge.nativeDestroy(handle)
        }
    }
}
