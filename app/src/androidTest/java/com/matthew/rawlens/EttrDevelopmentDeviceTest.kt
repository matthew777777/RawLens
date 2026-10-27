// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/** A6: real Camera2 AUTO / PROGRAM / ETTR pairs, plus a same-RAW replay of the removed cap. */
class EttrDevelopmentDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun <T> CompletableFuture<T>.await(): T = get(20, TimeUnit.SECONDS)
    private data class Reading(val pixels: IntArray, val clipped: Double)

    @Test fun pairedModesAndSameRawResidualReplay() {
        assumeTrue("Pass -e rawlensA6Audit true", InstrumentationRegistry.getArguments()
            .getString("rawlensA6Audit") == "true")
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        val thread = HandlerThread("ettr-develop-audit").apply { start() }
        val handler = Handler(thread.looper)
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first { id ->
            val c = manager.getCameraCharacteristics(id)
            c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
                c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        }
        val c = manager.getCameraCharacteristics(id)
        val size = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.RAW_SENSOR)!!.minBy { it.width.toLong() * it.height }
        val isoRange = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!
        val shutter = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]!!
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
        val output = File(context.getExternalFilesDir(null), "a6-audit").apply { mkdirs() }
        val report = StringBuilder("DEVICE ${android.os.Build.MODEL} Android=${android.os.Build.VERSION.RELEASE} camera=$id RAW=$size\n")
        val developer = RawDevelopmentCoordinator(context)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            val opened = CompletableFuture<CameraDevice>()
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { if (!opened.complete(d)) d.close() }
                override fun onDisconnected(d: CameraDevice) { d.close(); opened.completeExceptionally(IllegalStateException("disconnected")) }
                override fun onError(d: CameraDevice, error: Int) { d.close(); opened.completeExceptionally(IllegalStateException("camera error $error")) }
            }, handler)
            device = opened.await()
            val configured = CompletableFuture<CameraCaptureSession>()
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { if (!configured.complete(s)) s.close() }
                override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); configured.completeExceptionally(IllegalStateException("session rejected")) }
            }, handler)
            session = configured.await()
            fun capture(iso: Int?, ns: Long?): Pair<Image, TotalCaptureResult> {
                val imageFuture = CompletableFuture<Image>()
                val resultFuture = CompletableFuture<TotalCaptureResult>()
                reader.setOnImageAvailableListener({ source -> source.acquireNextImage()?.let { image ->
                    if (!imageFuture.complete(image)) image.close()
                } }, handler)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, if (iso == null) CaptureRequest.CONTROL_AE_MODE_ON else CaptureRequest.CONTROL_AE_MODE_OFF)
                    if (iso != null) set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    if (ns != null) set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
                }.build()
                session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) { resultFuture.complete(r) }
                    override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) { resultFuture.completeExceptionally(IllegalStateException("capture failed ${f.reason}")) }
                }, handler)
                val result = resultFuture.await()
                return imageFuture.await() to result
            }
            var baselineIso = isoRange.clamp(100)
            var baselineNs = shutter.clamp(10_000_000L)
            repeat(8) {
                val (image, result) = capture(null, null)
                image.close()
                baselineIso = result[CaptureResult.SENSOR_SENSITIVITY]!!
                baselineNs = result[CaptureResult.SENSOR_EXPOSURE_TIME]!!
                report.appendLine("AE_WARM $it iso=$baselineIso ns=$baselineNs state=${result[CaptureResult.CONTROL_AE_STATE]}")
            }
            val limits = ExposureBalanceLimits(isoRange.lower, minOf(isoRange.upper, 6400),
                shutter.lower, minOf(shutter.upper, 66_666_666L), minOf(shutter.upper, 33_333_333L))
            val readings = linkedMapOf<String, Reading>()
            for (mode in listOf("AUTO", "PROGRAM", "ETTR")) {
                val program = ProgramAeLoop()
                val ettr = EttrLoop(EttrSettings(enabled = true))
                var requestedIso = baselineIso
                var requestedNs = baselineNs
                val iterations = if (mode == "AUTO") 1 else 5
                repeat(iterations) { iteration ->
                    val (image, result) = capture(if (mode == "AUTO") null else requestedIso,
                        if (mode == "AUTO") null else requestedNs)
                    image.use {
                        assertEquals(result[CaptureResult.SENSOR_TIMESTAMP], image.timestamp)
                        val actualIso = result[CaptureResult.SENSOR_SENSITIVITY]!!
                        val actualNs = result[CaptureResult.SENSOR_EXPOSURE_TIME]!!
                        val sample = if (mode == "ETTR") RawEttrSampler.sample(image, c)!!
                            else RawEttrSampler.sampleProgram(image, c, ProgramMetering.CENTER_WEIGHTED)!!
                        if (iteration < iterations - 1) {
                            if (mode == "ETTR") {
                                ettr.update(sample, actualIso, actualNs, limits.isoMin, limits.isoMax,
                                    limits.shutterMinNanos, limits.shutterMaxNanos, limits.shutterMaxNanos)
                                requestedIso = ettr.iso!!; requestedNs = ettr.shutterNanos!!
                            } else {
                                program.onSample(sample, ProgramAeProfile(), limits, actualIso, actualNs, 0L)
                                program.tick(1)
                                requestedIso = program.iso!!; requestedNs = program.shutterNanos!!
                            }
                        } else {
                            val raw = image.planes[0].buffer
                            fun rawHash(): ByteArray = MessageDigest.getInstance("SHA-256").run { update(raw.duplicate()); digest() }
                            val beforeHash = rawHash()
                            val full = RawEttrSampler.sampleProgram(image, c, ProgramMetering.AVERAGE)!!
                            val clipFractions = full.saturated.indices.map { k ->
                                full.saturated[k].toDouble() / full.totals[k].coerceAtLeast(1)
                            }
                            report.appendLine("RAW_CLIPPING $mode fractions=$clipFractions")
                            val metadata = RawFrameMetadataFactory.capture(id, image, c, result, 1)
                            val geometry = metadata.bufferGeometry as RawBufferGeometry.Supported
                            val look = JpegOutputSettings()
                            val tuning = AdaptiveExposureTuning.fromOutputSettings(look)
                            val analysis = AdaptiveDevelopmentExposure.analyzeRaw(raw,
                                RawPlaneLayout(metadata.imageWidth, metadata.imageHeight, image.planes[0].rowStride,
                                    image.planes[0].pixelStride, geometry.sensorOriginX, geometry.sensorOriginY),
                                metadata.normalizationOrNull()!!, geometry.processingCrop,
                                RawPreDemosaicPipeline.lensShadingModel(metadata), tuning = tuning)
                            val strength = adaptivePreviewStrength(if (mode == "PROGRAM") CaptureExposureMode.PROGRAM else CaptureExposureMode.AUTO, look)
                            fun develop(name: String, correction: Double) {
                                val shared = SharedAdaptiveExposure().apply { resolve { analysis.copy(correctionEv = correction) } }
                                val started = System.nanoTime()
                                val developed = developer.developJpeg(raw, metadata,
                                    RawDevelopmentSettings(adaptiveExposureStrength = strength,
                                        sharedAdaptiveExposure = shared, adaptiveExposureTuning = tuning), look)
                                val bitmap = developed.bitmap
                                try {
                                    File(output, "$name.jpg").outputStream().use { stream -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) }
                                    val pixels = IntArray(96 * 72) { i -> bitmap.getPixel((i % 96) * bitmap.width / 96, (i / 96) * bitmap.height / 72) }
                                    val codes = pixels.map { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3 }.sorted()
                                    val white = pixels.count { minOf(Color.red(it), Color.green(it), Color.blue(it)) >= 250 }.toDouble() / pixels.size
                                    readings[name] = Reading(pixels, white)
                                    report.appendLine("DEVELOP $name iso=$actualIso ns=$actualNs strength=$strength correction=$correction appliedEV=${correction * strength} logAverage=${analysis.logAverage} highlight=${analysis.highlight} code10=${codes[codes.size / 10]} code50=${codes[codes.size / 2]} code99=${codes[codes.size * 99 / 100]} white=$white elapsedMs=${(System.nanoTime() - started) / 1e6}")
                                } finally { bitmap.recycle(); developed.gainmap?.recycle() }
                            }
                            if (mode == "ETTR") {
                                // Replay only the removed residual cap on this exact RAW; no new capture.
                                val former = minOf(analysis.correctionEv, log2(RawEttrMeter.targetLevel(0.5f) / analysis.highlight.coerceAtLeast(1e-6)))
                                develop("ETTR_FORMER_CAP", former)
                                report.appendLine("REMOVED_CAP lossEv=${analysis.correctionEv - former}")
                            }
                            develop(mode, analysis.correctionEv)
                            assertArrayEquals("Developer modified original RAW samples", beforeHash, rawHash())
                            report.appendLine("RAW_UNCHANGED $mode")
                        }
                    }
                }
            }
            val reference = readings.getValue("PROGRAM").pixels
            val mask = reference.indices.filter { i ->
                val p = reference[i]
                val channels = listOf(Color.red(p), Color.green(p), Color.blue(p))
                channels.average() in 35.0..150.0 && channels.max() - channels.min() < 35
            }
            assertTrue("Need neutral midtone area in the stationary scene", mask.size >= 32)
            fun luma(p: Int): Double {
                fun linear(code: Int): Double { val x = code / 255.0; return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
                return 0.2126 * linear(Color.red(p)) + 0.7152 * linear(Color.green(p)) + 0.0722 * linear(Color.blue(p))
            }
            val target = mask.map { luma(reference[it]) }.average()
            val errors = readings.mapValues { (_, reading) -> log2(mask.map { luma(reading.pixels[it]) }.average() / target) }
            report.appendLine("NEUTRAL_AREA pixels=${mask.size} relativeToPROGRAM_EV=$errors")
            assertTrue("ETTR brightness did not improve vs removed cap: $errors",
                abs(errors.getValue("ETTR")) < abs(errors.getValue("ETTR_FORMER_CAP")))
            assertTrue("ETTR neutral-area placement differs from PROGRAM by more than 0.3 EV: $errors",
                abs(errors.getValue("ETTR")) <= 0.3)
            assertTrue("ETTR neutral-area placement differs from AUTO by more than 0.3 EV: $errors",
                abs(errors.getValue("ETTR") - errors.getValue("AUTO")) <= 0.3)
            assertTrue("ETTR newly clipped JPEG whites", readings.getValue("ETTR").clipped <= readings.getValue("PROGRAM").clipped + 0.005)
        } finally {
            session?.close(); device?.close(); reader.close(); thread.quitSafely()
            developer.close(); File(output, "report.txt").writeText(report.toString())
        }
    }
}
