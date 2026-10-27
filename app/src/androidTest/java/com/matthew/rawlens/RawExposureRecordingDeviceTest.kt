// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in real recording comparison. Deletes only its own temporary clips. */
class RawExposureRecordingDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val manager = context.getSystemService(CameraManager::class.java)
    private val events = CopyOnWriteArrayList<RawExposureAssistFrame?>()
    private val report = StringBuilder("DEVICE ${android.os.Build.MODEL} Android=${android.os.Build.VERSION.RELEASE}\n")
    private data class Counts(val ms: Long, val frames: Int, val drops: Int)
    private data class Phase(val name: String, val fps: Double, val drops: Int, val samples: Int)
    private fun setup(): String {
        assumeTrue("Pass -e rawlensF12Audit true", InstrumentationRegistry.getArguments().getString("rawlensF12Audit") == "true")
        inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        return manager.cameraIdList.first { id -> manager.getCameraCharacteristics(id).let { c ->
            c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
                c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        } }
    }
    private fun exercise(set: (RawExposureAssistSettings) -> Unit, snapshot: () -> Counts, cropped: Boolean) {
        report.appendLine("CONTROL_ONLY=${InstrumentationRegistry.getArguments().getString("f12Control") == "true"}")
        SystemClock.sleep(2500)
        val phases = mutableListOf<Phase>()
        val controlOnly = InstrumentationRegistry.getArguments().getString("f12Control") == "true"
        for ((name, requestedConfig) in listOf(
            "OFF-before" to RawExposureAssistSettings(spotMeter = false),
            "SPOT" to RawExposureAssistSettings(),
            "AVERAGE" to RawExposureAssistSettings(meterMode = RawExposureMeterMode.AVERAGE),
            "ZEBRA" to RawExposureAssistSettings(RawExposureOverlayMode.ZEBRAS, true, RawExposureMeterMode.AVERAGE),
            "FALSE" to RawExposureAssistSettings(RawExposureOverlayMode.FALSE_COLOR, true, RawExposureMeterMode.AVERAGE),
            "OFF-after" to RawExposureAssistSettings(spotMeter = false))) {
            val config = if (controlOnly) RawExposureAssistSettings(spotMeter = false) else requestedConfig
            set(config)
            SystemClock.sleep(400) // Drain a previous phase before measuring throughput.
            val from = events.size
            val a = snapshot()
            SystemClock.sleep(3000)
            val b = snapshot()
            val frames = events.drop(from).filterNotNull()
            if (config.enabled) {
                assertTrue("$name: no continuous RAW callbacks", frames.size >= 5)
                if (config.meterMode == RawExposureMeterMode.SPOT) {
                    assertTrue("$name: missing spot readings", frames.all { it.spot?.ev?.isFinite() == true && it.average == null })
                } else {
                    assertTrue("$name: missing average readings", frames.all { it.average?.ev?.isFinite() == true && it.spot == null })
                    assertTrue("$name: invalid clipping estimate", frames.all { it.average!!.clippedPercent in 0.0..100.0 && it.average.sampledCells in 1..16_384 })
                }
                assertTrue("$name: wrong overlay payload", frames.all { (it.colors != null) == (config.overlay != RawExposureOverlayMode.OFF) })
                assertTrue("$name: timestamps failed to advance", frames.zipWithNext().all { it.second.timestampNanos > it.first.timestampNanos })
                val f = frames.last()
                assertEquals("$name: crop handoff", cropped, f.monitorCrop != null)
                f.spot?.let { s ->
                    assertEquals(0.5f, (s.left + s.right) / 2, 0.02f)
                    assertEquals(0.5f, (s.top + s.bottom) / 2, 0.02f)
                }
                f.average?.let { a ->
                    val crop = f.monitorCrop ?: intArrayOf(0, 0, f.frameWidth, f.frameHeight)
                    assertEquals(crop[0].toFloat() / f.frameWidth, a.left, 2f / f.frameWidth)
                    assertEquals((crop[1] + crop[3]).toFloat() / f.frameHeight, a.bottom, 2f / f.frameHeight)
                }
                if (cropped) {
                    val rects = FocusPeakingGeometry.monitorRects(f.frameWidth, f.frameHeight, f.rotation, f.mirrored, f.monitorCrop, 720, 1080)
                    assertNotNull("Missing recording crop projection", rects)
                    assertTrue(rects!!.second[2] > rects.second[0] && rects.second[3] > rects.second[1])
                }
                report.appendLine("GEOMETRY $name raw=${f.frameWidth}x${f.frameHeight} grid=${f.cols}x${f.rows} rotation=${f.rotation} mirror=${f.mirrored} crop=${f.monitorCrop?.contentToString()} spot=${f.spot} average=${f.average}")
            } else assertTrue("Disabled assist republished stale frames", frames.isEmpty())
            val phase = Phase(name, (b.frames - a.frames) * 1000.0 / (b.ms - a.ms), b.drops - a.drops, frames.size)
            phases += phase
            report.appendLine(phase)
        }
        // Compare with both surrounding baselines to expose gradual thermal/IO drift.
        val baseline = minOf(phases.first().fps, phases.last().fps)
        assertTrue("Baseline recording did not sustain a usable rate", baseline >= 12.0)
        for (p in phases.subList(1, phases.lastIndex)) {
            assertTrue("${p.name}: recording throughput regressed: ${p.fps} vs $baseline", p.fps >= baseline - maxOf(1.0, baseline * 0.05))
            assertTrue("${p.name}: extra recording drops", p.drops <= maxOf(phases.first().drops, phases.last().drops) + 1)
        }
    }
    private fun verifyStopped() {
        assertNull("Stop must clear the overlay", events.lastOrNull())
        val count = events.size
        SystemClock.sleep(500)
        assertEquals("Callback after stop", count, events.size)
        assertEquals("RAW leases leaked", 0, RawImageOwnership.liveCount())
        report.appendLine("STOP PASS callbacks=$count leases=0 no post-stop publications")
    }
    @Test fun crsExposureAssistsPreserveRecording() {
        val id = setup()
        val size = manager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            .getOutputSizes(ImageFormat.RAW_SENSOR)!!.maxBy { it.width.toLong() * it.height }
        val file = File(context.cacheDir, "f12-recording-audit.craw")
        val recorder = RawVideoRecorder(manager, id, size, context).apply {
            audioEnabled = false; motionEnabled = false
            videoManual = VideoManualSnapshot(iso = 1600, shutterNanos = 10_000_000, focusDiopters = 0.5f)
            onRawExposureAssist = { events.add(it) }
        }
        var started = false
        try {
            if (!CrawWriter.available) {
                recorder.rawExposureSettings = RawExposureAssistSettings(RawExposureOverlayMode.ZEBRAS, true)
                try {
                    recorder.start(file, "{}")
                    fail("Unavailable CRAW must reject recording before acquiring RAW frames")
                } catch (expected: IllegalArgumentException) {
                    assertEquals("craw native library unavailable", expected.message)
                }
                val stats = recorder.stop()
                assertEquals(0, stats.framesAcquired)
                assertFalse("Unavailable CRAW created an output", file.exists())
                verifyStopped()
                report.appendLine("CRAW UNAVAILABLE: existing backend is unimplemented; start rejection/cleanup PASS. Recording throughput is excluded, not claimed tested.")
                return
            }
            recorder.start(file, "{}")
            started = true
            exercise({ recorder.rawExposureSettings = it }, { recorder.snapshot().let { Counts(it.elapsedMs, it.framesEncoded, it.framesDropped) } }, false)
            val stats = recorder.stop(); started = false
            assertTrue("Empty CRAW recording", stats.containerFrames > 0)
            report.appendLine("CRAW STOP $stats")
            verifyStopped()
        } finally {
            if (started) recorder.stop()
            file.delete()
            File(context.getExternalFilesDir(null), "f12-recording-craw.txt").writeText(report.toString())
        }
    }
    @Test fun directLogExposureAssistsPreserveRecording() {
        val id = setup()
        val args = InstrumentationRegistry.getArguments()
        val w = args.getString("f12Width", "1920").toInt()
        val h = args.getString("f12Height", "1080").toInt()
        val rate = args.getString("f12Fps", "30").toInt()
        val file = File(context.cacheDir, "f12-recording-audit.mp4")
        val recorder = DirectLogRecorder(manager, id, context).apply {
            audioEnabled = false; stabEnabled = false; width = w; height = h; fps = rate
            logProfile = DirectLogProfile.fromName(args.getString("f12Profile", "BT709"))
            videoManual = VideoManualSnapshot(iso = 1600, shutterNanos = 10_000_000, focusDiopters = 0.5f)
            onRawExposureAssist = { events.add(it) }
        }
        var started = false
        try {
            recorder.start(file); started = true
            report.appendLine("NEGOTIATED encoder=${recorder.width}x${recorder.height} fps=${recorder.fps} profile=${recorder.logProfile}")
            exercise({ recorder.rawExposureSettings = it }, { recorder.snapshot().let { Counts(it.elapsedMs, it.framesGraded, it.framesDropped) } }, true)
            val stats = recorder.stop(); started = false
            report.appendLine("LOG requested=${w}x$h ${rate}fps STOP $stats")
            assertTrue("Empty LOG recording", stats.fileBytes > 0)
            assertEquals("LOG frame errors", 0, stats.frameErrors)
            verifyStopped()
        } finally {
            if (started) recorder.stop()
            file.delete()
            File(context.getExternalFilesDir(null), "f12-recording-log-${w}x$h-$rate.txt").writeText(report.toString())
        }
    }
}
