// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.RectF
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.log2
import kotlin.math.roundToInt

class RawExposureAssistDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun <T> CompletableFuture<T>.await(): T = get(20, TimeUnit.SECONDS)
    private val output get() = File(context.getExternalFilesDir(null), "raw-exposure-assist-audit").apply { mkdirs() }

    @Test fun rotationMirrorAndActualOverlayPixelsMatchTheRawRamp() {
        instrumentation.runOnMainSync {
            val settings = RawExposureAssistSettings(RawExposureOverlayMode.FALSE_COLOR, true)
            val view = RawExposureOverlayView(context)
            view.settings = settings
            view.layout(0, 0, 720, 1080)
            view.contentTransform = RectF(0f, 0f, 720f, 1080f)
            for (mirror in listOf(false, true)) {
                val frame = RawExposureAssistSampler.sampleCodes(256, 192, 0, DoubleArray(4), 1024,
                    90, mirror, 1, settings) { x, _ -> if (x < 128) 256 else 1024 }!!
                view.update(frame.copy(sampledAtElapsedMs = SystemClock.elapsedRealtime()))
                val bitmap = Bitmap.createBitmap(720, 1080, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                for ((u, level) in listOf(0.2f to 0.8, 0.8f to 1.0)) {
                    val p = FocusPeakingGeometry.viewPoint(u, 0.2f, 90, mirror)
                    assertEquals(TurboColormap.argb(level), bitmap.getPixel((p.first * 720).toInt(), (p.second * 1080).toInt()))
                }
                File(output, "false-colour-${if (mirror) "front" else "back"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            view.clear()
            val empty = Bitmap.createBitmap(720, 1080, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(empty))
            assertEquals(0, empty.getPixel(360, 300))
            assertEquals("RAW assist waiting", view.contentDescription.toString())
            empty.recycle()
        }
    }

    @Test fun zebraStripesAreDiagonalScreenSpaceAndLeaveUnaffectedAreasClear() {
        instrumentation.runOnMainSync {
            val settings = RawExposureAssistSettings(RawExposureOverlayMode.ZEBRAS, false)
            val view = RawExposureOverlayView(context).apply { this.settings = settings; layout(0, 0, 720, 1080) }
            val frame = RawExposureAssistSampler.sampleCodes(256, 192, 0, DoubleArray(4), 1024,
                0, false, 1, settings) { x, _ -> if (x < 128) 1024 else 512 }!!
            view.update(frame.copy(sampledAtElapsedMs = SystemClock.elapsedRealtime()))
            val bitmap = Bitmap.createBitmap(720, 1080, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            // Translucent white lines over clear gaps: the image must stay visible between stripes.
            var white = 0; var clear = 0; var transitions = 0; var previous = false
            for (x in 20 until 300) {
                val pixel = bitmap.getPixel(x, 200)
                val alpha = android.graphics.Color.alpha(pixel)
                val light = alpha > 200 && android.graphics.Color.red(pixel) > 220
                if (light) white++
                if (alpha < 20) clear++
                if (x > 20 && light != previous) transitions++
                previous = light
                // Moving perpendicular to the gradient must remain on the same diagonal stripe.
                assertTrue("Stripe must follow the diagonal (allow 8-bit dithering)",
                    kotlin.math.abs(alpha - android.graphics.Color.alpha(bitmap.getPixel(x + 1, 199))) <= 2)
            }
            assertTrue("Missing white stripes", white > 30)
            assertTrue("Missing clear gaps between stripes", clear > 80)
            assertTrue("Stripes are too coarse", transitions > 8)
            assertEquals("Unclipped area must remain clear", 0, bitmap.getPixel(600, 200))
            File(output, "zebra-screen-stripes.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun realRawSpotOracleExposureSweepAndBorrowRelease() {
        assumeTrue("Pass -e rawlensF12Audit true", InstrumentationRegistry.getArguments().getString("rawlensF12Audit") == "true")
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        val thread = HandlerThread("raw-assist-audit").apply { start() }
        val handler = Handler(thread.looper)
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first { id ->
            val c = manager.getCameraCharacteristics(id)
            c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
                c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        }
        val c = manager.getCameraCharacteristics(id)
        val size = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.RAW_SENSOR)!!.minBy { it.width.toLong() * it.height }
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 3)
        val report = StringBuilder("DEVICE ${android.os.Build.MODEL} Android=${android.os.Build.VERSION.RELEASE} camera=$id RAW=$size\n")
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val borrowed = CompletableFuture<RawExposureAssistFrame>()
        val assist = RawExposureAssist { if (it != null) borrowed.complete(it) }
        assist.configure(RawExposureAssistSettings(RawExposureOverlayMode.ZEBRAS, true))
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
            val isoRange = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!
            val shutter = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]!!
            var frames = 0
            for (iso in listOf(isoRange.lower, isoRange.clamp(3200)).distinct()) for (ns in listOf(1_000_000L, 16_000_000L, 50_000_000L).map { shutter.clamp(it) }.distinct()) {
                val imageFuture = CompletableFuture<Image>(); val resultFuture = CompletableFuture<TotalCaptureResult>()
                reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { if (!imageFuture.complete(it)) it.close() } }, handler)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface); set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso); set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
                }.build()
                session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) { resultFuture.complete(r) }
                    override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) { resultFuture.completeExceptionally(IllegalStateException("capture failed ${f.reason}")) }
                }, handler)
                val result = resultFuture.await(); val image = imageFuture.await()
                RawImageOwnership.adopt(image, reader)
                try {
                    val plane = image.planes[0]; val bytes = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
                    fun hash() = MessageDigest.getInstance("SHA-256").run { update(plane.buffer.duplicate()); digest() }
                    val hashBefore = hash()
                    val settings = RawExposureAssistSettings(RawExposureOverlayMode.ZEBRAS, true)
                    val times = mutableListOf<Double>()
                    var frame: RawExposureAssistFrame? = null
                    repeat(10) { iteration ->
                        val started = System.nanoTime()
                        frame = RawExposureAssistSampler.sample(image, c, settings, result = result)
                        if (iteration > 1) times += (System.nanoTime() - started) / 1e6
                    }
                    val f = frame!!; val spot = f.spot!!
                    val metadata = RawFrameMetadataFactory.capture(id, image, c, result, 1)
                    val normal = metadata.normalizationOrNull()!!
                    val l = (spot.left * image.width).roundToInt(); val t = (spot.top * image.height).roundToInt()
                    val r = (spot.right * image.width).roundToInt(); val b = (spot.bottom * image.height).roundToInt()
                    val origin = bytes.position()
                    var sum = 0.0; var count = 0
                    for (y in t until b) for (x in l until r) {
                        val code = bytes.getShort(origin + y * plane.rowStride + x * plane.pixelStride).toInt() and 0xffff
                        val black = normal.blackAt(x, y)
                        sum += ((code - black) / (normal.whiteLevel - black)).coerceIn(0f, 1f).toDouble(); count++
                    }
                    val expected = log2((sum / count).coerceIn(1.0 / (normal.whiteLevel - normal.blackAt(0, 0)), 1.0))
                    assertEquals("spot vs independent full-ROI oracle", expected, spot.ev, 0.001)
                    val averageTimes = mutableListOf<Double>()
                    var average: RawAverageReading? = null
                    repeat(8) { iteration ->
                        val start = System.nanoTime()
                        average = RawExposureAssistSampler.sample(image, c,
                            RawExposureAssistSettings(meterMode = RawExposureMeterMode.AVERAGE), result = result)!!.average
                        if (iteration > 1) averageTimes += (System.nanoTime() - start) / 1e6
                    }
                    // Independent dense oracle: every Bayer phase, not the production sampling grid.
                    val blacks = DoubleArray(4) { normal.blackAt(it % 2, it / 2).toDouble() }
                    var fullSum = 0.0; var fullClipped = 0; var fullCells = 0
                    for (yy in 0 until image.height - 1 step 2) for (xx in 0 until image.width - 1 step 2) {
                        var clippedCell = false
                        for (dy in 0..1) for (dx in 0..1) {
                            val raw = bytes.getShort(origin + (yy + dy) * plane.rowStride + (xx + dx) * plane.pixelStride).toInt() and 0xffff
                            val black = blacks[dy * 2 + dx]
                            val value = ((raw - black) / (normal.whiteLevel - black)).coerceIn(0.0, 1.0)
                            fullSum += value
                            clippedCell = clippedCell || value >= 1.0
                        }
                        fullCells++
                        if (clippedCell) fullClipped++
                    }
                    val denseEv = log2((fullSum / (4.0 * fullCells)).coerceIn(1.0 / (normal.whiteLevel - blacks.min()), 1.0))
                    val denseClip = fullClipped * 100.0 / fullCells
                    val avg = average!!
                    assertEquals("sampled average vs every-pixel oracle", denseEv, avg.ev, 0.05)
                    assertEquals("sampled clipping area vs dense oracle", denseClip, avg.clippedPercent, 0.5)
                    averageTimes.sort()
                    report.appendLine("AVERAGE iso=$iso ns=$ns EV=${avg.ev} denseEV=$denseEv clipPct=${avg.clippedPercent} denseClipPct=$denseClip cells=${avg.sampledCells} medianMs=${averageTimes[averageTimes.size / 2]}")
                    assertArrayEquals("RAW assist changed source pixels", hashBefore, hash())
                    times.sort()
                    report.appendLine("FRAME iso=$iso ns=$ns EV=${spot.ev} oracle=$expected clips=${spot.clippedChannels} marked=${f.colors!!.count { it != 0 }} cells=${f.cols}x${f.rows} noiseFloor=${f.underUsesNoiseProfile} medianMs=${times[times.size / 2]}")
                    // Exercise the real borrowed-image worker, not just the pure sampler.
                    if (frames == 0) {
                        assist.offer(image, c, result = result)
                        val delivered = borrowed.await()
                        assertEquals(image.timestamp, delivered.timestampNanos)
                        assertTrue(delivered.sampledAtElapsedMs > 0)
                    }
                    frames++
                } finally { RawImageOwnership.release(image) }
            }
            assertTrue(frames >= 6)
            report.appendLine("PASS realFrames=$frames RAW byte hashes unchanged; RAW spot oracle agreement <=0.001 EV")
        } finally {
            assist.close()
            assertEquals("borrowed RAW frame leaked", 0, RawImageOwnership.count(reader))
            session?.close(); device?.close(); reader.close(); thread.quitSafely()
            File(output, "report.txt").writeText(report.toString())
        }
    }
}
