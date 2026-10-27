// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Opt-in real RAW transfer/ISO/focus/exposure sweep: -e rawlensMlAudit true. */
class MlFocusEttrDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val report = StringBuilder()
    private fun log(s: String) { report.appendLine(s); Log.i("RawLensMlAudit", s) }
    private fun <T> CompletableFuture<T>.await(): T = get(20, TimeUnit.SECONDS)

    @Test fun realRawSweepAndArmCorrectionFixtures() {
        assumeTrue("Pass -e rawlensMlAudit true", InstrumentationRegistry.getArguments()
            .getString("rawlensMlAudit") == "true")
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.CAMERA").close()
        val thread = HandlerThread("ml-audit").apply { start() }
        val handler = Handler(thread.looper)
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first { id ->
            val c = manager.getCameraCharacteristics(id)
            c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
                c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        }
        val c = manager.getCameraCharacteristics(id)
        var device: CameraDevice? = null
        try {
            log("DEVICE ${android.os.Build.MODEL} SDK=${android.os.Build.VERSION.SDK_INT} camera=$id")
            assertTrue("native sampler library missing", ScopeMeterNative.available)
            correctionFixtures()
            val opened = CompletableFuture<CameraDevice>()
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { if (!opened.complete(d)) d.close() }
                override fun onDisconnected(d: CameraDevice) { d.close(); opened.completeExceptionally(IllegalStateException("disconnected")) }
                override fun onError(d: CameraDevice, error: Int) { d.close(); opened.completeExceptionally(IllegalStateException("camera error $error")) }
            }, handler)
            device = opened.await()
            val sizes = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
                .getOutputSizes(ImageFormat.RAW_SENSOR)!!.sortedByDescending { it.width.toLong() * it.height }
            val selected = listOf(sizes.first(), sizes.last()).distinct()
            val isoRange = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]!!
            val shutterRange = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]!!
            val focusMax = c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f
            val cal = ScopeMeterNative.readCalibration(c)!!
            val scratch = ScopeMeterNative.Scratch()
            var checked = 0
            var greenOnly = 0
            var greenOnlyQuads = 0L
            for (size in selected) {
                val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 3)
                var session: CameraCaptureSession? = null
                try {
                    val configured = CompletableFuture<CameraCaptureSession>()
                    device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(reader.surface)), { r -> handler.post(r) },
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { if (!configured.complete(s)) s.close() }
                            override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); configured.completeExceptionally(IllegalStateException("session rejected $size")) }
                        }))
                    session = configured.await()
                    log("SESSION $size iso=$isoRange shutter=$shutterRange focusMax=$focusMax white=${cal.white}")
                    for (iso in listOf(isoRange.lower, isoRange.clamp(3200)).distinct()) {
                        for (focus in listOf(0f, focusMax / 2, focusMax).distinct()) {
                            val liveMemory = MagicLanternEttr.Memory()
                            for (shutter in listOf(1_000_000L, 10_000_000L, 100_000_000L).map { shutterRange.clamp(it) }.distinct()) {
                                val imageFuture = CompletableFuture<Image>()
                                val resultFuture = CompletableFuture<TotalCaptureResult>()
                                reader.setOnImageAvailableListener({ source ->
                                    source.acquireNextImage()?.let { image -> if (!imageFuture.complete(image)) image.close() }
                                }, handler)
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(reader.surface)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                                }.build()
                                session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(s: CameraCaptureSession, q: CaptureRequest, r: TotalCaptureResult) { resultFuture.complete(r) }
                                    override fun onCaptureFailed(s: CameraCaptureSession, q: CaptureRequest, f: CaptureFailure) { resultFuture.completeExceptionally(IllegalStateException("capture failed ${f.reason}")) }
                                }, handler)
                                val result = resultFuture.await()
                                imageFuture.await().use { image ->
                                    assertEquals("RAW/result timestamp", result[CaptureResult.SENSOR_TIMESTAMP], image.timestamp)
                                    val ab = ScopeMeterAB.run(image, c, ProgramMetering.AVERAGE, true, null, scratch)
                                    assertNotNull("native transfer rejected real RAW", ab)
                                    assertTrue("native/Kotlin disagreement ${ab!!.diffs}", ab.passed)
                                    val focusFrame = RawFocusPeakingSampler.sample(image, c)!!
                                    val sample = RawEttrSampler.sample(image, c)!!
                                    val quads = sample.quads!!
                                    assertNotNull(quads.minChannelHistogram)
                                    assertEquals(quads.total, quads.minChannelHistogram!!.sum())
                                    assertEquals(quads.total, quads.evHistogram.sum())
                                    val baseline = baselineQuads(image, c, RawEttrSampler.TARGET_QUADS)!!
                                    assertArrayEquals("MAX histogram changed", baseline.evHistogram, quads.evHistogram)
                                    assertEquals("clipped area changed", baseline.overexposed, quads.overexposed)
                                    val correction = MagicLanternEttr.correction(quads, liveMemory, -0.5, Double.NaN)
                                    assertTrue("nonfinite correction", correction.correctionEv.isFinite())
                                    val partialGreen = greenOnlyCount(image, c)
                                    greenOnlyQuads += partialGreen
                                    val clipping = sample.saturated.indices.map { k -> sample.saturated[k].toDouble() / sample.totals[k] }
                                    if ((clipping[1] > 0.001 || clipping[2] > 0.001) && clipping[0] < 0.001 && clipping[3] < 0.001) greenOnly++
                                    val times = mutableListOf<Double>()
                                    val baseTimes = mutableListOf<Double>()
                                    // Alternate order to avoid giving either implementation every warm cache.
                                    repeat(8) { iteration ->
                                        fun timed(new: Boolean) {
                                            val t = System.nanoTime()
                                            if (new) RawEttrSampler.sampleQuads(image, c, RawEttrSampler.TARGET_QUADS)
                                            else baselineQuads(image, c, RawEttrSampler.TARGET_QUADS)
                                            if (iteration > 1) (if (new) times else baseTimes).add((System.nanoTime() - t) / 1e6)
                                        }
                                        timed(iteration % 2 == 0)
                                        timed(iteration % 2 != 0)
                                    }
                                    times.sort(); baseTimes.sort()
                                    log("FRAME $size iso=${result[CaptureResult.SENSOR_SENSITIVITY]} ns=${result[CaptureResult.SENSOR_EXPOSURE_TIME]} focus=${result[CaptureResult.LENS_FOCUS_DISTANCE]} lensState=${result[CaptureResult.LENS_STATE]} mask=${focusFrame.mask.count { it }}/${focusFrame.mask.size} clip=$clipping greenOnlyQuads=$partialGreen max999=${quads.percentileEv(999)} min999=${quads.percentileEv(999, quads.minChannelHistogram!!)} correction=${correction.correctionEv} nativeMs=${ab.nativeNs / 1e6} kotlinFocusMs=${ab.focusNs / 1e6} quadMedianMs=${times[times.size / 2]} baselineQuadMedianMs=${baseTimes[baseTimes.size / 2]}")
                                    checked++
                                }
                            }
                        }
                    }
                } finally { session?.close(); reader.close() }
            }
            assertTrue("no real RAW cases", checked > 0)
            log("PASS realFrames=$checked greenOnlyClippedFrames=$greenOnly greenOnlyQuads=$greenOnlyQuads sizes=$selected")
            log("Scene: owner reports lamp. Sky/foliage, multi-device and recording MF pull are NOT implied by this sweep.")
        } finally {
            device?.close()
            thread.quitSafely()
            thread.join(3000)
            File(context.getExternalFilesDir(null), "ml-focus-ettr-device.txt").writeText(report.toString())
        }
    }

    /** Local single-colour clipping can exist even when other frame regions clip all colours. */
    private fun greenOnlyCount(image: Image, c: CameraCharacteristics): Int {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val white = c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL]!!
        val black = c[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]!!
        val cfa = c[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]!!
        val channels = RawEttrSampler.phaseChannelLut(cfa)
        val step = meterGridStep((image.width / 2).toLong() * (image.height / 2), RawEttrSampler.TARGET_QUADS)
        val level = FloatArray(4)
        var count = 0
        for (qy in 0 until image.height / 2 step step) for (qx in 0 until image.width / 2 step step) {
            for (phase in 0..3) {
                val x = qx * 2 + phase % 2
                val y = qy * 2 + phase / 2
                val code = buffer.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 65535
                val floor = black.getOffsetForIndex(phase % 2, phase / 2)
                level[channels[phase]] = (code - floor).toFloat() / (white - floor)
            }
            if ((level[1] >= 1f || level[2] >= 1f) && level[0] < 0.99f && level[3] < 0.99f) count++
        }
        return count
    }

    private fun correctionFixtures() {
        fun stats(shift: Double): EttrQuadStats {
            val max = IntArray(EttrQuadStats.BINS)
            val min = IntArray(EttrQuadStats.BINS)
            var over = 0
            for (i in 0 until 6000) {
                val g = Math.pow(2.0, -4.0 + 2 * (i + 0.5) / 6000 + shift)
                val gg = minOf(1.0, g).toFloat()
                val rb = minOf(1.0, g / 16).toFloat()
                max[EttrQuadStats.binOf(gg)]++
                min[EttrQuadStats.binOf(rb)]++
                if ((gg + 2 * rb) / 3 >= 0.8f) over++
            }
            return EttrQuadStats(max, 6000, over, min)
        }
        val memory = MagicLanternEttr.Memory()
        val good = MagicLanternEttr.correction(stats(0.0), memory, -0.5, Double.NaN)
        assertEquals(1.5, good.correctionEv, 0.04)
        val clipped = MagicLanternEttr.correction(stats(4.0), memory, -0.5, Double.NaN)
        assertTrue(clipped.overexposed)
        assertEquals(-2.5, clipped.correctionEv, 0.04)
        log("PASS ARM green-clips/RB-gradient fixture correction=${clipped.correctionEv}")
        memory.clear()
        assertFalse(memory.hasMinGradient)
    }
    // Pre-F15 sampler from HEAD, retained as a device timing/output oracle.
    private fun baselineQuads(image: Image, characteristics: CameraCharacteristics, targetQuads: Int): EttrQuadStats? {
        val plane = image.planes.singleOrNull() ?: return null
        val width = image.width and 1.inv()
        val height = image.height and 1.inv()
        if (width < 2 || height < 2) return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: return null
        if (cfa !in 0..3) return null
        val black = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val white = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.coerceAtLeast(1) ?: return null
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val packed = if (plane.pixelStride < 2) {
            val format = PackedRawFormat.ofInt(image.format) ?: return null
            try {
                RawPackedUnpacker.PackedSampleReader(buffer, image.width, image.height, plane.rowStride, 0, format)
            } catch (_: IllegalArgumentException) {
                return null
            }
        } else null
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val limit = buffer.limit()
        val blackLut = IntArray(4) { i -> black?.getOffsetForIndex(i % 2, i / 2) ?: 0 }
        val invLut = FloatArray(4) { i -> RawEttrSampler.invRange(white, blackLut[i]) }
        val channelLut = RawEttrSampler.phaseChannelLut(cfa)
        val quadCount = (width / 2).toLong() * (height / 2)
        val step = meterGridStep(quadCount, targetQuads)
        val hist = IntArray(EttrQuadStats.BINS)
        var total = 0
        var over = 0
        val level = FloatArray(4)
        var qy = 0
        while (qy < height / 2) {
            var qx = 0
            while (qx < width / 2) {
                var ok = true
                for (phase in 0..3) {
                    val x = qx * 2 + (phase and 1)
                    val y = qy * 2 + (phase shr 1)
                    val value = if (packed != null) packed.codeAt(x, y) else {
                        val offset = y * rowStride + x * pixelStride
                        if (offset + 1 >= limit) { ok = false; break }
                        buffer.getShort(offset).toInt() and 0xffff
                    }
                    level[channelLut[phase]] =
                        ((value - blackLut[phase]).coerceAtLeast(0) * invLut[phase]).coerceIn(0f, 1f)
                }
                if (ok) {
                    val g = 0.5f * (level[1] + level[2])
                    hist[EttrQuadStats.binOf(maxOf(level[0], g, level[3]))]++
                    if ((level[0] + g + level[3]) / 3f >= EttrQuadStats.OVEREXPOSED_LEVEL) over++
                    total++
                }
                qx += step
            }
            qy += step
        }
        return if (total > 0) EttrQuadStats(hist, total, over) else null
    }

}
