// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.graphics.ImageFormat
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

/** Explicitly headless: no Activity, TextureView, screenshot, or injected input. */
class FocusPeakingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun enabled() = assumeTrue("Pass -e rawlensPeaking true",
        InstrumentationRegistry.getArguments().getString("rawlensPeaking") == "true")

    @Test fun detailFixturesOnDevice() {
        enabled()
        val n = FocusPeakingDetail.SIDE
        fun patch(f: (Int, Int) -> Float) = FloatArray(n * n) { f(it % n, it / n) }
        fun detects(p: FloatArray, c: BooleanArray = BooleanArray(n * n)) = FocusPeakingDetail.detect(p, c) >= 0
        assertFalse(detects(patch { _, _ -> 0.5f }))
        assertFalse(detects(patch { x, y -> 0.1f + 0.02f * x + 0.01f * y }))
        for (contrast in listOf(0.02f, 0.1f, 0.6f)) {
            assertTrue(detects(patch { x, _ -> 0.2f + if (x >= 8) contrast else 0f }))
            assertTrue(detects(patch { x, y -> 0.2f + if (x + y >= 16) contrast else 0f }))
            // Integrated Gaussian edge: a broad edge, not a fabricated energy-pair assertion.
            val blurred = patch { x, _ ->
                var total = 0.0; var weight = 0.0
                for (dx in -8..8) {
                    val w = exp(-dx.toDouble().pow(2) / 8.0)
                    total += (0.2 + if (x + dx >= 8) contrast else 0f) * w; weight += w
                }
                (total / weight).toFloat()
            }
            assertFalse(detects(blurred))
        }
        val clipped = patch { x, _ -> if (x >= 8) 1f else 0.2f }
        assertFalse(detects(clipped, BooleanArray(n * n) { clipped[it] >= FocusPeakingDetail.CLIP_LEVEL }))
        val rng = java.util.Random(18941)
        repeat(400) { i ->
            val sigma = doubleArrayOf(0.002, 0.01, 0.03, 0.07)[i / 100]
            assertFalse("noise patch $i", detects(patch { _, _ -> (0.4 + sigma * rng.nextGaussian()).toFloat() }))
        }
        val hot = patch { _, _ -> 0.2f }; hot[8 * n + 8] = 0.95f
        assertFalse(detects(hot))
        File(context.getExternalFilesDir(null), "peaking-headless-fixtures.txt")
            .writeText("PASS on ${android.os.Build.MODEL}: smooth/ramp, low-contrast axis/diagonal edges, broad blur, clipped edges, 400 noise patches, isolated hot sample\n")
    }

    @Test fun nativePackedAndCfaParity() {
        enabled()
        val width = 64; val height = 48; val cols = 4; val rows = 3
        var cases = 0
        for (cfa in 0..3) for (packedBits in listOf(0, 10, 12)) {
            val bits = if (packedBits == 0) 12 else packedBits
            val white = ((1 shl bits) - 1).toFloat()
            val black = floatArrayOf(64f, 65f, 66f, 67f)
            val inverse = FloatArray(4) { 1f / (white - black[it]) }
            val rng = java.util.Random(512L + cfa)
            val codes = IntArray(width * height) { i ->
                val x = i % width; val y = i / width; val phase = (y and 1) * 2 + (x and 1)
                val value = 0.15 + (if (x >= 32) 0.3 else 0.0) +
                    (if (y >= 24) 0.2 else 0.0) + rng.nextGaussian() * 0.002
                if (x > 40 && y < 16 && phase == 0) white.toInt()
                else (black[phase] + value * (white - black[phase])).roundToInt()
            }
            val rowBytes = when (packedBits) { 10 -> width / 4 * 5; 12 -> width / 2 * 3; else -> width * 2 }
            val stride = rowBytes + 12
            val parent = java.nio.ByteBuffer.allocateDirect(stride * height + 17).order(ByteOrder.nativeOrder())
            parent.position(17)
            val raw = parent.slice().order(ByteOrder.nativeOrder())
            for (y in 0 until height) when (packedBits) {
                10 -> for (x in 0 until width step 4) {
                    val base = y * stride + x / 4 * 5
                    var shared = 0
                    for (i in 0..3) {
                        val v = codes[y * width + x + i]
                        raw.put(base + i, (v shr 2).toByte()); shared = shared or ((v and 3) shl (i * 2))
                    }
                    raw.put(base + 4, shared.toByte())
                }
                12 -> for (x in 0 until width step 2) {
                    val base = y * stride + x / 2 * 3
                    val l = codes[y * width + x]; val r = codes[y * width + x + 1]
                    raw.put(base, (l shr 4).toByte()); raw.put(base + 1, (r shr 4).toByte())
                    raw.put(base + 2, ((l and 15) or ((r and 15) shl 4)).toByte())
                }
                else -> for (x in 0 until width) raw.putShort(y * stride + x * 2, codes[y * width + x].toShort())
            }
            val profile = DoubleArray(8) { if (it % 2 == 0) 0.00001 else 0.000001 }
            val native = NativeFocusPeaking.sample(raw, width, height, stride,
                if (packedBits == 0) 2 else 0, packedBits, cfa, black, white, profile, cols, rows)
                ?: error("native peaking unavailable cfa=$cfa format=$packedBits")
            val expected = IntArray(cols * rows) { -1 }
            val side = FocusPeakingDetail.SIDE
            val scratch = FocusPeakingDetail.Scratch()
            val patch = FloatArray(side * side); val clip = BooleanArray(patch.size)
            val greens = if (cfa == 1 || cfa == 2) intArrayOf(0, 3) else intArrayOf(1, 2)
            val quad = FloatArray(4)
            for (row in 0 until rows) for (col in 0 until cols) {
                val cx = ((col + 0.5f) * width / cols).toInt()
                val cy = ((row + 0.5f) * height / rows).toInt()
                val qx = ((cx - 16) / 2 * 2).coerceIn(0, (width - 32) / 2 * 2)
                val qy = ((cy - 16) / 2 * 2).coerceIn(0, (height - 32) / 2 * 2)
                var mean = 0f
                for (y in 0 until side) for (x in 0 until side) {
                    var saturated = false
                    for (phase in 0..3) {
                        val code = codes[(qy + y * 2 + (phase shr 1)) * width + qx + x * 2 + (phase and 1)]
                        val value = (code - black[phase]) * inverse[phase]
                        if (value >= FocusPeakingDetail.CLIP_LEVEL) saturated = true
                        quad[phase] = value.coerceIn(0f, 1f)
                    }
                    val i = y * side + x
                    patch[i] = (quad[greens[0]] + quad[greens[1]]) * 0.5f
                    clip[i] = saturated; mean += patch[i]
                }
                mean /= patch.size
                var variance = 0f
                for (phase in 0..3) variance = maxOf(variance, (profile[phase * 2] * mean + profile[phase * 2 + 1]).toFloat())
                val edge = FocusPeakingDetail.detect(patch, clip, variance, scratch)
                if (edge >= 0) expected[row * cols + col] = (qy + edge / side * 2) * width + qx + edge % side * 2
            }
            assertArrayEquals("native/reference cfa=$cfa packed=$packedBits", expected, native)
            assertNull("truncated buffer accepted", NativeFocusPeaking.sample(raw.duplicate().apply { limit(10) }.slice(),
                width, height, stride, if (packedBits == 0) 2 else 0, packedBits, cfa, black, white, profile, cols, rows))
            cases++
        }
        File(context.getExternalFilesDir(null), "peaking-headless-native-fixtures.txt")
            .writeText("PASS $cases native/Kotlin cases: all 4 CFA patterns, RAW16/10/12, row padding, nonzero plane origin, mixed-channel clipping, weak edges, noise, truncated buffers\n")
    }

    @Test fun rawFocusAndExposureSweep() {
        enabled()
        val report = StringBuilder()
        fun note(s: String) { report.appendLine(s); android.util.Log.i("RawLensPeakingTest", s) }
        val thread = HandlerThread("peaking-device-camera").apply { start() }
        val handler = Handler(thread.looper)
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first { id -> manager.getCameraCharacteristics(id).let { c ->
            c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
                c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true &&
                (c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f) > 0f
        } }
        val c = manager.getCameraCharacteristics(id)
        val size = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            .getOutputSizes(ImageFormat.RAW_SENSOR)!!.maxBy { it.width.toLong() * it.height }
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 3)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val imageWait = AtomicReference<CompletableFuture<Image>?>(null)
        val results = ConcurrentHashMap<Long, TotalCaptureResult>()
        val resultQueue = LinkedBlockingQueue<TotalCaptureResult>(16)
        try {
            val opened = CompletableFuture<CameraDevice>()
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { if (!opened.complete(d)) d.close() }
                override fun onDisconnected(d: CameraDevice) { d.close(); opened.completeExceptionally(IllegalStateException("disconnected")) }
                override fun onError(d: CameraDevice, error: Int) { d.close(); opened.completeExceptionally(IllegalStateException("camera error $error")) }
            }, handler)
            device = opened.get(15, TimeUnit.SECONDS)
            val configured = CompletableFuture<CameraCaptureSession>()
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { if (!configured.complete(s)) s.close() }
                override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); configured.completeExceptionally(IllegalStateException("RAW-only session rejected")) }
            }, handler)
            session = configured.get(15, TimeUnit.SECONDS)
            reader.setOnImageAvailableListener({ rd ->
                rd.acquireLatestImage()?.let { image ->
                    val future = imageWait.getAndSet(null)
                    if (future == null || !future.complete(image)) image.close()
                }
            }, handler)
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) {
                    r[CaptureResult.SENSOR_TIMESTAMP]?.let { results[it] = r }
                    if (results.size > 24) results.keys.minOrNull()?.let(results::remove)
                    if (!resultQueue.offer(r)) { resultQueue.poll(); resultQueue.offer(r) }
                }
                override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) {
                    note("CAPTURE_FAILED reason=${f.reason}")
                }
            }
            val maxFocus = c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]!!
            val isoRange = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!
            val expRange = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]!!
            note("DEVICE ${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT} camera=$id RAW=$size PREVIEW=none maxFocus=$maxFocus")
            data class Stage(val focus: Float, val iso: Int? = null, val ns: Long? = null)
            val stages = listOf(0f, minOf(2f, maxFocus), minOf(4f, maxFocus), maxFocus)
                .distinct().map { Stage(it) } + listOf(
                    Stage(minOf(2f, maxFocus), isoRange.clamp(800), expRange.clamp(100_000_000L)),
                    Stage(minOf(2f, maxFocus), isoRange.clamp(1600), expRange.clamp(250_000_000L)))
            for ((index, stage) in stages.withIndex()) {
                val q = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface); setTag(index)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, stage.focus)
                    set(CaptureRequest.CONTROL_AE_MODE, if (stage.iso == null) CaptureRequest.CONTROL_AE_MODE_ON else CaptureRequest.CONTROL_AE_MODE_OFF)
                    stage.iso?.let { set(CaptureRequest.SENSOR_SENSITIVITY, it) }
                    stage.ns?.let { set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
                }.build()
                session.setRepeatingRequest(q, callback, handler)
                var settled = 0
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (settled < 8 && SystemClock.elapsedRealtime() < deadline) {
                    val r = resultQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    if (r.request.tag == index && r[CaptureResult.LENS_STATE] != CaptureResult.LENS_STATE_MOVING) settled++
                }
                assertTrue("lens never settled stage $index", settled >= 8)
                repeat(2) { sample ->
                    val nextImage = CompletableFuture<Image>(); imageWait.set(nextImage)
                    nextImage.get(8, TimeUnit.SECONDS).use { image ->
                        val resultDeadline = SystemClock.elapsedRealtime() + 3_000
                        while (results[image.timestamp] == null && SystemClock.elapsedRealtime() < resultDeadline) SystemClock.sleep(5)
                        val result = results[image.timestamp] ?: error("missing timestamp-matched metadata")
                        val frame = RawFocusPeakingSampler.sample(image, c, result) ?: error("sampler rejected RAW")
                        assertEquals("native peaking fast path did not run", "native", frame.backend)
                        val reference = RawFocusPeakingSampler.sample(image, c, result, preferNative = false)
                            ?: error("Kotlin reference rejected RAW")
                        assertArrayEquals("native/reference detail masks differ", reference.mask, frame.mask)
                        assertArrayEquals("native/reference edge coordinates differ", reference.sensorPoints, frame.sensorPoints, 0f)
                        val old = LegacyScopeFocusSampler.sample(image, c) ?: error("legacy sampler rejected RAW")
                        assertEquals(image.timestamp, frame.timestampNanos)
                        val points = frame.sensorPoints ?: error("missing detected-edge positions")
                        assertEquals(frame.mask.size * 2, points.size)
                        val marked = frame.mask.indices.filter { frame.mask[it] }
                        for (i in marked) {
                            assertTrue(points[2 * i] in 0f..1f && points[2 * i + 1] in 0f..1f)
                        }
                        val clippedChecks = checkSupports(image, c, result, frame)
                        note("STAGE=$index SAMPLE=$sample requestedFocus=${stage.focus} actualFocus=${result[CaptureResult.LENS_FOCUS_DISTANCE]} ISO=${result[CaptureResult.SENSOR_SENSITIVITY]} ns=${result[CaptureResult.SENSOR_EXPOSURE_TIME]} new=${marked.size}/${frame.mask.size} old=${old.mask.count { it }} supportChecks=$clippedChecks newMs=${frame.samplingNanos / 1e6} referenceMs=${reference.samplingNanos / 1e6} backend=${frame.backend}")
                        if (index == stages.lastIndex && sample == 1) {
                            val timestamp = image.timestamp
                            val delivered = CompletableFuture<Pair<FocusPeakingFrame, String>>()
                            val visibility = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
                            val host = object : LiveScopes.Host {
                                override fun jpegOutputSettings() = JpegOutputSettings()
                                override fun vfStats(): RawVfStats? = null
                                override fun manualFocusSet() = true
                                override fun touchFocusActive() = false
                                override fun onRawHistogram(histogram: RgbHistogram) = Unit
                                override fun onRawWaveform(waveform: RgbWaveform) = Unit
                                override fun onFocusPeaking(frame: FocusPeakingFrame) {
                                    delivered.complete(frame to Thread.currentThread().name)
                                }
                                override fun onFocusPeakingActive(active: Boolean) { visibility += active }
                            }
                            val scopes = LiveScopes(host, SamplerGuard(), false, false,
                                ScopeMode.HISTOGRAM, true)
                            RawImageOwnership.adopt(image, reader)
                            try {
                                scopes.publishPeakingActiveIfChanged()
                                scopes.publishFocusPeakingIfDue(image, c, result)
                                // The camera can release immediately: the worker must keep its own lease.
                                RawImageOwnership.release(image)
                                val (published, workerThread) = delivered.get(5, TimeUnit.SECONDS)
                                assertEquals(timestamp, published.timestampNanos)
                                assertEquals("native", published.backend)
                                assertEquals("photo-peaking", workerThread)
                                scopes.stopPeaking()
                                assertEquals(false, visibility.last())
                                note("WORKER_PASS thread=$workerThread timestamp=$timestamp stopHidden=${!visibility.last()}")
                            } finally {
                                scopes.close()
                                RawImageOwnership.release(image)
                            }
                            assertEquals("worker leaked a borrowed RAW frame", 0, RawImageOwnership.count(reader))
                        }
                    }
                }
            }
        } finally {
            imageWait.getAndSet(null)?.cancel(false)
            session?.close(); device?.close(); reader.close()
            thread.quitSafely(); thread.join(3000)
            File(context.getExternalFilesDir(null), "peaking-headless-raw.txt").writeText(report.toString())
        }
    }

    /** Independent check of every marked point's entire two-scale Bayer support. */
    private fun checkSupports(image: Image, c: CameraCharacteristics, result: CaptureResult,
                              frame: FocusPeakingFrame): Int {
        val plane = image.planes[0]
        assertEquals(2, plane.pixelStride)
        val data = plane.buffer.duplicate().slice().order(ByteOrder.nativeOrder())
        val static = c[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]
        val dynamic = result[CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL]
        val white = (result[CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL]
            ?: c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL]!!).toFloat()
        val black = FloatArray(4) { dynamic?.get(it) ?: static?.getOffsetForIndex(it and 1, it shr 1)?.toFloat() ?: 0f }
        val points = frame.sensorPoints!!
        var checks = 0
        for (i in frame.mask.indices) if (frame.mask[i]) {
            val x = (points[2 * i] * image.width - 0.5f).roundToInt()
            val y = (points[2 * i + 1] * image.height - 0.5f).roundToInt()
            for (dy in -3..3) for (dx in -3..3) for (phase in 0..3) {
                val sx = x + dx * 2 + (phase and 1); val sy = y + dy * 2 + (phase shr 1)
                val code = data.getShort(sy * plane.rowStride + sx * 2).toInt() and 0xffff
                val normalized = (code - black[phase]) / (white - black[phase])
                assertTrue("marked clipped support: cell=$i xy=$sx,$sy phase=$phase code=$code white=$white", normalized < FocusPeakingDetail.CLIP_LEVEL)
                checks++
            }
        }
        return checks
    }
}
