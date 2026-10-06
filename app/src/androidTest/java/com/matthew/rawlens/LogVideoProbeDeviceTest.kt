// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device probe: HEVC Main10 3840x2160@30 75 Mbps BT.709 SDR.
 *
 * Opt-in: `-e rawlensLogProbe true [-e frames 30]`.
 * Log-only on capability miss (assume), hard fail only when an encoder
 * claims support but configure/surface/drain breaks.
 *
 * Run:
 *   ./gradlew :app:installRelease
 *   adb shell am instrument -w -e rawlensLogProbe true \
 *     -e class com.matthew.rawlens.LogVideoProbeDeviceTest \
 *     com.matthew.rawlens.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class LogVideoProbeDeviceTest {
    @Test
    fun hevc10_4k30_75mBt709() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensLogProbe true to run the encoder probe",
            args.getString("rawlensLogProbe") == "true"
        )
        val frames = args.getString("frames", "30").toInt().coerceIn(1, 120)
        val report = LogVideoProbe.probe(frames)
        for (c in report.candidates) {
            Log.i(
                TAG, "SPIKE enc=${c.name} hw=${c.isHardware} " +
                    "main10=${c.supportsMain10} sizeRate=${c.supportsSizeRate} " +
                    "surface=${c.supportsSurface}"
            )
        }
        Log.i(
            TAG, "SPIKE selected=${report.selected} configured=${report.configured} " +
                "frames=${report.framesSubmitted} outBufs=${report.outputBuffers} " +
                "bytes=${report.outputBytes} err=${report.error}"
        )
        assumeTrue(
            "No HEVC Main10 4K30 Surface encoder on this device: ${report.error}",
            report.selected != null
        )
        assertTrue("configure failed: ${report.error}", report.configured)
        assertNotNull(report.selected)
        assertTrue(
            "no encoder output (bytes=${report.outputBytes}): ${report.error}",
            report.outputBytes > 0 && report.outputBuffers > 0
        )
    }

    companion object {
        private const val TAG = "LogVideoProbe"
    }
}
