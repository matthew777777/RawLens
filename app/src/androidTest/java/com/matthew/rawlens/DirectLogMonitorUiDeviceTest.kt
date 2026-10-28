// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real sensor -> recorder -> P010 monitor -> EGL import -> optional display assist. */
@RunWith(AndroidJUnit4::class)
class DirectLogMonitorUiDeviceTest {
    @Test fun unassistedTechnicalAndImportedMonitorsRecordSlog3() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e rawlensSonyMonitorUi true",args.getString("rawlensSonyMonitorUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val path = requireNotNull(args.getString("sonyLutPath"))
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        val prefs = context.getSharedPreferences("rawlens_settings",Context.MODE_PRIVATE)
        val wasSetup = prefs.getBoolean("lens_setup_complete",false)
        prefs.edit().putBoolean("lens_setup_complete",true).commit()
        val library = DirectLogLutLibrary(context)
        val originalSelection = library.selected
        val imported = library.import(android.net.Uri.fromFile(File(path)))
        val activity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val dir = File(context.getExternalFilesDir(null),"sony-direct-log").apply { mkdirs() }
        val report = StringBuilder()
        try {
            SystemClock.sleep(2000)
            instrumentation.runOnMainSync { activity.controller.stop() }
            val preview = activity.findViewById<DirectLogPreviewView>(R.id.recordPreview)
            instrumentation.runOnMainSync { preview.visibility = View.VISIBLE }
            val cameraManager = context.getSystemService(CameraManager::class.java)
            val camera = cameraManager.cameraIdList.first {
                val c = cameraManager.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    !c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()
            }
            fun counter(name: String) = (DirectLogPreviewView::class.java.getDeclaredField(name).apply {
                isAccessible = true
            }.get(preview) as AtomicInteger).get()
            for ((selection, baked) in listOf(DirectLogLutLibrary.OFF to false,
                DirectLogLutLibrary.TECHNICAL to false, imported to false, imported to true)) {
                library.selected = selection
                preview.configureMonitor(DirectLogProfile.SLOG3)
                val before = counter("presented")
                val failures = counter("presentFailed")
                val recorder = DirectLogRecorder(cameraManager,camera,context).apply {
                    width=1920; height=1080; fps=24; audioEnabled=false; stabEnabled=false
                    logProfile=DirectLogProfile.SLOG3
                    if (baked) recordLut = library.load(imported)
                    attachPreviewDisplay(preview)
                }
                try {
                    recorder.start(File(dir,"monitor-$selection-baked$baked.mp4"))
                    SystemClock.sleep(4000)
                    val stats = recorder.stop()
                    val shown = counter("presented")-before
                    val errors = counter("presentFailed")-failures
                    report.append("selection=$selection baked=$baked presented=$shown displayErrors=$errors stats=$stats\n")
                    File(dir,"monitor-ui-report.txt").writeText(report.toString())
                    assertTrue("No monitor frames for $selection",shown > 10)
                    assertEquals("Display failure for $selection",0,errors)
                    if (selection != DirectLogLutLibrary.OFF && !baked) {
                        val displayTransform = DirectLogPreviewView::class.java.getDeclaredField("monitorGl").apply {
                            isAccessible = true
                        }.get(preview)
                        assertNotNull("Assist silently fell back to unassisted log",displayTransform)
                    }
                    assertEquals("File profile changed",if (baked) DirectLogProfile.BT709.name else DirectLogProfile.SLOG3.name,stats.profile)
                    assertTrue("No recorded frames",stats.framesGraded > 20)
                    assertEquals("Recording error",0,stats.frameErrors)
                } finally { runCatching { recorder.stop() } }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            library.remove(imported)
            library.selected = originalSelection
            prefs.edit().putBoolean("lens_setup_complete",wasSetup).commit()
        }
    }
}
