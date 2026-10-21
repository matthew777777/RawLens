// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * V8 (docs/refactor/video-study.md): dumps every key/value of the repeating
 * request each recorder builds, plus the manual slider ranges, for a fixed
 * set of manual/lock states. Run on the build before and after a change and
 * diff the files. Opens the back camera; no session, no capture.
 * Opt-in: `-e rawlensVideoRequests <label>` (output: files/video-requests-<label>.txt).
 */
class VideoRequestSnapshotDeviceTest {
    @Test
    fun dumpRequests() {
        val label = InstrumentationRegistry.getArguments().getString("rawlensVideoRequests")
        assumeTrue("Pass -e rawlensVideoRequests <label>", label != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.CAMERA").close()
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        val chars = manager.getCameraCharacteristics(id)
        val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width * it.height }
        val thread = HandlerThread("VideoRequestSnapshot").apply { start() }
        val opened = CountDownLatch(1)
        var device: CameraDevice? = null
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { device = d; opened.countDown() }
            override fun onDisconnected(d: CameraDevice) { d.close(); opened.countDown() }
            override fun onError(d: CameraDevice, error: Int) { d.close(); opened.countDown() }
        }, Handler(thread.looper))
        opened.await(10, TimeUnit.SECONDS)
        val cam = device ?: error("camera $id did not open")
        val cases = listOf(
            "auto" to (VideoManualSnapshot() to VideoRecordLocks()),
            "iso+shutter" to (VideoManualSnapshot(iso = 800, shutterNanos = 8_000_000L) to VideoRecordLocks()),
            "iso-only" to (VideoManualSnapshot(iso = 3200) to VideoRecordLocks()),
            "shutter-too-long" to (VideoManualSnapshot(shutterNanos = 500_000_000L) to VideoRecordLocks()),
            "wb3200" to (VideoManualSnapshot(wbKelvin = 3200) to VideoRecordLocks()),
            "focus" to (VideoManualSnapshot(focusDiopters = 2.5f) to VideoRecordLocks()),
            "ev+2" to (VideoManualSnapshot(evIndex = 2) to VideoRecordLocks()),
            "ev-out-of-range" to (VideoManualSnapshot(evIndex = 99) to VideoRecordLocks()),
            "locks" to (VideoManualSnapshot() to VideoRecordLocks(lockExposure = true, lockWb = true, lockFocus = true)),
            "all-manual" to (VideoManualSnapshot(iso = 400, shutterNanos = 20_000_000L, wbKelvin = 6500,
                focusDiopters = 0.5f, evIndex = -1) to VideoRecordLocks(lockFocus = true)),
        )
        val out = StringBuilder()
        try {
            for ((name, case) in cases) {
                val (manual, locks) = case
                val log = DirectLogRecorder(manager, id, context).apply {
                    videoManual = manual; videoLocks = locks
                    videoMeteredIso = 250; videoMeteredShutterNanos = 16_000_000L; videoMeteredFocus = 1.25f
                }
                val raw = RawVideoRecorder(manager, id, size, context).apply {
                    videoManual = manual; videoLocks = locks
                    videoMeteredIso = 250; videoMeteredShutterNanos = 16_000_000L; videoMeteredFocus = 1.25f
                }
                for ((who, apply, range) in listOf(
                    Triple("directlog", { b: CaptureRequest.Builder -> log.applyVideoManualToRequest(b, chars) },
                        { c: ManualControl -> log.videoControlRange(c) }),
                    Triple("rawvideo", { b: CaptureRequest.Builder -> raw.applyVideoManualToRequest(b, chars) },
                        { c: ManualControl -> raw.videoControlRange(c) }),
                )) {
                    val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    val result = runCatching { apply(builder) }
                    out.append("[$who $name] ${result.exceptionOrNull()?.let { "THREW ${it.message}" } ?: "ok"}\n")
                    val req = builder.build()
                    for (key in req.keys.sortedBy { it.name }) {
                        out.append("  ${key.name}=${show(req.get(key))}\n")
                    }
                    for (c in ManualControl.entries) out.append("  range.$c=${range(c)}\n")
                }
            }
        } finally {
            cam.close()
            thread.quitSafely()
        }
        File(context.getExternalFilesDir(null), "video-requests-$label.txt").writeText(out.toString())
    }

    private fun show(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.contentToString()
        is FloatArray -> v.contentToString()
        is LongArray -> v.contentToString()
        is BooleanArray -> v.contentToString()
        is ByteArray -> v.contentToString()
        is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { show(it) }
        else -> v.toString()
    }
}
