// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.MainActivity.Companion.KEY_FACE_PRIORITY

import com.matthew.rawlens.MainActivity.Companion.KEY_SEQ_TRIO_ENABLED

import com.matthew.rawlens.MainActivity.Companion.KEY_HDR_BRACKET_STOPS

import com.matthew.rawlens.MainActivity.Companion.KEY_HDR_SAVE_EACH_BRACKET

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

/** Settings → Exposure. Moved verbatim from MainActivity.showSettings (showExposureTab). */
internal fun MainActivity.showExposureSettings(p: SettingsPage) {
    val content = p.content
    fun sectionTitle(text: String): TextView = p.kit.sectionTitle(text)
    fun sectionDesc(text: String): TextView = p.kit.sectionDesc(text)
    fun polish() = p.kit.polish()
    fun markActive(active: Button) = p.markActive(active)
    val exposureTab = p.tabs[3]
        rawZslSettingsStatus = null
        sidecarSettingsStatus = null
        content.removeAllViews()
        content.addView(sectionTitle("Two-exposure RAW HDR (experimental)"))
        content.addView(CheckBox(this).apply {
            text = "Experimental two-exposure RAW HDR"
            setTextColor(getColor(R.color.text_primary))
            isChecked = dualRawEnabled
            isEnabled = controller.supportsDualRaw() || dualRawEnabled
            setOnCheckedChangeListener { button, enabled ->
                if (enabled && !controller.supportsDualRaw()) {
                    button.isChecked = false
                    setStatus("RAW HDR TEST NEEDS CAMERA 0")
                } else {
                    dualRawEnabled = enabled
                    setStatus(if (enabled) "TWO-EXPOSURE RAW HDR ON" else "TWO-EXPOSURE RAW HDR OFF")
                    updateQuickControls()
                }
            }
        })
        content.addView(TextView(this).apply {
            text = "Camera 0 only. Saves a 16-bit DNG from two sequential exposures, " +
                "using the short exposure to recover clipped highlights. " +
                "Does not reduce shadow noise. No alignment or motion correction; " +
                "keep the phone and scene still. Overrides the release mode " +
                "while enabled. Resets when the app restarts or you change cameras."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        content.addView(sectionTitle("HDR brackets"))
        content.addView(sectionDesc("Merged HDR saves one DNG by default; optionally keep every bracket. Frame-level debug output lives under the Debug tab."))
        content.addView(CheckBox(this).apply {
            text = "HDR: save all 3 brackets as separate DNGs (do not merge)"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(KEY_HDR_SAVE_EACH_BRACKET, false)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(KEY_HDR_SAVE_EACH_BRACKET, enabled).apply()
                setStatus(if (lensPreferences().getBoolean(KEY_HDR_SAVE_DEBUG_FRAMES, false))
                    "HDR DBG ×3"
                else if (enabled) "HDR ×3 DNG" else "HDR MERGED")
                preloadFlowNetForMergedHdr()
                updateQuickControls()
            }
        })
        content.addView(CheckBox(this).apply {
            text = "HDR bracket range: −4 / 0 / +4 EV"
            setTextColor(getColor(R.color.text_primary))
            isChecked = hdrBracketStops() == 4
            setOnCheckedChangeListener { _, enabled ->
                val stops = if (enabled) 4 else 2
                lensPreferences().edit().putInt(KEY_HDR_BRACKET_STOPS, stops).apply()
                setStatus("HDR ±${stops} EV")
                updateQuickControls()
            }
        })
        content.addView(CheckBox(this).apply {
            text = "Sequential trio 0 / −2 / 0 EV (shadow averaging + highlights)"
            setTextColor(getColor(R.color.text_primary))
            isChecked = seqTrioEnabled()
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(KEY_SEQ_TRIO_ENABLED, enabled).apply()
                setStatus(if (enabled) "SEQ TRIO 0/−2/0 ON" else "SEQ TRIO OFF")
                updateQuickControls()
            }
        })
        content.addView(TextView(this).apply {
            text = "Two 0 EV frames average shadows (~3 dB); the −2 EV frame " +
                "rescues highlights. Same ISO, lightweight local alignment. " +
                "Needs the HDR toggle on; overrides the ±2/±4 bracket range."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        content.addView(sectionTitle("Face priority"))
        content.addView(sectionDesc("HAL face detection steers AF/AE to the largest face (sand box). Tap targets override faces; manual sensor modes ignore the regions but still show boxes."))
        content.addView(CheckBox(this).apply {
            text = "Face-priority AF/AE"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(KEY_FACE_PRIORITY, false)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(KEY_FACE_PRIORITY, enabled).apply()
                controller.setFacePriorityEnabled(enabled)
                setStatus(
                    if (!enabled) "FACE OFF"
                    else if (controller.isFaceDetectSupported()) "FACE ON" else "FACE N/A"
                )
            }
        })
        markActive(exposureTab)
        polish()
}
