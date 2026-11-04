// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real capture + monitor sleep + focus controls. Runs only in the isolated audit package. */
class RecordingToolsDeviceTest {
    @Test fun recordingContinuesThroughSleepAndFocusPulls() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        assumeTrue("Build with -PrecordingToolsAudit", context.packageName.endsWith(".recordingcheck"))
        for (permission in listOf("CAMERA", "RECORD_AUDIO", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO")) {
            inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.$permission").close()
        }
        val prefs = context.getSharedPreferences("rawlens_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("lens_setup_complete", true).putBoolean("media_permission_asked", true)
            .putBoolean(SidecarTreeAccess.KEY_FIRST_INSTALL_PROMPTED, true)
            .putBoolean("video_audio", false).putBoolean("encoded_video_enabled", false)
            .putString("encoded_video_res", "FHD1080").putInt("encoded_video_fps", 30)
            .putLong("video_focus_pull_duration_ms", 1200L)
            .putInt(RecordingTools.KEY_SLEEP, 0).commit()
        context.getSharedPreferences(UpdateChecker.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(UpdateConsent.KEY_CONSENT_ASKED, true).putBoolean(UpdateChecker.KEY_AUTO_CHECK, false).commit()
        inst.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        var activity: MainActivity? = null
        fun main(block: () -> Unit) = inst.runOnMainSync(block)
        fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue(label, condition())
        }
        await("MainActivity resumed") {
            main { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull() }
            activity != null
        }
        val a = requireNotNull(activity)
        val output = File(context.getExternalFilesDir(null), "recording-tools-audit").apply { mkdirs() }
        val report = StringBuilder()
        val takes = mutableListOf<Uri>()
        fun shot(name: String) {
            inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun click(id: Int) = main { assertTrue(a.findViewById<View>(id).performClick()) }
        fun frames(): Int {
            var count = 0
            main { count = a.videoRecorder?.snapshot()?.framesEncoded ?: a.encodedVideoRecorder?.snapshot()?.framesGraded ?: 0 }
            return count
        }
        fun previewFrames(): Int {
            var count = 0
            main { count = a.encodedVideoRecorder?.snapshot()?.previewFrames ?: a.videoRecorder?.previewFramesOffered ?: 0 }
            return count
        }
        fun sleeping(): Boolean { var value = false; main { value = a.recordingTools.isPreviewSleeping }; return value }
        try {
            await("Live preview") { var ready = false; main { ready = a.controller.vfSnapshot()?.fps?.let { it > 0f } == true }; ready }
            // Photo modes are temporary-suspended for either video format, without preference edits.
            val controllerMode = RawCameraController::class.java.getDeclaredField("captureExposureMode").apply { isAccessible = true }
            val zslRequested = RawCameraController::class.java.getDeclaredField("rawZslRequested").apply { isAccessible = true }
            for (mode in listOf(CaptureExposureMode.PROGRAM, CaptureExposureMode.ZSL, CaptureExposureMode.MANUAL)) {
                main { a.applyCaptureExposureMode(mode) }
                await("Photo mode installed") { controllerMode.get(a.controller) == mode }
                if (mode == CaptureExposureMode.MANUAL) {
                    main {
                        a.setManualValueFor(ManualControl.ISO, 400L)
                        a.setManualValueFor(ManualControl.SHUTTER, 70_000_000L)
                    }
                    await("Photo manual values installed") { a.controller.snapshotVideoManual().iso == 400 }
                }
                val before = a.controller.snapshotVideoManual()
                val savedPreferences = prefs.all.toMap()
                click(R.id.videoRecordButton)
                await("Video uses Android Auto") {
                    controllerMode.get(a.controller) == CaptureExposureMode.AUTO &&
                        a.controller.snapshotVideoManual().iso == null &&
                        a.controller.snapshotVideoManual().shutterNanos == null &&
                        !zslRequested.getBoolean(a.controller)
                }
                main {
                    assertEquals(CaptureExposureMode.AUTO, a.captureExposureMode)
                    assertEquals("Video entry preserves photo preferences", savedPreferences, prefs.all)
                    a.setEncodedVideoMode(true)
                    a.setEncodedVideoMode(false)
                    a.findViewById<View>(R.id.isoControl).performClick()
                }
                // Manual video exposure remains available without activating the photo MANUAL mode.
                main { a.setManualValueFor(ManualControl.ISO, 800L) }
                await("Manual video adjustment works") { a.controller.snapshotVideoManual().iso == 800 }
                main { assertEquals(CaptureExposureMode.AUTO, a.captureExposureMode) }
                click(R.id.videoRecordButton)
                await("Photo controls restored") {
                    controllerMode.get(a.controller) == mode && a.controller.snapshotVideoManual() == before
                }
                main { assertEquals(mode, a.captureExposureMode) }
                report.appendLine("$mode -> VIDEO AUTO -> $mode, manual controls restored")
            }
            main { a.applyCaptureExposureMode(CaptureExposureMode.AUTO) }
            await("Auto photo restored for recording checks") { controllerMode.get(a.controller) == CaptureExposureMode.AUTO }
            val photoPanel = Rect()
            main { a.findViewById<View>(R.id.controlPanel).getGlobalVisibleRect(photoPanel) }
            shot("photo-layout")
            click(R.id.videoRecordButton)
            SystemClock.sleep(500)
            main {
                val videoPanel = Rect()
                a.findViewById<View>(R.id.controlPanel).getGlobalVisibleRect(videoPanel)
                assertEquals("Photo and video main panel match exactly", photoPanel, videoPanel)
                assertEquals(View.GONE, a.findViewById<View>(R.id.recordingToolsRow).visibility)
                report.appendLine("photo/video panel=$photoPanel")
            }
            shot("video-layout")
            for (encoded in listOf(false, true)) {
                main { a.setEncodedVideoMode(encoded) }
                SystemClock.sleep(1000)
                click(R.id.shutter)
                await("Recording started") { var active = false; main { active = a.videoRecording }; active }
                await("Frames encoded") { frames() >= 10 }
                val destination = MainActivity::class.java.getDeclaredField("recordingDestination").apply { isAccessible = true }
                main { (destination.get(a) as? DirectRecordingOutput)?.uri?.let(takes::add) }
                main {
                    val tools = a.findViewById<View>(R.id.recordingToolsRow)
                    val rect = Rect()
                    assertFalse("Focus tools hidden until focus slider opens", tools.isShown)
                    assertTrue(a.findViewById<TextView>(R.id.videoDebugOverlay).text.contains("HEALTH"))
                    report.appendLine("${if (encoded) "encoded" else "raw"} tools=$rect")
                }
                shot(if (encoded) "encoded-awake" else "raw-awake")
                // A/B marks live in the floating focus slider, never the capture panel.
                click(R.id.focusControl)
                SystemClock.sleep(300)
                main {
                    val tools = a.findViewById<View>(R.id.recordingToolsRow)
                    assertTrue("Focus slider owns tools", tools.parent.parent === a.manualPanel)
                    assertEquals(View.VISIBLE, tools.visibility)
                    val videoPanel = Rect()
                    a.findViewById<View>(R.id.controlPanel).getGlobalVisibleRect(videoPanel)
                    assertEquals("Focus slider leaves main panel unchanged", photoPanel, videoPanel)
                }
                shot(if (encoded) "encoded-focus-panel" else "raw-focus-panel")
                main {
                    val range = a.manualRangeFor(ManualControl.FOCUS_DISTANCE)
                    assertNotNull("Device must expose manual focus", range)
                    a.setManualValueFor(ManualControl.FOCUS_DISTANCE, range!!.minimum)
                    a.findViewById<View>(R.id.focusMarkA).performLongClick()
                    a.setManualValueFor(ManualControl.FOCUS_DISTANCE, range.maximum)
                    a.findViewById<View>(R.id.focusMarkB).performLongClick()
                }
                click(R.id.focusMarkA)
                SystemClock.sleep(1600)
                main {
                    val r = a.manualRangeFor(ManualControl.FOCUS_DISTANCE)!!
                    assertFalse(r.automatic)
                    assertEquals("Pull reached A", r.minimum, r.current)
                }
                click(R.id.focusMarkB)
                SystemClock.sleep(150)
                main { a.setManualValueFor(ManualControl.FOCUS_DISTANCE, 0L) }
                SystemClock.sleep(1400)
                main { assertEquals("Manual adjustment cancels pull", 0L, a.manualRangeFor(ManualControl.FOCUS_DISTANCE)!!.current) }
                click(R.id.recordingPreviewSleep)
                await("Preview sleeps") { sleeping() }
                SystemClock.sleep(700) // allow the already-submitted monitor frame to finish
                val beforeFrames = frames()
                val beforePreview = previewFrames()
                SystemClock.sleep(2500)
                assertTrue("Capture continues while asleep", frames() > beforeFrames)
                assertEquals("No new preview while asleep", beforePreview, previewFrames())
                main {
                    assertEquals(View.GONE, a.findViewById<View>(R.id.histogramView).visibility)
                    assertTrue(a.videoRecorder?.monitoringSuspended ?: a.encodedVideoRecorder!!.monitoringSuspended)

                }
                report.appendLine("sleep frames=$beforeFrames->${frames()} previews=$beforePreview->${previewFrames()}")
                shot(if (encoded) "encoded-asleep" else "raw-asleep")
                click(R.id.recordingSleepOverlay)
                await("Preview wakes") { !sleeping() && previewFrames() > beforePreview }
                if (!encoded) {
                    main { prefs.edit().putInt(RecordingTools.KEY_SLEEP, 5).commit(); a.recordingTools.onInteraction() }
                    await("Automatic preview sleep", 10_000) { sleeping() }
                    click(R.id.recordingSleepOverlay)
                    main { prefs.edit().putInt(RecordingTools.KEY_SLEEP, 0).commit() }
                }
                // Stop directly from the sleeping view; controls must remain available.
                click(R.id.recordingPreviewSleep)
                click(R.id.shutter)
                await("Take finalizes") { var done = false; main { done = a.videoRecorder == null && a.encodedVideoRecorder == null }; done }
                assertFalse("Sleep restored after stop", sleeping())
                assertTrue("Valid saved take", context.contentResolver.openFileDescriptor(takes.last(), "r")!!.use { it.statSize > 0 })
                report.appendLine("${if (encoded) "encoded" else "raw"} finalized=${takes.last()}")
                SystemClock.sleep(1500)
            }
            main { a.findViewById<View>(R.id.videoRecordButton).performClick() }
            main { assertEquals(View.GONE, a.findViewById<View>(R.id.recordingToolsRow).visibility) }
        } finally {
            File(output, "report.txt").writeText(report.toString())
            main { a.finish() }
            // Only the audit's own takes, in the isolated package, are removed.
            takes.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }
}
