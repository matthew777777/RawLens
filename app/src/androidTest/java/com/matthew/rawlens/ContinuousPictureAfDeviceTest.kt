package com.matthew.rawlens

import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in lens-motion experiment; does not infer sharpness from an AF badge. */
class ContinuousPictureAfDeviceTest {
    @Test fun sweepTriggerSequences() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("rawlensAfProbe") == "true")
        val context = inst.targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val id = args.getString("cameraId") ?: "0"
        val chars = manager.getCameraCharacteristics(id)
        val thread = HandlerThread("af-probe").apply { start() }
        val handler = Handler(thread.looper)
        val opened = CompletableFuture<CameraDevice>()
        data class Sample(val state: Int?, val distance: Float?, val mode: Int?)
        val samples = CopyOnWriteArrayList<Sample>()
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) {
                samples.add(Sample(r[CaptureResult.CONTROL_AF_STATE], r[CaptureResult.LENS_FOCUS_DISTANCE], r[CaptureResult.CONTROL_AF_MODE]))
            }
        }
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3)
        val rawSize = if (args.getString("raw") == "true") chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            .getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } else null
        val rawReader = rawSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 3) }
        rawReader?.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        val surfaces = listOfNotNull(reader.surface, rawReader?.surface)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { opened.complete(d) }
                override fun onDisconnected(d: CameraDevice) { d.close(); opened.completeExceptionally(Exception("disconnected")) }
                override fun onError(d: CameraDevice, e: Int) { d.close(); opened.completeExceptionally(Exception("camera error $e")) }
            }, handler)
            device = opened.get(15, TimeUnit.SECONDS)
            val configured = CompletableFuture<CameraCaptureSession>()
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { configured.complete(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(Exception("configure failed")) }
            }, handler)
            session = configured.get(15, TimeUnit.SECONDS)
            val d = device
            val s = session
            val active = chars[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]!!
            val region = MeteringRectangle(active.left + active.width()/3, active.top + active.height()/3,
                active.width()/3, active.height()/3, 1000)
            var template = CameraDevice.TEMPLATE_PREVIEW
            var clearRegion = false
            var controlMode = 1
            fun request(mode: Int, trigger: Int = 0, ae: Int = 1, distance: Float? = null): CaptureRequest =
                d.createCaptureRequest(template).apply {
                    surfaces.forEach { addTarget(it) }
                    set(CaptureRequest.CONTROL_MODE, controlMode)
                    set(CaptureRequest.CONTROL_AF_MODE, mode)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
                    set(CaptureRequest.CONTROL_AE_MODE, ae)
                    if (ae == 0) {
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                        set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                    }
                    if ((chars[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0)
                        set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(if (clearRegion) MeteringRectangle(active, 0) else region))
                    if (distance != null) set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                }.build()
            fun repeat(mode: Int, ae: Int = 1, distance: Float? = null) {
                s.setRepeatingRequest(request(mode, ae = ae, distance = distance), callback, handler)
            }
            fun trigger(mode: Int, value: Int, ae: Int = 1) { s.capture(request(mode, value, ae), callback, handler) }
            val report = StringBuilder("device=${android.os.Build.MODEL} camera=$id raw=$rawSize modes=${chars[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.toList()}\n")
            val strategies = args.getString("strategies")?.split(",") ?: listOf("region", "cancel", "start", "cancel-start", "off-reset", "auto-reset", "video-reset", "auto-scan")
            report.appendLine("vendorAfRequestKeys=${chars.availableCaptureRequestKeys.filter { it.name.contains("af", true) || it.name.contains("focus", true) }.map { it.name }}")
            val warm = args.getString("warm") == "true"
            val aeModes = args.getString("ae")?.let { listOf(it.toInt()) } ?: listOf(1, 0)
            for (ae in aeModes) for (strategy in strategies) {
                for (seed in listOf(0f, chars[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 5f)) {
                    template = CameraDevice.TEMPLATE_PREVIEW
                    clearRegion = false
                    controlMode = 1
                    repeat(0, ae, seed)
                    Thread.sleep(500)
                    if (warm) {
                        repeat(4, ae)
                        Thread.sleep(3000)
                    }
                    val baseline = samples.lastOrNull()
                    samples.clear()
                    when (strategy) {
                        "off-reset" -> { repeat(0, ae, if (warm) null else seed); Thread.sleep(200) }
                        "auto-reset", "auto-scan" -> { repeat(1, ae); Thread.sleep(200); trigger(1, 2, ae) }
                        "video-reset" -> { repeat(3, ae); Thread.sleep(200); trigger(3, 2, ae) }
                        "region-clear" -> { clearRegion = true; repeat(4, ae); Thread.sleep(200); clearRegion = false }
                        "zsl" -> { template = CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG }
                        "still-template" -> { template = CameraDevice.TEMPLATE_STILL_CAPTURE }
                        "control-reset" -> { controlMode = 0; repeat(4, ae); Thread.sleep(200); controlMode = 1 }
                        "repeat-restart" -> { s.stopRepeating(); Thread.sleep(200) }
                        "ae-pulse" -> { repeat(4, 1); Thread.sleep(500) }
                        "off-cancel" -> { repeat(0, ae); trigger(0, 2, ae); Thread.sleep(200) }
                        "auto-cancel" -> { repeat(1, ae); Thread.sleep(200); trigger(1, 2, ae); Thread.sleep(200) }
                    }
                    repeat(if (strategy == "auto-scan") 1 else 4, ae)
                    Thread.sleep(200)
                    when (strategy) {
                        "cancel", "off-reset", "auto-reset", "video-reset", "auto-cancel", "off-cancel", "ae-pulse", "region-clear" -> { clearRegion = true; repeat(4, ae); Thread.sleep(200); clearRegion = false }
                        "zsl" -> { template = CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG }
                        "still-template" -> { template = CameraDevice.TEMPLATE_STILL_CAPTURE }
                        "control-reset" -> { controlMode = 0; repeat(4, ae); Thread.sleep(200); controlMode = 1 }
                        "repeat-restart", "region-clear", "zsl", "still-template", "control-reset" -> trigger(4, 2, ae)
                        "start" -> trigger(4, 1, ae)
                        "cancel-start" -> { trigger(4, 2, ae); Thread.sleep(200); trigger(4, 1, ae) }
                        "auto-scan" -> trigger(1, 1, ae)
                    }
                    Thread.sleep(3000)
                    val snapshot = samples.toList()
                    check(snapshot.isNotEmpty()) { "No capture results for $strategy" }
                    val distances = snapshot.filter { it.mode == 4 }.mapNotNull { it.distance }
                    val line = "ae=$ae strategy=$strategy seed=$seed frames=${snapshot.size} baseline=$baseline modes=${snapshot.map { it.mode }.distinct()} states=${snapshot.map { it.state }.distinct()} fd=${distances.minOrNull()}..${distances.maxOrNull()} final=${distances.lastOrNull()}"
                    report.appendLine(line)
                    java.io.File(context.getExternalFilesDir(null), "continuous-af-probe.txt").writeText(report.toString())
                    Log.i("RawLensAfProbe", line)
                }
            }
            java.io.File(context.getExternalFilesDir(null), "continuous-af-probe.txt").writeText(report.toString())
        } finally {
            session?.close(); device?.close(); reader.close(); rawReader?.close(); thread.quitSafely()
        }
    }
}
