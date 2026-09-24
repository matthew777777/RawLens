package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Opt-in hardware experiment. Unsupported combinations are recorded, not assertions. */
class DcgProbeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputDir = File(context.getExternalFilesDir(null), "dcg-probe")
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("dcg-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val report = StringBuilder()
    private val devices = mutableListOf<CameraDevice>()
    private fun log(s: String) { synchronized(report) { report.appendLine(s) }; Log.i("DCGPROBE", s) }
    private fun <T> CompletableFuture<T>.waitFor(): T = get(12, TimeUnit.SECONDS)
    private fun attempt(name: String, block: () -> Unit) {
        log("BEGIN $name")
        try { block(); log("PASS $name") } catch (e: Exception) { log("FAIL $name: $e") }
        finally { devices.toList().forEach { it.close() }; devices.clear(); Thread.sleep(600) }
    }
    private fun open(id: String): CameraDevice {
        val f = CompletableFuture<CameraDevice>()
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { devices.add(d); if (!f.complete(d)) d.close() }
            override fun onDisconnected(d: CameraDevice) { log("DISCONNECTED $id"); d.close(); f.completeExceptionally(Exception("disconnected $id")) }
            override fun onError(d: CameraDevice, error: Int) { log("DEVICE_ERROR $id code=$error"); d.close(); f.completeExceptionally(Exception("camera $id error $error")) }
        }, handler)
        try { return f.waitFor() } catch (e: Exception) { f.cancel(false); throw e }
    }
    private inner class Route(val device: CameraDevice, val physical: String?, count: Int = 1) : AutoCloseable {
        val c = manager.getCameraCharacteristics(physical ?: device.id)
        val size = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.RAW_SENSOR)!!.let { sizes -> sizes.firstOrNull { it.width == 4080 && it.height == 3060 } ?: sizes.minBy { it.width.toLong() * it.height } }
        val readers = List(count) { ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 3) }
        val session: CameraCaptureSession
        init {
            val f = CompletableFuture<CameraCaptureSession>()
            try {
                device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    readers.map { OutputConfiguration(it.surface).apply { physical?.let { p -> setPhysicalCameraId(p) } } },
                    { r -> handler.post(r) }, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (!f.complete(s)) s.close() }
                        override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); f.completeExceptionally(Exception("configure rejected")) }
                    }))
                session = f.waitFor()
                log("CONFIGURED ${device.id}/$physical RAW=$size outputs=$count")
            } catch (e: Exception) { f.cancel(false); readers.forEach { it.close() }; throw e }
        }
        fun capture(label: String, iso: Int, exposure: Long, saveDng: Boolean = false): () -> Unit {
            val images = readers.mapIndexed { index, reader ->
                CompletableFuture<Long>().also { future -> reader.setOnImageAvailableListener({ source ->
                    source.acquireNextImage()?.use { image ->
                        try {
                            val plane = image.planes[0]; val b = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val bytes = ByteArray(b.remaining()); b.duplicate().get(bytes)
                            File(outputDir, "$label-$index.raw16").writeBytes(bytes)
                            var sum = 0.0; var squares = 0.0; var n = 0; var min = 65535; var max = 0
                            for (y in 0 until image.height step 8) for (x in 0 until image.width step 8) {
                                val v = b.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 65535
                                sum += v; squares += v.toDouble() * v; n++; min = minOf(min,v); max = maxOf(max,v)
                            }
                            log("IMAGE $label/$index timestamp=${image.timestamp} width=${image.width} height=${image.height} rowStride=${plane.rowStride} pixelStride=${plane.pixelStride} mean=${sum/n} spatialVariance=${squares/n-(sum/n)*(sum/n)} min=$min max=$max")
                            future.complete(image.timestamp)
                        } catch(e: Exception) { future.completeExceptionally(e) }
                    }
                }, handler) }
            }
            val result = CompletableFuture<TotalCaptureResult>()
            val request = if (physical != null) device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, setOf(physical)) else device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            val actualIso = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!.clamp(iso)
            val actualExposure = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]!!.clamp(exposure)
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            request.set(CaptureRequest.SENSOR_SENSITIVITY, actualIso)
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, actualExposure)
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            if (physical != null) {
                val keys = manager.getCameraCharacteristics(device.id).availablePhysicalCameraRequestKeys.orEmpty()
                if (CaptureRequest.CONTROL_AE_MODE in keys) request.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF, physical)
                if (CaptureRequest.SENSOR_SENSITIVITY in keys) request.setPhysicalCameraKey(CaptureRequest.SENSOR_SENSITIVITY, actualIso, physical)
                if (CaptureRequest.SENSOR_EXPOSURE_TIME in keys) request.setPhysicalCameraKey(CaptureRequest.SENSOR_EXPOSURE_TIME, actualExposure, physical)
            }
            readers.forEach { request.addTarget(it.surface) }
            log("REQUEST $label iso=$actualIso exposure=$actualExposure")
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) { result.complete(r) }
                override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) { result.completeExceptionally(Exception("capture failure ${f.reason}")) }
            }, handler)
            return {
                val r = result.waitFor()
                fun metadata(tag: String, v: CaptureResult) { log("RESULT $tag timestamp=${v[CaptureResult.SENSOR_TIMESTAMP]} iso=${v[CaptureResult.SENSOR_SENSITIVITY]} exposure=${v[CaptureResult.SENSOR_EXPOSURE_TIME]} duration=${v[CaptureResult.SENSOR_FRAME_DURATION]} black=${v[CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL]?.contentToString()} white=${v[CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL]} noise=${v[CaptureResult.SENSOR_NOISE_PROFILE]?.contentToString()}") }
                metadata(label,r); r.physicalCameraResults.forEach { (id, v) -> metadata("$label/physical$id",v) }
                val timestamps = images.map { it.waitFor() }
                check(timestamps.all { it == r[CaptureResult.SENSOR_TIMESTAMP] }) { "image/result timestamps differ: $timestamps" }
                if (saveDng) {
                    val physicalResult = physical?.let { r.physicalCameraResults[it] } ?: r
                    DngCreator(c, physicalResult).use { creator ->
                        creator.setDescription("RawLens sequential dual-gain experiment: $label")
                        File(outputDir, "$label.dng").outputStream().use { out ->
                            File(outputDir, "$label-0.raw16").inputStream().use { input ->
                                creator.writeInputStream(out, size, input, 0L)
                            }
                        }
                    }
                    log("DNG $label.dng")
                }
            }
        }
        override fun close() { session.close(); readers.forEach { it.close() } }
    }
    @Test fun captureMergeInputs() {
        outputDir.mkdirs()
        try {
            for ((id, physical) in listOf("0" to null, "3" to "0")) {
                try {
                    Route(open(id), physical).use { route ->
                        repeat(2) { index ->
                            route.capture("merge-route$id-$index", if (id == "0") 100 else 800,
                                if (id == "0") 33_333_333L else 4_166_667L, saveDng = true)()
                        }
                    }
                } finally { devices.toList().forEach { it.close() }; devices.clear(); Thread.sleep(600) }
            }
        } finally {
            File(outputDir, "merge-report.txt").writeText(report.toString())
            thread.quitSafely()
        }
    }
    @Test fun probeRoutes() {
        outputDir.mkdirs()
        try {
            log("DEVICE ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}")
            log("IDS ${manager.cameraIdList.contentToString()} concurrent=${manager.concurrentCameraIds}")
            for (id in listOf("0", "3")) {
                val c = manager.getCameraCharacteristics(id)
                log("CHAR $id physical=${c.physicalCameraIds} capabilities=${c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contentToString()} level=${c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]} iso=${c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]} analogMax=${c[CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY]} exposure=${c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]} white=${c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL]} black=${c[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]} cfa=${c[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]} focal=${c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.contentToString()} raw=${c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(ImageFormat.RAW_SENSOR)?.contentToString()} physicalKeys=${c.availablePhysicalCameraRequestKeys}")
            }
            for ((id,p) in listOf("0" to null, "3" to "0")) attempt("separate $id/$p") {
                Route(open(id),p).use { route ->
                    repeat(2) { route.capture("route${id}-low-$it",100,33_333_333L)() }
                    repeat(2) { route.capture("route${id}-high-$it",800,4_166_667L)() }
                }
            }
            if (InstrumentationRegistry.getArguments().getString("skipDual") != "true") attempt("two RAW outputs physical 0") { Route(open("3"),"0",2).use { it.capture("dual-output",100,33_333_333L)() } }
            for ((first,second) in listOf("0" to "3", "3" to "0")) attempt("concurrent $first then $second") {
                val a = open(first); val b = open(second)
                Route(a,if(first=="3") "0" else null).use { ra -> Route(b,if(second=="3") "0" else null).use { rb ->
                    val wa = ra.capture("concurrent-$first-first",100,33_333_333L)
                    val wb = rb.capture("concurrent-$second-second",800,4_166_667L)
                    wa(); wb()
                } }
            }
        } finally {
            devices.toList().forEach { it.close() }
            File(outputDir,"report.txt").writeText(report.toString())
            thread.quitSafely()
        }
    }
}
