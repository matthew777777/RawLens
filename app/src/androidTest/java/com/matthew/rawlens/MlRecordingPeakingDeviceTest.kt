// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Release-native recording/MF handoff. Only its own staged test clips are deleted. */
class MlRecordingPeakingDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val manager = context.getSystemService(CameraManager::class.java)
    private val events = CopyOnWriteArrayList<Pair<Long, FocusPeakingFrame?>>()
    private val report = StringBuilder()
    private fun log(s: String) { report.appendLine(s); Log.i("RawLensMlRecord", s) }
    private fun setup(): String {
        assumeTrue("Pass -e rawlensMlAudit true", InstrumentationRegistry.getArguments().getString("rawlensMlAudit") == "true")
        inst.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        return manager.cameraIdList.first { id ->
            val c = manager.getCameraCharacteristics(id)
            c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
                c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]!!.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        }
    }
    private fun await(label: String, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(label, check())
    }
    private fun observer(frame: FocusPeakingFrame?) { events += SystemClock.elapsedRealtime() to frame }
    private fun pull(setFocus: (Long?) -> Unit) {
        for (focus in listOf(0L, 500L, 5000L)) {
            val before = events.count { it.second != null }
            setFocus(focus)
            await("peaking did not refresh after MF=$focus") { events.count { it.second != null } >= before + 2 }
            log("MF=$focus frames=${events.count { it.second != null }} mask=${events.last().second?.mask?.count { it }}")
        }
        setFocus(null)
        await("peaking did not clear on AF") { events.lastOrNull()?.second == null }
        val after = events.size
        SystemClock.sleep(400)
        assertEquals("stale worker republished after AF", after, events.size)
        setFocus(500L)
        await("peaking did not resume after AF->MF") { events.size > after && events.last().second != null }
    }
    private fun verifyStopped() {
        val after = events.size
        SystemClock.sleep(400)
        assertEquals("peaking published after stop", after, events.size)
        val frames = events.mapNotNull { if (it.second != null) it.first else null }
        assertTrue("too few peaking frames", frames.size >= 7)
        val intervals = frames.zipWithNext().map { it.second - it.first }.sorted()
        log("PEAKING frames=${frames.size} medianIntervalMs=${intervals[intervals.size / 2]} maxIntervalMs=${intervals.last()} geometry=${events.first { it.second != null }.second!!.let { "${it.cols}x${it.rows}" }} leases=${RawImageOwnership.liveCount()}")
        assertEquals("RAW leases leaked after stop", 0, RawImageOwnership.liveCount())
    }
    @Test fun rawVideoMfPullKeepsSamplingAndClearsOnAuto() {
        val id = setup()
        assumeTrue("CRS backend is not implemented in this tree", CrsWriter.available)
        val size = manager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            .getOutputSizes(ImageFormat.RAW_SENSOR)!!.maxBy { it.width.toLong() * it.height }
        val file = File(context.cacheDir, "ml-peaking-raw-test.crs")
        val recorder = RawVideoRecorder(manager, id, size, context).apply {
            audioEnabled = false; motionEnabled = false
            videoManual = VideoManualSnapshot(iso = 1600, shutterNanos = 10_000_000, focusDiopters = 0.5f)
            onFocusPeaking = ::observer
        }
        var started = false
        try {
            recorder.start(file, "{}")
            started = true
            SystemClock.sleep(2500)
            val b0 = recorder.snapshot()
            SystemClock.sleep(2000)
            val b1 = recorder.snapshot()
            recorder.focusPeakingEnabled = true
            val p0 = recorder.snapshot()
            pull { recorder.setVideoManualControl(ManualControl.FOCUS_DISTANCE, it) }
            val p1 = recorder.snapshot()
            val baseFps = (b1.framesEncoded - b0.framesEncoded) * 1000.0 / (b1.elapsedMs - b0.elapsedMs)
            val peakFps = (p1.framesEncoded - p0.framesEncoded) * 1000.0 / (p1.elapsedMs - p0.elapsedMs)
            log("CRS sensor=$size baselineFps=$baseFps peakingFps=$peakFps baselineDrops=${b1.framesDropped - b0.framesDropped} peakingDrops=${p1.framesDropped - p0.framesDropped}")
            assertTrue("recording stalled with peaking", peakFps >= 12.0)
            val stats = recorder.stop()
            started = false
            log("CRS STOP $stats")
            assertTrue(stats.containerFrames > 0)
            verifyStopped()
        } finally {
            if (started) recorder.stop()
            file.delete()
            File(context.getExternalFilesDir(null), "ml-peaking-crs-device.txt").writeText(report.toString())
        }
    }
    @Test fun directLogMfPullKeepsSamplingAndClearsOnAuto() {
        val id = setup()
        val args = InstrumentationRegistry.getArguments()
        val requestedWidth = args.getString("mlRecordWidth", "1920").toInt()
        val requestedHeight = args.getString("mlRecordHeight", "1080").toInt()
        val requestedFps = args.getString("mlRecordFps", "24").toInt()
        require((requestedWidth to requestedHeight) in listOf(1920 to 1080, 2560 to 1440, 3840 to 2160))
        require(requestedFps == 24 || requestedFps == 30)
        val label = "${requestedWidth}x${requestedHeight}-${requestedFps}"
        val file = File(context.cacheDir, "ml-peaking-log-$label-test.mp4")
        val recorder = DirectLogRecorder(manager, id, context).apply {
            audioEnabled = false; stabEnabled = false
            width = requestedWidth; height = requestedHeight; fps = requestedFps
            videoManual = VideoManualSnapshot(iso = 1600, shutterNanos = 10_000_000, focusDiopters = 0.5f)
            onFocusPeaking = ::observer
        }
        var started = false
        try {
            recorder.start(file)
            started = true
            SystemClock.sleep(2500)
            val b0 = recorder.snapshot()
            SystemClock.sleep(2000)
            val b1 = recorder.snapshot()
            recorder.focusPeakingEnabled = true
            val p0 = recorder.snapshot()
            pull { recorder.setVideoManualControl(ManualControl.FOCUS_DISTANCE, it) }
            val p1 = recorder.snapshot()
            val baseFps = (b1.framesGraded - b0.framesGraded) * 1000.0 / (b1.elapsedMs - b0.elapsedMs)
            val peakFps = (p1.framesGraded - p0.framesGraded) * 1000.0 / (p1.elapsedMs - p0.elapsedMs)
            log("LOG $label baselineFps=$baseFps peakingFps=$peakFps baselineDrops=${b1.framesDropped - b0.framesDropped} peakingDrops=${p1.framesDropped - p0.framesDropped}")
            assertTrue("recording stalled with peaking", peakFps >= 12.0)
            val stats = recorder.stop()
            started = false
            log("LOG STOP $stats")
            assertEquals("record frame errors", 0, stats.frameErrors)
            assertTrue(stats.fileBytes > 0)
            verifyStopped()
        } finally {
            if (started) recorder.stop()
            file.delete()
            File(context.getExternalFilesDir(null), "ml-peaking-log-$label-device.txt").writeText(report.toString())
        }
    }
}
