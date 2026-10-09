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
 * Overlay counter proof for Direct Log video: record a real take through
 * the app, read the on-screen debug overlay mid-roll, and pin the output
 * FPS it shows. The counter is windowed from the first muxed sample, so a
 * healthy take reads the muxed rate (~30fps), not samples/wall-clock
 * (which blends ~1.5s of camera/encoder bring-up and reads ~24fps on a
 * 6s take — the regression this guards).
 *
 * Take health (zero drops) is asserted alongside: on a device too slow to
 * hold the rate, drops explain a low readout and fail with the right
 * message instead of blaming the counter.
 *
 * Opt-in: `-e rawlensDirectLogHud true`. Requires completed lens setup
 * (preseeded) and CAMERA grant via shell. Deletes the published clip.
 */
@RunWith(AndroidJUnit4::class)
class DirectLogHudFpsDeviceTest {
    @Test fun overlayShowsMuxedRateMidRoll() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensDirectLogHud true to drive the app UI",
            args.getString("rawlensDirectLogHud") == "true"
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
        val prefs = context.getSharedPreferences("rawlens_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("lens_setup_complete", true).apply()
        // Keys mirror MainActivity.KEY_* (private companion).
        prefs.edit().putBoolean("direct_log_video", true).apply()
        prefs.edit().putBoolean("video_audio", true).apply()
        prefs.edit().putInt("direct_log_fps", 30).apply()
        prefs.edit().putString("video_demosaic", "MHC").apply()

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

            click(view(R.id.videoRecordButton))
            SystemClock.sleep(500)
            var badge = ""
            instrumentation.runOnMainSync {
                badge = (activity.findViewById<TextView>(R.id.rawBadge)).text.toString()
            }
            assertEquals("LOG • 709", badge)

            click(view(R.id.shutter))
            val takeStartWall = System.currentTimeMillis()
            SystemClock.sleep(2000)
            var hudVisible = false
            instrumentation.runOnMainSync {
                hudVisible = view(R.id.videoHudTop).visibility == View.VISIBLE
            }
            assertTrue("Video HUD never appeared", hudVisible)
            // Roll past bring-up: ~5s of muxed output behind the readout.
            SystemClock.sleep(4500)
            var overlay = ""
            instrumentation.runOnMainSync {
                overlay = (activity.findViewById<TextView>(R.id.videoDebugOverlay)).text.toString()
            }
            android.util.Log.i(TAG, "SPIKE hud overlay=[$overlay]")
            val fps = Regex("""(\d+\.\d)FPS""").find(overlay)?.groupValues?.get(1)?.toFloatOrNull()
            assertTrue("No FPS readout in overlay: [$overlay]", fps != null)
            assertTrue("Overlay counters missing: [$overlay]", "MEM" in overlay && "IO" in overlay && "ENC" in overlay)
            // 26fps splits fixed (~30, muxed rate) from startup-blended
            // (~24 on this roll length); drops==0 below proves the take
            // itself held the rate, so a low readout blames the counter.
            assertTrue("Overlay FPS $fps below 26 mid-roll: [$overlay]", fps!! >= 26f)

            click(view(R.id.shutter))
            val resolver = context.contentResolver
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
                        if (cursor.getLong(2) * 1000L >= takeStartWall - 10_000) {
                            publishedUri = android.net.Uri.withAppendedPath(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                cursor.getLong(0).toString()
                            )
                        }
                    }
                }
                if (publishedUri != null) break
                SystemClock.sleep(1000)
            }
            assertTrue("No _LOG709.mp4 published to MediaStore", publishedUri != null)

            val stats = activity.lastDirectLogStats
            assertTrue("No take stats recorded", stats != null)
            stats!!
            android.util.Log.i(
                TAG, "SPIKE hud stats graded=${stats.framesGraded} dropped=${stats.framesDropped} " +
                    "overBudget=${stats.overBudgetFrames} errors=${stats.frameErrors}"
            )
            assertTrue("Take graded nothing: $stats", stats.framesGraded > 60)
            assertEquals("Dropped frames in take: $stats", 0, stats.framesDropped)
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
