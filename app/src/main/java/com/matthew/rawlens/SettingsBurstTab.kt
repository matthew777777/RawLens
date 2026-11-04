// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.MainActivity.Companion.KEY_ZSL_HYBRID_TOPUP

import com.matthew.rawlens.MainActivity.Companion.MAX_RAW_ZSL_FRAMES

import com.matthew.rawlens.MainActivity.Companion.MIN_RAW_ZSL_FRAMES

import com.matthew.rawlens.MainActivity.Companion.DEFAULT_RAW_ZSL_FRAME_COUNT

import com.matthew.rawlens.MainActivity.Companion.KEY_RAW_ZSL_FRAME_COUNT


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

/** Settings → Burst. Moved verbatim from MainActivity.showSettings (showBurstTab). */
internal fun MainActivity.showBurstSettings(p: SettingsPage) {
    val content = p.content
    fun sectionTitle(text: String): TextView = p.kit.sectionTitle(text)
    fun sectionDesc(text: String): TextView = p.kit.sectionDesc(text)
    fun settingsButton(label: String, active: Boolean = false): Button = p.kit.settingsButton(label, active)
    fun settingsCheck(label: String, checked: Boolean): SettingsSwitch = p.kit.settingsCheck(label, checked)
    fun polish() = p.kit.polish()
    fun markActive(active: Button) = p.markActive(active)
    val burstTab = p.tabs[4]
        content.removeAllViews()
        content.addView(sectionTitle("Offline burst merges"))
        content.addView(sectionDesc(
            "Staged IMG_<ts>_BURST.craw takes merge here later via MFSR, LK-SR or HDR+, " +
                "to DNG, JPEG, or both. RAW Video never lists."
        ))
        content.addView(settingsButton("OPEN MERGES").apply {
            setOnClickListener { openMerges() }
        })
        content.addView(SettingsSwitch(this).apply {
            text = "Save ZSL bursts to .craw for offline merge"
            setTextColor(getColor(R.color.text_primary))
            isChecked = burstCrawEnabled
            setOnCheckedChangeListener { _, enabled ->
                if (enabled != burstCrawEnabled) setBurstCrawEnabled(enabled)
            }
        })
        content.addView(sectionDesc(
            "When on, the shutter dumps the whole ZSL ring to one " +
                "IMG_<ts>_BURST.craw instead of merging immediately."
        ))
        content.addView(sectionTitle("Zero shutter lag"))
        content.addView(SettingsSwitch(this).apply {
            text = "RAW zero shutter lag"
            setTextColor(getColor(R.color.text_primary))
            isChecked = captureExposureMode == CaptureExposureMode.ZSL
            setOnCheckedChangeListener { _, enabled ->
                // Do not update the old ZSL preference independently: doing so left the
                // Settings UI enabled while the capture mode restored AUTO on reopen.
                if (enabled != (captureExposureMode == CaptureExposureMode.ZSL)) {
                    applyCaptureExposureMode(
                        if (enabled) CaptureExposureMode.ZSL else CaptureExposureMode.AUTO
                    )
                }
            }
        })
        content.addView(sectionDesc("Keeps full-resolution RAW frames in camera memory. Unsupported devices fall back to normal RAW."))
        var selectedFrameCount = lensPreferences()
            .getInt(KEY_RAW_ZSL_FRAME_COUNT, DEFAULT_RAW_ZSL_FRAME_COUNT)
            .coerceIn(MIN_RAW_ZSL_FRAMES, MAX_RAW_ZSL_FRAMES)
        val frameCountLabel = TextView(this).apply {
            text = rawZslFrameCountText(selectedFrameCount)
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        content.addView(frameCountLabel)
        content.addView(SettingsSlider(this).apply {
            max = MAX_RAW_ZSL_FRAMES - MIN_RAW_ZSL_FRAMES
            progress = selectedFrameCount - MIN_RAW_ZSL_FRAMES
            setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                override fun onProgressChanged(
                    seekBar: SettingsSlider,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    selectedFrameCount = progress + MIN_RAW_ZSL_FRAMES
                    frameCountLabel.text = rawZslFrameCountText(selectedFrameCount)
                }

                override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit

                override fun onStopTrackingTouch(seekBar: SettingsSlider) {
                    lensPreferences().edit()
                        .putInt(KEY_RAW_ZSL_FRAME_COUNT, selectedFrameCount)
                        .apply()
                    controller.setRawZslFrameCount(selectedFrameCount)
                    setStatus("BURST / MERGE ×$selectedFrameCount")
                }
            })
        })
        var hybridTopup = lensPreferences().getBoolean(KEY_ZSL_HYBRID_TOPUP, true)
        content.addView(Button(this).apply {
            text = zslHybridTopupText(hybridTopup)
            setOnClickListener {
                hybridTopup = !hybridTopup
                lensPreferences().edit().putBoolean(KEY_ZSL_HYBRID_TOPUP, hybridTopup).apply()
                controller.setZslHybridTopup(hybridTopup)
                text = zslHybridTopupText(hybridTopup)
                setStatus(if (hybridTopup) "ZSL TOP-UP ON" else "ZSL TOP-UP OFF")
            }
        })
        rawZslSettingsStatus = TextView(this).apply {
            text = rawZslSettingsText(rawZslStatus)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(dp(12), 0, dp(12), dp(16))
        }.also(content::addView)
        content.addView(sectionTitle("Burst gyro sidecars"))
        sidecarSettingsStatus = TextView(this).apply {
            text = sidecarFolderText()
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
        }.also(content::addView)
        content.addView(Button(this).apply {
            text = "Save sidecars next to DNGs (choose photo folder)"
            setOnClickListener { pickSidecarFolder() }
        })
        content.addView(TextView(this).apply {
            text = "Android forbids text sidecars under DCIM via MediaStore. " +
                "RawLens asks for the photo folder on first install; choose " +
                "DCIM/RawLens so burst.json + gyro/ land in " +
                "DCIM/RawLens/<burst> next to the DNGs. Use the button above " +
                "to re-grant after a reinstall or a revoked grant — no other " +
                "permission is needed."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(0, 0, 0, dp(16))
        })
        markActive(burstTab)
        polish()
}
