// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Force-10-bit probe: attempts Main10 / Main10HDR10 configure by codec
 * name even when the advertised profile list says Main-only (MotionCam
 * style). Log-only, never fails the build — every outcome is data.
 *
 * Run:
 *   ./gradlew :app:installRelease :app:installReleaseAndroidTest
 *   adb shell am instrument -w -e rawlensLogForceMain10 true [-e frames 15] \
 *     -e class com.matthew.rawlens.LogVideoProbeForceMain10DeviceTest \
 *     com.matthew.rawlens.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class LogVideoProbeForceMain10DeviceTest {
    @Test
    fun forceMain10ByName() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensLogForceMain10 true to run the force-10-bit probe",
            args.getString("rawlensLogForceMain10") == "true"
        )
        val frames = args.getString("frames", "15").toInt().coerceIn(1, 60)
        val report = LogVideoProbe.probeForceMain10(frames)
        for (c in report.candidates) {
            Log.i(
                TAG, "SPIKE enc=${c.name} hw=${c.isHardware} " +
                    "main10=${c.supportsMain10} sizeRate=${c.supportsSizeRate} " +
                    "surface=${c.supportsSurface}"
            )
        }
        if (report.attempts.isEmpty()) {
            Log.i(TAG, "SPIKE force: no attempts, err=${report.error}")
        }
        for (a in report.attempts) {
            Log.i(
                TAG, "SPIKE force enc=${a.codecName} profile=${a.profileLabel} " +
                    "advertised=[${a.advertisedProfiles.joinToString { LogVideoProbe.profileLabel(it) }}] " +
                    "cfgOk=${a.configureOk} frames=${a.framesSubmitted} " +
                    "outBufs=${a.outputBuffers} bytes=${a.outputBytes} " +
                    "outProfile=${a.outputProfile?.let { LogVideoProbe.profileLabel(it) }} " +
                    "outFmt=[${a.outputFormat}] err=${a.error}"
            )
        }
        // Intentionally no hard asserts: a total rejection IS the answer
        // (this SoC cannot do HW Main10 encode). Success = cfgOk + bytes>0.
    }

    companion object {
        private const val TAG = "LogVideoProbe"
    }
}
