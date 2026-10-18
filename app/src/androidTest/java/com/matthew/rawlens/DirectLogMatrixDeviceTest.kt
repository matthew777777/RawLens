// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Headless cross-device matrix (no UI): BT709/SLOG3/HLG x 1080p/4K x
 * 24/30fps direct through [DirectLogRecorder], plus a stab warp pass on
 * one BT709 take. Each case either records a playable file or fails
 * with the negotiated error; outcomes are collected and asserted once
 * so one case never hides the rest.
 *
 * Opt-in (opens the camera): `-e rawlensDirectLogMatrix true
 * [-e seconds 5]`. Audio off for determinism; stab on for the warp case.
 */
@RunWith(AndroidJUnit4::class)
class DirectLogMatrixDeviceTest {
    data class Case(
        val profile: DirectLogProfile,
        val width: Int,
        val height: Int,
        val fps: Int,
    ) {
        val label = "${profile.name}_${width}x${height}_${fps}fps"
    }

    @Test fun runMatrix() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensDirectLogMatrix true to open the camera and record",
            args.getString("rawlensDirectLogMatrix") == "true"
        )
        val seconds = args.getString("seconds", "5").toInt().coerceIn(3, 10)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()

        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.first { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val failures = ArrayList<String>()
        var warpSource: File? = null
        var warpSidecar: File? = null

        val cases = listOf(
            Case(DirectLogProfile.BT709, 1920, 1080, 24),
            Case(DirectLogProfile.BT709, 1920, 1080, 30),
            Case(DirectLogProfile.BT709, 2560, 1440, 24),
            Case(DirectLogProfile.BT709, 2560, 1440, 30),
            Case(DirectLogProfile.BT709, 3840, 2160, 24),
            Case(DirectLogProfile.BT709, 3840, 2160, 30),
            Case(DirectLogProfile.SLOG3, 1920, 1080, 30),
            Case(DirectLogProfile.HLG, 1920, 1080, 30),
            Case(DirectLogProfile.SLOG3, 2560, 1440, 30),
            Case(DirectLogProfile.HLG, 2560, 1440, 30),
        )
        for (c in cases) {
            val file = File(context.cacheDir, "matrix_${c.label}.mp4")
            if (file.exists()) file.delete()
            val rec = DirectLogRecorder(manager, cameraId, context)
            rec.audioEnabled = false
            rec.width = c.width
            rec.height = c.height
            rec.fps = c.fps
            rec.bitrate = LogVideoProbe.defaultBitrateFor(c.width, c.height)
            rec.logProfile = c.profile
            try {
                rec.start(file)
                repeat(seconds * 2) { SystemClock.sleep(500) }
                val stats = rec.stop()
                val ok = file.exists() && file.length() > 0 && stats.framesGraded > 0
                Log.i(
                    TAG, "MATRIX ${c.label} recorded=${file.length()}B " +
                        "graded=${stats.framesGraded} dropped=${stats.framesDropped} " +
                        "degraded=${stats.degradedFrames} errors=${stats.frameErrors} " +
                        "10bit=${stats.true10Bit} profile=${stats.profile}"
                )
                if (!ok) {
                    failures += "${c.label}: empty file or no graded frames"
                    continue
                }
                if (c == Case(DirectLogProfile.BT709, 1920, 1080, 30) &&
                    stats.hasStabGyro
                ) {
                    warpSource = file
                    warpSidecar = File(
                        file.parent,
                        file.nameWithoutExtension + StabSidecar.FILE_SUFFIX
                    )
                }
            } catch (e: Exception) {
                val expectFail = c.profile != DirectLogProfile.BT709
                Log.i(TAG, "MATRIX ${c.label} start failed: ${e.message}")
                if (!expectFail || !e.message.orEmpty().contains("10-bit")) {
                    failures += "${c.label}: unexpected failure: ${e.message}"
                }
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
            }
        }

        val src = warpSource
        val side = warpSidecar
        if (src != null && side != null && side.isFile) {
            val out = File(context.cacheDir, "matrix_warp_1080p30.mp4")
            if (out.exists()) out.delete()
            val report = DirectLogStabilizer(context).stabilize(
                src, side, out, DirectLogProfile.BT709
            ) { done, total ->
                if (done % 30 == 0) Log.i(TAG, "MATRIX warp $done/$total")
            }
            Log.i(
                TAG, "MATRIX warp ok=${report.ok} decoded=${report.framesDecoded} " +
                    "warped=${report.framesWarped} degraded=${report.framesDegraded} " +
                    "bytes=${report.outputBytes} error=${report.error}"
            )
            if (!report.ok || !out.isFile || out.length() == 0L) {
                failures += "warp: ok=${report.ok} error=${report.error}"
            }
        } else {
            failures += "warp: no BT709 take with gyro sidecar to warp"
        }

        assertTrue(
            "matrix failures:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    companion object {
        private const val TAG = "LogMatrixDevice"
    }
}
