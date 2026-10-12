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

/**
 * Opt-in hardware experiment: can the RAW stream deliver real >10-bit codes, or do the
 * bit-depth vendor keys only relabel black/white levels? Verdict is pixel-measured, never
 * metadata-trusted: only codes above 1023 count as deeper data. Recorded, not asserted.
 */
class BitDepthProbeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputDir = File(context.getExternalFilesDir(null), "bitdepth-probe")
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("bitdepth-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val report = StringBuilder()
    private val devices = mutableListOf<CameraDevice>()
    private fun log(s: String) { synchronized(report) { report.appendLine(s) }; Log.i("BITDEPTHPROBE", s) }
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

    private data class DepthStats(val mean: Double, val over1023: Long, val at1023: Long, val max: Int, val n: Long, val low2nz: Long, val low4nz: Long)

    private inner class Route(val device: CameraDevice) : AutoCloseable {
        val c = manager.getCameraCharacteristics(device.id)
        val size = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.RAW_SENSOR)!!
            .maxBy { it.width.toLong() * it.height }
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 4)
        val session: CameraCaptureSession
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
                log("CONFIGURED ${device.id} RAW=$size staticWhite=${c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL]}")
            } catch (e: Exception) { f.cancel(false); reader.close(); throw e }
        }
        /** Full-frame code census (step 8): the >1023 count is the depth verdict. */
        fun capture(label: String, iso: Int, exposure: Long, extras: (CaptureRequest.Builder) -> Unit = {}, saveRaw: Boolean = false): DepthStats? {
            val imgF = CompletableFuture<Pair<DepthStats, ByteArray>>()
            reader.setOnImageAvailableListener({ source ->
                source.acquireNextImage()?.use { image ->
                    try {
                        val plane = image.planes[0]
                        val b = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val bytes = ByteArray(b.remaining())
                        b.duplicate().get(bytes)
                        var sum = 0.0; var n = 0L; var over = 0L; var at = 0L; var max = 0
                        var low2 = 0L; var low4 = 0L
                        var y = 0
                        while (y < image.height) {
                            var x = 0
                            while (x < image.width) {
                                val v = b.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 65535
                                sum += v; n++
                                if (v > 1023) over++
                                if (v == 1023) at++
                                if (v > max) max = v
                                if ((v and 3) != 0) low2++
                                if ((v and 15) != 0) low4++
                                x += 8
                            }
                            y += 8
                        }
                        imgF.complete(DepthStats(sum / n, over, at, max, n, low2, low4) to bytes)
                    } catch (e: Exception) { imgF.completeExceptionally(e) }
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
            try { extras(request) } catch (e: Exception) { log("EXTRAS_FAIL $label: $e") }
            request.addTarget(reader.surface)
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) { result.complete(r) }
                override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) { result.completeExceptionally(Exception("capture failure ${f.reason}")) }
            }, handler)
            return try {
                val r = result.waitFor()
                val (st, bytes) = imgF.waitFor()
                if (saveRaw) File(outputDir, "$label.raw16").writeBytes(bytes)
                val dynWhite = r[CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL]
                val dynBlack = r[CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL]?.contentToString()
                log("CENSUS $label ${size.width}x${size.height} iso=$actualIso exp=$actualExposure mean=${"%.1f".format(st.mean)} max=${st.max} over1023=${st.over1023}/${st.n} at1023=${st.at1023} low2nz=${st.low2nz} low4nz=${st.low4nz} dynWhite=$dynWhite dynBlack=$dynBlack")
                st
            } catch (e: Exception) { log("CAPTURE_FAIL $label: $e"); null }
        }
        override fun close() { session.close(); reader.close() }
    }

    private fun setAny(builder: CaptureRequest.Builder, name: String, v: Int): String {
        try { builder.set(CaptureRequest.Key(name, Int::class.java), v); return "int" } catch (_: Exception) { }
        try { builder.set(CaptureRequest.Key(name, IntArray::class.java), intArrayOf(v)); return "int[]" } catch (e: Exception) { return "FAIL $e" }
    }

    @Test fun probeBitDepth() {
        outputDir.mkdirs()
        try {
            log("DEVICE ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT}")
            val c0 = manager.getCameraCharacteristics("0")
            val reqNames = c0.availableCaptureRequestKeys.map { it.name }
            val resNames = c0.availableCaptureResultKeys.map { it.name }
            for (n in (reqNames + resNames).filter {
                it.contains("bpp", ignoreCase = true) || it.contains("convertfmt", ignoreCase = true) ||
                    it.contains("packedraw", ignoreCase = true) || it.contains("processraw", ignoreCase = true) ||
                    it.contains("whitelevel", ignoreCase = true) || it.contains("blacklevel", ignoreCase = true) ||
                    (it.contains("raw", ignoreCase = true) && it.contains("bit", ignoreCase = true))
            }.sorted()) {
                log("SCAN $n req=${n in reqNames} res=${n in resNames}")
            }
            fun resolve(vararg subs: String): String? =
                reqNames.filter { n -> subs.all { n.contains(it, ignoreCase = true) } }.minByOrNull { it.length }
            val bppKey = resolve("raw.bpp")
            val packedKey = resolve("packedraw")
            val processKey = resolve("processraw")
            val convertKeys = reqNames.filter { it.contains("convertfmt", ignoreCase = true) }.sorted()
            val remosaicKey = resolve("remosaicenable")
            log("RESOLVE bpp=$bppKey packed=$packedKey process=$processKey convert=$convertKeys remosaic=$remosaicKey")
            attempt("meter + push") {
                Route(open("0")).use { route ->
                    // Meter frame, then push exposure so whites approach clipping: only a
                    // near-white frame can show codes past 1023 if deeper data exists.
                    val meter = route.capture("depth-meter", 50, 10_000_000L)
                        ?: throw Exception("meter capture failed")
                    val pushed = (10_000_000L * 850.0 / meter.mean.coerceAtLeast(1.0)).toLong()
                        .coerceIn(1_000_000L, 400_000_000L)
                    if (pushed >= 400_000_000L && meter.mean * 40 < 850) {
                        log("SCENE_TOO_DARK meterMean=${"%.1f".format(meter.mean)} pushed=$pushed (verdict weakened: whites may never reach 1023)")
                    }
                    log("PUSHED exp=$pushed from meterMean=${"%.1f".format(meter.mean)}")
                    val base = route.capture("depth-baseline", 50, pushed, saveRaw = true)
                    log("BASELINE_TAKEN ok=${base != null}")
                    var positive = 0
                    fun variant(tag: String, key: String?, values: List<Int>) {
                        if (key == null) { log("SKIP $tag: no request key"); return }
                        for (v in values) {
                            var used = ""
                            val st = route.capture("depth-$tag$v", 50, pushed, { used = setAny(it, key, v) })
                            log("SET $key=$v via $used")
                            if (st != null && st.over1023 > 0) {
                                positive++
                                log("POSITIVE $tag=$v shows ${st.over1023} codes over 1023 (max=${st.max})")
                            }
                        }
                    }
                    variant("bpp", bppKey, listOf(10, 12, 14, 16))
                    variant("packed", packedKey, listOf(0, 1))
                    variant("process", processKey, listOf(0, 1))
                    for (ck in convertKeys) variant("convert-${ck.substringAfterLast('.')}", ck, listOf(0, 1, 2))
                    variant("remosaic", remosaicKey, listOf(1))
                    log("VERDICT positives=$positive (positive = any pixel code above 1023 in a pushed frame)")
                }
            }
        } finally {
            devices.toList().forEach { it.close() }
            File(outputDir, "report.txt").writeText(report.toString())
            thread.quitSafely()
        }
    }
}
