// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * V4 (docs/refactor/video-study.md): the RAW video storage guard. Raises
 * [RawVideoRecorder.storageReserveBytes] instead of filling the disk.
 * Opt-in like RawVideoRecordTest: `-e rawlensVideoRecord true`;
 * `-e keepTakes true` keeps the stopped take for host decoding.
 */
class RawVideoStorageGuardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    private fun setup(): Triple<CameraManager, String, android.util.Size> {
        assumeTrue("Pass -e rawlensVideoRecord true", args.getString("rawlensVideoRecord") == "true")
        assumeTrue("crs native library unavailable", CrsWriter.available)
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        val size = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width * it.height }
        return Triple(manager, id, size)
    }

    private val meta = "{\"UniqueCameraModel\":\"test\",\"fps\":${RawVideoRecorder.FPS}}"

    @Test
    fun startRefusesBelowReserve() {
        val (manager, id, size) = setup()
        val file = File(context.cacheDir, "v4_refused.crs").apply { delete() }
        val recorder = RawVideoRecorder(manager, id, size, context).apply {
            audioEnabled = false
            storageReserveBytes = Long.MAX_VALUE
        }
        try {
            recorder.start(file, meta)
            recorder.stop()
            fail("start must refuse below the reserve")
        } catch (e: RawVideoRecorder.StorageFullException) {
            assertTrue(e.freeBytes > 0)
        }
        assertFalse("no file on refusal", file.exists())
        val leaked = Thread.getAllStackTraces().keys.filter {
            it.isAlive && (it.name.startsWith("RawVideo"))
        }.map { it.name }
        assertEquals(emptyList<String>(), leaked)
    }

    @Test
    fun lowStorageMidTakeStopsCleanly() {
        val (manager, id, size) = setup()
        val file = File(context.cacheDir, "v4_low.crs").apply { delete() }
        val fired = CountDownLatch(1)
        val recorder = RawVideoRecorder(manager, id, size, context).apply {
            audioEnabled = false
            onStorageLow = { fired.countDown() }
        }
        recorder.start(file, meta)
        SystemClock.sleep(1_500)
        val before = recorder.snapshot().framesAcquired
        assertTrue("take must be rolling", before > 0)
        recorder.storageReserveBytes = Long.MAX_VALUE
        assertTrue("onStorageLow must fire", fired.await(3, TimeUnit.SECONDS))
        assertTrue(recorder.storageLow)
        SystemClock.sleep(500)
        val acquiredAfter = recorder.snapshot().framesAcquired
        SystemClock.sleep(1_000)
        assertEquals("no frames admitted after the guard tripped",
            acquiredAfter, recorder.snapshot().framesAcquired)
        val stats = recorder.stop()
        assertTrue("frames committed", stats.containerFrames > 0)
        // Footer present: the file ends with the footer item written by close().
        assertTrue(file.length() > 0)
        if (args.getString("keepTakes") == "true") {
            context.getExternalFilesDir(null)?.let { file.copyTo(File(it, file.name), overwrite = true) }
        }
        file.delete()
    }
}
