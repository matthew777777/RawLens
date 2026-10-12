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

/** Opt-in hardware experiment: MTK/Xiaomi stagger-HDR sensor readout. Unsupported = recorded, not asserted. */
class StaggerProbeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputDir = File(context.getExternalFilesDir(null), "stagger-probe")
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("stagger-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val report = StringBuilder()
    private val devices = mutableListOf<CameraDevice>()
    private fun log(s: String) { synchronized(report) { report.appendLine(s) }; Log.i("STAGGERPROBE", s) }
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

    /** Best-effort read of a vendor result key across the plausible scalar/vector types. */
    private fun probeResult(r: CaptureResult, name: String): String {
        try { r.get(CaptureResult.Key(name, Int::class.java))?.let { return "int=$it" } } catch (_: Exception) { }
        try { r.get(CaptureResult.Key(name, IntArray::class.java))?.let { return "int[]=${fmtArray(it)}" } } catch (_: Exception) { }
        try { r.get(CaptureResult.Key(name, Long::class.java))?.let { return "long=$it" } } catch (_: Exception) { }
        try { r.get(CaptureResult.Key(name, LongArray::class.java))?.let { return "long[]=${fmtArray(it)}" } } catch (_: Exception) { }
        try { r.get(CaptureResult.Key(name, Float::class.java))?.let { return "float=$it" } } catch (_: Exception) { }
        try { r.get(CaptureResult.Key(name, FloatArray::class.java))?.let { return "float[]=${it.take(12)}...len=${it.size}" } } catch (_: Exception) { }
        return "absent"
    }

    private fun probeChars(c: CameraCharacteristics, keys: List<String>) {
        for (name in keys) {
            var shown = "absent"
            try { c.get(CameraCharacteristics.Key(name, IntArray::class.java))?.let { shown = "int[]=${fmtArray(it)}" } } catch (_: Exception) { }
            if (shown == "absent") try { c.get(CameraCharacteristics.Key(name, Int::class.java))?.let { shown = "int=$it" } } catch (_: Exception) { }
            if (shown == "absent") try { c.get(CameraCharacteristics.Key(name, LongArray::class.java))?.let { shown = "long[]=${fmtArray(it)}" } } catch (_: Exception) { }
            log("VCHAR $name $shown")
        }
    }

    private inner class Route(val device: CameraDevice, extras: (CaptureRequest.Builder) -> Unit = {}) : AutoCloseable {
        val c = manager.getCameraCharacteristics(device.id)
        val reqNames = c.availableCaptureRequestKeys.map { it.name }.toSet()
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
        fun capture(label: String, iso: Int, exposure: Long, saveRaw: Boolean = true): TotalCaptureResult {
            val imageFuture = CompletableFuture<Triple<Int, Int, String>>()
            reader.setOnImageAvailableListener({ source ->
                source.acquireNextImage()?.use { image ->
                    try {
                        val plane = image.planes[0]
                        val b = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                        if (saveRaw) {
                            val bytes = ByteArray(b.remaining())
                            b.duplicate().get(bytes)
                            File(outputDir, "$label.raw16").writeBytes(bytes)
                        }
                        imageFuture.complete(Triple(image.width, image.height, layoutLine(image.width, image.height, b, plane.rowStride, plane.pixelStride)))
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
            val (w, h, layout) = imageFuture.waitFor()
            log("IMAGE $label ${w}x$h ts=${r[CaptureResult.SENSOR_TIMESTAMP]} echoIso=${r[CaptureResult.SENSOR_SENSITIVITY]} echoExp=${r[CaptureResult.SENSOR_EXPOSURE_TIME]} $layout")
            for (name in STAGGER_RESULT_KEYS) log("RESULT $label $name ${probeResult(r, name)}")
            return r
        }
        /** Center-crop stats: per-channel means + even/odd row ratio (interleaved stagger detector). */
        private fun layoutLine(w: Int, h: Int, b: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int): String {
            val cfa = c[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT] ?: 0
            // channel index per (x&1, y&1): order R=0 G=1 B=2
            val map = when (cfa) {
                1 -> intArrayOf(1, 0, 2, 1) // GRBG
                2 -> intArrayOf(1, 2, 0, 1) // GBRG
                3 -> intArrayOf(2, 1, 1, 0) // BGGR
                else -> intArrayOf(0, 1, 1, 2) // RGGB
            }
            val sum = DoubleArray(3); val cnt = LongArray(3)
            var evenSum = 0.0; var evenN = 0L; var oddSum = 0.0; var oddN = 0L
            var topSum = 0.0; var topN = 0L; var botSum = 0.0; var botN = 0L
            val x0 = w / 4; val x1 = w * 3 / 4; val y0 = h / 4; val y1 = h * 3 / 4
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val v = (b.getShort(y * rowStride + x * pixelStride).toInt() and 65535).toDouble()
                    val ch = map[((y and 1) shl 1) or (x and 1)]
                    sum[ch] += v; cnt[ch]++
                    if ((y and 1) == 0) { evenSum += v; evenN++ } else { oddSum += v; oddN++ }
                    if (y < h / 2) { topSum += v; topN++ } else { botSum += v; botN++ }
                    x += 1
                }
                y += 1
            }
            val rMean = if (cnt[0] > 0) sum[0] / cnt[0] else 0.0
            val gMean = if (cnt[1] > 0) sum[1] / cnt[1] else 0.0
            val bMean = if (cnt[2] > 0) sum[2] / cnt[2] else 0.0
            val rowRatio = if (oddN > 0 && oddSum > 0) (evenSum / evenN) / (oddSum / oddN) else 0.0
            val halfRatio = if (botN > 0 && botSum > 0) (topSum / topN) / (botSum / botN) else 0.0
            return "cfa=$cfa RGB=${"%.1f".format(rMean)}/${"%.1f".format(gMean)}/${"%.1f".format(bMean)} evenOddRatio=${"%.4f".format(rowRatio)} topBotRatio=${"%.4f".format(halfRatio)} stride=$rowStride pxStride=$pixelStride"
        }
        override fun close() { session.close(); reader.close() }
    }

    private fun setInt(builder: CaptureRequest.Builder, name: String, v: Int): Boolean {
        return try { builder.set(CaptureRequest.Key(name, Int::class.java), v); log("SET $name int=$v ok"); true } catch (e: Exception) { log("SET $name int=$v FAIL $e"); false }
    }
    private fun setIntArray(builder: CaptureRequest.Builder, name: String, v: IntArray): Boolean {
        return try { builder.set(CaptureRequest.Key(name, IntArray::class.java), v); log("SET $name int[]=${v.contentToString()} ok"); true } catch (e: Exception) { log("SET $name int[] FAIL $e"); false }
    }

    @Test fun probeStagger() {
        outputDir.mkdirs()
        try {
            log("DEVICE ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}")
            val c0 = manager.getCameraCharacteristics("0")
            probeChars(c0, listOf(
                "com.mediatek.hdrfeature.availableHdrModesPhoto",
                "com.mediatek.hdrfeature.availableHdrModesVideo",
                "com.mediatek.hdrfeature.availableVhdrModes",
                "com.mediatek.hdrfeature.availableAisModes",
                "com.mediatek.hdrfeature.availableMfbModes",
                "com.mediatek.hdrfeature.available3DnrModes"
            ))
            val reqNames = c0.availableCaptureRequestKeys.map { it.name }.toSet()
            val resNames = c0.availableCaptureResultKeys.map { it.name }.toSet()
            for (name in STAGGER_REQUEST_KEYS + STAGGER_RESULT_KEYS) {
                log("KEY $name req=${name in reqNames} res=${name in resNames}")
            }
            // Safety net: exact vendor prefixes vary by HAL generation, so also
            // enumerate every HDR/stagger key and resolve settable names at runtime.
            for (n in (reqNames + resNames).filter {
                it.contains("hdr", ignoreCase = true) || it.contains("stagger", ignoreCase = true) ||
                    it.contains("vhdr", ignoreCase = true) || it.contains("mfb", ignoreCase = true)
            }.sorted()) {
                log("SCAN $n req=${n in reqNames} res=${n in resNames}")
            }
            fun resolve(vararg subs: String): String? =
                reqNames.filter { n -> subs.all { n.contains(it, ignoreCase = true) } }.minByOrNull { it.length }
            fun resolveAll(vararg subs: String): List<String> =
                reqNames.filter { n -> subs.all { n.contains(it, ignoreCase = true) } }.sorted()
            // Set EVERY hdrMode key (MTK HAL switch + Xiaomi algo switch are distinct).
            val hdrModeKeys = resolveAll("hdrMode").ifEmpty { listOf("com.mediatek.hdrfeature.hdrMode") }
            val expcntKey = resolve("stagger", "expcnt") ?: "xiaomi.capture.stagger.expcnt"
            val evListKey = resolve("evList") ?: "xiaomi.capture.stagger.hdr.evList"
            val hdrEnableKeys = resolveAll("hdr", "enabled").filter { it.endsWith("enabled") }
            log("RESOLVE hdrMode=$hdrModeKeys expcnt=$expcntKey evList=$evListKey hdrEnable=$hdrEnableKeys")
            fun setAll(builder: CaptureRequest.Builder, keys: List<String>, v: Int) {
                for (k in keys) if (!setInt(builder, k, v)) setIntArray(builder, k, intArrayOf(v))
            }
            attempt("baseline") {
                Route(open("0")).use { route -> route.capture("stagger-baseline", 400, 20_000_000L) }
            }
            for (mode in listOf(1, 3)) attempt("hdrMode=$mode") {
                Route(open("0")) {
                    setAll(it, hdrModeKeys, mode)
                }.use { route -> route.capture("stagger-hdrmode$mode", 400, 20_000_000L) }
            }
            for (count in listOf(2, 3)) attempt("expcnt=$count") {
                Route(open("0")) {
                    setInt(it, expcntKey, count)
                    setIntArray(it, evListKey, intArrayOf(-20, 0, 20).take(count).toIntArray())
                }.use { route -> route.capture("stagger-expcnt$count", 400, 20_000_000L) }
            }
            if (hdrEnableKeys.isNotEmpty()) attempt("hdrEnabled=1") {
                Route(open("0")) {
                    setAll(it, hdrEnableKeys, 1)
                }.use { route -> route.capture("stagger-hdrEnabled", 400, 20_000_000L) }
            }
            attempt("hdrMode3+expcnt3") {
                Route(open("0")) {
                    setAll(it, hdrModeKeys, 3)
                    setAll(it, hdrEnableKeys, 1)
                    setInt(it, expcntKey, 3)
                    setIntArray(it, evListKey, intArrayOf(-20, 0, 20))
                }.use { route -> route.capture("stagger-combo", 400, 20_000_000L) }
            }
            log("HINT compare evenOddRatio/topBotRatio + expList/isoList lengths across variants; ratio far from 1.0 means interleaved multi-exposure layout")
        } finally {
            devices.toList().forEach { it.close() }
            File(outputDir, "report.txt").writeText(report.toString())
            thread.quitSafely()
        }
    }

    companion object {
        private val STAGGER_REQUEST_KEYS = listOf(
            "com.mediatek.hdrfeature.hdrMode",
            "com.mediatek.control.capture.hdr.type",
            "com.mediatek.hdrfeature.SessionParamhdrMode",
            "xiaomi.capture.stagger.expcnt",
            "xiaomi.capture.stagger.skip",
            "xiaomi.capture.stagger.hdr.evList"
        )
        private val STAGGER_RESULT_KEYS = listOf(
            "com.mediatek.hdrfeature.hdrMode",
            "com.mediatek.hdrfeature.hdrDetectionResult",
            "com.mediatek.hdrfeature.vhdrResult",
            "com.mediatek.hdrfeature.aeSmoothResult",
            "xiaomi.capture.stagger.expcnt",
            "xiaomi.capture.stagger.hdr.evList",
            "xiaomi.capture.stagger.hdr.expList",
            "xiaomi.capture.stagger.hdr.isoList",
            "xiaomi.capture.stagger.hdr.ispGainList",
            "xiaomi.capture.stagger.hdr.sensorGainList",
            "xiaomi.hdr.enabled",
            "xiaomi.hdr.detected",
            "xiaomi.hdr.hdrDetected",
            "xiaomi.hdr.hdrFrameReq",
            "xiaomi.hdr.custom.safe.evindex",
            "com.mediatek.mfnrfeature.mfbresult",
            "com.mediatek.3afeature.aeHdrRatio"
        )
    }
}
