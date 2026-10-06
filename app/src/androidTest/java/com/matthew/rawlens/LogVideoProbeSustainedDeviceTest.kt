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
 * Sustained soak: paced 30fps EGL submit for N seconds into HW HEVC
 * Main10 3840x2160 + concurrent drain. Asserts the encoder-reported
 * output profile is Main10 and logs sustained submit fps.
 *
 * Run:
 *   ./gradlew :app:installRelease :app:installReleaseAndroidTest
 *   adb shell am instrument -w -e rawlensLogSustained true [-e seconds 10] \
 *     -e class com.matthew.rawlens.LogVideoProbeSustainedDeviceTest \
 *     com.matthew.rawlens.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class LogVideoProbeSustainedDeviceTest {
    @Test
    fun sustainedMain10_4k30() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensLogSustained true to run the sustained probe",
            args.getString("rawlensLogSustained") == "true"
        )
        val seconds = args.getString("seconds", "10").toInt().coerceIn(3, 30)
        val rep = LogVideoProbe.probeSustained(seconds)
        Log.i(
            TAG, "SPIKE sustain enc=${rep.codecName} req=${rep.secondsRequested}s " +
                "submitted=${rep.framesSubmitted} outBufs=${rep.outputBuffers} " +
                "bytes=${rep.outputBytes} elapsed=${rep.elapsedMs}ms " +
                "submitFps=${"%.1f".format(rep.submitFps)} " +
                "outProfile=${rep.outputProfile?.let { LogVideoProbe.profileLabel(it) }} " +
                "outFmt=[${rep.outputFormat}] err=${rep.error}"
        )
        assumeTrue("sustain failed to run: ${rep.error}", rep.codecName != null && rep.error != "no HW HEVC 4K30 Surface encoder")
        assertTrue("no output: ${rep.error}", rep.outputBuffers > 0 && rep.outputBytes > 0)
        assertEquals(
            "encoder output is not 10-bit: ${rep.outputFormat}",
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            rep.outputProfile
        )
    }

    companion object {
        private const val TAG = "LogVideoProbe"
    }
}
