// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import org.json.JSONObject
import java.io.IOException

/** Explicit opt-in, no activities: exercises app UID → su → root capture → DNG → rollback. */
class LapisRootProfileInstrumentedTest {
    @Test fun capture50MpHeadless() = capture(LapisRootProfile.Mode.FULL)
    @Test fun capture2xHeadless() = capture(LapisRootProfile.Mode.CROP_2X)

    @Test fun failedCaptureRestoresHeadless() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lapisRootCapture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThrows(IOException::class.java) {
            LapisRootCapture.capture(context, LapisRootProfile.Mode.FULL,
                LapisRootCapture.Settings(400,20_000_000L,6,listOf(2f,1f,1f,1.5f),Float.NaN))
        }
        val report = JSONObject(File(context.noBackupFilesDir,"lapis-last-report.json").readText())
        assertTrue(report.getBoolean("restored"))
        assertTrue(report.getString("error").contains("focus"))
        val root = ProcessBuilder("su","-M","-c","getprop vendor.debug.cameng.force_sensormode; sha256sum /vendor/lib64/mt6878/libmtkcam_metastore.so; getenforce").redirectErrorStream(true).start()
        val state = root.inputStream.bufferedReader().readText()
        assertEquals(0,root.waitFor())
        assertTrue(state.startsWith("\n"))
        assertTrue(state.contains(LapisRootProfile.VENDOR_SHA256))
        assertTrue(state.contains("Enforcing"))
    }

    private fun capture(mode: LapisRootProfile.Mode) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("lapisRootCapture") == "true")
        assertTrue("Exact device/firmware required", LapisRootProfile.supported())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = ProcessBuilder("su", "-M", "-c", "id -u").redirectErrorStream(true).start()
        val identity = root.inputStream.bufferedReader().readText().trim()
        assertEquals("RawLens must have its own root grant: $identity", 0, root.waitFor())
        assertEquals("0", identity)
        val name = LapisRootCapture.capture(context, mode,
            LapisRootCapture.Settings(400,20_000_000L,6,listOf(2f,1f,1f,1.5f),0f))
        assertTrue(name.endsWith(".dng"))
        val report = File(context.noBackupFilesDir,"lapis-last-capture.json").readText()
        File(context.filesDir,"lapis-${mode.argument}-acceptance.json").writeText(report)
        assertTrue(report.contains("\"restored\": true"))
        assertTrue(report.contains("\"precision14Verified\": false"))
    }
}
