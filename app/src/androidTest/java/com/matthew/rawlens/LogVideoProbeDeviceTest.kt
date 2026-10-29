// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
 *   ./gradlew :app:installGithubRelease
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

    /**
     * On-device negotiation table (no camera needed): dumps the per-size
     * encoder capability selectors the direct-log ladder negotiates
     * against and asserts their internal consistency. Always runs (no
     * opt-in): on encoder-less devices every selector is false, which
     * still validates the failure-branch inputs the recorder reports.
     */
    @Test
    fun dumpNegotiationTable() {
        for ((w, h) in listOf(1920 to 1080, 3840 to 2160)) {
            for (fps in listOf(24, 30)) {
                val main10 = LogVideoProbe.supportsMain10At(w, h, fps)
                val p010 = LogVideoProbe.supportsP010At(w, h, fps)
                val main8 = LogVideoProbe.supportsMain8At(w, h, fps)
                val direct = LogVideoProbe.preferredP010EncoderAt(w, h, fps)
                val surface = LogVideoProbe.preferredHwEncoderAt(w, h, fps)
                val main8Name = LogVideoProbe.preferredMain8EncoderAt(w, h, fps)
                Log.i(
                    TAG, "PROBE ${w}x${h}@${fps} main10=$main10 p010=$p010 " +
                        "main8=$main8 direct=$direct surface=$surface main8enc=$main8Name"
                )
                if (direct != null) assertTrue("P010 without Main10@$w,$h,$fps", p010)
                if (surface == null) assertTrue(
                    "Main10 without Surface pick @$w,$h,$fps",
                    !main10
                )
                val plan = LogVideoProbe.selectEncodePlan(w, h, fps, { a, b, f ->
                    LogVideoProbe.preferredP010EncoderAt(a, b, f)
                }, { a, b, f ->
                    if (LogVideoProbe.writerPathAvailable()) {
                        LogVideoProbe.preferredHwEncoderAt(a, b, f)
                    } else {
                        null
                    }
                })
                val sdr = LogVideoProbe.selectSdrPlan(w, h, fps, sdrAllowed = true) { a, b, f ->
                    LogVideoProbe.preferredMain8EncoderAt(a, b, f)
                }
                Log.i(TAG, "PROBE plan ${w}x${h}@${fps} -> $plan sdr=$sdr")
                if (plan == null) {
                    assertEquals(
                        "null plan must have no direct pick",
                        null, direct
                    )
                }
            }
        }
        Log.i(
            TAG, "PROBE writerPathAvailable=${LogVideoProbe.writerPathAvailable()} " +
                "maxBitrateMbps=${LogVideoProbe.maxBitrateMbps} " +
                "bitrateModes=${LogVideoProbe.supportedBitrateModes}"
        )
    }

    companion object {
        private const val TAG = "LogVideoProbe"
    }
}
