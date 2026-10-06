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
 * AHB->encoder bridge: CPU-filled AHB (Vulkan-export stand-in) -> EGL blit
 * -> HW HEVC Main10 4K surface. Opt-in: `-e rawlensAhbBridge true`.
 */
@RunWith(AndroidJUnit4::class)
class AhbEncoderBridgeDeviceTest {
    @Test
    fun ahbBlitToMain10() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensAhbBridge true to run the AHB bridge probe",
            args.getString("rawlensAhbBridge") == "true"
        )
        assumeTrue("rawLensVfEgl native library unavailable", VfEglImport.available)
        val frames = args.getString("frames", "90").toInt().coerceIn(10, 300)
        val rep = AhbEncoderBridge.probe(AhbEncoderBridge.Params(frames = frames))
        Log.i(
            TAG, "SPIKE bridge enc=${rep.codecName} submitted=${rep.framesSubmitted} " +
                "outBufs=${rep.outputBuffers} bytes=${rep.outputBytes} " +
                "elapsed=${rep.elapsedMs}ms submitFps=${"%.1f".format(rep.submitFps)} " +
                "fillAvg=${"%.1f".format(rep.fillMsAvg)}ms blitAvg=${"%.1f".format(rep.blitMsAvg)}ms " +
                "outProfile=${rep.outputProfile?.let { LogVideoProbe.profileLabel(it) }} " +
                "outFmt=[${rep.outputFormat}] err=${rep.error}"
        )
        assertTrue("bridge failed: ${rep.error}", rep.error == null)
        assertTrue("no output", rep.outputBuffers > 0 && rep.outputBytes > 0)
        assertEquals(
            "not 10-bit: ${rep.outputFormat}",
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            rep.outputProfile
        )
    }

    companion object {
        private const val TAG = "AhbBridge"
    }
}
