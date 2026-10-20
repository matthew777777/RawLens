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

    private fun touchFocus(): TouchFocus = field(controller, "touchFocus") as TouchFocus

    @Test
    fun touchFocusReachesVerdictHoldsAndReleases() {
        val vf = activity.findViewById<android.view.View>(R.id.viewfinder)
        val cx = vf.width / 2f
        val cy = vf.height / 2f
        controller.setFocusAndMeteringPoint(cx, cy)
        await("tap never started a scan", 3_000) { touchFocus().active }
        await("tap scan never reached a verdict", 6_000) { touchFocus().completed || !touchFocus().active }
        Log.i(TAG, "tap verdict: active=${touchFocus().active} completed=${touchFocus().completed}")

        SystemClock.sleep(400) // past AutoFocusPolicy.TAP_BOUNCE_MS
        controller.setFocusAndMeteringHold(cx + 200f, cy)
        await("hold never froze AE", 3_000) { touchFocus().aeLockIndefinite }
        await("hold scan never reached a verdict", 6_000) { touchFocus().completed }
        assertTrue("hold must keep AE locked after its verdict", touchFocus().aeLockIndefinite)

        controller.resetMeteringTargets()
        await("reset did not release to continuous AF", 3_000) {
            !touchFocus().active && !touchFocus().aeLockIndefinite
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
