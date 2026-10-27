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

/** Opt-in: writes real photographs. Seeds first-run lens setup with defaults. */
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
        // Forward burst (non-ZSL modes): fires captureBurst() instead of capture().
        val burst = args.getString("burst", "false").toBoolean()
        // HDR bracket (-2/0/+2 EV): one merged DNG and/or JPEG per press.
        val hdrBracket = args.getString("hdrBracket", "false").toBoolean()
        // ZSL merge (-e zslMerge hdrplus|mfsr, with -e mode ZSL): one merged
        // file per press. Enables that merge and disables source-burst saves
        // for the run only; the user's values are restored afterwards.
        val zslMerge = args.getString("zslMerge")
        // ETTR (-e ettr true, with -e mode AUTO): enabled for the run only;
        // the user's value is restored afterwards.
        val ettrOn = args.getString("ettr") == "true"
        // AgX (JPEG-look) scopes, as the UI selects for JPEG formats; default linear.
        val agxScope = args.getString("agxScope", "false").toBoolean()
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        // Headless runs skip the modal first-run lens discovery exactly like a
        // user tapping SAVE with defaults (keys mirror MainActivity.PREFS_NAME /
        // KEY_LENS_SETUP_COMPLETE, as in VideoModeUiTest).
        context.getSharedPreferences("rawlens_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("lens_setup_complete", true).commit()
        val mergePrefs = context.getSharedPreferences("rawlens_settings", android.content.Context.MODE_PRIVATE)
        val mergeKeys = listOf("hdr_plus_enabled", "hdr_plus_keep_source_burst", "mfsr_enabled",
            "mfsr_keep_source_burst", "ettr_enabled")
        val savedMerge = mergePrefs.all.filterKeys { it in mergeKeys }
        if (ettrOn) mergePrefs.edit().putBoolean("ettr_enabled", true).commit()
        if (zslMerge != null) {
            mergePrefs.edit()
                .putBoolean("hdr_plus_enabled", zslMerge == "hdrplus")
                .putBoolean("mfsr_enabled", zslMerge == "mfsr")
                .putBoolean("hdr_plus_keep_source_burst", false)
                .putBoolean("mfsr_keep_source_burst", false)
                .commit()
        }
        fun restoreMergePrefs() {
            if (zslMerge == null && !ettrOn) return
            val edit = mergePrefs.edit()
            for (key in mergeKeys) {
                val value = savedMerge[key]
                if (value is Boolean) edit.putBoolean(key, value) else edit.remove(key)
            }
            edit.commit()
        }
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
            // -e mfsr <n>: ZSL MFSR merge of n frames (one artifact per press).
            args.getString("mfsr")?.toInt()?.let {
                assertTrue(controller.setMfsrSettings(
                    controller.mfsrSettings().copy(enabled = true, keepSourceBurst = false)))
            }
            controller.setRawZslFrameCount(args.getString("mfsr")?.toInt() ?: frames)
            controller.setZslHybridTopup(hybrid)
            controller.setCaptureExposureMode(mode)
            assertTrue(controller.setCaptureFormat(format))
            // Developed output is a user preference (JPEG or JXL); the artifact
            // count below expects .jpg, so pin JPEG for this run (controller
            // state only, the saved preference is untouched).
            assertTrue(controller.setDevelopedFormat(DevelopedFormat.JPEG))
            controller.setScopeAgxApplied(agxScope)
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
            val burstFrames = RawCameraController::class.java.getDeclaredField("BURST_FRAME_COUNT")
                .apply { isAccessible = true }.getInt(null)
            val expectedFrames = when {
                burst -> burstFrames
                mode != CaptureExposureMode.ZSL -> 1
                format.includesJpeg -> minOf(frames, 6)
                else -> frames
            }
            val outputs = if (hdrBracket || zslMerge != null) (if (format == CaptureFormat.JPEG_DNG) 2 else 1)
                else expectedFrames * (if (format == CaptureFormat.JPEG_DNG) 2 else 1)
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
                    when {
                        hdrBracket -> controller.captureHdrBracket()
                        burst -> controller.captureBurst()
                        else -> controller.capture()
                    }
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
                // RAW-only sessions (ladder combo 0) attach no preview stream, so the
                // TextureView never advances there; the RAW viewfinder check below
                // covers display recovery in every session.
                val sessionHasPreview = RawCameraController::class.java.getDeclaredField("previewSize")
                    .apply { isAccessible = true }.get(controller) != null
                if (sessionHasPreview) {
                    awaitCondition("Cycle $cycle Android preview froze", 15_000) {
                        uiBoolean { (activity.findViewById<AutoFitTextureView>(R.id.viewfinder).surfaceTexture?.timestamp ?: 0L) > previewTimestamp }
                    }
                }
                awaitCondition("Cycle $cycle RAW viewfinder did not recover", 15_000) {
                    uiBoolean { activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot().glActive }
                }
                assertTrue("Cycle $cycle queued extra shutter presses", artifacts() == before + outputs)
                monitorFps = false
                assertTrue("Cycle $cycle RAW VF dropped to $minObservedFps FPS (required $minimumFps)",
                    minObservedFps >= minimumFps)
                // This soak deliberately waits for the entire save; production admission
                // can now rearm sooner whenever the bounded input queue has room.
                val allSaves = RawCameraController::class.java.getDeclaredField("pendingSaveCount")
                    .apply { isAccessible = true }.get(controller) as java.util.concurrent.atomic.AtomicInteger
                awaitCondition("Cycle $cycle saves did not drain") { allSaves.get() == 0 }
                val inspected = java.util.concurrent.CountDownLatch(1)
                var heldPairs = -1
                var heldSaves = -1
                handler.post {
                    heldPairs = (RawCameraController::class.java.getDeclaredField("pairing")
                        .apply { isAccessible = true }.get(controller) as FramePairing).pendingImageCount
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
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            restoreMergePrefs()
        }
    }
}
