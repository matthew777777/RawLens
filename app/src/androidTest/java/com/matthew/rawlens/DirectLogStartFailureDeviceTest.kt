// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * V1 (docs/refactor/video-study.md): a [DirectLogRecorder.start] that throws
 * after its copy workers started must leave no worker threads behind. An
 * unknown camera id throws from getCameraCharacteristics, which runs after
 * startCopyWorker. Opens no camera.
 */
class DirectLogStartFailureDeviceTest {
    @Test
    fun failedStartLeavesNoWorkerThreads() {
        assumeTrue("native video libs unavailable", VfEglImport.available && VfVulkan.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val out = File(context.cacheDir, "directlog-start-failure.mp4")
        repeat(3) {
            val recorder = DirectLogRecorder(manager, "rawlens-no-such-camera", context)
            try {
                recorder.start(out)
                fail("start must throw for an unknown camera id")
            } catch (_: Exception) {
            }
        }
        // Workers poll every 100 ms; give a stray one time to show up.
        SystemClock.sleep(300)
        val leaked = Thread.getAllStackTraces().keys.filter {
            it.isAlive && (it.name.startsWith("DirectLogCopy") || it.name == "DirectLogDrain")
        }.map { it.name }
        assertEquals("leaked threads", emptyList<String>(), leaked)
        assertFalse("empty output left behind", out.exists())
    }
}
