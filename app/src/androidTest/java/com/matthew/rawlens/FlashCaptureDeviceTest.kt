// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Real controller OFF/ON/AUTO + derived/manual exposure flash probe. Opt-in -e rawlensFlash true. */
class FlashCaptureDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun await(message: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30_000
        while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue(message, predicate())
    }
    @Test fun controllerFlashRequestsAndHardwareVerdicts() {
        assumeTrue("Pass -e rawlensFlash true", InstrumentationRegistry.getArguments().getString("rawlensFlash") == "true")
        inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        val activity = inst.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val controller = field(activity, "controller") as RawCameraController
        val report = StringBuilder()
        fun log(s: String) { report.appendLine(s); Log.i("RawLensFlashProbe", s) }
        val originalMode = field(controller, "captureExposureMode") as CaptureExposureMode
        val originalIso = field(controller, "selectedIso") as Int?
        val originalShutter = field(controller, "selectedExposureNanos") as Long?
        val originalEttr = (field(controller, "ettr") as EttrLoop).settings
        val originalDynamic = field(controller, "dynamicExposureSettings") as DynamicExposureSettings
        val originalFlash = controller.flashStillMode()
        try {
            await("camera session unavailable") { field(controller, "session") != null }
            val c = field(controller, "characteristics") as CameraCharacteristics
            assumeTrue("active camera has no flash", c[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true)
            log("DEVICE ${android.os.Build.MODEL} camera=${controller.activeCameraId()} AE=${c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]?.contentToString()} flash=${c[CameraCharacteristics.FLASH_INFO_AVAILABLE]} level=${c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]}")
            controller.setCaptureExposureMode(CaptureExposureMode.AUTO)
            controller.setEttrSettings(originalEttr.copy(enabled = false))
            controller.setManualControl(ManualControl.ISO, null)
            controller.setManualControl(ManualControl.SHUTTER, null)
            controller.resetMeteringTargets()
            SystemClock.sleep(1200)
            var lastResultAeState: Int? = null
            var lastRequestAeMode: Int? = null
            var forcedFired = false
            fun capture(label: String, mode: RawCameraController.FlashStillMode): Int? {
                while (controller.flashStillMode() != mode) {
                    val old = controller.flashStillMode()
                    controller.cycleFlashStill()
                    await("flash mode failed to cycle") { controller.flashStillMode() != old }
                }
                val busy = field(controller, "captureInProgress") as AtomicBoolean
                await("previous shot still busy") { !busy.get() }
                val sequence = (field(controller, "captureSequence") as java.util.concurrent.atomic.AtomicInteger).get()
                controller.capture()
                await("capture never started") { (field(controller, "captureSequence") as java.util.concurrent.atomic.AtomicInteger).get() != sequence || busy.get() }
                await("capture did not finish") { !busy.get() }
                val trace = ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(
                    "logcat -d -s RawLensCamera:I")).bufferedReader().use { reader ->
                    reader.lineSequence().lastOrNull { it.contains(" ${Process.myPid()} ") && it.contains("FLASH_RESULT") }
                }
                assertNotNull("no still hardware result for $label", trace)
                log("$label $trace")
                lastResultAeState = trace!!.substringAfter("aeState=").substringBefore(' ').toIntOrNull()
                lastRequestAeMode = trace.substringAfter("requestAe=").substringBefore(' ').toIntOrNull()
                return trace.substringAfter("flashState=").substringBefore(' ').toIntOrNull()
            }
            capture("OFF_AUTO_EXPOSURE", RawCameraController.FlashStillMode.OFF)
            forcedFired = capture("ON_AUTO_EXPOSURE", RawCameraController.FlashStillMode.ON) in
                listOf(CaptureResult.FLASH_STATE_FIRED, CaptureResult.FLASH_STATE_PARTIAL)
            val automatic = capture("AUTO_AUTO_EXPOSURE", RawCameraController.FlashStillMode.AUTO)
            if (lastResultAeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                assertTrue("AUTO reported FLASH_REQUIRED but never fired", automatic in
                    listOf(CaptureResult.FLASH_STATE_FIRED, CaptureResult.FLASH_STATE_PARTIAL))
            }
            controller.setManualControl(ManualControl.ISO, 800)
            controller.setManualControl(ManualControl.SHUTTER, 10_000_000)
            SystemClock.sleep(500)
            val manual = capture("ON_MANUAL_EXPOSURE", RawCameraController.FlashStillMode.ON)
            log("FORCED_AUTO_FIRED=$forcedFired MANUAL_FIRED=${manual in listOf(CaptureResult.FLASH_STATE_FIRED, CaptureResult.FLASH_STATE_PARTIAL)}")
            assertTrue("manual forced flash did not fire", manual in listOf(CaptureResult.FLASH_STATE_FIRED, CaptureResult.FLASH_STATE_PARTIAL))
            controller.setManualControl(ManualControl.ISO, null)
            controller.setManualControl(ManualControl.SHUTTER, null)
            controller.cycleFlashStill() // ON -> AUTO under ordinary hardware AE.
            await("hardware AE should offer AUTO flash") { controller.flashStillMode() == RawCameraController.FlashStillMode.AUTO }
            controller.setDynamicExposureSettings(originalDynamic.copy(enabled = true))
            await("custom PROGRAM settings retained AUTO flash") { controller.flashStillMode() == RawCameraController.FlashStillMode.OFF }
            assertFalse(controller.isAutoFlashAvailable())
            controller.setDynamicExposureSettings(originalDynamic.copy(enabled = false))
            await("custom PROGRAM settings did not release AUTO flash") { controller.isAutoFlashAvailable() }
            controller.cycleFlashStill()
            await("flash did not select ON") { controller.flashStillMode() == RawCameraController.FlashStillMode.ON }
            controller.cycleFlashStill()
            await("flash did not select AUTO") { controller.flashStillMode() == RawCameraController.FlashStillMode.AUTO }
            controller.setCaptureExposureMode(CaptureExposureMode.PROGRAM)
            await("PROGRAM retained an unavailable AUTO flash selection") { controller.flashStillMode() == RawCameraController.FlashStillMode.OFF }
            assertFalse(controller.isAutoFlashAvailable())
            SystemClock.sleep(1200)
            val program = capture("ON_PROGRAM_EXPOSURE", RawCameraController.FlashStillMode.ON)
            assertEquals("PROGRAM flash must preserve the RAW exposure pair", CaptureResult.CONTROL_AE_MODE_OFF, lastRequestAeMode)
            controller.cycleFlashStill() // ON -> OFF; AUTO must be skipped.
            await("PROGRAM cycle offered AUTO") { controller.flashStillMode() == RawCameraController.FlashStillMode.OFF }
            assertTrue("PROGRAM flash did not fire", program in listOf(CaptureResult.FLASH_STATE_FIRED, CaptureResult.FLASH_STATE_PARTIAL))
            controller.setCaptureExposureMode(CaptureExposureMode.AUTO)
            await("normal AE still suppresses AUTO") { controller.isAutoFlashAvailable() }
            controller.cycleFlashStill() // OFF -> ON.
            await("flash did not select ON") { controller.flashStillMode() == RawCameraController.FlashStillMode.ON }
            controller.cycleFlashStill() // ON -> AUTO.
            await("flash did not select AUTO") { controller.flashStillMode() == RawCameraController.FlashStillMode.AUTO }
            controller.setEttrSettings(originalEttr.copy(enabled = true))
            await("ETTR retained AUTO flash") { controller.flashStillMode() == RawCameraController.FlashStillMode.OFF }
            assertFalse(controller.isAutoFlashAvailable())
            await("ETTR never metered a pair") { (field(controller, "ettr") as EttrLoop).shutterNanos != null }
            val ettr = capture("ON_ETTR_EXPOSURE", RawCameraController.FlashStillMode.ON)
            assertEquals("ETTR flash must preserve the RAW exposure pair", CaptureResult.CONTROL_AE_MODE_OFF, lastRequestAeMode)
            assertTrue("ETTR flash did not fire", ettr in listOf(CaptureResult.FLASH_STATE_FIRED, CaptureResult.FLASH_STATE_PARTIAL))
            await("flash override leaked after capture") { field(controller, "flashRepeatingAeModeOverride") == null }

            assertTrue("forced flash did not fire under hardware AE (see probe report)", forcedFired)
        } finally {
            controller.setCaptureExposureMode(originalMode)
            controller.setDynamicExposureSettings(originalDynamic)
            controller.setEttrSettings(originalEttr)
            controller.setManualControl(ManualControl.ISO, originalIso?.toLong())
            controller.setManualControl(ManualControl.SHUTTER, originalShutter)
            val restoreFlash = FlashStillPolicy.permittedMode(originalFlash, controller.isAutoFlashAvailable())
            repeat(3) { if (controller.flashStillMode() != restoreFlash) { controller.cycleFlashStill(); SystemClock.sleep(100) } }
            File(context.getExternalFilesDir(null), "flash-capture-device.txt").writeText(report.toString())
            inst.runOnMainSync { activity.finish() }
        }
    }
}
