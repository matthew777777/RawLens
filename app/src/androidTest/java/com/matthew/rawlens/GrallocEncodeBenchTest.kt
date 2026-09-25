// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Decides the video frame-rate product question with one number: is the
 * type-7 encoder slower on a real gralloc camera plane (typically uncached
 * CPU mapping) than on a malloc'd direct buffer (cached)?
 *
 * Opens the rear camera (TEMPLATE_RECORD, like the recorder), holds one
 * full-res frame, and times (a) Path-A encode straight off the plane and
 * (b) a raw plane memcpy for the read-bandwidth floor. Compare against the
 * malloc-buffer medians from [CinemaRawSpikeTest.encodeBench].
 * If (a) >> malloc time, CPU reads are uncached and Open Gate full-res
 * belongs at 24fps (41.6ms budget) rather than 30fps (33.3ms).
 *
 * Opt-in: `-e rawlensGrallocBench true`. Log-only, never fails on perf.
 */
@RunWith(AndroidJUnit4::class)
class GrallocEncodeBenchTest {
    @Test fun grallocVsMallocEncode() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensGrallocBench true to open the camera",
            args.getString("rawlensGrallocBench") == "true"
        )
        assumeTrue("cinemaraw native library unavailable", CinemaRawSpike.available)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()

        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.first { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val chars = manager.getCameraCharacteristics(cameraId)
        val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width * it.height }
            ?: error("No RAW_SENSOR sizes")
        val w = size.width
        val h = size.height
        Log.i(TAG, "bench camera=$cameraId ${w}x$h")

        val thread = HandlerThread("GrallocBench").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(w, h, ImageFormat.RAW_SENSOR, 4)
        var device: CameraDevice? = null
        var session: android.hardware.camera2.CameraCaptureSession? = null
        var held: Image? = null
        try {
            val opened = CountDownLatch(1)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    opened.countDown()
                }

                override fun onDisconnected(d: CameraDevice) = opened.countDown()
                override fun onError(d: CameraDevice, e: Int) = opened.countDown()
            }, handler)
            assertTrue(opened.await(10, TimeUnit.SECONDS))
            val dev = device ?: error("open failed")

            val configured = CountDownLatch(1)
            dev.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(reader.surface)),
                    instrumentation.context.mainExecutor,
                    object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                            session = s
                            configured.countDown()
                        }

                        override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                            configured.countDown()
                        }
                    }
                )
            )
            assertTrue(configured.await(10, TimeUnit.SECONDS))
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            }.build()
            session!!.setRepeatingRequest(req, null, handler)
            // Settle AE, then hold one frame (stop the stream so nothing moves).
            SystemClock.sleep(2000)
            session!!.stopRepeating()
            var img = reader.acquireNextImage()
            var guard = 0
            while (img == null && guard++ < 50) {
                SystemClock.sleep(100)
                img = reader.acquireNextImage()
            }
            held = img ?: error("no frame")
            val plane = held!!.planes[0]
            assertTrue("expected unpacked RAW16", plane.pixelStride == 2)
            val src = plane.buffer
            val stride = plane.rowStride
            val planeBytes = ((h - 1) * stride + w * 2)
            Log.i(
                TAG, "plane stride=$stride planeBytes=${"%.1f".format(planeBytes / 1e6)}MB " +
                    "remaining=${src.remaining()}"
            )

            val ew = (w + 63) / 64 * 64
            val dst = ByteBuffer.allocateDirect((ew * h * 2 + ew * h / 8 + 4096).toInt())
                .order(ByteOrder.nativeOrder())
            // Warmup + timed Path-A encodes straight off the gralloc plane.
            src.rewind()
            dst.rewind()
            CinemaRawSpike.encode(src, planeBytes, w, h, stride, false, 0, h / 4 * 4, false, dst)
            val times = mutableListOf<Double>()
            var bytes = 0
            repeat(5) {
                src.rewind()
                dst.rewind()
                val t0 = System.nanoTime()
                bytes = CinemaRawSpike.encode(
                    src, planeBytes, w, h, stride, false, 0, h / 4 * 4, false, dst
                )
                times.add((System.nanoTime() - t0) / 1e6)
            }
            times.sort()
            val med = times[times.size / 2]
            Log.i(
                TAG, "SPIKE gralloc-enc ${w}x$h med=${"%.1f".format(med)}ms " +
                    "out=${"%.2f".format(bytes / 1e6)}MB " +
                    "(malloc-buffer reference ~10ms; gap = mapping cost)"
            )
            // Raw read-bandwidth floor: plane -> malloc copy.
            val sink = ByteBuffer.allocateDirect(planeBytes)
            val copies = mutableListOf<Double>()
            repeat(3) {
                src.rewind()
                sink.rewind()
                val t0 = System.nanoTime()
                sink.put(src)
                copies.add((System.nanoTime() - t0) / 1e6)
            }
            copies.sort()
            val copyMed = copies[1]
            Log.i(
                TAG, "SPIKE gralloc-read ${"%.1f".format(planeBytes / 1e6)}MB " +
                    "med=${"%.1f".format(copyMed)}ms " +
                    "bw=${"%.2f".format(planeBytes / 1e6 / copyMed * 1000 / 1000)}GB/s"
            )
        } finally {
            try {
                held?.close()
            } catch (_: Exception) {
            }
            try {
                session?.close()
            } catch (_: Exception) {
            }
            try {
                device?.close()
            } catch (_: Exception) {
            }
            reader.close()
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "CinemaRawSpike"
    }
}
