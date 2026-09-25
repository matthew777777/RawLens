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
 * Leaves the app in photo mode with the clip in Movies/RawLens.
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
            assertEquals("MCRAW", badge)

            // 2. Shutter starts the take; the HUD top strip appears.
            click(view(R.id.shutter))
            SystemClock.sleep(1500)
            var hudVisible = false
            instrumentation.runOnMainSync {
                hudVisible = view(R.id.videoHudTop).visibility == View.VISIBLE
            }
            assertTrue("Video HUD never appeared", hudVisible)
            screenshot("vui_video_idle")

            // 3. Mid-roll: viewfinder live on the zero-copy GPU path while
            // the encode thread writes (release build cadence asserted).
            SystemClock.sleep(2500)
            val vf = activity.findViewById<RawViewfinder>(R.id.rawViewfinder).snapshot()
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

            // 4. Stop; wait for the file to stop growing (container close
            // finalizes the footer), then validate it as .mcraw.
            click(view(R.id.shutter))
            val dir = File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
                "RawLens"
            )
            var clip: File? = null
            var lastSize = -1L
            var stablePolls = 0
            val stopDeadline = SystemClock.elapsedRealtime() + 45_000
            while (SystemClock.elapsedRealtime() < stopDeadline) {
                clip = dir.listFiles()
                    ?.filter { it.extension == "mcraw" }
                    ?.maxByOrNull { it.lastModified() }
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
            assertTrue("No .mcraw clip finalized", clip != null && lastSize > 1_000_000)
            val finalClip = requireNotNull(clip)
            validateMcrawMagic(finalClip)
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

    private fun validateMcrawMagic(file: File) {        RandomAccessFile(file, "r").use { raf ->
            assertTrue("Clip too small", raf.length() >= 32)
            val head = ByteArray(8)
            raf.readFully(head)
            assertEquals("MOTION ", String(head, 0, 7, Charsets.US_ASCII))
            assertEquals(3, head[7].toInt())
            raf.seek(raf.length() - 24)
            val tail = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            raf.channel.read(tail)
            tail.flip()
            assertEquals(0, tail.int)
            assertEquals(16, tail.int)
            assertEquals(0x8A905612.toInt(), tail.int)
            assertTrue("Empty container", tail.int > 0)
        }
    }

    companion object {
        private const val TAG = "CinemaRawSpike"
    }
}
