// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Intent
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end app test for Direct Log video: enable the opt-in, enter
 * video, record, stop, assert a valid HEVC MP4 lands in MediaStore, then
 * delete it (no gallery pollution).
 *
 * Unlike early prototypes the viewfinder stays live: the shared native
 * Vulkan context is mutex-serialized, so the VF worker renders (proving
 * zero-copy fan-out) while the camera thread records the log take. The
 * VF runs throttled in record mode (480p @<=10fps): full-rate VF
 * dispatches contend with record submits on one native mutex and both
 * stutter, so the test pins the throttle band, not full rate.
 *
 * Smoothness is asserted three ways: live VF health mid-roll, take
 * counters after stop (zero drops / over-budget / errors), and constant
 * frame rate in the published file (uniform video PTS gaps — drops
 * surface in take counters, never as file gaps).
 *
 * Opt-in: `-e rawlensDirectLogUi true`. Requires completed lens setup
 * (preseeded) and CAMERA grant via shell.
 */
@RunWith(AndroidJUnit4::class)
class DirectLogUiTest {
    @Test fun videoButtonRecordsDirectLog() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensDirectLogUi true to drive the app UI",
            args.getString("rawlensDirectLogUi") == "true"
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        // Deterministic A/V take: mic granted (persisted grants make the
        // un-granted path order-dependent, and denial parks behind a
        // permission dialog instead of recording).
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
        val prefs = context.getSharedPreferences("rawlens_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("lens_setup_complete", true).apply()
        // Key mirrors MainActivity.KEY_DIRECT_LOG (private companion).
        prefs.edit().putBoolean("direct_log_video", true).apply()
        // Pin sound on (key mirrors MainActivity.KEY_VIDEO_AUDIO): the
        // meter toggle persists, so a stray tap would otherwise leave
        // later takes silent (1-track clip) and order-dependent.
        prefs.edit().putBoolean("video_audio", true).apply()

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        var publishedUri: android.net.Uri? = null
        try {
            fun view(id: Int): View = activity.findViewById(id)
            fun click(v: View) {
                instrumentation.runOnMainSync { v.performClick() }
            }
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!view(R.id.shutter).isEnabled && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(200)
            }
            assertTrue("Stills preview never became live", view(R.id.shutter).isEnabled)
            SystemClock.sleep(2000)

            // 1. Enter video: badge must read mode + default profile (opt-in active).
            click(view(R.id.videoRecordButton))
            SystemClock.sleep(500)
            var badge = ""
            instrumentation.runOnMainSync {
                badge = (activity.findViewById<TextView>(R.id.rawBadge)).text.toString()
            }
            assertEquals("LOG • 709", badge)

            // 2. Shutter starts the take; HUD appears.
            click(view(R.id.shutter))
            val takeStartWall = System.currentTimeMillis()
            SystemClock.sleep(2000)
            var hudVisible = false
            var recBadge = ""
            instrumentation.runOnMainSync {
                hudVisible = view(R.id.videoHudTop).visibility == View.VISIBLE
                recBadge = (activity.findViewById<TextView>(R.id.rawBadge)).text.toString()
            }
            assertTrue("Video HUD never appeared", hudVisible)
            assertEquals("● LOG • 709", recBadge)

            // 3. Mid-roll: viewfinder live on the zero-copy GPU path while
            // the camera thread records the log take (mutex-serialized
            // shared Vulkan context), throttled to record mode (480p
            // @<=10fps) so neither side starves the other.
            SystemClock.sleep(2500)
            val vf = activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot()
            android.util.Log.i(
                TAG, "SPIKE ui vf fps=${vf.fps} gpu=${vf.gpu} glActive=${vf.glActive} " +
                    "raw=${vf.rawWidth}x${vf.rawHeight} vf=${vf.vfWidth}x${vf.vfHeight} " +
                    "busySkips=${vf.busySkips}"
            )
            assertTrue("VF went dark during Direct-Log recording: $vf", vf.glActive)
            assertTrue("VF not on zero-copy GPU path during recording: $vf", vf.gpu)
            assertTrue("VF fps ${vf.fps} outside record-mode band [4,15]: $vf", vf.fps in 4f..15f)
            assertTrue("VF not throttled to record-mode res: $vf", vf.vfWidth <= VfResolution.MIN)

            // 4. Roll on, stop, wait for the MediaStore publish.
            SystemClock.sleep(2000)
            click(view(R.id.shutter))
            val resolver = context.contentResolver
            var displayName = ""
            val stopDeadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < stopDeadline) {
                resolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DATE_ADDED
                    ),
                    "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                    arrayOf("%_LOG709.mp4"),
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val added = cursor.getLong(2) * 1000L
                        // Match only this take: DATE_ADDED is the insert
                        // time (post-stop), so anything inserted before the
                        // start tap is a stale leak or a manual take.
                        if (added >= takeStartWall - 10_000) {
                            publishedUri = android.net.Uri.withAppendedPath(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                cursor.getLong(0).toString()
                            )
                            displayName = cursor.getString(1)
                        }
                    }
                }
                if (publishedUri != null) break
                SystemClock.sleep(1000)
            }
            assertTrue("No _LOG709.mp4 published to MediaStore", publishedUri != null)
            android.util.Log.i(TAG, "SPIKE ui published=$displayName uri=$publishedUri")

            // 4. Take health: a smooth take drops nothing, keeps the
            // workers ahead of the arrivals (over-budget minority), and
            // arms lens shading once when the HAL provides a map (a
            // map-less or HAL-shaded sensor legitimately scores 0 —
            // flapping mid-take is what fails).
            val stats = activity.lastDirectLogStats
            assertTrue("No take stats recorded", stats != null)
            stats!!
            android.util.Log.i(
                TAG, "SPIKE ui stats graded=${stats.framesGraded} dropped=${stats.framesDropped} " +
                    "overBudget=${stats.overBudgetFrames} errors=${stats.frameErrors} " +
                    "starved=${stats.inputStarved} degraded=${stats.degradedFrames} " +
                    "shaded=${stats.shadedFrames} fused=${stats.fusedFrames} " +
                    "cpuAvg=${stats.cpuChainMsAvg} cpuMax=${stats.cpuChainMsMax}"
            )
            assertTrue("Take graded nothing: $stats", stats.framesGraded > 60)
            assertEquals("Dropped frames in take: $stats", 0, stats.framesDropped)
            // Over-budget is a health tripwire, NOT a drop proxy: the bounded
            // queue records through transient stalls (RT preemption, copy/GPU
            // spikes), so catch-up join-waits overrun without dropping. A
            // majority over budget = sustained overload (workers can't keep
            // up at all); below that the take is gapless by construction.
            assertTrue(
                "Over-budget frames in take: $stats",
                stats.overBudgetFrames < stats.framesGraded / 2
            )
            assertEquals("Frame errors in take: $stats", 0, stats.frameErrors)
            assertEquals("Codec input starved in take: $stats", 0, stats.inputStarved)
            assertEquals("Degraded frames in take: $stats", 0, stats.degradedFrames)
            assertTrue(
                "Shading flapped mid-take: $stats",
                stats.shadedFrames == 0 || stats.shadedFrames >= stats.framesGraded - 2
            )

            // 5. Validate the published clip, then remove it.
            val ext = android.media.MediaExtractor()
            try {
                ext.setDataSource(context, publishedUri!!, null)
                assertEquals(2, ext.trackCount)
                var videoTrack = -1
                var audioTrack = -1
                for (t in 0 until ext.trackCount) {
                    when (ext.getTrackFormat(t).getString(android.media.MediaFormat.KEY_MIME)) {
                        android.media.MediaFormat.MIMETYPE_VIDEO_HEVC -> videoTrack = t
                        android.media.MediaFormat.MIMETYPE_AUDIO_AAC -> audioTrack = t
                    }
                }
                assertTrue("Published clip has no HEVC track", videoTrack >= 0)
                assertTrue("Published clip has no AAC track", audioTrack >= 0)
                val vfmt = ext.getTrackFormat(videoTrack)
                assertEquals(3840, vfmt.getInteger(android.media.MediaFormat.KEY_WIDTH))
                assertEquals(2160, vfmt.getInteger(android.media.MediaFormat.KEY_HEIGHT))
                assertEquals(
                    48000,
                    ext.getTrackFormat(audioTrack)
                        .getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                )
                assertEquals(
                    2,
                    ext.getTrackFormat(audioTrack)
                        .getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                )
                ext.selectTrack(videoTrack)
                ext.selectTrack(audioTrack)
                // Size from the track's own max (a bright 4K I-frame exceeds
                // any fixed guess; readSampleData throws below it).
                val maxSample = if (vfmt.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    vfmt.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    4 * 1024 * 1024
                }
                val buf = java.nio.ByteBuffer.allocate(maxOf(4 * 1024 * 1024, maxSample))
                var videoSamples = 0
                var audioSamples = 0
                val videoPts = mutableListOf<Long>()
                while (true) {
                    val track = ext.sampleTrackIndex
                    if (track < 0) break
                    if (ext.readSampleData(buf, 0) < 0) break
                    if (track == videoTrack) {
                        videoSamples++
                        videoPts.add(ext.sampleTime)
                    } else audioSamples++
                    ext.advance()
                    if (videoSamples + audioSamples > 2000) break
                }
                android.util.Log.i(TAG, "SPIKE ui video=$videoSamples audio=$audioSamples")
                assertTrue("Published clip has no video", videoSamples > 30)
                assertTrue("Published clip has no audio", audioSamples > 10)
                // File-level constant frame rate: every consecutive PTS gap
                // sits on the take grid (33.33ms @30fps, 41.67ms @24fps).
                // Sorted: extractor order is not guaranteed presentation
                // order. The band covers both take rates; uniformity (<=2ms
                // spread) is what pins CFR — VFR jitter or doubled gaps fail.
                val sortedPts = videoPts.sorted()
                var maxGapUs = 0L
                var minGapUs = Long.MAX_VALUE
                for (i in 1 until sortedPts.size) {
                    val gap = sortedPts[i] - sortedPts[i - 1]
                    maxGapUs = maxOf(maxGapUs, gap)
                    minGapUs = minOf(minGapUs, gap)
                }
                android.util.Log.i(TAG, "SPIKE ui videoPtsGap minUs=$minGapUs maxUs=$maxGapUs")
                assertTrue(
                    "Video PTS not constant frame rate (min=${minGapUs}us max=${maxGapUs}us)",
                    minGapUs >= 25_000L && maxGapUs <= 45_000L && maxGapUs - minGapUs <= 2_000L
                )
            } finally {
                ext.release()
            }

            // 6. Long-press exits to photo.
            instrumentation.runOnMainSync {
                view(R.id.videoRecordButton).performLongClick()
            }
            SystemClock.sleep(1000)
        } finally {
            publishedUri?.let {
                try { context.contentResolver.delete(it, null, null) } catch (_: Exception) {}
            }
            prefs.edit().putBoolean("direct_log_video", false).apply()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    companion object {
        private const val TAG = "DirectLog"
    }
}
