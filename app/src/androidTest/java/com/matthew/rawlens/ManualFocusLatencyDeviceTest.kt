// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Isolated, opt-in diagnostic; never changes the product autofocus controller. */
class ManualFocusLatencyDeviceTest {
    @Test fun measureManualLensFeedback() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e manualFocusProbe true", args.getString("manualFocusProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val thread = HandlerThread("manual-focus-probe").apply { start() }
        val handler = Handler(thread.looper)
        val manager = context.getSystemService(CameraManager::class.java)
        val id = args.getString("cameraId") ?: "0"
        val c = manager.getCameraCharacteristics(id)
        val maxFocus = c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f
        assertTrue("Manual movable focus not advertised", maxFocus > 0f &&
            c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) == true)
        val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.minByOrNull { it.width.toLong() * it.height }
            ?: error("RAW_SENSOR unavailable")
        val yuvSize = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width >= 640 && it.height >= 480 }
            .minByOrNull { it.width.toLong() * it.height } ?: error("No preview analysis stream")
        val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3)
        val yuv = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2)
        val output = File(context.getExternalFilesDir(null), "manual-focus-latency.jsonl")
        val writer = output.bufferedWriter()
        val crops = DataOutputStream(File(context.getExternalFilesDir(null), "manual-focus-crops.f32").outputStream().buffered())
        val cropBuffer = ByteBuffer.allocate(64 * 64 * 4).order(ByteOrder.BIG_ENDIAN)
        val cfa = c[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]!!
        val greenPhases = if (cfa == 0 || cfa == 3) intArrayOf(1, 2) else intArrayOf(0, 3)
        val step = args.getString("focusStep")?.toFloatOrNull()
        val dwellMs = (args.getString("dwellMs")?.toLongOrNull() ?: 1200L).coerceIn(500L, 2000L)
        val focusStart = (args.getString("focusStart")?.toFloatOrNull() ?: 1f).coerceIn(0f, maxFocus)
        val focusEnd = (args.getString("focusEnd")?.toFloatOrNull() ?: minOf(5f, maxFocus)).coerceIn(focusStart, maxFocus)
        require(step == null || step.isFinite() && step > 0f && (focusEnd - focusStart) / step <= 100)
        val repeats = (args.getString("repeats")?.toIntOrNull() ?: 2).coerceIn(1, 3)
        var accepting = true // Accessed only on the camera handler after initialization.
        fun emit(o: JSONObject) { if (accepting) { writer.write(o.toString()); writer.newLine(); writer.flush() } }
        val results = LinkedHashMap<Long, TotalCaptureResult>()
        data class Optical(val timestamp: Long, val received: Long, val green: FloatArray)
        val samples = LinkedHashMap<Long, Optical>()
        val started = LinkedHashMap<Long, Long>()
        var latestResult: TotalCaptureResult? = null
        var pairs = 0
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        fun pair(timestamp: Long) {
            val result = results[timestamp] ?: return
            val sample = samples.remove(timestamp) ?: return
            results.remove(timestamp)
            val dynamic = result[CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL]
            val static = c[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]
            val white = result[CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL] ?: c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL]!!
            val black = DoubleArray(4) { phase -> dynamic?.getOrNull(phase)?.toDouble()
                ?: static?.getOffsetForIndex(phase % 2, phase / 2)?.toDouble() ?: 0.0 }
            val values = DoubleArray(64 * 64)
            for (i in values.indices) {
                val normalized = DoubleArray(4) { phase ->
                    ((sample.green[i * 4 + phase] - black[phase]) / (white - black[phase]).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                }
                values[i] = if (normalized.any { it >= .98 }) Double.NaN
                    else (normalized[greenPhases[0]] + normalized[greenPhases[1]]) * .5
            }
            val finite = values.filter { it.isFinite() }
            val mean = if (finite.isEmpty()) 0.0 else finite.average()
            // Conservative pooled-green variance bound; preserve the complete profile for offline auditing.
            val profile = result[CaptureResult.SENSOR_NOISE_PROFILE]
            val noiseVariance = profile?.maxOfOrNull { (it.first * mean + it.second).coerceAtLeast(0.0) * .5 } ?: 0.0
            cropBuffer.clear()
            for (value in values) cropBuffer.putFloat(value.toFloat())
            val cropIndex = pairs
            crops.write(cropBuffer.array())
            var energy = 0.0; var valid = 0
            for (y in 1 until 63) for (x in 1 until 63) {
                val i = y * 64 + x
                if (listOf(i, i - 1, i + 1, i - 64, i + 64).any { !values[it].isFinite() }) continue
                val gx = values[i + 1] - values[i - 1]; val gy = values[i + 64] - values[i - 64]
                energy += gx * gx + gy * gy; valid++
            }
            emit(JSONObject().put("type", "frame").put("timestampNs", timestamp)
                .put("frame", result.frameNumber).put("command", result.request.tag)
                .put("imageReceivedNs", sample.received).put("resultReceivedNs", SystemClock.elapsedRealtimeNanos())
                .put("startedCallbackNs", started.remove(result.frameNumber))
                .put("reportedFocus", result[CaptureResult.LENS_FOCUS_DISTANCE])
                .put("lensState", result[CaptureResult.LENS_STATE])
                .put("iso", result[CaptureResult.SENSOR_SENSITIVITY])
                .put("exposureNs", result[CaptureResult.SENSOR_EXPOSURE_TIME])
                .put("cropIndex", cropIndex).put("noiseVarianceBound", noiseVariance)
                .put("noiseProfile", profile?.let { org.json.JSONArray(it.flatMap { v -> listOf(v.first, v.second) }) })
                .put("mean", mean).put("gradientEnergy", if (valid > 0) energy / valid else 0.0)
                .put("validPixels", valid))
            pairs++
        }
        try {
            emit(JSONObject().put("type", "config").put("model", android.os.Build.MODEL).put("cameraId", id)
                .put("maxFocus", maxFocus).put("calibration", c[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION])
                .put("timestampSource", c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE])
                .put("rawSize", rawSize.toString()).put("previewSize", yuvSize.toString())
                .put("metric", "per-phase normalized pooled-green central 128x128 RAW ROI; clipped quads are NaN")
                .put("cropFormat", "64x64 pooled green, float32 big endian, sequential cropIndex; NaN masks clipping")
                .put("focusStep", step).put("dwellMs", dwellMs).put("repeats", repeats))
            val opened = CompletableFuture<CameraDevice>()
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { if (!opened.complete(d)) d.close() }
                override fun onDisconnected(d: CameraDevice) { d.close(); opened.completeExceptionally(IllegalStateException("disconnected")) }
                override fun onError(d: CameraDevice, e: Int) { d.close(); opened.completeExceptionally(IllegalStateException("camera error $e")) }
            }, handler)
            device = opened.get(15, TimeUnit.SECONDS)
            val ready = CompletableFuture<CameraCaptureSession>()
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(raw.surface, yuv.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { if (!ready.complete(s)) s.close() }
                override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); ready.completeExceptionally(IllegalStateException("RAW+YUV rejected")) }
            }, handler)
            session = ready.get(15, TimeUnit.SECONDS)
            yuv.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
            raw.setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { image ->
                    val crop = copyGreen(image, c) ?: return@use
                    samples[image.timestamp] = Optical(image.timestamp, SystemClock.elapsedRealtimeNanos(), crop)
                    pair(image.timestamp)
                    while (samples.size > 12) samples.remove(samples.keys.first())
                }
            }, handler)
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, q: CaptureRequest, ts: Long, frame: Long) {
                    started[frame] = SystemClock.elapsedRealtimeNanos()
                    while (started.size > 32) started.remove(started.keys.first())
                }
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) {
                    if (!accepting) return
                    latestResult = r
                    r[CaptureResult.SENSOR_TIMESTAMP]?.let { results[it] = r; pair(it) }
                    while (results.size > 12) results.remove(results.keys.first())
                }
                override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) {
                    emit(JSONObject().put("type", "captureFailure").put("command", q.tag).put("reason", f.reason))
                }
            }
            val warm = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(raw.surface); addTarget(yuv.surface); setTag(-1)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
            session.setRepeatingRequest(warm.build(), callback, handler)
            Thread.sleep(2000)
            // Freeze actual AE output if manual sensor controls are supported, to separate exposure from focus.
            val snapshot = CompletableFuture<TotalCaptureResult?>()
            handler.post { snapshot.complete(latestResult) }
            val baseline = snapshot.get(2, TimeUnit.SECONDS)
            val manualSensor = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            val ascending = if (step != null) {
                (0..kotlin.math.floor(((focusEnd - focusStart) / step).toDouble()).toInt())
                    .map { (focusStart + it * step).coerceAtMost(focusEnd) }
            } else listOf(0f, maxFocus * .25f, maxFocus * .5f, maxFocus * .75f, maxFocus)
            val positions = (0 until repeats).flatMap { ascending + ascending.reversed() }
            for ((index, target) in positions.withIndex()) {
                val completed = CompletableFuture<Unit>()
                handler.post {
                    try {
                        val q = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(raw.surface); addTarget(yuv.surface); setTag(index)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                            set(CaptureRequest.LENS_FOCUS_DISTANCE, target)
                            val iso = baseline?.get(CaptureResult.SENSOR_SENSITIVITY)
                            val ns = baseline?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                            if (manualSensor && iso != null && ns != null) {
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                set(CaptureRequest.SENSOR_SENSITIVITY, iso); set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
                            }
                            if (c[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true) set(CaptureRequest.CONTROL_AWB_LOCK, true)
                        }.build()
                        val submitted = SystemClock.elapsedRealtimeNanos()
                        val sequence = session.setRepeatingRequest(q, callback, handler)
                        emit(JSONObject().put("type", "command").put("command", index).put("target", target)
                            .put("submittedNs", submitted).put("sequence", sequence).put("manualExposure", manualSensor && baseline != null))
                        completed.complete(Unit)
                    } catch (t: Throwable) { completed.completeExceptionally(t) }
                }
                completed.get(5, TimeUnit.SECONDS)
                Thread.sleep(dwellMs)
            }
            val stopped = CompletableFuture<Unit>()
            handler.post { session.stopRepeating(); emit(JSONObject().put("type", "end").put("pairedFrames", pairs)); stopped.complete(Unit) }
            stopped.get(5, TimeUnit.SECONDS)
            assertTrue("Too few exact RAW/result pairs", pairs >= 30)
        } finally {
            val closed = CompletableFuture<Unit>()
            handler.post {
                try { accepting = false; results.clear(); samples.clear(); started.clear()
                    raw.setOnImageAvailableListener(null, null); yuv.setOnImageAvailableListener(null, null)
                    session?.close(); device?.close(); raw.close(); yuv.close(); crops.close(); writer.close()
                } finally { closed.complete(Unit) }
            }
            closed.get(10, TimeUnit.SECONDS)
            thread.quitSafely(); thread.join(3000)
        }
    }

    private fun copyGreen(image: Image, c: CameraCharacteristics): FloatArray? {
        val plane = image.planes.singleOrNull() ?: return null
        if (plane.pixelStride != 2 || image.width < 128 || image.height < 128) return null
        val cfa = c[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT] ?: return null
        if (cfa !in 0..3) return null
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val ox = ((image.width - 128) / 2) and -2; val oy = ((image.height - 128) / 2) and -2
        return FloatArray(64 * 64 * 4) { j ->
            val i = j / 4; val phase = j % 4
            val x = ox + (i % 64) * 2 + (phase % 2)
            val y = oy + (i / 64) * 2 + (phase / 2)
            (buffer.getShort(y * plane.rowStride + x * 2).toInt() and 65535).toFloat()
        }
    }
}
