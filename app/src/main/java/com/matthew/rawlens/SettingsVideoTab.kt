// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.MainActivity.Companion.KEY_VIDEO_STAB_OUTPUT

import com.matthew.rawlens.MainActivity.Companion.KEY_VIDEO_BINNING

import com.matthew.rawlens.MainActivity.Companion.KEY_VIDEO_AUDIO

import com.matthew.rawlens.MainActivity.Companion.KEY_DIRECT_LOG_GAIN

import com.matthew.rawlens.MainActivity.Companion.KEY_VIDEO_DEMOSAIC

import com.matthew.rawlens.MainActivity.Companion.KEY_DIRECT_LOG


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

/** Settings → Video. Moved verbatim from MainActivity.showSettings (showVideoTab). */
internal fun MainActivity.showVideoSettings(p: SettingsPage) {
    val content = p.content
    fun sectionTitle(text: String): TextView = p.kit.sectionTitle(text)
    fun sectionDesc(text: String): TextView = p.kit.sectionDesc(text)
    fun polish() = p.kit.polish()
    fun markActive(active: Button) = p.markActive(active)
    val videoTab = p.tabs[2]
        content.removeAllViews()
        content.addView(sectionTitle("Direct Log"))
        content.addView(CheckBox(this).apply {
            text = "Direct Log video"
            setTextColor(getColor(R.color.text_primary))
            isChecked = directLogEnabled
            setOnCheckedChangeListener { _, enabled ->
                directLogEnabled = enabled
                lensPreferences().edit().putBoolean(KEY_DIRECT_LOG, enabled).apply()
                if (isVideoMode && !videoRecording) {
                    refreshVideoAudioDescription()
                    refreshVideoSensorInfo()
                    rawBadge.text = if (enabled) logBadgeLabel() else "CRS"
                    setStatus(if (enabled) "${directLogProfile().hudLabel} • HEVC 10-BIT" else "CRS • TYPE-7")
                } else {
                    setStatus(if (enabled) "LOG VIDEO ON" else "LOG VIDEO OFF")
                }
            }
        })
        content.addView(sectionDesc("HEVC 10-bit MP4 instead of RAW .crs: BT.709, S-Log3, or HLG in this tab or the LOG quick tile. Full sensor, live RAW viewfinder; sound follows the Sound toggle."))
        content.addView(Button(this).apply {
            fun refresh() {
                text = "LOG profile: ${directLogProfile().hudLabel}"
            }
            refresh()
            setOnClickListener {
                cycleDirectLogProfile()
                refresh()
            }
        })
        content.addView(Button(this).apply {
            fun refresh() {
                text = "LOG frame rate: ${directLogFpsPref()}FPS"
            }
            refresh()
            setOnClickListener {
                cycleDirectLogFps()
                refresh()
            }
        })
        content.addView(Button(this).apply {
            fun refresh() {
                val res = directLogResPref()
                text = "LOG resolution: ${res.width}x${res.height}"
            }
            refresh()
            setOnClickListener {
                cycleDirectLogRes()
                refresh()
            }
        })
        content.addView(Button(this).apply {
            fun refresh() {
                text = "LOG demosaic: ${videoDemosaic().label}"
            }
            refresh()
            setOnClickListener {
                val next = videoDemosaic().next()
                lensPreferences().edit().putString(KEY_VIDEO_DEMOSAIC, next.name).apply()
                refresh()
                setStatus("LOG DEMOSAIC • ${next.label} — NEXT TAKE")
            }
        })
        content.addView(sectionDesc("MHC (fused, one dispatch) is the realtime record path. RCD runs the 4-mode split path and drops to a few fps at 4K: A/B comparison only."))
        var logGainEv = directLogGainPref()
        val logGainLabel = TextView(this).apply {
            text = logGainText(logGainEv)
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        content.addView(logGainLabel)
        content.addView(SeekBar(this).apply {
            max = 60 // -2.0..+4.0 EV stops in 0.1 steps, shown as ×gain
            progress = ((logGainEv + 2f) * 10).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    logGainEv = progress / 10f - 2f
                    logGainLabel.text = logGainText(logGainEv)
                    // Live: the camera thread reads exposureEv per frame.
                    logRecorder?.exposureEv = logGainEv
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    lensPreferences().edit().putFloat(KEY_DIRECT_LOG_GAIN, logGainEv).apply()
                    setStatus("LOG GAIN • ${formatDirectLogGain(logGainEv)}")
                }
            })
        })
        content.addView(sectionDesc("Digital gain folded into the grade (×1.0 = neutral): brightens the log image without touching sensor exposure. Applies live, including mid-take."))
        content.addView(CheckBox(this).apply {
            text = "Video sound"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true)
            setOnCheckedChangeListener { button, enabled ->
                if (enabled == lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true)) return@setOnCheckedChangeListener
                toggleVideoSound()
                button.isChecked = lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true)
            }
        })
        content.addView(sectionTitle("RAW video"))
        content.addView(Button(this).apply {
            fun refresh() {
                text = "CRS crop: ${videoCropShort()}"
            }
            refresh()
            setOnClickListener {
                cycleVideoCrop()
                refresh()
            }
        })
        content.addView(sectionDesc("RAW .crs container crop. N/A in Direct Log (full sensor)."))
        content.addView(CheckBox(this).apply {
            text = "Binning (half resolution)"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(KEY_VIDEO_BINNING, false)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(KEY_VIDEO_BINNING, enabled).apply()
                if (isVideoMode && !videoRecording) refreshVideoSensorInfo()
                setStatus(if (enabled) "BINNING ON — NEXT TAKE" else "BINNING OFF")
            }
        })
        content.addView(sectionDesc("2x2 sensor binning for smaller, cleaner .crs: averaged R/B and diamond-averaged green. Applies to the next take; N/A in Direct Log."))
        content.addView(sectionTitle("Locks on record"))
        content.addView(CheckBox(this).apply {
            text = "Lock exposure on record"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(VideoRecordLocks.KEY_LOCK_EXPOSURE, false)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(VideoRecordLocks.KEY_LOCK_EXPOSURE, enabled).apply()
                setStatus(if (enabled) "AE LOCK ON RECORD" else "AE CONTINUOUS IN TAKES")
            }
        })
        content.addView(CheckBox(this).apply {
            text = "Lock white balance on record"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(VideoRecordLocks.KEY_LOCK_WB, false)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(VideoRecordLocks.KEY_LOCK_WB, enabled).apply()
                setStatus(if (enabled) "AWB LOCK ON RECORD" else "AWB CONTINUOUS IN TAKES")
            }
        })
        content.addView(CheckBox(this).apply {
            text = "Lock focus on record"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(VideoRecordLocks.KEY_LOCK_FOCUS, false)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(VideoRecordLocks.KEY_LOCK_FOCUS, enabled).apply()
                setStatus(if (enabled) "AF LOCK ON RECORD" else "AF CONTINUOUS IN TAKES")
            }
        })
        content.addView(sectionDesc("Freeze the preview AE/AWB/AF at REC (manual tiles always hold). Off = continuous auto during takes. SS/ISO/WB/focus/EV tiles stay live before and during takes."))
        content.addView(sectionTitle("Stabilization"))
        content.addView(Button(this).apply {
            fun refresh() {
                text = "Stabilized output: ${stabOutputMode().label.uppercase(Locale.US)}"
            }
            refresh()
            setOnClickListener {
                val next = stabOutputMode().next()
                lensPreferences().edit().putString(KEY_VIDEO_STAB_OUTPUT, next.name).apply()
                refresh()
                setStatus("STAB OUTPUT • ${next.label.uppercase(Locale.US)} — NEXT TAKE")
            }
        })
        content.addView(sectionDesc("Post-record gyro stabilization (Direct Log only): keep the original take, the stabilized re-encode, or both. The warp pass runs on-device right after stop; any failure keeps the original."))
        markActive(videoTab)
        polish()
}
