// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end Direct Log: real sensor RAW -> fused Vulkan MHC demosaic +
 * log grade (per-frame WB/CCM) -> HW HEVC Main10 4K -> MP4, at the FULL
 * rung plus the next ladder rung down when the sensor exposes more than
 * one RAW size. The step-4 config runs the superpixel fallback instead
 * (permanent fallback coverage).
 *
 * Opt-in (opens the camera): `-e rawlensDirectLog true [-e seconds 4]`.
 */
@RunWith(AndroidJUnit4::class)
class DirectLogRecordDeviceTest {
    /**
     * CCM row-major pin (device: ColorSpaceTransform is framework, so
     * this can't live in JVM tests). Regression: the row index was i/2
     * (out-of-bounds rows past element 5). Ungated: pure function, no
     * camera needed.
     */
    @Test
    fun ccmFromTransformRowMajor() {
        // Distinct rationals: row r, col c holds 10r+c (row-major ctor).
        val els = Array(9) { i -> Rational(i / 3 * 10 + i % 3, 1) }
        val ccm = DirectLogRecorder.ccmFromTransform(ColorSpaceTransform(els))
        assertArrayEquals(
            floatArrayOf(0f, 1f, 2f, 10f, 11f, 12f, 20f, 21f, 22f), ccm, 0f
        )
        assertArrayEquals(
            VfLogGrade.IDENTITY_CCM, DirectLogRecorder.ccmFromTransform(null), 0f
        )
    }

    @Test
    fun recordMain10Mp4() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensDirectLog true to open the camera and record",
            args.getString("rawlensDirectLog") == "true"
        )
        val seconds = args.getString("seconds", "4").toInt().coerceIn(3, 60)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()

        val foreground = if (args.getString("foreground") == "true") {
            context.getSharedPreferences("rawlens_settings", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("lens_setup_complete", true).commit()
            val activity = instrumentation.startActivitySync(android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            SystemClock.sleep(1500)
            instrumentation.runOnMainSync {
                activity.controller.stop()
                val preview = activity.findViewById<DirectLogPreviewView>(R.id.recordPreview)
                val vf = listOf(activity.findViewById<android.view.View>(R.id.rawViewfinder),
                    activity.findViewById<android.view.View>(R.id.bguViewfinder))
                    .firstOrNull { it.visibility == android.view.View.VISIBLE && it.width > 0 && it.height > 0 }
                preview.layoutParams = android.widget.FrameLayout.LayoutParams(
                    vf?.width ?: android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    vf?.height ?: android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.TOP or android.view.Gravity.START).apply {
                    leftMargin = vf?.left ?: 0; topMargin = vf?.top ?: 0
                }
                preview.visibility = android.view.View.VISIBLE
            }
            SystemClock.sleep(500)
            activity
        } else null

        val manager = context.getSystemService(CameraManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        Log.i(TAG, "SPIKE thermal status=${power?.currentThermalStatus} " +
            "(0=none; hot runs grade ~4x slower — foreground-compile + cool down first)")
        val cameraId = manager.cameraIdList.first { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val chars = manager.getCameraCharacteristics(cameraId)
        val rawSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
        assumeTrue("no RAW_SENSOR sizes", rawSizes.isNotEmpty())
        val rungs = LogVideoLadder.distinctRungs(rawSizes).take(2)
        Log.i(TAG, "SPIKE ladder rungs=${rungs.joinToString {
            "${it.first.label}=${it.second.width}x${it.second.height}"
        }}")
        data class Config(
            val label: String,
            val rung: LogVideoLadder.Rung,
            val step: Int,
            val fused: Boolean,
        )
        val configs = mutableListOf<Config>()
        for ((rung, _) in rungs) configs.add(Config(rung.label, rung, 2, true))
        // Compute-side scaling (same FOV, 1/4 the invocations): the lever
        // that works on single-size sensors like this one. Runs the
        // superpixel fallback (preferMhc=false): permanent coverage for
        // the fallback rung, where computeStep applies.
        configs.add(Config("open-gate-step4", LogVideoLadder.Rung.FULL, 4, false))

        val rcdOnly = args.getString("rcdOnly") == "true"
        if (rcdOnly) {
            configs.clear()
            configs.add(Config("rcd-4k", LogVideoLadder.Rung.FULL, 2, true))
        }
        try {
        for ((label, rung, step, fused) in configs) {
            val file = File(context.cacheDir, "directlog_${label.replace('-', '_')}.mp4")
            if (file.exists()) file.delete()
            val rec = DirectLogRecorder(manager, cameraId, context)
            rec.rung = rung
            // -e fps 24|30 (default: the recorder default).
            args.getString("fps")?.let { rec.fps = it.toInt() }
            args.getString("width")?.let { rec.width = it.toInt() }
            args.getString("height")?.let { rec.height = it.toInt() }
            rec.bitrate = LogVideoProbe.defaultBitrateFor(rec.width, rec.height)
            rec.computeStep = step
            rec.preferMhc = fused
            if (rcdOnly) {
                rec.videoDemosaic = VideoDemosaic.RCD
                rec.preferRcdGpuWriter = args.getString("gpuWriter") != "false"
                rec.logProfile = DirectLogProfile.ACES
            }
            if (foreground != null) rec.attachPreviewDisplay(foreground.findViewById(R.id.recordPreview))
            rec.allowThermalDegrade = false
            rec.start(file)
            foreground?.let { activity -> instrumentation.runOnMainSync {
                activity.findViewById<android.widget.TextView>(R.id.rawBadge).apply {
                    text = "● RCD TEST • ${rec.width}×${rec.height} • ${rec.fps}FPS"
                    setTextColor(android.graphics.Color.RED)
                }
            } }
            repeat(seconds * 2) { tick ->
                SystemClock.sleep(500)
                if (args.getString("resetDuringRecording") == "true" && tick == seconds) {
                    // A photo-viewfinder teardown must not invalidate an active take.
                    VfVulkan.resetNative()
                }
            }
            foreground?.let { activity -> instrumentation.runOnMainSync {
                activity.findViewById<android.widget.TextView>(R.id.rawBadge).text = "RCD TEST • SAVING"
            } }
            val stats = rec.stop()
            Log.i(
                TAG, "SPIKE directlog fps=${rec.fps} label=$label rung=${rung.label} step=$step sensor=${stats.sensorSize} " +
                    "crop=${stats.cropRect} export=${stats.exportSize} " +
                    "acq=${stats.framesAcquired} graded=${stats.framesGraded} " +
                    "drop=${stats.framesDropped} miss=${stats.resultMiss} stale=${stats.staleResults} " +
                    "waitFree=${stats.waitFreeFrames} fallback=${stats.fallbackWaits} preview=${stats.previewFrames} shaded=${stats.shadedFrames} " +
                    "fused=${stats.fusedFrames} " +
                    "heat=${stats.heatDegraded} thermal=${stats.thermalStatus} " +
                    "cpuChain=${"%.1f".format(stats.cpuChainMsAvg)}ms " +
                    "chainMax=${"%.1f".format(stats.cpuChainMsMax)}ms " +
                    "over=${stats.overBudgetFrames} " +
                    "copy=${"%.1f".format(stats.copyMsAvg)}ms " +
                    "copyMax=${"%.1f".format(stats.copyMsMax)}ms " +
                    "p010=${stats.true10Bit} starved=${stats.inputStarved} degraded=${stats.degradedFrames} " +
                    "aChunks=${stats.audioChunks} aFrames=${stats.audioFrames} " +
                    "aDrop=${stats.audioDropped} hasAudio=${stats.hasAudio} " +
                    "aacBytes=${stats.aacBytes} aOut=${stats.audioOutBufs} " +
                    "outBufs=${stats.outputBuffers} bytes=${stats.outputBytes} " +
                    "file=${stats.fileBytes}"
            )
            if (step == 2) {
                val expected = rungs.first { it.first == rung }.second
                assertEquals(
                    "ladder resolved ${stats.sensorSize}, expected ${expected.width}x${expected.height}",
                    "${expected.width}x${expected.height}", stats.sensorSize
                )
            }
            // Aspect-correct compute region: the blit stretches, so the
            // export itself must already be 16:9 (not the 4:3 sensor).
            val exportDims = stats.exportSize.split("x").map { it.toInt() }
            val aspect = exportDims[0].toDouble() / exportDims[1]
            assertTrue(
                "[$label] export not 16:9: ${stats.exportSize}",
                Math.abs(aspect - 16.0 / 9.0) < 0.005
            )
            if (foreground != null) assertTrue("Foreground monitor never submitted a frame", stats.previewFrames > 0)
            assertTrue("[$label] no frames acquired", stats.framesAcquired > 0)
            assertTrue("[$label] no frames graded", stats.framesGraded > 0)
            // Cooled floor (observed ~80%+ graded; below half the take is
            // unusable): distinguishes a broken path from normal drops.
            assertTrue(
                "[$label] graded ${stats.framesGraded}/${stats.framesAcquired} below half",
                rcdOnly || stats.framesGraded * 2 >= stats.framesAcquired
            )
            if (!rcdOnly) assertTrue("[$label] wait-free path never engaged", stats.waitFreeFrames > 0)
            if (rcdOnly) {
                assertTrue("RCD path did not run", stats.rcdFrames > 0)
                if (args.getString("requireShading") == "true") {
                    assertTrue("Lens shading was not applied", stats.shadedFrames > 0)
                    assertTrue("Lens shading flapped during recording: ${stats.shadedFrames}/${stats.framesGraded}",
                        stats.shadedFrames >= stats.framesGraded - 2)
                }
            } else if (fused) {
                assertTrue("[$label] fused record path never engaged", stats.fusedFrames > 0)
            } else {
                assertEquals("[$label] fallback config ran fused", 0, stats.fusedFrames)
            }
            assertEquals("[$label] blocking fallbacks: ${stats.fallbackWaits}", 0, stats.fallbackWaits)
            assertEquals("[$label] frame exceptions: ${stats.frameErrors}", 0, stats.frameErrors)
            // Pipeline loss tripwire (heat-robust: ratio, not fps).
            // Halfway between broken (the per-pixel grade bled ~30%) and
            // worst-healthy (10% on a heat-soaked second rung; cool runs
            // sit at ~1%). A sustained copy regression trips this.
            val offered = stats.framesAcquired + stats.framesDropped
            val dropPct = if (offered > 0) 100.0 * stats.framesDropped / offered else 0.0
            assertTrue("[$label] drop ratio too high: $dropPct%", rcdOnly || dropPct <= 15.0)
            // No corrupt wedges: failed copies/queues are visible
            // glitches, not noise.
            assertEquals("[$label] degraded frames", 0, stats.degradedFrames)
            // The drain thread must keep the codec pool wet: a starved
            // dequeue loses an already-graded frame.
            assertEquals("[$label] starved codec inputs", 0, stats.inputStarved)
            // P010 direct input (true 10-bit content, no EGL surface).
            assertTrue("[$label] P010 direct path inactive", stats.true10Bit)
            assertTrue("[$label] no encoder output", stats.outputBuffers > 0 && stats.outputBytes > 0)
            assertTrue("[$label] no file", file.exists() && file.length() > 0)
            // Durable eyeball copy: external files are pullable via adb
            // (cacheDir needs run-as, which release builds lack).
            val eyeball = File(
                context.getExternalFilesDir(null),
                "directlog_${label.replace('-', '_')}.mp4"
            )
            file.copyTo(eyeball, overwrite = true)
            Log.i(TAG, "SPIKE directlog [$label] eyeball copy: ${eyeball.absolutePath}")
            validateMp4(file, seconds, label, rcdOnly)
            if (rcdOnly) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val frame = retriever.getFrameAtTime(if (seconds >= 8) 5_000_000L else 1_000_000L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    assertTrue("RCD MP4 could not decode a video frame", frame != null)
                    frame?.let { bitmap ->
                        val decoded = File(context.getExternalFilesDir(null), "directlog_rcd_decoded.png")
                        if (args.getString("fast") != "true") {
                            decoded.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            Log.i(TAG, "RCD decoded frame: ${decoded.absolutePath}")
                        }
                        bitmap.recycle()
                    }
                } finally { retriever.release() }
                val expectedBytes = file.length()
                val published = DirectLogSaver.save(context.contentResolver, file)
                context.contentResolver.openFileDescriptor(published.uri, "r")!!.use {
                    assertEquals("Published RCD MP4 size", expectedBytes, it.statSize)
                }
                Log.i(TAG, "RCD published: ${published.uri} bytes=$expectedBytes")
            }
            file.delete()
        }
        } finally {
            foreground?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    private fun validateMp4(file: File, seconds: Int, label: String, rcd: Boolean = false) {
        val ext = MediaExtractor()
        try {
            ext.setDataSource(file.absolutePath)
            assertEquals("[$label] expected video+audio tracks", 2, ext.trackCount)
            var videoTrack = -1
            var audioTrack = -1
            for (t in 0 until ext.trackCount) {
                when (ext.getTrackFormat(t).getString(MediaFormat.KEY_MIME)) {
                    MediaFormat.MIMETYPE_VIDEO_HEVC -> videoTrack = t
                    MediaFormat.MIMETYPE_AUDIO_AAC -> audioTrack = t
                }
            }
            assertTrue("[$label] no HEVC track", videoTrack >= 0)
            assertTrue("[$label] no AAC track", audioTrack >= 0)
            val vfmt = ext.getTrackFormat(videoTrack)
            val afmt = ext.getTrackFormat(audioTrack)
            assertEquals(InstrumentationRegistry.getArguments().getString("width")?.toInt() ?: 3840, vfmt.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(InstrumentationRegistry.getArguments().getString("height")?.toInt() ?: 2160, vfmt.getInteger(MediaFormat.KEY_HEIGHT))
            if (rcd && vfmt.containsKey(MediaFormat.KEY_PROFILE))
                assertEquals("RCD output must remain Main10", android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                    vfmt.getInteger(MediaFormat.KEY_PROFILE))
            // Developed-file color signalling: the pixels are sRGB-OETF
            // (see the grade shaders) and the encoder is configured
            // SDR/BT.709/limited. Log always (evidence); assert the values
            // when the muxer propagated the keys into the container.
            val colorStd = if (vfmt.containsKey(MediaFormat.KEY_COLOR_STANDARD))
                vfmt.getInteger(MediaFormat.KEY_COLOR_STANDARD) else -1
            val colorTransfer = if (vfmt.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                vfmt.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else -1
            val colorRange = if (vfmt.containsKey(MediaFormat.KEY_COLOR_RANGE))
                vfmt.getInteger(MediaFormat.KEY_COLOR_RANGE) else -1
            Log.i(TAG, "SPIKE directlog-color std=$colorStd transfer=$colorTransfer range=$colorRange")
            if (colorStd >= 0) assertEquals("[$label] color standard", MediaFormat.COLOR_STANDARD_BT709, colorStd)
            if (colorTransfer >= 0) assertEquals("[$label] color transfer", MediaFormat.COLOR_TRANSFER_SDR_VIDEO, colorTransfer)
            if (colorRange >= 0) assertEquals("[$label] color range", MediaFormat.COLOR_RANGE_LIMITED, colorRange)
            assertEquals(48000, afmt.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(2, afmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            var videoSamples = 0
            var audioSamples = 0
            val videoPts = mutableListOf<Long>()
            ext.selectTrack(videoTrack)
            ext.selectTrack(audioTrack)
            // Size from the track's own max (a bright 4K I-frame exceeds
            // any fixed guess; readSampleData throws below it).
            val maxSample = if (vfmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                vfmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                4 * 1024 * 1024
            }
            val buf = java.nio.ByteBuffer.allocate(maxOf(4 * 1024 * 1024, maxSample))
            while (true) {
                val track = ext.sampleTrackIndex
                if (track < 0) break
                val n = ext.readSampleData(buf, 0)
                if (n < 0) break
                if (track == videoTrack) {
                    videoSamples++
                    videoPts.add(ext.sampleTime)
                } else audioSamples++
                ext.advance()
                if (videoSamples + audioSamples > 4000) break
            }
            val durationUs = vfmt.getLong(MediaFormat.KEY_DURATION)
            Log.i(TAG, "SPIKE directlog [$label] muxed video=$videoSamples audio=$audioSamples durationUs=$durationUs")
            assertTrue("[$label] no muxed video", videoSamples > if (rcd) 2 else seconds * 10)
            // Telemetry only (no assert): absolute fps belongs to the scene
            // (dim light forces long AE exposures) and the SoC skin temp,
            // not to this pipeline. Steady state on a cool device: ~29fps.
            val muxedFps = videoSamples * 1_000_000.0 / durationUs.coerceAtLeast(1L)
            Log.i(TAG, "SPIKE directlog [$label] muxedFps=${"%.1f".format(muxedFps)}")
            if (videoPts.size > 2) {
                val start = videoPts.first()
                val fullSeconds = ((videoPts.last() - start) / 1_000_000L).toInt()
                val windows = (2 until fullSeconds).map { second ->
                    videoPts.count { it >= start + second * 1_000_000L && it < start + (second + 1) * 1_000_000L }
                }
                val gaps = videoPts.zipWithNext { a, b -> b - a }.drop(48).sorted()
                val stableFps = InstrumentationRegistry.getArguments().getString("stableFps")?.toInt()
                if (stableFps != null) {
                    assertTrue("No steady-state cadence windows", windows.isNotEmpty())
                    assertTrue("Unstable one-second frame counts: $windows", windows.all { it >= stableFps - 1 })
                    assertTrue("Steady p95 frame gap exceeds cadence", gaps[(gaps.size * .95).toInt()] <= 1_200_000L / stableFps)
                }
                Log.i(TAG, "RCD cadence fullSecondCounts=$windows steadyGapUs=" +
                    if (gaps.isEmpty()) "none" else "min=${gaps.first()} p50=${gaps[gaps.size / 2]} p95=${gaps[(gaps.size * .95).toInt()]} max=${gaps.last()}")
            }

            assertTrue("[$label] no muxed audio", audioSamples > seconds * 10)
            assertTrue("[$label] duration implausible: $durationUs", durationUs > (seconds - if (rcd) 3 else 2) * 1_000_000L)
            // Constant frame rate: every consecutive PTS gap sits on the
            // take grid (33.33ms @30fps, 41.67ms @24fps). Sorted: extractor
            // order is not guaranteed presentation order. The band covers
            // both take rates; uniformity (<=2ms spread) pins CFR.
            val sortedPts = videoPts.sorted()
            var maxGapUs = 0L
            var minGapUs = Long.MAX_VALUE
            for (i in 1 until sortedPts.size) {
                val gap = sortedPts[i] - sortedPts[i - 1]
                maxGapUs = maxOf(maxGapUs, gap)
                minGapUs = minOf(minGapUs, gap)
            }
            Log.i(TAG, "SPIKE directlog [$label] videoPtsGap minUs=$minGapUs maxUs=$maxGapUs")
            assertTrue(
                "[$label] video PTS not constant frame rate (min=${minGapUs}us max=${maxGapUs}us)",
                if (rcd) minGapUs > 0L else minGapUs >= 25_000L && maxGapUs <= 45_000L && maxGapUs - minGapUs <= 2_000L
            )
        } finally {
            ext.release()
        }
    }

    companion object {
        private const val TAG = "DirectLog"
    }
}
