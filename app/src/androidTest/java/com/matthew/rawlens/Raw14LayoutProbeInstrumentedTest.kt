// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Derives the unpublished RAW14 low-bits layout empirically. Captures one
 * static scene per rear camera in `RAW_SENSOR` and RAW14 at a shared size
 * with locked manual exposure, decodes the RAW14 buffer under every
 * [Raw14Candidate], and scores each against the `RAW_SENSOR` frame.
 * Recorded, not asserted: a human declares the winner from the report,
 * then the winning layout ships in [RawPackedUnpacker].
 *
 * Needs an API-37 device whose map lists RAW14. Run, then pull
 * `raw14-probe` from external files. Tripod + static scene assumed.
 */
class Raw14LayoutProbeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputDir = File(context.getExternalFilesDir(null), "raw14-probe")
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("raw14-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val report = StringBuilder()
    private fun log(s: String) { synchronized(report) { report.appendLine(s) }; Log.i("RAW14PROBE", s) }
    private fun <T> CompletableFuture<T>.waitFor(): T = get(15, TimeUnit.SECONDS)

    private data class Frame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val pixelStride: Int
    )

    @Test fun deriveRaw14Layout() {
        try {
            outputDir.mkdirs()
            if (Build.VERSION.SDK_INT < 37) {
                log("UNSUPPORTED api=${Build.VERSION.SDK_INT} needs 37")
                return
            }
            manager.cameraIdList.forEach { id ->
                try {
                    probeCamera(id)
                } catch (e: Exception) {
                    log("FAIL camera $id: $e")
                }
            }
            log("DONE")
        } finally {
            File(outputDir, "report.txt").writeText(report.toString())
            thread.quitSafely()
        }
    }

    private fun probeCamera(id: String) {
        val c = manager.getCameraCharacteristics(id)
        if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
            log("SKIP $id not rear")
            return
        }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sensorSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
        val packedSizes = map.getOutputSizes(IMAGE_FORMAT_RAW14)?.toList().orEmpty()
        if (packedSizes.isEmpty()) {
            log("NO_RAW14 $id sensorSizes=${sensorSizes.size}")
            return
        }
        val white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val common = sensorSizes.toSet().intersect(packedSizes.toSet())
            .maxByOrNull { it.width.toLong() * it.height }
        log("CAMERA $id white=$white sensorSizes=${sensorSizes.size} raw14Sizes=${packedSizes.size} common=$common")
        if (common == null) {
            log("NO_COMMON_SIZE $id")
            return
        }
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!
        val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
        val iso = isoRange.lower
        val exposure = 10_000_000L.coerceIn(expRange.lower, expRange.upper)
        val reference = captureOne(id, ImageFormat.RAW_SENSOR, common.width, common.height, iso, exposure, "ref")
        val packed = captureOne(id, IMAGE_FORMAT_RAW14, common.width, common.height, iso, exposure, "raw14")
        scoreCandidates(id, reference, packed)
    }

    private fun captureOne(
        id: String,
        format: Int,
        width: Int,
        height: Int,
        iso: Int,
        exposure: Long,
        label: String
    ): Frame {
        val openFuture = CompletableFuture<CameraDevice>()
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { if (!openFuture.complete(d)) d.close() }
            override fun onDisconnected(d: CameraDevice) { d.close(); openFuture.completeExceptionally(Exception("disconnected")) }
            override fun onError(d: CameraDevice, e: Int) { d.close(); openFuture.completeExceptionally(Exception("error $e")) }
        }, handler)
        val device = openFuture.waitFor()
        try {
            val reader = ImageReader.newInstance(width, height, format, 2)
            try {
                val sessionFuture = CompletableFuture<CameraCaptureSession>()
                device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(reader.surface)),
                    { r -> handler.post(r) }, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (!sessionFuture.complete(s)) s.close() }
                        override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); sessionFuture.completeExceptionally(Exception("configure rejected")) }
                    }))
                val session = sessionFuture.waitFor()
                try {
                    val imageFuture = CompletableFuture<Frame>()
                    reader.setOnImageAvailableListener({ source ->
                        source.acquireNextImage()?.use { image ->
                            imageFuture.complete(readFrame(image))
                        }
                    }, handler)
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                    }
                    session.capture(request.build(), null, handler)
                    val frame = imageFuture.waitFor()
                    File(outputDir, "$id-$label-${width}x$height.raw").writeBytes(frame.bytes)
                    log("CAPTURED $id format=$format ${width}x$height rowStride=${frame.rowStride} pxStride=${frame.pixelStride}")
                    return frame
                } finally {
                    session.close()
                }
            } finally {
                reader.close()
            }
        } finally {
            device.close()
            Thread.sleep(600)
        }
    }

    private fun readFrame(image: Image): Frame {
        val plane = image.planes[0]
        val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val bytes = ByteArray(buffer.remaining())
        buffer.duplicate().get(bytes)
        return Frame(bytes, image.width, image.height, plane.rowStride, plane.pixelStride)
    }

    private fun Long.coerceIn(range: Range<Long>): Long = minOf(range.upper, maxOf(range.lower, this))

    private fun scoreCandidates(id: String, reference: Frame, packed: Frame) {
        val refCodes = ShortArray(reference.width * reference.height)
        val refBuffer = java.nio.ByteBuffer.wrap(reference.bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in refCodes.indices) refCodes[i] = refBuffer.short
        for (candidate in Raw14Candidate.entries) {
            val decoded = try {
                RawPackedUnpacker.decodeRaw14Plane(
                    java.nio.ByteBuffer.wrap(packed.bytes),
                    packed.width, packed.height, packed.rowStride, 0, candidate, 0
                )
            } catch (e: IllegalArgumentException) {
                log("SCORE $id $candidate REFUSED ${e.message}")
                continue
            }
            val rates = listOf(0, 4, 16).associateWith { tol ->
                "%.4f".format(RawPackedUnpacker.matchRate(refCodes, decoded, tol))
            }
            log("SCORE $id $candidate tol0=${rates[0]} tol4=${rates[4]} tol16=${rates[16]}")
        }
    }
}
