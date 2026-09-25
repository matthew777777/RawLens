// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 follow-up: settles whether this HAL delivers 30fps full-res RAW when
 * asked the MotionCam way (TEMPLATE_RECORD + fixed [30,30] FPS range) instead
 * of the stills/ZSL path. The static `getOutputMinFrameDuration` (50ms on
 * MT6878) is a worst-case guarantee, not necessarily the record-mode rate.
 *
 * Opt-in: `-e rawlensVideoProbe true [-e seconds 6]`. Opens the rear camera
 * directly (no app UI), drains a RAW ImageReader, and logs the achieved
 * frame cadence. Never fails on fps — it reports; the verdict interprets.
 */
@RunWith(AndroidJUnit4::class)
class VideoFpsProbeTest {
    @Test fun recordModeRawCadence() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensVideoProbe true to open the camera",
            args.getString("rawlensVideoProbe") == "true"
        )
        val seconds = args.getString("seconds", "6").toInt().coerceIn(2, 30)
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
        val rawSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.sortedByDescending { it.width * it.height }
            ?: emptyList()
        assertTrue("No RAW_SENSOR sizes", rawSizes.isNotEmpty())
        val size = rawSizes.first()
        Log.i(TAG, "probing camera=$cameraId size=${size.width}x${size.height}")

        val thread = HandlerThread("VideoFpsProbe").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 6)
        val frames = AtomicInteger(0)
        val stamps = mutableListOf<Long>()
        reader.setOnImageAvailableListener({ r ->
            var img = r.acquireNextImage()
            while (img != null) {
                frames.incrementAndGet()
                synchronized(stamps) { stamps.add(img.timestamp) }
                img.close()
                img = r.acquireNextImage()
            }
        }, handler)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            val opened = CountDownLatch(1)
            var openError: String? = null
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    opened.countDown()
                }

                override fun onDisconnected(d: CameraDevice) {
                    openError = "disconnected"
                    opened.countDown()
                }

                override fun onError(d: CameraDevice, error: Int) {
                    openError = "error=$error"
                    opened.countDown()
                }
            }, handler)
            assertTrue(opened.await(10, TimeUnit.SECONDS))
            assertTrue("open failed: $openError", device != null)

            val configured = CountDownLatch(1)
            var configError: String? = null
            device!!.createCaptureSession(
                android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    listOf(
                        android.hardware.camera2.params.OutputConfiguration(reader.surface)
                    ),
                    instrumentation.context.mainExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            configured.countDown()
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            configError = "configureFailed"
                            configured.countDown()
                        }
                    }
                )
            )
            assertTrue(configured.await(10, TimeUnit.SECONDS))
            assertTrue("session failed: $configError", session != null)

            val req: CaptureRequest = device!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }.build()
            session!!.setRepeatingRequest(req, null, handler)
            SystemClock.sleep(seconds * 1000L)
            session!!.stopRepeating()

            val n = frames.get()
            val ts = synchronized(stamps) { stamps.toList() }.sorted()
            val fps = n / seconds.toDouble()
            val deltas = ts.zipWithNext { a, b -> (b - a) / 1e6 }
            fun pct(p: Double) = if (deltas.isEmpty()) Double.NaN
            else deltas.sorted()[(p * deltas.size).toInt().coerceAtMost(deltas.size - 1)]
            Log.i(
                TAG, "SPIKE rec ${size.width}x${size.height} ${seconds}s frames=$n " +
                    "fps=${"%.1f".format(fps)} " +
                    "dMean=${"%.1f".format(if (deltas.isEmpty()) Double.NaN else deltas.average())}ms " +
                    "dP50=${"%.1f".format(pct(0.5))}ms dP99=${"%.1f".format(pct(0.99))}ms " +
                    "dMax=${"%.1f".format(deltas.maxOrNull() ?: Double.NaN)}ms"
            )
            assertTrue("No RAW frames captured in record mode", n > 0)
        } finally {
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
