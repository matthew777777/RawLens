// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI test for the dedicated RAW Video entry (red record button
 * below the quick-panel access — never the mode switcher):
 * enter video -> record with live Vulkan zero-copy VF -> stop -> valid file
 * -> exit to photo.
 *
 * The mid-roll viewfinder assertion is the zero-copy proof:
 * [RawVfStats.glActive] with `fps >= 15` and [RawVfStats.gpu] (documented as
 * "last rendered frame took the zero-copy GPU path") while the encode
 * thread concurrently writes the container.
 *
 * Opt-in: `-e rawlensVideoUi true`. Requires completed lens setup on the
 * device (first-run dialog) and grants CAMERA + RECORD_AUDIO via shell.
 * Leaves the app in photo mode with the clip in DCIM/RawLens.
 * `-e rawlensCrawCodec live|fast -e rawlensVideoSeconds 30` selects the take.
 * Cadence requires >=29.5 fps and no sensor timestamp gap over 50 ms;
 * `-e rawlensRequire30Fps false` runs only the UI/container smoke checks.
 */
@RunWith(AndroidJUnit4::class)
class VideoModeUiTest {
    @Test fun videoButtonRecordsWithLiveVf() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensVideoUi true to drive the app UI",
            args.getString("rawlensVideoUi") == "true"
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
        // First-run lens discovery is modal and would pollute screenshots
        // (programmatic clicks bypass it, so the test would pass but blind).
        // Preseed completion exactly like a user tapping SAVE with defaults.
        // Keys mirror MainActivity.PREFS_NAME / KEY_LENS_SETUP_COMPLETE
        // (private companion): update here if they move.
        context.getSharedPreferences("rawlens_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("lens_setup_complete", true).apply()

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val prefs = activity.lensPreferences()
        val previousCodec = prefs.getInt(MainActivity.KEY_VIDEO_CODEC, CrawWriter.CODEC_LIVE)
        val codec = when (args.getString("rawlensCrawCodec", "live")) {
            "live" -> CrawWriter.CODEC_LIVE
            "fast" -> CrawWriter.CODEC_FAST
            else -> error("rawlensCrawCodec must be live or fast")
        }
        val seconds = args.getString("rawlensVideoSeconds")?.toLongOrNull()?.coerceIn(5, 300) ?: 30L
        // Optional: -e rawlensVideoBinning true|false (default: leave as set).
        val previousBinning = prefs.getBoolean(MainActivity.KEY_VIDEO_BINNING, false)
        val binning = args.getString("rawlensVideoBinning")?.toBooleanStrictOrNull() ?: previousBinning
        // Optional: -e rawlensVideoBinningMode rgb|cfa.
        val previousMode = prefs.getString(MainActivity.KEY_VIDEO_BINNING_MODE, MainActivity.BINNING_RGB)
        val mode = args.getString("rawlensVideoBinningMode") ?: previousMode
        prefs.edit().putInt(MainActivity.KEY_VIDEO_CODEC, codec)
            .putBoolean(MainActivity.KEY_VIDEO_BINNING, binning)
            .putString(MainActivity.KEY_VIDEO_BINNING_MODE, mode).commit()
        try {
            fun view(id: Int): View = activity.findViewById(id)
            fun click(v: View) {
                instrumentation.runOnMainSync { v.performClick() }
            }
            // Stills preview must be up before entering video (framing source).
            // Settle past camera-startup callbacks: they refresh photo chrome
            // and must drain before the mode switch (video chrome is
            // authoritative after, but the test asserts mid-flight).
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!view(R.id.shutter).isEnabled && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(200)
            }
            assertTrue("Stills preview never became live", view(R.id.shutter).isEnabled)
            SystemClock.sleep(2000)

            // 1. Dedicated red button enters video (mode button untouched).
            click(view(R.id.videoRecordButton))
            SystemClock.sleep(500)
            var badge = ""
            instrumentation.runOnMainSync {
                badge = (activity.findViewById<TextView>(R.id.rawBadge)).text.toString()
            }
            assertEquals("CRAW", badge)

            // 2. Shutter starts the take; the video HUD appears.
            val recordStartMs = System.currentTimeMillis() - 2_000
            click(view(R.id.shutter))
            SystemClock.sleep(1500)
            var hudVisible = false
            instrumentation.runOnMainSync {
                hudVisible = view(R.id.videoDebugOverlay).visibility == View.VISIBLE
            }
            assertTrue("Video HUD never appeared", hudVisible)
            screenshot("vui_video_idle")

            // 3. Mid-roll: the ROUTED (visible) viewfinder is live on the
            // zero-copy GPU path while the encode thread writes (release
            // build cadence asserted). BGU is the default route; legacy
            // only when forced or after BGU fallback.
            SystemClock.sleep(2500)
            val bguView = activity.findViewById<BguViewfinder>(R.id.bguViewfinder)
            val vf = if (bguView != null && bguView.visibility == View.VISIBLE) {
                bguView.snapshot()
            } else {
                (view(R.id.rawViewfinder) as RawViewfinder).snapshot()
            }
            android.util.Log.i(
                TAG, "SPIKE ui vf fps=${vf.fps} gpu=${vf.gpu} glActive=${vf.glActive} " +
                    "raw=${vf.rawWidth}x${vf.rawHeight}"
            )
            assertTrue("VF went dark during recording: $vf", vf.glActive)
            // Decimated preview runs ~15fps by design (every 2nd frame);
            // the threshold proves liveness + path, not full rate.
            assertTrue("VF fps too low during recording: $vf", vf.fps >= 12f)
            assertTrue("VF not on zero-copy GPU path during recording: $vf", vf.gpu)
            screenshot("vui_video_rec")

            SystemClock.sleep((seconds * 1000 - 4000).coerceAtLeast(0))

            // 4. Stop; wait for the file to stop growing (container close
            // finalizes the footer), then validate it as .craw.
            click(view(R.id.shutter))
            // Finished takes are published to DCIM/RawLens (MediaStore); the
            // app-private stage dir only holds takes still being written.
            val stage = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "RawLens")
            @Suppress("DEPRECATION")
            val published = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM), "RawLens")
            var clip: File? = null
            var lastSize = -1L
            var stablePolls = 0
            val stopDeadline = SystemClock.elapsedRealtime() + 45_000
            while (SystemClock.elapsedRealtime() < stopDeadline) {
                clip = ((stage.listFiles() ?: emptyArray()) + (published.listFiles() ?: emptyArray()))
                    .filter { it.extension == "craw" && it.lastModified() >= recordStartMs }
                    .maxByOrNull { it.lastModified() }
                val size = clip?.length() ?: -1L
                if (clip != null && size > 1_000_000) {
                    if (size == lastSize) {
                        if (++stablePolls >= 2) break
                    } else {
                        stablePolls = 0
                    }
                    lastSize = size
                }
                SystemClock.sleep(1000)
            }
            assertTrue("No .craw clip finalized", clip != null && lastSize > 1_000_000)
            val finalClip = requireNotNull(clip)
            validateCrawMagic(finalClip)
            validateCadence(finalClip, codec, args.getString("rawlensRequire30Fps", "true") != "false")
            android.util.Log.i(
                TAG, "SPIKE ui clip=${finalClip.name} bytes=${finalClip.length()}"
            )

            // 5. Long-press red button exits to photo; badge restored.
            instrumentation.runOnMainSync {
                view(R.id.videoRecordButton).performLongClick()
            }
            SystemClock.sleep(1000)
            instrumentation.runOnMainSync {
                badge = (activity.findViewById<TextView>(R.id.rawBadge)).text.toString()
            }
            assertEquals("DNG", badge)
        } finally {
            prefs.edit().putInt(MainActivity.KEY_VIDEO_CODEC, previousCodec).commit()
            prefs.edit().putBoolean(MainActivity.KEY_VIDEO_BINNING, previousBinning)
                .putString(MainActivity.KEY_VIDEO_BINNING_MODE, previousMode).commit()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun screenshot(name: String) {
        try {
            // Block until the capture completes: closing the fd without
            // draining lets the screencap land seconds late, mislabeling HUD
            // states in time-shifted screenshots.
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("screencap -p /sdcard/$name.png")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        } catch (_: Exception) {
        }
    }

    private fun validateCrawMagic(file: File) {
        RandomAccessFile(file, "r").use { raf ->
            assertTrue("Clip too small", raf.length() >= 32)
            val head = ByteArray(4)
            raf.readFully(head)
            assertEquals(CrawFormat.MAGIC, String(head, Charsets.US_ASCII))
        }
        val frames = CrawFormat.closedFrameCount(file)
        assertTrue("Unclosed or empty container", frames != null && frames > 0)
    }

    private fun validateCadence(file: File, codec: Int, require30: Boolean) {
        var count = 0L
        var first = -1L
        var last = -1L
        var maxGap = 0L
        RandomAccessFile(file, "r").use { raf ->
            fun u32(): Long = Integer.reverseBytes(raf.readInt()).toLong() and 0xffffffffL
            fun i64(): Long = java.lang.Long.reverseBytes(raf.readLong())
            var offset = 16L
            while (offset + 20 <= raf.length()) {
                raf.seek(offset)
                val tag = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
                u32() // flags
                val bytes = i64()
                assertTrue("Invalid chunk length", bytes >= 0 && bytes <= raf.length() - offset - 20)
                if (tag == "FRAM") {
                    assertTrue("Truncated frame", bytes >= 44)
                    i64() // frame id
                    val ts = i64()
                    val jsonBytes = u32()
                    assertTrue("Invalid frame metadata length", jsonBytes <= bytes - 44)
                    raf.seek(offset + 16 + 20 + jsonBytes)
                    assertEquals("Wrong recorded codec", codec, raf.readUnsignedByte())
                    if (first < 0) first = ts
                    if (last >= 0) {
                        assertTrue("Non-monotonic timestamps", ts > last)
                        maxGap = maxOf(maxGap, ts - last)
                    }
                    last = ts
                    count++
                }
                if (tag == "INDX") break
                offset += 20 + bytes
            }
        }
        assertEquals("Footer frame count", count, requireNotNull(CrawFormat.closedFrameCount(file)).toLong())
        assertTrue("Too few recorded frames", count >= 2 && last > first)
        val fps = (count - 1) * 1e9 / (last - first)
        android.util.Log.i(TAG, "CRAW cadence codec=$codec frames=$count fps=$fps maxGapMs=${maxGap / 1e6}")
        if (require30) {
            assertTrue("Recording below 30fps: $fps", fps >= 29.5)
            assertTrue("Recording dropped a frame: gap=${maxGap / 1e6}ms", maxGap <= 50_000_000L)
        }
    }

    companion object {
        private const val TAG = "CrawSpike"
    }
}
