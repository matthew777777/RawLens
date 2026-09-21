// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic queue test: pause the writer, capture six inputs, then allow all six to save. */
class JpegQueueInstrumentedTest {
    @Test fun sixJpegsCanBeCapturedBeforeTheFirstSaveCompletes() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("rawlensQueue") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val controller = MainActivity::class.java.getDeclaredField("controller")
            .apply { isAccessible = true }.get(activity) as RawCameraController
        fun field(name: String): Any = RawCameraController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller)!!
        val pending = field("pendingFrameSaveCount") as AtomicInteger
        val sequence = field("captureSequence") as AtomicInteger
        val handler = field("cameraHandler") as Handler
        val releaseWriter = CountDownLatch(1)
        var minimumFps = Float.MAX_VALUE
        fun enabled(): Boolean {
            var result = false
            instrumentation.runOnMainSync { result = activity.findViewById<View>(R.id.shutter).isEnabled }
            return result
        }
        fun await(label: String, timeout: Long = 180_000L, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!condition() && SystemClock.elapsedRealtime() < deadline) {
                val vf = activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot()
                if (vf.glActive && vf.fps > 0f && vf.fps < minimumFps) {
                    minimumFps = vf.fps
                    android.util.Log.i("RawLensQueue", "minFps=$minimumFps phase=$label pending=${pending.get()} frameMs=${vf.frameMs}")
                }
                SystemClock.sleep(50)
            }
            assertTrue(label, condition())
        }
        fun jpegCount(): Int = context.contentResolver.query(MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.IS_PENDING}=0 AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("DCIM/RawLens/%", "%.jpg"), null)?.use { it.count } ?: 0
        try {
            controller.setRawZslFrameCount(1)
            controller.setZslHybridTopup(true)
            controller.setCaptureExposureMode(CaptureExposureMode.valueOf(args.getString("mode", "ZSL")))
            assertTrue(controller.setCaptureFormat(CaptureFormat.JPEG))
            val configured = CountDownLatch(1)
            handler.post { configured.countDown() }
            assertTrue(configured.await(10, TimeUnit.SECONDS))
            SystemClock.sleep(1500)
            await("Camera and RAW VF did not become ready", 30_000) {
                enabled() && activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot().glActive
            }
            minimumFps = Float.MAX_VALUE
            val before = jpegCount()
            val writerPaused = CountDownLatch(1)
            (field("writer") as Executor).execute {
                writerPaused.countDown()
                releaseWriter.await(60, TimeUnit.SECONDS)
            }
            assertTrue(writerPaused.await(10, TimeUnit.SECONDS))
            repeat(6) { index ->
                await("Shutter blocked after $index JPEG inputs while writer was paused", 10_000) { enabled() }
                controller.capture()
                await("JPEG input ${index + 1} was not captured", 10_000) { pending.get() == index + 1 }
            }
            await("Seventh JPEG was not gated", 5000) { !enabled() }
            val fullSequence = sequence.get()
            controller.capture()
            SystemClock.sleep(300)
            assertEquals("A full queue admitted or queued another capture", fullSequence, sequence.get())
            assertEquals(6, pending.get())
            assertEquals("Writer should still be paused", before, jpegCount())
            assertTrue(activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot().glActive)
            releaseWriter.countDown()
            await("Six JPEGs did not finish saving") { pending.get() == 0 && jpegCount() == before + 6 }
            await("Shutter did not rearm as saves finished") { enabled() }
            android.util.Log.i("RawLensQueue", "Six JPEGs captured before saving; all six saved; minFps=$minimumFps " +
                "vf=${activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot()}")
            assertTrue("RAW VF dropped to $minimumFps FPS", minimumFps >= args.getString("minFps", "0").toFloat())
        } finally {
            releaseWriter.countDown()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
