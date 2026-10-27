// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoFormatSwitchTest {
    @Test fun repeatedSwitchesRefreshBadgeControlsAndCropGuide() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rawlensVideoModeUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(
            instrumentation.targetContext, MainActivity::class.java
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.isVideoMode = true
                activity.videoCrop = VideoCrop.SCOPE_2_39
                repeat(3) {
                    activity.setDirectLogMode(true)
                    // Simulate the shared stills refresh that used to overwrite LOG with CRS.
                    activity.refreshCaptureFormatControl()
                    assertTrue(activity.findViewById<TextView>(R.id.rawBadge).text.startsWith("LOG"))
                    assertNull(activity.findViewById<CameraGuideOverlay>(R.id.guideOverlay).videoCrop)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.videoBinningQuick).visibility)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.releaseQuick).visibility)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videoFormatQuickRow).visibility)
                    activity.setDirectLogMode(false)
                    assertEquals("CRS", activity.findViewById<TextView>(R.id.rawBadge).text.toString())
                    assertEquals(VideoCrop.SCOPE_2_39, activity.findViewById<CameraGuideOverlay>(R.id.guideOverlay).videoCrop)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videoBinningQuick).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.videoFormatQuickRow).visibility)
                }
                activity.videoRecording = true
                activity.setDirectLogMode(true)
                assertFalse(activity.directLogEnabled)
                activity.videoRecording = false
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
