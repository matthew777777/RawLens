// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Color
import android.graphics.Bitmap
import java.io.File
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow

/** GPU ramp is deterministic; real scene sweep is opt-in and does not label the physical scene. */
class HighlightLightingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun <T> CompletableFuture<T>.await(): T = get(20, TimeUnit.SECONDS)

    @Test fun allStrengthsRenderGreyAndShadowRampOnGpu() {
        Gles31AmazeProcessor.EglComputeContext().use {
            val texture = IntArray(1)
            GLES31.glGenTextures(1, texture, 0)
            val processor = Gles31JpegOutputProcessor(context)
            val ramp = floatArrayOf(0.001f, 0.01f, 0.04f, 0.18f, 0.5f, 0.8f, 1f, 2f)
            try {
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture[0])
                GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, 8, 1)
                var previousGrey = -1
                for (level in ActiveDLighting.values()) {
                    val scale = 2.0.pow(-level.protectionEv).toFloat()
                    val pixels = FloatArray(32) { i -> if (i % 4 == 3) 1f else ramp[i / 4] * scale }
                    val buffer = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    buffer.put(pixels).position(0)
                    GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, texture[0])
                    GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, 8, 1, GLES31.GL_RGBA, GLES31.GL_FLOAT, buffer)
                    val settings = JpegOutputSettings(activeDLighting = level, highlightShoulder = 0f)
                    val result = processor.process(AmazeGpuOutput(texture[0], 8, 1), settings)
                    val codes = IntArray(8) { Color.red(result.bitmap.getPixel(it, 0)) }
                    result.bitmap.recycle()
                    for (x in 1..7) assertTrue("nonmonotonic $level $x", codes[x] >= codes[x - 1])
                    if (level == ActiveDLighting.OFF) previousGrey = codes[3]
                    assertTrue("grey $level ${codes[3]} vs $previousGrey", abs(codes[3] - previousGrey) <= 2)
                    Log.i("RawLensH12", "GPU $level codes=${codes.toList()}")
                }
                assertEquals(GLES31.GL_NO_ERROR, GLES31.glGetError())
            } finally { processor.close(); GLES31.glDeleteTextures(1, texture, 0) }
        }
    }

    @Test fun realRawTransferAndProtectionSweep() {
        assumeTrue("Pass -e rawlensH12Audit true", InstrumentationRegistry.getArguments()
            .getString("rawlensH12Audit") == "true")
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        val thread = HandlerThread("h12-audit").apply { start() }
        val handler = Handler(thread.looper)
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first { id ->
            val c = manager.getCameraCharacteristics(id)
            c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
                c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        }
        val c = manager.getCameraCharacteristics(id)
        val size = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.RAW_SENSOR)!!.minBy { it.width.toLong() * it.height }
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
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
            val iso = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!.lower
            val shutter = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]!!
            fun capture(requestedIso: Int, requestedNs: Long): Pair<Image, TotalCaptureResult> {
                val imageFuture = CompletableFuture<Image>()
                val resultFuture = CompletableFuture<TotalCaptureResult>()
                reader.setOnImageAvailableListener({ source -> source.acquireNextImage()?.let { image ->
                    if (!imageFuture.complete(image)) image.close()
                } }, handler)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, requestedIso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, requestedNs)
                }.build()
                session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) { resultFuture.complete(r) }
                    override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) { resultFuture.completeExceptionally(IllegalStateException("capture failed ${f.reason}")) }
                }, handler)
                val result = resultFuture.await()
                return imageFuture.await() to result
            }
            for (ns in listOf(1_000_000L, 10_000_000L, 50_000_000L).map { shutter.clamp(it) }.distinct()) {
                val (raw, result) = capture(iso, ns)
                raw.use { image ->
                    assertEquals(result[CaptureResult.SENSOR_TIMESTAMP], image.timestamp)
                    val ab = ScopeMeterAB.run(image, c, ProgramMetering.HIGHLIGHT_WEIGHTED, true, null, ScopeMeterNative.Scratch())!!
                    assertTrue("native/Kotlin transfer ${ab.diffs}", ab.passed)
                    val sample = RawEttrSampler.sampleProgram(image, c, ProgramMetering.HIGHLIGHT_WEIGHTED)!!
                    val t0 = System.nanoTime()
                    repeat(1000) { RawProgramMeter.protectedGain(sample, 2.0, ActiveDLighting.HIGH) }
                    val us = (System.nanoTime() - t0) / 1e6
                    var previous = Double.POSITIVE_INFINITY
                    val gains = ActiveDLighting.values().map { level ->
                        val gain = RawProgramMeter.protectedGain(sample, 2.0, level)
                        assertTrue(gain.isFinite() && gain > 0.0 && gain <= previous)
                        previous = gain
                        gain
                    }
                    Log.i("RawLensH12", "RAW ${android.os.Build.MODEL} camera=$id size=$size iso=$iso ns=$ns brightness=${RawProgramMeter.highlightBrightness(sample)} gains=$gains meterUs=$us")
                }
            }
            // Drive actual sensor requests from the same PROGRAM/ETTR solvers as production.
            // Re-start from the same baseline for each case; never infer a capture from a computed gain.
            val isoRange = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!
            val maxNs = minOf(shutter.upper, 66_666_666L)
            val limits = ExposureBalanceLimits(isoRange.lower, minOf(isoRange.upper, 6400),
                shutter.lower, maxNs, minOf(maxNs, 33_333_333L))
            val outputDir = File(context.getExternalFilesDir(null), "h12-audit").apply { mkdirs() }
            val report = StringBuilder()
            val developer = RawDevelopmentCoordinator(context)
            try {
                val cases = listOf("CENTER" to ProgramMetering.CENTER_WEIGHTED,
                    "SPOT" to ProgramMetering.SPOT) + ActiveDLighting.values().map {
                    "HIGHLIGHT_${it.name}" to ProgramMetering.HIGHLIGHT_WEIGHTED
                } + ActiveDLighting.values().map { "ETTR_${it.name}" to ProgramMetering.AVERAGE }
                for ((name, metering) in cases) {
                    val lighting = if (name.startsWith("HIGHLIGHT_")) ActiveDLighting.valueOf(name.removePrefix("HIGHLIGHT_"))
                        else if (name.startsWith("ETTR_")) ActiveDLighting.valueOf(name.removePrefix("ETTR_")) else ActiveDLighting.OFF
                    val isEttr = name.startsWith("ETTR_")
                    val program = ProgramAeLoop()
                    val ettr = EttrLoop(EttrSettings(enabled = true))
                    var requestedIso = isoRange.clamp(100)
                    var requestedNs = shutter.clamp(10_000_000L)
                    repeat(4) { iteration ->
                        val (image, result) = capture(requestedIso, requestedNs)
                        image.use {
                            val actualIso = result[CaptureResult.SENSOR_SENSITIVITY]!!
                            val actualNs = result[CaptureResult.SENSOR_EXPOSURE_TIME]!!
                            assertTrue(actualIso in isoRange.lower..isoRange.upper)
                            assertTrue(actualNs in shutter.lower..shutter.upper)
                            val sample = if (isEttr) RawEttrSampler.sample(image, c)!!
                                else RawEttrSampler.sampleProgram(image, c, metering)!!
                            if (iteration < 3) {
                                if (isEttr) {
                                    ettr.update(sample, actualIso, actualNs, limits.isoMin, limits.isoMax,
                                        limits.shutterMinNanos, limits.shutterMaxNanos, limits.shutterMaxNanos,
                                        lighting = lighting)
                                    requestedIso = ettr.iso!!; requestedNs = ettr.shutterNanos!!
                                } else {
                                    program.onSample(sample, ProgramAeProfile(metering = metering), limits,
                                        actualIso, actualNs, 0L, lighting = lighting)
                                    program.tick(1)
                                    requestedIso = program.iso!!; requestedNs = program.shutterNanos!!
                                }
                            } else {
                                val metadata = RawFrameMetadataFactory.capture(id, image, c, result, 1)
                                val look = JpegOutputSettings(activeDLighting = lighting)
                                val developed = developer.developJpeg(image.planes[0].buffer, metadata,
                                    RawDevelopmentSettings(adaptiveExposureStrength = adaptivePreviewStrength(
                                        if (isEttr) CaptureExposureMode.AUTO else CaptureExposureMode.PROGRAM, look),
                                        adaptiveExposureTuning = AdaptiveExposureTuning.fromOutputSettings(look)), look)
                                val file = File(outputDir, "$name.jpg")
                                file.outputStream().use { stream -> assertTrue(developed.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) }
                                val bitmap = developed.bitmap
                                val codes = mutableListOf<Int>()
                                for (y in 0 until bitmap.height step 32) for (x in 0 until bitmap.width step 32) {
                                    val pixel = bitmap.getPixel(x, y)
                                    codes += (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                                }
                                codes.sort()
                                val frameSample = RawEttrSampler.sampleProgram(image, c, ProgramMetering.AVERAGE)!!
                                val clipped = frameSample.saturated.indices.map { k ->
                                    frameSample.saturated[k].toDouble() / frameSample.totals[k].coerceAtLeast(1)
                                }
                                val line = "CAPTURE $name iso=$actualIso ns=$actualNs clips=$clipped code10=${codes[codes.size / 10]} code50=${codes[codes.size / 2]} code99=${codes[codes.size * 99 / 100]} jpeg=${bitmap.width}x${bitmap.height}"
                                Log.i("RawLensH12", line); report.appendLine(line)
                                bitmap.recycle(); developed.gainmap?.recycle()
                                assertTrue(file.length() > 1000)
                            }
                        }
                    }
                }
            } finally { developer.close(); File(outputDir, "report.txt").writeText(report.toString()) }
        } finally {
            session?.close(); device?.close(); reader.close(); thread.quitSafely()
        }
    }
}
