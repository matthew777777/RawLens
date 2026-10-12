package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opt-in hardware experiment: stagger-HDR escalation with AE running, a YUV stream present,
 * and HDR mode delivered as session parameters (the stock-app-like path). Recorded, not asserted.
 */
class StaggerAeProbeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputDir = File(context.getExternalFilesDir(null), "stagger-ae-probe")
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("stagger-ae-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val report = StringBuilder()
    private val devices = mutableListOf<CameraDevice>()
    private fun log(s: String) { synchronized(report) { report.appendLine(s) }; Log.i("STAGGERAEPROBE", s) }
    private fun <T> CompletableFuture<T>.waitFor(): T = get(15, TimeUnit.SECONDS)
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
        return "absent"
    }

    private inner class Route(
        val device: CameraDevice,
        val watchKeys: List<String>,
        val sessionKeys: Map<String, Int> = emptyMap(),
        val clientName: String? = null
    ) : AutoCloseable {
        val c = manager.getCameraCharacteristics(device.id)
        val rawSize = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.RAW_SENSOR)!!
            .maxBy { it.width.toLong() * it.height }
        val yuvSize = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.YUV_420_888)!!
            .filter { it.width <= 1920 }.maxBy { it.width.toLong() * it.height }
        val rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 4)
        val yuvReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 4)
        val yuvFrames = AtomicInteger(0)
        val session: CameraCaptureSession
        init {
            yuvReader.setOnImageAvailableListener({ source ->
                source.acquireNextImage()?.close()
                yuvFrames.incrementAndGet()
            }, handler)
            val f = CompletableFuture<CameraCaptureSession>()
            try {
                val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(rawReader.surface), OutputConfiguration(yuvReader.surface)),
                    { r -> handler.post(r) }, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (!f.complete(s)) s.close() }
                        override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); f.completeExceptionally(Exception("configure rejected")) }
                    })
                if (sessionKeys.isNotEmpty() || clientName != null) {
                    val params = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    for ((name, v) in sessionKeys) {
                        try { params.set(CaptureRequest.Key(name, Int::class.java), v); log("SESSIONPARAM $name int=$v ok") }
                        catch (_: Exception) {
                            try { params.set(CaptureRequest.Key(name, IntArray::class.java), intArrayOf(v)); log("SESSIONPARAM $name int[]=$v ok") }
                            catch (e: Exception) { log("SESSIONPARAM $name FAIL $e") }
                        }
                    }
                    if (clientName != null) {
                        val cnKey = sessionKeyNames().firstOrNull { it.contains("clientName", ignoreCase = true) }
                        if (cnKey != null) {
                            try {
                                params.set(CaptureRequest.Key(cnKey, ByteArray::class.java), (clientName + "\u0000").toByteArray())
                                log("SESSIONPARAM $cnKey clientName=$clientName ok")
                            } catch (e: Exception) { log("SESSIONPARAM $cnKey clientName FAIL $e") }
                        } else log("SESSIONPARAM clientName: no session key")
                    }
                    try { config.setSessionParameters(params.build()); log("SESSIONPARAMS applied") }
                    catch (e: Exception) { log("SESSIONPARAMS apply FAIL $e") }
                }
                device.createCaptureSession(config)
                session = f.waitFor()
                log("CONFIGURED ${device.id} RAW=$rawSize YUV=$yuvSize")
            } catch (e: Exception) { f.cancel(false); rawReader.close(); yuvReader.close(); throw e }
        }
        private fun sessionKeyNames(): List<String> =
            if (Build.VERSION.SDK_INT >= 28) {
                try { c.availableSessionKeys.map { it.name } } catch (_: Exception) { emptyList() }
            } else emptyList()

        /** AE-ON repeating stream for [millis], logging every 5th frame + last; returns last result or null. */
        fun streamAe(label: String, millis: Long, extras: (CaptureRequest.Builder) -> Unit = {}): TotalCaptureResult? {
            val rawFrames = AtomicInteger(0)
            rawReader.setOnImageAvailableListener({ source ->
                source.acquireNextImage()?.close()
                rawFrames.incrementAndGet()
            }, handler)
            fun frameLine(tag: String, r: TotalCaptureResult): String {
                val sb = StringBuilder("$tag ae=${r[CaptureResult.CONTROL_AE_STATE]} iso=${r[CaptureResult.SENSOR_SENSITIVITY]} exp=${r[CaptureResult.SENSOR_EXPOSURE_TIME]}")
                for (k in watchKeys) sb.append(" $k=${probeResult(r, k)}")
                return sb.toString()
            }
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            try { extras(request) } catch (e: Exception) { log("EXTRAS_FAIL $label: $e") }
            request.addTarget(rawReader.surface)
            request.addTarget(yuvReader.surface)
            var last: TotalCaptureResult? = null
            var count = 0
            session.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) {
                    last = r
                    val n = count++
                    if (n % 5 == 0) log(frameLine("FRAME $label#$n", r))
                }
            }, handler)
            Thread.sleep(millis)
            session.stopRepeating()
            last?.let { log(frameLine("FRAME $label#last", it)) }
            log("STREAM $label frames~$count rawFrames=${rawFrames.get()} yuvFrames=${yuvFrames.get()}")
            return last
        }

        fun still(label: String, extras: (CaptureRequest.Builder) -> Unit = {}): TotalCaptureResult? {
            val imageFuture = CompletableFuture<Triple<Int, Int, String>>()
            rawReader.setOnImageAvailableListener({ source ->
                source.acquireNextImage()?.use { image ->
                    try {
                        val plane = image.planes[0]
                        val b = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val bytes = ByteArray(b.remaining())
                        b.duplicate().get(bytes)
                        File(outputDir, "$label.raw16").writeBytes(bytes)
                        var sum = 0.0; var n = 0L
                        var y = image.height / 4
                        while (y < image.height * 3 / 4) {
                            var x = image.width / 4
                            while (x < image.width * 3 / 4) {
                                sum += (b.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 65535).toDouble()
                                n++
                                x += 8
                            }
                            y += 8
                        }
                        imageFuture.complete(Triple(image.width, image.height, "mean=${"%.1f".format(sum / n)} stride=${plane.rowStride}"))
                    } catch (e: Exception) { imageFuture.completeExceptionally(e) }
                }
            }, handler)
            val result = CompletableFuture<TotalCaptureResult>()
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            try { extras(request) } catch (e: Exception) { log("EXTRAS_FAIL $label: $e") }
            request.addTarget(rawReader.surface)
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) { result.complete(r) }
                override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) { result.completeExceptionally(Exception("capture failure ${f.reason}")) }
            }, handler)
            return try {
                val r = result.waitFor()
                val (w, h, stats) = imageFuture.waitFor()
                val sb = StringBuilder("STILL $label ${w}x$h iso=${r[CaptureResult.SENSOR_SENSITIVITY]} exp=${r[CaptureResult.SENSOR_EXPOSURE_TIME]} $stats")
                for (k in watchKeys) sb.append(" $k=${probeResult(r, k)}")
                log(sb.toString())
                r
            } catch (e: Exception) { log("STILL_FAIL $label: $e"); null }
        }
        override fun close() { try { session.close() } catch (_: Exception) { }; rawReader.close(); yuvReader.close() }
    }

    private fun setInt(builder: CaptureRequest.Builder, name: String, v: Int): Boolean {
        return try { builder.set(CaptureRequest.Key(name, Int::class.java), v); true } catch (_: Exception) { false }
    }
    private fun setIntArray(builder: CaptureRequest.Builder, name: String, v: IntArray): Boolean {
        return try { builder.set(CaptureRequest.Key(name, IntArray::class.java), v); true } catch (_: Exception) { false }
    }

    @Test fun probeStaggerAe() {
        outputDir.mkdirs()
        try {
            log("DEVICE ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}")
            val c0 = manager.getCameraCharacteristics("0")
            val reqNames = c0.availableCaptureRequestKeys.map { it.name }
            val sessionNames = if (Build.VERSION.SDK_INT >= 28) {
                try { c0.availableSessionKeys.map { it.name } } catch (e: Exception) { log("SESSIONKEYS_FAIL $e"); emptyList() }
            } else emptyList()
            val sessHdr = sessionNames.filter { it.contains("hdr", ignoreCase = true) }.sorted()
            val sessClient = sessionNames.filter { it.contains("client", ignoreCase = true) || it.contains("operation", ignoreCase = true) }.sorted()
            for (n in sessHdr) log("SESSKEY $n")
            for (n in sessClient) log("SESSKEY $n")
            fun resolve(vararg subs: String): String? =
                reqNames.filter { n -> subs.all { n.contains(it, ignoreCase = true) } }.minByOrNull { it.length }
            fun resolveAll(vararg subs: String): List<String> =
                reqNames.filter { n -> subs.all { n.contains(it, ignoreCase = true) } }.sorted()
            val hdrModeKeys = resolveAll("hdrMode")
            val expcntKey = resolve("stagger", "expcnt")
            val evListKey = resolve("evList")
            val hdrEnableKeys = resolveAll("hdr", "enabled").filter { it.endsWith("enabled") }
            val sessHdrModeKey = sessHdr.firstOrNull { it.contains("hdrMode", ignoreCase = true) }
            log("RESOLVE hdrMode=$hdrModeKeys expcnt=$expcntKey evList=$evListKey hdrEnable=$hdrEnableKeys sessHdrMode=$sessHdrModeKey")
            val watch = listOf(
                "com.mediatek.hdrfeature.hdrDetectionResult",
                "com.mediatek.hdrfeature.vhdrResult",
                "com.mediatek.hdrfeature.aeSmoothResult",
                "com.mediatek.3afeature.aeHdrRatio",
                "xiaomi.capture.stagger.expcnt",
                "xiaomi.capture.stagger.hdr.evList",
                "xiaomi.capture.stagger.hdr.expList",
                "xiaomi.capture.stagger.hdr.isoList",
                "xiaomi.hdr.hdrDetected",
                "xiaomi.hdr.hdrFrameReq",
                "xiaomi.hdr.custom.safe.evindex"
            )
            fun fullExtras(mode: Int, count: Int): (CaptureRequest.Builder) -> Unit = { b ->
                for (k in hdrModeKeys) if (!setInt(b, k, mode)) setIntArray(b, k, intArrayOf(mode))
                for (k in hdrEnableKeys) if (!setInt(b, k, 1)) setIntArray(b, k, intArrayOf(1))
                expcntKey?.let { setInt(b, it, count) }
                evListKey?.let { setIntArray(b, it, intArrayOf(-20, 0, 20).take(count).toIntArray()) }
            }
            attempt("ae baseline") {
                Route(open("0"), watch).use { route ->
                    route.streamAe("ae-baseline", 2000L)
                    route.still("ae-baseline")
                }
            }
            if (sessHdrModeKey != null) for (m in listOf(1, 3)) attempt("sessHdrMode=$m") {
                Route(open("0"), watch, mapOf(sessHdrModeKey to m)).use { route ->
                    route.streamAe("ae-sess$m", 2000L)
                    route.still("ae-sess$m")
                }
            } else log("SKIP sessHdrMode: no session key")
            attempt("full combo + clientName") {
                val sess = if (sessHdrModeKey != null) mapOf(sessHdrModeKey to 3) else emptyMap()
                Route(open("0"), watch, sess, clientName = "com.matthew.rawlens").use { route ->
                    route.streamAe("ae-combo", 2500L) { fullExtras(3, 3)(it) }
                    route.still("ae-combo") { fullExtras(3, 3)(it) }
                }
            }
            log("HINT verdict = did any FRAME/STILL line grow expList/isoList past 1 entry, or flip hdrDetectionResult/hdrDetected/hdrFrameReq?")
        } finally {
            devices.toList().forEach { it.close() }
            File(outputDir, "report.txt").writeText(report.toString())
            thread.quitSafely()
        }
    }
}
