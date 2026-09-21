// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Intent
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: writes real photographs. Configure lens access in the app before running. */
class ZslCaptureSoakTest {
    @Test fun sequentialCaptureSaveRearmsAndKeepsBothPreviewsLive() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e rawlensSoak true to create real capture artifacts", args.getString("rawlensSoak") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cycles = args.getString("cycles", "50").toInt()
        val frames = args.getString("frames", "2").toInt()
        val hybrid = args.getString("hybrid", "true").toBoolean()
        val mode = CaptureExposureMode.valueOf(args.getString("mode", "ZSL"))
        val minimumFps = args.getString("minFps", "0").toFloat()
        var minObservedFps = Float.MAX_VALUE
        var monitorFps = false
        val exposureMs = args.getString("exposureMs", "0").toLong()
        val lifecycleEvery = args.getString("lifecycleEvery", "0").toInt()
        val format = CaptureFormat.valueOf(args.getString("format", "DNG_ONLY"))
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        android.util.Log.i("RawLensSoak", "Launching activity")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        android.util.Log.i("RawLensSoak", "Activity launched")
        val controller = MainActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
            .get(activity) as RawCameraController
        fun uiBoolean(check: () -> Boolean): Boolean {
            var value = false
            instrumentation.runOnMainSync { value = check() }
            return value
        }
        fun awaitCondition(label: String, timeoutMs: Long = 180_000L, check: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (!check() && SystemClock.elapsedRealtime() < deadline) {
                if (monitorFps) {
                    val stats = activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot()
                    if (stats.fps > 0f) minObservedFps = minOf(minObservedFps, stats.fps)
                }
                SystemClock.sleep(100)
            }
            assertTrue(label, check())
        }
        fun artifacts(): Int = context.contentResolver.query(MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.IS_PENDING}=0 AND (${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?)",
            arrayOf("DCIM/RawLens/%", "%.dng", "%.jpg"), null)?.use { it.count } ?: 0
        try {
            controller.setRawZslFrameCount(frames)
            controller.setZslHybridTopup(hybrid)
            controller.setCaptureExposureMode(mode)
            assertTrue(controller.setCaptureFormat(format))
            if (exposureMs > 0) {
                controller.setManualControl(ManualControl.ISO, 100L)
                controller.setManualControl(ManualControl.SHUTTER, exposureMs * 1_000_000L)
            }
            // Changing ring capacity recreates the session asynchronously. Drain the
            // controller's configuration messages before observing readiness on the UI.
            val configured = java.util.concurrent.CountDownLatch(1)
            val handler = RawCameraController::class.java.getDeclaredField("cameraHandler")
                .apply { isAccessible = true }.get(controller) as android.os.Handler
            handler.post { configured.countDown() }
            assertTrue(configured.await(10, java.util.concurrent.TimeUnit.SECONDS))
            SystemClock.sleep(1500)
            awaitCondition("Initial RAW preview never became live", 30_000) {
                uiBoolean { activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot().glActive &&
                    activity.findViewById<View>(R.id.shutter).isEnabled }
            }
            android.util.Log.i("RawLensSoak", "Preview ready")
            val expectedFrames = if (mode != CaptureExposureMode.ZSL) 1 else if (format.includesJpeg) minOf(frames, 6) else frames
            val outputs = expectedFrames * (if (format == CaptureFormat.JPEG_DNG) 2 else 1)
            repeat(cycles) { cycle ->
                awaitCondition("Cycle $cycle did not rearm") {
                    uiBoolean { activity.findViewById<View>(R.id.shutter).isEnabled }
                }
                if (!hybrid) SystemClock.sleep(maxOf(3000L, frames * maxOf(300L, exposureMs))) // allow the strict buffered selection to refill
                android.util.Log.i("RawLensSoak", "Cycle $cycle counting existing artifacts")
                val before = artifacts()
                minObservedFps = Float.MAX_VALUE
                monitorFps = true
                android.util.Log.i("RawLensSoak", "Cycle $cycle firing; artifacts=$before")
                var previewTimestamp = 0L
                instrumentation.runOnMainSync {
                    previewTimestamp = activity.findViewById<AutoFitTextureView>(R.id.viewfinder).surfaceTexture?.timestamp ?: 0L
                    controller.capture()
                    // These presses must be rejected, including before the first handler dispatch.
                    repeat(3) { controller.capture() }
                }
                if (lifecycleEvery > 0 && (cycle + 1) % lifecycleEvery == 0) {
                    val saves = RawCameraController::class.java.getDeclaredField("pendingSaveCount")
                        .apply { isAccessible = true }.get(controller) as java.util.concurrent.atomic.AtomicInteger
                    awaitCondition("Cycle $cycle never entered saving") { saves.get() > 0 }
                    instrumentation.runOnMainSync {
                        activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    SystemClock.sleep(1000)
                    context.startActivity(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                awaitCondition("Cycle $cycle missing artifacts: expected $outputs new files") { artifacts() >= before + outputs }
                awaitCondition("Cycle $cycle shutter remained disabled after save") {
                    uiBoolean { activity.findViewById<View>(R.id.shutter).isEnabled }
                }
                awaitCondition("Cycle $cycle Android preview froze", 15_000) {
                    uiBoolean { (activity.findViewById<AutoFitTextureView>(R.id.viewfinder).surfaceTexture?.timestamp ?: 0L) > previewTimestamp }
                }
                awaitCondition("Cycle $cycle RAW viewfinder did not recover", 15_000) {
                    uiBoolean { activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot().glActive }
                }
                assertTrue("Cycle $cycle queued extra shutter presses", artifacts() == before + outputs)
                monitorFps = false
                assertTrue("Cycle $cycle RAW VF dropped to $minObservedFps FPS (required $minimumFps)",
                    minObservedFps >= minimumFps)
                val inspected = java.util.concurrent.CountDownLatch(1)
                var heldPairs = -1
                var heldSaves = -1
                handler.post {
                    heldPairs = (RawCameraController::class.java.getDeclaredField("pendingImages")
                        .apply { isAccessible = true }.get(controller) as Map<*, *>).size
                    heldSaves = (RawCameraController::class.java.getDeclaredField("pendingSaveCount")
                        .apply { isAccessible = true }.get(controller) as java.util.concurrent.atomic.AtomicInteger).get()
                    inspected.countDown()
                }
                assertTrue(inspected.await(10, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue("Save ownership retained after rearm", heldSaves == 0)
                assertTrue("Unbounded image pairing retention: $heldPairs", heldPairs <= 12)
                android.util.Log.i("RawLensSoak", "Cycle ${cycle + 1}/$cycles passed; artifacts=${artifacts()} " +
                    "pssKb=${android.os.Debug.getPss()} nativeBytes=${android.os.Debug.getNativeHeapAllocatedSize()} " +
                    "pairs=$heldPairs saves=$heldSaves minFps=$minObservedFps gpuOnly=${RawImageOwnership.gpuOnlyCount()} " +
                    "vf=${activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot()}")
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
