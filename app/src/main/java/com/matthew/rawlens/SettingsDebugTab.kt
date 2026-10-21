// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.MainActivity.Companion.KEY_LOGCAT_FILE

import com.matthew.rawlens.MainActivity.Companion.KEY_RAW_VF_DEBUG_OVERLAY

import com.matthew.rawlens.MainActivity.Companion.KEY_DEBUG_OVERLAY

import com.matthew.rawlens.MainActivity.Companion.KEY_HDR_SAVE_DEBUG_FRAMES


import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.net.Uri
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.particlesdevs.photoncamera.processing.ml.FlowNetNcnnProcessor
import com.particlesdevs.photoncamera.processing.ml.RawNindNcnnProcessor
import java.io.File
import java.util.Locale
import android.os.Build
import android.os.SystemClock

/** Settings → Debug. Moved verbatim from MainActivity.showSettings (showDebugTab). */
internal fun MainActivity.showDebugSettings(p: SettingsPage) {
    val content = p.content
    fun sectionTitle(text: String): TextView = p.kit.sectionTitle(text)
    fun polish() = p.kit.polish()
    fun markActive(active: Button) = p.markActive(active)
    val debugTab = p.tabs[7]
        rawZslSettingsStatus = null
        sidecarSettingsStatus = null
        content.removeAllViews()
        content.addView(sectionTitle("HDR debug frames"))
        content.addView(CheckBox(this).apply {
            text = "Save debug HDR frames"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(KEY_HDR_SAVE_DEBUG_FRAMES, false)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(KEY_HDR_SAVE_DEBUG_FRAMES, enabled).apply()
                setStatus(if (enabled) "HDR DBG ×3"
                    else "HDR DBG OFF")
                preloadFlowNetForMergedHdr()
                updateQuickControls()
            }
        })
        content.addView(TextView(this).apply {
            text = "Debug saves all 3 source DNGs and a merged DNG with matching filenames, plus JPEG when selected. Overrides do not merge; uses extra storage."
            setTextColor(getColor(R.color.text_primary))
        })
        content.addView(sectionTitle("Camera diagnostics"))
        // The two debug overlays stack on the same corner: enabling one
        // switches the other off. The guard stops the programmatic
        // uncheck from ping-ponging back through the other listener.
        var overlayExclusionGuard = false
        var cameraDebugCheckBox: CheckBox? = null
        var rawVfDebugCheckBox: CheckBox? = null
        content.addView(CheckBox(this).apply {
            text = "Camera2 debug overlay"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(KEY_DEBUG_OVERLAY, false)
            cameraDebugCheckBox = this
            setOnCheckedChangeListener { _, enabled ->
                if (overlayExclusionGuard) return@setOnCheckedChangeListener
                lensPreferences().edit().putBoolean(KEY_DEBUG_OVERLAY, enabled).apply()
                debugOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
                if (enabled) {
                    overlayExclusionGuard = true
                    try {
                        lensPreferences().edit().putBoolean(KEY_RAW_VF_DEBUG_OVERLAY, false).apply()
                        rawVfDebugCheckBox?.isChecked = false
                        rawVfDebugOverlay.visibility = View.GONE
                    } finally {
                        overlayExclusionGuard = false
                    }
                    debugOverlay.post { positionWholeRotatedPanels() }
                }
            }
        })
        content.addView(CheckBox(this).apply {
            text = "RAW viewfinder debug overlay"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(KEY_RAW_VF_DEBUG_OVERLAY, false)
            rawVfDebugCheckBox = this
            setOnCheckedChangeListener { _, enabled ->
                if (overlayExclusionGuard) return@setOnCheckedChangeListener
                lensPreferences().edit().putBoolean(KEY_RAW_VF_DEBUG_OVERLAY, enabled).apply()
                rawVfDebugOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
                if (enabled) {
                    overlayExclusionGuard = true
                    try {
                        lensPreferences().edit().putBoolean(KEY_DEBUG_OVERLAY, false).apply()
                        cameraDebugCheckBox?.isChecked = false
                        debugOverlay.visibility = View.GONE
                    } finally {
                        overlayExclusionGuard = false
                    }
                    rawVfDebugOverlay.post { positionWholeRotatedPanels() }
                }
            }
        })
        content.addView(sectionTitle("App log"))
        content.addView(CheckBox(this).apply {
            text = "Save app log to file (survives crashes)"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(KEY_LOGCAT_FILE, true)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(KEY_LOGCAT_FILE, enabled).apply()
                if (enabled) LogcatFileWriter.start(this@showDebugSettings)
                else LogcatFileWriter.stop()
            }
        })
        content.addView(Button(this).apply {
            text = "Share latest log"
            setOnClickListener { shareLatestLog() }
        })
        content.addView(TextView(this).apply {
            text = LogcatFileWriter.statusLine()
            setTextColor(getColor(R.color.text_primary))
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(TextView(this).apply {
            text = "The app's own logcat streams to its private folder and is " +
                "mirrored to Download/RawLens/logs/ by itself (no permission " +
                "needed, no taps): 8 MB sessions, newest 5 kept, crashes " +
                "appended before the process dies. After a crash on launch, " +
                "the next start re-exports that session and offers to share it."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        markActive(debugTab)
        polish()
}
