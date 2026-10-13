// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The floating scope (histogram/waveform) must sit above the opaque capture
 * panel in every mode — never behind it. The old fixed 150dp video override
 * parked the scope inside the 232dp-minimum panel span, hiding it in video
 * portrait and peeking out "cut" in landscape.
 *
 * Opt-in: `-e rawlensVideoScopeSeat true`. Preseeds lens setup and grants
 * CAMERA via shell, like [VideoModeUiTest]. Leaves the app in photo mode.
 */
@RunWith(AndroidJUnit4::class)
class VideoScopeSeatTest {
    @Test fun scopeClearsPanelInVideoAndPhoto() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Pass -e rawlensVideoScopeSeat true to drive the app UI",
            args.getString("rawlensVideoScopeSeat") == "true"
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        context.getSharedPreferences("rawlens_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("lens_setup_complete", true)
            .putBoolean("viewfinder_histogram", true)
            .apply()

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            fun view(id: Int): View = activity.findViewById(id)
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!view(R.id.shutter).isEnabled && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(200)
            }
            assertTrue("Stills preview never became live", view(R.id.shutter).isEnabled)
            SystemClock.sleep(2000)

            // Photo seat first: the visible scope clears the panel top.
            assertScopeAbovePanel(activity, "photo")

            // Enter video; the seat re-settles above the panel (and above
            // the DirectLog slider row when it shows) after layout.
            instrumentation.runOnMainSync { view(R.id.videoRecordButton).performClick() }
            SystemClock.sleep(1500)
            instrumentation.runOnMainSync {
                assertTrue(
                    "Video chrome never appeared",
                    view(R.id.videoAudioMeter).visibility == View.VISIBLE
                )
            }
            assertScopeAbovePanel(activity, "video")

            // Back to photo; the seat restores exactly.
            instrumentation.runOnMainSync { view(R.id.videoRecordButton).performLongClick() }
            SystemClock.sleep(1500)
            assertScopeAbovePanel(activity, "photo-after-video")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /**
     * All three views are direct children of the root content frame, so
     * parent-relative [View.getTop]/[View.getBottom] are directly comparable.
     */
    private fun assertScopeAbovePanel(activity: MainActivity, where: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val histogram: View = activity.findViewById(R.id.histogramView)
            val waveform: View = activity.findViewById(R.id.waveformView)
            val scope = when {
                histogram.visibility == View.VISIBLE -> histogram
                waveform.visibility == View.VISIBLE -> waveform
                else -> null
            }
            assertTrue("No scope visible in $where", scope != null)
            scope!!
            // Laid-out bounds only: a mid-transition zero rect proves nothing.
            assertTrue("Scope not laid out in $where", scope.width > 0 && scope.height > 0)
            val panel: View = activity.findViewById(R.id.controlPanel)
            assertTrue(
                "Scope behind panel in $where: scopeBottom=${scope.bottom} panelTop=${panel.top}",
                scope.bottom <= panel.top
            )
            // (The Direct-Log VF slider this also cleared is gone: LOG gain
            // lives in the Settings Video tab now.)
        }
    }
}
