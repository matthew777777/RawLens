// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.Intent
import android.graphics.Rect
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

/** Main-screen control and real photo RAW handoff. Restores assist preferences after the test. */
class RawExposureAssistUiTest {
    @Test fun mainCycleTopSpotAndPhotoTap() {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass -e rawlensF12Audit true", InstrumentationRegistry.getArguments().getString("rawlensF12Audit") == "true")
        val context = inst.targetContext
        inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        val prefs = context.getSharedPreferences("rawlens_settings", Context.MODE_PRIVATE)
        assumeTrue("Initial lens setup must be complete", prefs.getBoolean("lens_setup_complete", false))
        val videoKeys = listOf("direct_log_res", "direct_log_fps", "direct_log_profile", "video_stab_output", "direct_log_video")
        val savedVideo = prefs.all.filterKeys { it in videoKeys }
        var ownTakeUri: android.net.Uri? = null
        val old = RawExposureAssistSettings(RawExposureOverlayMode.fromName(prefs.getString(RawExposureAssistSettings.KEY_OVERLAY, null)),
            prefs.getBoolean(RawExposureAssistSettings.KEY_SPOT, true),
            RawExposureMeterMode.fromName(prefs.getString(RawExposureAssistSettings.KEY_METER_MODE, null)))
        inst.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        var resumed: MainActivity? = null
        val launchDeadline = SystemClock.elapsedRealtime() + 15_000
        while (resumed == null && SystemClock.elapsedRealtime() < launchDeadline) {
            inst.runOnMainSync { resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
            if (resumed == null) SystemClock.sleep(50)
        }
        val activity = requireNotNull(resumed) { "MainActivity did not resume" }
        val output = File(context.getExternalFilesDir(null), "raw-exposure-assist-audit").apply { mkdirs() }
        val report = StringBuilder()
        val field = RawExposureOverlayView::class.java.getDeclaredField("frame").apply { isAccessible = true }
        fun current(): RawExposureAssistFrame? { var f: RawExposureAssistFrame? = null; inst.runOnMainSync { f = field.get(activity.rawExposureOverlay) as? RawExposureAssistFrame }; return f }
        fun await(label: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 30_000
            while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
            assertTrue(label, condition())
        }
        fun screenshot(name: String) { inst.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        } }
        try {
            inst.runOnMainSync { activity.saveRawExposureAssist(RawExposureAssistSettings()) }
            await("No live RAW spot reading") { current()?.spot != null }
            inst.runOnMainSync {
                assertTrue(activity.rawExposureSettings.spotMeter)
                val sensor = activity.findViewById<TextView>(R.id.sensorInfo)
                val spot = activity.findViewById<TextView>(R.id.rawSpotReading)
                val a = Rect(); val b = Rect()
                sensor.getGlobalVisibleRect(a); spot.getGlobalVisibleRect(b)
                assertTrue("RAW spot is not below resolution", b.top >= a.bottom)
                assertTrue(spot.text.toString().contains("EV"))
                report.appendLine("HUD ${spot.text} sensor=$a spot=$b")
            }
            screenshot("photo-off-spot")
            for ((mode, name) in listOf(RawExposureOverlayMode.ZEBRAS to "photo-zebras", RawExposureOverlayMode.FALSE_COLOR to "photo-false-colour", RawExposureOverlayMode.OFF to "photo-cycle-off")) {
                val before = current()?.timestampNanos
                inst.runOnMainSync {
                    activity.findViewById<View>(R.id.rawExposureToggle).performClick()
                    assertEquals(mode, activity.rawExposureSettings.overlay)
                    assertTrue(activity.rawExposureSettings.spotMeter)
                }
                await("No fresh $mode frame") { current()?.let { it.timestampNanos != before && it.spot != null && (if (mode == RawExposureOverlayMode.OFF) it.colors == null else it.colors != null) } == true }
                screenshot(name)
                report.appendLine("CYCLE $mode cols=${current()?.cols} rows=${current()?.rows}")
            }
            inst.runOnMainSync {
                val vf = activity.findViewById<View>(R.id.viewfinder)
                activity.controller.setAePoint(vf.width * 0.75f, vf.height * 0.25f)
            }
            await("Spot did not follow photo AE target") {
                val f = current(); val s = f?.spot
                if (f == null || s == null) false else {
                    val p = RawExposureAssistSampler.sensorPoint(0.75f, 0.25f, f.rotation, f.mirrored)
                    kotlin.math.abs((s.left + s.right) / 2 - p.first) < 0.02f && kotlin.math.abs((s.top + s.bottom) / 2 - p.second) < 0.02f
                }
            }
            report.appendLine("TAP PASS spot=${current()?.spot}")
            inst.runOnMainSync { activity.saveRawExposureAssist(RawExposureAssistSettings(spotMeter = false)) }
            SystemClock.sleep(400)
            inst.runOnMainSync {
                assertEquals(View.GONE, activity.findViewById<View>(R.id.rawSpotReading).visibility)
                assertEquals(View.GONE, activity.rawExposureOverlay.visibility)
                assertNull(field.get(activity.rawExposureOverlay))
            }
            report.appendLine("OFF PASS no stale overlay/readout")
            fun bounds(id: Int): Rect = Rect().also { activity.findViewById<View>(id).getGlobalVisibleRect(it) }
            for (mode in listOf("PHOTO", "LOG", "CRAW")) {
                val oldLog = activity.directLogEnabled
                inst.runOnMainSync {
                    if (mode != "PHOTO") {
                        MainActivity::class.java.getDeclaredMethod("enterVideoMode").apply { isAccessible = true }.invoke(activity)
                        activity.setDirectLogMode(mode == "LOG")
                    }
                }
                SystemClock.sleep(250)
                var top = Rect(); var preview = Rect()
                inst.runOnMainSync { top = bounds(R.id.topBar); preview = bounds(R.id.viewfinder) }
                inst.runOnMainSync { activity.saveRawExposureAssist(RawExposureAssistSettings()) }
                SystemClock.sleep(250)
                inst.runOnMainSync {
                    assertEquals("$mode header grew with spot meter", top, bounds(R.id.topBar))
                    assertEquals("$mode preview moved with spot meter", preview, bounds(R.id.viewfinder))
                    val spot = bounds(R.id.rawSpotReading); val status = bounds(R.id.status)
                    assertTrue("$mode RAW reading must share status row", spot.top < status.bottom && status.top < spot.bottom)
                    assertTrue("$mode RAW reading overlaps status", spot.right <= status.left)
                    assertEquals("$mode format badge wrapped", 1, activity.findViewById<TextView>(R.id.rawBadge).lineCount)
                }
                inst.runOnMainSync { activity.saveRawExposureAssist(RawExposureAssistSettings(meterMode = RawExposureMeterMode.AVERAGE)) }
                await("$mode: no average reading") { current()?.average != null && current()?.spot == null }
                inst.runOnMainSync {
                    assertEquals("$mode header grew with average meter", top, bounds(R.id.topBar))
                    assertEquals("$mode preview moved with average meter", preview, bounds(R.id.viewfinder))
                    val text = activity.findViewById<TextView>(R.id.rawSpotReading)
                    assertTrue(text.text.startsWith("AVG ") && text.text.contains("%"))
                    report.appendLine("AVERAGE $mode ${text.text}")
                }
                screenshot("compact-${mode.lowercase()}")
                report.appendLine("COMPACT $mode header=$top preview=$preview unchanged by spot")
                inst.runOnMainSync {
                    activity.saveRawExposureAssist(RawExposureAssistSettings(spotMeter = false))
                    if (activity.isVideoMode) MainActivity::class.java.getDeclaredMethod("exitVideoMode").apply { isAccessible = true }.invoke(activity)
                    activity.setDirectLogMode(oldLog)
                }
            }
            if (InstrumentationRegistry.getArguments().getString("f12UiRecord") == "true") {
                inst.runOnMainSync {
                    prefs.edit().putString("direct_log_res", "QHD1440").putInt("direct_log_fps", 30)
                        .putString("direct_log_profile", "HLG").putString("video_stab_output", "OG_ONLY").commit()
                    activity.setDirectLogMode(true)
                    MainActivity::class.java.getDeclaredMethod("enterVideoMode").apply { isAccessible = true }.invoke(activity)
                    activity.saveRawExposureAssist(RawExposureAssistSettings())
                    MainActivity::class.java.getDeclaredMethod("doStartDirectLog", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
                    assertTrue("UI recording did not start", activity.videoRecording)
                    val destination = MainActivity::class.java.getDeclaredField("recordingDestination")
                        .apply { isAccessible = true }.get(activity) as DirectRecordingOutput
                    ownTakeUri = destination.uri
                }
                SystemClock.sleep(2500)
                val recorder = requireNotNull(activity.logRecorder)
                inst.runOnMainSync { activity.saveRawExposureAssist(RawExposureAssistSettings(meterMode = RawExposureMeterMode.AVERAGE)) }
                var top = Rect(); var preview = Rect()
                inst.runOnMainSync { top = bounds(R.id.topBar); preview = bounds(R.id.viewfinder) }
                for (mode in listOf(RawExposureOverlayMode.ZEBRAS, RawExposureOverlayMode.FALSE_COLOR, RawExposureOverlayMode.OFF)) {
                    inst.runOnMainSync { activity.findViewById<View>(R.id.rawExposureToggle).performClick() }
                    await("No live recording $mode overlay") { current()?.let { it.average != null && it.spot == null && it.monitorCrop != null && (it.colors != null) == (mode != RawExposureOverlayMode.OFF) } == true }
                    val a = recorder.snapshot()
                    SystemClock.sleep(2500)
                    val b = recorder.snapshot()
                    val rate = (b.framesGraded - a.framesGraded) * 1000.0 / (b.elapsedMs - a.elapsedMs)
                    assertTrue("Live monitor recording stalled: $rate fps", rate >= 27.0)
                    assertEquals("Live monitor recording dropped frames", a.framesDropped, b.framesDropped)
                    assertTrue("Recording monitor did not present frames", b.previewFrames > a.previewFrames)
                    inst.runOnMainSync {
                        assertEquals(top, bounds(R.id.topBar)); assertEquals(preview, bounds(R.id.viewfinder))
                        assertEquals(mode, activity.rawExposureSettings.overlay)
                    }
                    report.appendLine("UI RECORD $mode fps=$rate previewFrames=${b.previewFrames - a.previewFrames} drops=${b.framesDropped - a.framesDropped}")
                    screenshot("record-${mode.name.lowercase()}")
                }
                inst.runOnMainSync { activity.findViewById<View>(R.id.shutter).performClick() }
                await("Recording stop did not finish") { activity.lastDirectLogStats != null && !activity.videoRecording }
                val stats = activity.lastDirectLogStats!!
                report.appendLine("UI STOP $stats")
                assertEquals(0, stats.frameErrors)
                inst.runOnMainSync { MainActivity::class.java.getDeclaredMethod("exitVideoMode").apply { isAccessible = true }.invoke(activity) }
                await("Photo RAW did not resume after recording") { current()?.average != null && current()?.monitorCrop == null }
            }
        } finally {
            inst.runOnMainSync { activity.saveRawExposureAssist(old); activity.controller.resetMeteringTargets(); activity.finish() }
            prefs.edit().apply {
                videoKeys.forEach { remove(it) }
                savedVideo.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Int -> putInt(key, value); is Boolean -> putBoolean(key, value) } }
            }.commit()
            ownTakeUri?.let { uri ->
                if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
                    android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
                } else context.contentResolver.delete(uri, null, null)
            }
            File(output, "ui-report.txt").writeText(report.toString())
        }
    }
}
