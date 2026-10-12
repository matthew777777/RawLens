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

/** Opt-in hardware experiment: seamless sensor-mode / in-sensor-zoom / remosaic forcing. Recorded, not asserted. */
class SensorModeProbeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputDir = File(context.getExternalFilesDir(null), "sensormode-probe")
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("sensormode-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val report = StringBuilder()
    private val devices = mutableListOf<CameraDevice>()
    private fun log(s: String) { synchronized(report) { report.appendLine(s) }; Log.i("SENSORMODEPROBE", s) }
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

    private fun fmtArray(a: IntArray): String = if (a.size <= 12) a.contentToString() else a.take(12).toString() + "...len=${a.size}"
    private fun fmtArray(a: LongArray): String = if (a.size <= 12) a.contentToString() else a.take(12).toString() + "...len=${a.size}"

    private fun probeResult(r: CaptureResult, name: String): String {
        try { r.get(CaptureResult.Key(name, Int::class.java))?.let { return "int=$it" } } catch (_: Exception) { }
        try { r.get(CaptureResult.Key(name, IntArray::class.java))?.let { return "int[]=${fmtArray(it)}" } } catch (_: Exception) { }
        try { r.get(CaptureResult.Key(name, Long::class.java))?.let { return "long=$it" } } catch (_: Exception) { }
        try { r.get(CaptureResult.Key(name, LongArray::class.java))?.let { return "long[]=${fmtArray(it)}" } } catch (_: Exception) { }
        try { r.get(CaptureResult.Key(name, Float::class.java))?.let { return "float=$it" } } catch (_: Exception) { }
        try { r.get(CaptureResult.Key(name, FloatArray::class.java))?.let { return "float[]=${it.take(12)}...len=${it.size}" } } catch (_: Exception) { }
        return "absent"
    }

    private fun probeChar(c: CameraCharacteristics, name: String): String {
        try { c.get(CameraCharacteristics.Key(name, IntArray::class.java))?.let { return "int[]=${fmtArray(it)}" } } catch (_: Exception) { }
        try { c.get(CameraCharacteristics.Key(name, Int::class.java))?.let { return "int=$it" } } catch (_: Exception) { }
        try { c.get(CameraCharacteristics.Key(name, LongArray::class.java))?.let { return "long[]=${fmtArray(it)}" } } catch (_: Exception) { }
        try { c.get(CameraCharacteristics.Key(name, Long::class.java))?.let { return "long=$it" } } catch (_: Exception) { }
        return "absent"
    }

    private data class SweepRow(val v: Int, val line: String, val sig: String)

    private inner class Route(val device: CameraDevice, val watchKeys: List<String>, extras: (CaptureRequest.Builder) -> Unit = {}) : AutoCloseable {
        val c = manager.getCameraCharacteristics(device.id)
        val size = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.RAW_SENSOR)!!
            .maxBy { it.width.toLong() * it.height }
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 4)
        val session: CameraCaptureSession
        val applyExtras = extras
        init {
            val f = CompletableFuture<CameraCaptureSession>()
            try {
                device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(reader.surface)),
                    { r -> handler.post(r) }, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (!f.complete(s)) s.close() }
                        override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); f.completeExceptionally(Exception("configure rejected")) }
                    }))
                session = f.waitFor()
                log("CONFIGURED ${device.id} RAW=$size")
            } catch (e: Exception) { f.cancel(false); reader.close(); throw e }
        }
        fun capture(label: String, iso: Int, exposure: Long): TotalCaptureResult {
            val imageFuture = CompletableFuture<Triple<Int, Int, String>>()
            reader.setOnImageAvailableListener({ source ->
                source.acquireNextImage()?.use { image ->
                    try {
                        val plane = image.planes[0]
                        val b = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val bytes = ByteArray(b.remaining())
                        b.duplicate().get(bytes)
                        File(outputDir, "$label.raw16").writeBytes(bytes)
                        var sum = 0.0; var n = 0L; var min = 65535; var max = 0
                        var y = image.height / 4
                        while (y < image.height * 3 / 4) {
                            var x = image.width / 4
                            while (x < image.width * 3 / 4) {
                                val v = b.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 65535
                                sum += v; n++; min = minOf(min, v); max = maxOf(max, v)
                                x += 8
                            }
                            y += 8
                        }
                        imageFuture.complete(Triple(image.width, image.height,
                            "mean=${"%.1f".format(sum / n)} min=$min max=$max stride=${plane.rowStride} pxStride=${plane.pixelStride}"))
                    } catch (e: Exception) { imageFuture.completeExceptionally(e) }
                }
            }, handler)
            val result = CompletableFuture<TotalCaptureResult>()
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            val actualIso = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!.clamp(iso)
            val actualExposure = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]!!.clamp(exposure)
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            request.set(CaptureRequest.SENSOR_SENSITIVITY, actualIso)
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, actualExposure)
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            try { applyExtras(request) } catch (e: Exception) { log("EXTRAS_FAIL $label: $e") }
            request.addTarget(reader.surface)
            log("REQUEST $label iso=$actualIso exposure=$actualExposure")
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) { result.complete(r) }
                override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) { result.completeExceptionally(Exception("capture failure ${f.reason}")) }
            }, handler)
            val r = result.waitFor()
            val (w, h, stats) = imageFuture.waitFor()
            log("IMAGE $label ${w}x$h echoIso=${r[CaptureResult.SENSOR_SENSITIVITY]} echoExp=${r[CaptureResult.SENSOR_EXPOSURE_TIME]} $stats")
            for (name in watchKeys) log("RESULT $label $name ${probeResult(r, name)}")
            return r
        }
        /**
         * One lightweight sweep step: single manual capture with [forceKey]=[v] ([v] < 0
         * means no set = baseline). No .raw16 is kept unless the signature (size, strides,
         * echo, scenario, mode, status) differs from [saveIfChangedFrom].
         */
        fun sweepCapture(v: Int, forceKey: String, saveIfChangedFrom: String?): SweepRow {
            data class Img(val w: Int, val h: Int, val stride: Int, val mean: Double, val bytes: ByteArray)
            val imgF = CompletableFuture<Img>()
            reader.setOnImageAvailableListener({ source ->
                source.acquireNextImage()?.use { image ->
                    try {
                        val plane = image.planes[0]
                        val b = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val bytes = ByteArray(b.remaining())
                        b.duplicate().get(bytes)
                        var sum = 0.0; var n = 0L
                        var y = image.height / 4
                        while (y < image.height * 3 / 4) {
                            var x = image.width / 4
                            while (x < image.width * 3 / 4) {
                                sum += (b.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 65535).toDouble()
                                n++
                                x += 32
                            }
                            y += 32
                        }
                        imgF.complete(Img(image.width, image.height, plane.rowStride, sum / n, bytes))
                    } catch (e: Exception) { imgF.completeExceptionally(e) }
                }
            }, handler)
            val result = CompletableFuture<TotalCaptureResult>()
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            request.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 20_000_000L)
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            if (v >= 0) {
                try { request.set(CaptureRequest.Key(forceKey, Int::class.java), v) }
                catch (_: Exception) {
                    try { request.set(CaptureRequest.Key(forceKey, IntArray::class.java), intArrayOf(v)) }
                    catch (e: Exception) { return SweepRow(v, "v=$v SETFAIL $e", "SETFAIL") }
                }
            }
            request.addTarget(reader.surface)
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) { result.complete(r) }
                override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) { result.completeExceptionally(Exception("capture failure ${f.reason}")) }
            }, handler)
            return try {
                val r = result.get(8, TimeUnit.SECONDS)
                val img = imgF.get(6, TimeUnit.SECONDS)
                val echo = watchKeys.firstOrNull { it.contains("force", ignoreCase = true) }?.let { probeResult(r, it) } ?: "n/a"
                val scenario = watchKeys.firstOrNull { it.contains("sensorScenario", ignoreCase = true) }?.let { probeResult(r, it) } ?: "n/a"
                val mode = watchKeys.firstOrNull { it.contains("sensormode", ignoreCase = true) }?.let { probeResult(r, it) } ?: "n/a"
                val status = watchKeys.firstOrNull { it.contains("insensorzoom.status", ignoreCase = true) }?.let { probeResult(r, it) } ?: "n/a"
                // NOTE: echo excluded on purpose (it tracks v); a change means the HAL *did* something.
                val sig = "${img.w}x${img.h}s${img.stride}|$scenario|$mode|$status"
                if (saveIfChangedFrom == null || sig != saveIfChangedFrom) {
                    File(outputDir, "forcesweep-v$v.raw16").writeBytes(img.bytes)
                }
                SweepRow(v, "v=$v ${img.w}x${img.h} stride=${img.stride} mean=${"%.1f".format(img.mean)} echo=$echo scenario=$scenario sensormode=$mode status=$status", sig)
            } catch (e: Exception) {
                SweepRow(v, "v=$v FAILED $e", "FAILED")
            }
        }
        override fun close() { session.close(); reader.close() }
    }

    private fun setInt(builder: CaptureRequest.Builder, name: String, v: Int): Boolean {
        return try { builder.set(CaptureRequest.Key(name, Int::class.java), v); log("SET $name int=$v ok"); true } catch (e: Exception) { log("SET $name int=$v FAIL $e"); false }
    }
    private fun setIntArray(builder: CaptureRequest.Builder, name: String, v: IntArray): Boolean {
        return try { builder.set(CaptureRequest.Key(name, IntArray::class.java), v); log("SET $name int[]=${v.contentToString()} ok"); true } catch (e: Exception) { log("SET $name int[] FAIL $e"); false }
    }

    @Test fun probeSensorMode() {
        outputDir.mkdirs()
        try {
            log("DEVICE ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}")
            val c0 = manager.getCameraCharacteristics("0")
            val reqNames = c0.availableCaptureRequestKeys.map { it.name }
            val resNames = c0.availableCaptureResultKeys.map { it.name }
            val charNames = try { c0.keys.map { it.name } } catch (e: Exception) { log("CHARKEYS_FAIL $e"); emptyList() }
            val subs = listOf("seamless", "sensorScenario", "sensormode", "insensorzoom", "insensor", "remosaic", "cellcrop", "cellfull", "forcesensor", "sensorMode")
            fun interesting(n: String): Boolean { val l = n.lowercase(); return subs.any { l.contains(it.lowercase()) } }
            val watchRes = resNames.filter(::interesting).sorted()
            for (n in reqNames.filter(::interesting).sorted()) log("REQKEY $n")
            for (n in watchRes) log("RESKEY $n")
            for (n in charNames.filter(::interesting).sorted()) log("VCHAR $n ${probeChar(c0, n)}")
            log("RAW_SIZES_ALL ${c0[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.RAW_SENSOR)!!.contentToString()}")
            // Resolve settable keys by substring so exact vendor prefixes never matter.
            fun resolve(pool: List<String>, vararg subs: String): String? =
                pool.filter { n -> subs.all { n.contains(it, ignoreCase = true) } }.minByOrNull { it.length }
            val forceKey = resolve(reqNames, "force", "sensor", "mode")
            val hintsKey = resolve(reqNames, "insensorzoom", "hint") ?: resolve(reqNames, "insensor", "hint")
            val internalApKey = resolve(reqNames, "insensor", "internal")
            val remosaicKeys = reqNames.filter { it.contains("remosaic", ignoreCase = true) && it.contains("enable", ignoreCase = true) }.sorted()
            log("RESOLVE force=$forceKey hints=$hintsKey internalAp=$internalApKey remosaic=$remosaicKeys")
            attempt("baseline") {
                Route(open("0"), watchRes).use { route -> route.capture("sensormode-baseline", 400, 20_000_000L) }
            }
            if (forceKey != null) for (v in listOf(0, 1)) attempt("forceSensorMode=$v") {
                Route(open("0"), watchRes) {
                    if (!setInt(it, forceKey, v)) setIntArray(it, forceKey, intArrayOf(v))
                }.use { route -> route.capture("sensormode-force$v", 400, 20_000_000L) }
            } else log("SKIP forceSensorMode: no request key")
            if (hintsKey != null) for (v in listOf(0, 1)) attempt("iszHints=$v") {
                Route(open("0"), watchRes) {
                    if (!setInt(it, hintsKey, v)) setIntArray(it, hintsKey, intArrayOf(v))
                }.use { route -> route.capture("sensormode-hints$v", 400, 20_000_000L) }
            } else log("SKIP insensorzoom hints: no request key")
            if (internalApKey != null) attempt("internalAP=1") {
                Route(open("0"), watchRes) {
                    if (!setInt(it, internalApKey, 1)) setIntArray(it, internalApKey, intArrayOf(1))
                }.use { route -> route.capture("sensormode-internalap", 400, 20_000_000L) }
            } else log("SKIP internalAP: no request key")
            for (rk in remosaicKeys.take(2)) attempt("remosaic $rk=1") {
                Route(open("0"), watchRes) {
                    if (!setInt(it, rk, 1)) setIntArray(it, rk, intArrayOf(1))
                }.use { route -> route.capture("sensormode-remosaic", 400, 20_000_000L) }
            }
            if (remosaicKeys.isEmpty()) log("SKIP remosaic: no request key")
            log("HINT verdict = did any IMAGE line change size/strides, or any RESULT scenario/status line change vs baseline?")
        } finally {
            devices.toList().forEach { it.close() }
            File(outputDir, "report.txt").writeText(report.toString())
            thread.quitSafely()
        }
    }

    @Test fun forceSensorSweep() {
        outputDir.mkdirs()
        try {
            log("DEVICE ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}")
            val c0 = manager.getCameraCharacteristics("0")
            val reqNames = c0.availableCaptureRequestKeys.map { it.name }
            val resNames = c0.availableCaptureResultKeys.map { it.name }
            val subs = listOf("seamless", "sensorScenario", "sensormode", "insensorzoom", "remosaic")
            fun interesting(n: String): Boolean { val l = n.lowercase(); return subs.any { l.contains(it.lowercase()) } }
            val watchRes = resNames.filter(::interesting).sorted()
            val forceKey = reqNames.filter { it.contains("force", ignoreCase = true) && it.contains("sensor", ignoreCase = true) }.minByOrNull { it.length }
            if (forceKey == null) { log("ABORT no force-sensor request key"); return }
            log("SWEEPKEY $forceKey")
            Route(open("0"), watchRes).use { route ->
                val base = route.sweepCapture(-1, forceKey, null)
                log("BASE ${base.line}")
                var prevSig = base.sig
                var changes = 0
                for (v in 0..200) {
                    val row = try { route.sweepCapture(v, forceKey, prevSig) } catch (e: Exception) { SweepRow(v, "v=$v ERROR $e", "ERROR") }
                    log("SWEEP ${row.line}")
                    if (row.sig != prevSig && !row.sig.startsWith("FAILED") && row.sig != "SETFAIL" && row.sig != "ERROR") {
                        changes++
                        prevSig = row.sig
                    }
                }
                log("SWEEP_DONE values=201 changes=$changes (a change = size/stride/echo/scenario/mode/status moved; .raw16 kept for baseline + every change)")
            }
        } finally {
            devices.toList().forEach { it.close() }
            File(outputDir, "forcesweep-report.txt").writeText(report.toString())
            thread.quitSafely()
        }
    }
}
