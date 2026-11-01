// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * On-device checks for code moved or fixed by the 2.0 refactor that the
 * capture soak does not reach (docs/refactor/AUDIT.md). Opt-in:
 * `-e rawlensRefactor true`. Touches only this app's own state and restores it.
 */
class RefactorDeviceChecksTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: MainActivity
    private lateinit var controller: RawCameraController

    @Before
    fun launch() {
        val args: Bundle = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e rawlensRefactor true", args.getString("rawlensRefactor") == "true")
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.CAMERA").close()
        // Same headless first-run seed as ZslCaptureSoakTest / VideoModeUiTest.
        context.getSharedPreferences("rawlens_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("lens_setup_complete", true).commit()
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        controller = field(activity, "controller") as RawCameraController
        await("camera session never became ready", 30_000) { session() != null }
        SystemClock.sleep(1500)
    }

    @After
    fun finish() {
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun session(): Any? = field(controller, "session")

    private fun await(label: String, timeoutMs: Long, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(label, check())
    }

    private fun touchFocus(): PhotoAutoFocus = field(controller, "touchFocus") as PhotoAutoFocus

    @Test
    fun touchFocusReachesVerdictHoldsAndReleases() {
        val vf = activity.findViewById<android.view.View>(R.id.viewfinder)
        val cx = vf.width / 2f
        val cy = vf.height / 2f
        controller.setFocusAndMeteringPoint(cx, cy)
        await("tap never started a scan", 3_000) { touchFocus().active }
        await("tap scan never reached a verdict", 6_000) { touchFocus().completed || !touchFocus().active }
        Log.i(TAG, "tap verdict: active=${touchFocus().active} completed=${touchFocus().completed}")

        await("timed tap did not return to continuous AF", 12_000) { !touchFocus().active }
        controller.setFocusAndMeteringHold(cx + 200f, cy)
        await("hold never froze AE", 3_000) { touchFocus().aeLockIndefinite }
        await("hold scan never reached a verdict", 6_000) { touchFocus().completed }
        assertTrue("hold must keep AE locked after its verdict", touchFocus().aeLockIndefinite)
        SystemClock.sleep(PhotoAutoFocus.LOCK_HOLD_MS + 500L)
        assertTrue("long press expired like a timed tap", touchFocus().active)

        controller.resetMeteringTargets()
        await("reset did not release to continuous AF", 3_000) {
            !touchFocus().active && !touchFocus().aeLockIndefinite
        }
    }

    private fun <T> cameraRead(block: () -> T): T {
        val answer = java.util.concurrent.CompletableFuture<T>()
        val handler = field(controller, "cameraHandler") as android.os.Handler
        handler.post {
            try { answer.complete(block()) } catch (failure: Throwable) {
                answer.completeExceptionally(failure)
            }
        }
        return answer.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Opt-in acceptance of the production controller, including a normal ISP preview. */
    @Test
    fun photoAfPreviewAndExposureMatrix() {
        val report = StringBuilder()
        fun note(line: String) {
            report.appendLine(line)
            Log.i("RawLensCamera", "AF acceptance $line")
        }
        fun latest() = cameraRead {
            field(controller, "latestPreviewResult") as? android.hardware.camera2.TotalCaptureResult
        }
        fun afMode() = latest()?.get(android.hardware.camera2.CaptureResult.CONTROL_AF_MODE)
        fun sample(label: String, durationMs: Long) {
            val end = SystemClock.elapsedRealtime() + durationMs
            var previousFrame = -1L
            while (SystemClock.elapsedRealtime() < end) {
                latest()?.let { r ->
                    if (r.frameNumber > previousFrame) {
                        previousFrame = r.frameNumber
                        note("$label t=${SystemClock.elapsedRealtime()} frame=${r.frameNumber}" +
                            " mode=${r.get(android.hardware.camera2.CaptureResult.CONTROL_AF_MODE)}" +
                            " state=${r.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)}" +
                            " fd=${r.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)}" +
                            " ae=${r.get(android.hardware.camera2.CaptureResult.CONTROL_AE_MODE)}")
                    }
                }
                SystemClock.sleep(100)
            }
        }
        val id = cameraRead { field(controller, "selectedCameraId") as String }
        val originalMode = cameraRead { field(controller, "captureExposureMode") as CaptureExposureMode }
        val originalFocus = cameraRead { field(controller, "selectedFocusDistanceDiopters") as? Float }
        val prefs = context.getSharedPreferences("raw_isp_preview_request", Context.MODE_PRIVATE)
        val key = "needs_preview_request_v2_$id"
        val hadKey = prefs.contains(key)
        val originalPreviewRequired = prefs.getBoolean(key, false)
        val vf = activity.findViewById<android.view.View>(R.id.viewfinder)
        val cx = vf.width / 2f
        val cy = vf.height / 2f
        try {
            controller.setManualControl(ManualControl.FOCUS_DISTANCE, null)
            controller.setCaptureExposureMode(CaptureExposureMode.AUTO)
            await("continuous-picture never appeared", 5_000) {
                afMode() == android.hardware.camera2.CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            sample("raw-idle-move-target", 12_000)
            val oldSession = cameraRead { session() }
            // Exercise the preview-required branch without persisting a guessed device quirk.
            prefs.edit().putBoolean(key, true).commit()
            cameraRead {
                (field(controller, "ladder") as SessionLadder).comboIndex = 0
                controller.javaClass.getDeclaredMethod("restartCameraForConfigurationChange")
                    .apply { isAccessible = true }.invoke(controller)
            }
            await("normal-preview session did not configure", 30_000) {
                cameraRead { session() != null && session() !== oldSession }
            }
            await("normal-preview session produced no metadata", 8_000) { latest() != null }
            val preview = cameraRead { field(controller, "previewSize") as? android.util.Size }
            assertTrue("preview-required branch fell back to RAW-only", preview != null)
            assertTrue("ISP preview is a low-resolution placeholder: $preview",
                maxOf(preview!!.width, preview.height) >= 1280 &&
                    minOf(preview.width, preview.height) >= 720)
            note("NORMAL_PREVIEW camera=$id size=$preview")
            sample("isp-idle-move-target", 8_000)
            for (mode in listOf(CaptureExposureMode.AUTO, CaptureExposureMode.PROGRAM,
                CaptureExposureMode.MANUAL)) {
                controller.resetMeteringTargets()
                controller.setCaptureExposureMode(mode)
                SystemClock.sleep(1200)
                note("EXPOSURE $mode")
                repeat(3) { i ->
                    controller.setFocusAndMeteringPoint(cx + (i - 1) * 100f, cy)
                    SystemClock.sleep(100)
                }
                await("retap scan never completed in $mode", 5_000) {
                    cameraRead { touchFocus().completed || !touchFocus().active }
                }
                val result = latest()
                note("VERDICT $mode active=${cameraRead { touchFocus().active }}" +
                    " state=${result?.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)}" +
                    " mode=${result?.get(android.hardware.camera2.CaptureResult.CONTROL_AF_MODE)}")
                sample("$mode-after-taps", 500)
                controller.resetMeteringTargets()
                await("reset did not restore continuous-picture in $mode", 4_000) {
                    afMode() == android.hardware.camera2.CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                }
            }
        } finally {
            if (hadKey) prefs.edit().putBoolean(key, originalPreviewRequired).commit()
            else prefs.edit().remove(key).commit()
            controller.resetMeteringTargets()
            controller.setCaptureExposureMode(originalMode)
            controller.setManualControl(ManualControl.FOCUS_DISTANCE,
                originalFocus?.let { (it * 1000f).toLong() })
            java.io.File(context.getExternalFilesDir(null), "photo-af-device-acceptance.txt")
                .writeText(report.toString())
        }
    }

    @Test
    fun settingsExportImportRoundTrip() {
        val transfer = field(activity, "settingsTransfer") as SettingsTransfer
        val prefs = context.getSharedPreferences(SettingsBackup.STORE_MAIN, Context.MODE_PRIVATE)
        val probeKey = "refactor_probe"
        prefs.edit().putString(probeKey, "exported").commit()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "rawlens-refactor-probe-${System.currentTimeMillis()}.json")
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        try {
            // export() opens the system picker; answer it directly with our file.
            instrumentation.runOnMainSync {
                transfer.export()
                transfer.onExportResult(Activity.RESULT_OK, Intent().setData(uri))
            }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            val written = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertTrue("export wrote nothing", written.isNotEmpty())
            assertTrue("export missed the probe key", String(written).contains(probeKey))

            prefs.edit().putString(probeKey, "changed").commit()
            instrumentation.runOnMainSync {
                transfer.onImportResult(Activity.RESULT_OK, Intent().setData(uri))
            }
            assertEquals("import did not restore the exported value",
                "exported", prefs.getString(probeKey, null))
        } finally {
            context.contentResolver.delete(uri, null, null)
            prefs.edit().remove(probeKey).commit()
        }
    }

    @Test
    fun vendorOperatingModeSession() {
        val cameraId = controller.activeCameraId() ?: error("no active camera")
        val store = VendorKeyStore(context)
        val original = store.get(cameraId)
        val opmode = 37379 // Xiaomi DCG vendor operating mode (>= 0x8000 vendor range)
        try {
            store.save(cameraId, original.copy(operatingMode = opmode))
            controller.reopenCamera()
            SystemClock.sleep(500)
            await("camera did not reopen with the operating mode", 30_000) { session() != null }
            SystemClock.sleep(2000)
            val label = field(controller, "lastSessionPlanLabel") as String
            Log.i(TAG, "opmode $opmode session label: $label")
            // Either the vendor session applied, or the platform refused hidden
            // access and the standard session ran (logged); both must stream.
            val streamed = field(controller, "rawStreamImages") as Int
            assertTrue("no RAW frames after reopen (label=$label)", streamed > 0)
        } finally {
            store.save(cameraId, original)
            controller.reopenCamera()
            SystemClock.sleep(500)
            await("camera did not reopen after restoring the profile", 30_000) { session() != null }
        }
    }

    @Test
    fun manualUpdateCheckReportsAnOutcome() {
        assumeTrue("update check disabled in this build", BuildConfig.ENABLE_UPDATE_CHECK)
        val flow = field(activity, "updateFlow") as UpdateFlow
        val status = field(activity, "status") as TextView
        instrumentation.runOnMainSync { flow.checkManually() }
        // The check runs off the main thread; it ends in a status line or an
        // "Update available" dialog (dialog: status stays on CHECKING).
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var text = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { text = status.text.toString() }
            if (!text.contains("CHECKING", ignoreCase = true)) break
            SystemClock.sleep(200)
        }
        Log.i(TAG, "update check status: $text")
        assertTrue("update check produced no outcome", text.isNotBlank())
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
    }

    private companion object {
        const val TAG = "RawLensRefactorCheck"
    }
}
