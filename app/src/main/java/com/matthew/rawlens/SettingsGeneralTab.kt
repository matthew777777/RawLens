// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.MainActivity.Companion.KEY_FOCUS_PEAKING_COLOR

import com.matthew.rawlens.MainActivity.Companion.KEY_FOCUS_PEAKING

import com.matthew.rawlens.MainActivity.Companion.LOCATION_PERMISSION

import com.matthew.rawlens.MainActivity.Companion.KEY_DNG_COMPRESSION

import com.matthew.rawlens.MainActivity.Companion.KEY_DNG_WRITER_BACKEND


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

/** Settings → General. Moved verbatim from MainActivity.showSettings (showGeneralTab). */
internal fun MainActivity.showGeneralSettings(p: SettingsPage) {
    val content = p.content
    fun sectionTitle(text: String): TextView = p.kit.sectionTitle(text)
    fun sectionDesc(text: String): TextView = p.kit.sectionDesc(text)
    fun polish() = p.kit.polish()
    fun markActive(active: Button) = p.markActive(active)
    val generalTab = p.tabs[0]
        content.removeAllViews()
        content.addView(sectionTitle("RAW capture"))
        content.addView(Button(this).apply {
            text = "DNG sensor calibration (levels • noise • color)"
            setOnClickListener { showDngMetadataOverrideEditor() }
        })
        content.addView(Button(this).apply {
            text = "Vendor keys (per lens • session + request)"
            setOnClickListener { showVendorKeysEditor() }
        })
        content.addView(TextView(this).apply {
            text = "DNG writer backend"
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        })
        content.addView(Button(this).apply {
            fun refresh() { text = dngWriterBackend().label }
            refresh()
            setOnClickListener {
                val entries = DngWriterBackend.entries.toTypedArray()
                val current = dngWriterBackend()
                AlertDialog.Builder(this@showGeneralSettings)
                    .setTitle("DNG writer backend")
                    .setSingleChoiceItems(entries.map { it.label }.toTypedArray(), current.ordinal) { dialog, which ->
                        val selected = entries[which]
                        lensPreferences().edit().putString(KEY_DNG_WRITER_BACKEND, selected.preferenceValue).apply()
                        setStatus("WRITER • ${selected.name}")
                        refresh()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        content.addView(TextView(this).apply {
            text = "Android DngCreator is the default. AUTO falls back to patched TinyDNG only if the platform writer fails."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        content.addView(TextView(this).apply {
            text = "DNG compression"
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        })
        content.addView(Button(this).apply {
            fun refresh() { text = dngCompression().label }
            refresh()
            setOnClickListener {
                val entries = DngCompression.entries.toTypedArray()
                val current = dngCompression()
                AlertDialog.Builder(this@showGeneralSettings)
                    .setTitle("DNG compression")
                    .setSingleChoiceItems(entries.map { it.label }.toTypedArray(), current.ordinal) { dialog, which ->
                        val selected = entries[which]
                        lensPreferences().edit().putString(KEY_DNG_COMPRESSION, selected.preferenceValue).apply()
                        setStatus("DNG • ${selected.name}")
                        refresh()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        content.addView(TextView(this).apply {
            text = "JXL stores the mosaic losslessly (DNG 1.7, like iPhone ProRAW) at roughly half the size. " +
                "Needs Lightroom / Adobe DNG Converter 16+ or Apple Photos — darktable and most LibRaw tools cannot read JXL DNGs yet. " +
                "Single-frame and HDR+ merged DNGs; packed and float/linear merges stay uncompressed. " +
                "Single-frame GPS files stay uncompressed to preserve location; merged JXL keeps GPS."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        content.addView(sectionTitle("Location"))
        var suppressGpsToggle = false
        content.addView(CheckBox(this).apply {
            text = "Save GPS location in photos (DNG + JPEG)"
            setTextColor(getColor(R.color.text_primary))
            isChecked = gpsEnabled()
            setOnCheckedChangeListener { button, enabled ->
                if (suppressGpsToggle) return@setOnCheckedChangeListener
                if (enabled == gpsEnabled()) return@setOnCheckedChangeListener
                if (enabled) {
                    if (gpsProvider?.hasPermission() == true) {
                        setGpsEnabled(true)
                    } else {
                        suppressGpsToggle = true
                        button.isChecked = false
                        suppressGpsToggle = false
                        requestPermissions(
                            GpsLocationProvider.permissions(), LOCATION_PERMISSION
                        )
                    }
                } else {
                    setGpsEnabled(false)
                }
            }
        })
        content.addView(TextView(this).apply {
            text = "Enabling requests location permission. Without a fresh fix " +
                "photos save without GPS tags rather than with a stale position."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        content.addView(sectionTitle("Viewfinder"))
        // GPU VF: selectable superpixel resolution (480/640 full-rate / 960 balanced / 1080 detail).
        content.addView(Button(this).apply {
            text = vfResolutionText(vfResolution)
            setOnClickListener {
                cycleVfResolution()
                text = vfResolutionText(vfResolution)
            }
        })
        content.addView(Button(this).apply {
            text = vfPreviewModeText()
            setOnClickListener {
                cycleVfPreviewMode()
                text = vfPreviewModeText()
            }
        })
        content.addView(Button(this).apply {
            fun refresh() { text = "Exposure overlay: ${rawExposureSettings.overlay.label}" }
            refresh()
            setOnClickListener {
                saveRawExposureAssist(rawExposureSettings.copy(overlay = rawExposureSettings.overlay.next()))
                refresh()
            }
        })
        content.addView(CheckBox(this).apply {
            text = "RAW exposure meter"
            isChecked = rawExposureSettings.spotMeter
            setOnCheckedChangeListener { _, enabled -> saveRawExposureAssist(rawExposureSettings.copy(spotMeter = enabled)) }
        })
        content.addView(Button(this).apply {
            fun refresh() { text = "RAW meter: ${rawExposureSettings.meterMode.label}" }
            refresh()
            setOnClickListener {
                saveRawExposureAssist(rawExposureSettings.copy(meterMode = rawExposureSettings.meterMode.next()))
                refresh()
            }
        })
        content.addView(sectionDesc("RAW values before white balance, tone mapping or LOG gain. Spot follows the photo AE target; recording uses centre. Average shows the whole frame's mean EV and estimated clipped area (any RAW channel); recording measures its crop. Sparse sampling can miss tiny clipped highlights. This readout does not change exposure. Zebras mark clipping and the black/noise floor with diagonal stripes. False colour maps −10 to 0 RAW EV. Waiting means no fresh RAW data."))
        content.addView(CheckBox(this).apply {
            text = "Focus peaking (manual + tap focus)"
            setTextColor(getColor(R.color.text_primary))
            isChecked = focusPeakingEnabled
            setOnCheckedChangeListener { _, enabled ->
                focusPeakingEnabled = enabled
                lensPreferences().edit().putBoolean(KEY_FOCUS_PEAKING, enabled).apply()
                controller.setFocusPeakingEnabled(enabled)
                videoRecorder?.focusPeakingEnabled = enabled
                logRecorder?.focusPeakingEnabled = enabled
                if (!enabled) setFocusPeakingVisible(false)
                setStatus(if (enabled) "PEAKING ON" else "PEAKING OFF")
            }
        })
        content.addView(sectionDesc("Peaking auto-shows while a manual focus distance is set or a tap-to-focus scan/lock is in flight."))
        content.addView(Button(this).apply {
            fun refresh() {
                text = "Peaking color: ${focusPeakingColor.label}"
            }
            refresh()
            setOnClickListener {
                focusPeakingColor = focusPeakingColor.next()
                lensPreferences().edit()
                    .putString(KEY_FOCUS_PEAKING_COLOR, focusPeakingColor.preferenceValue).apply()
                focusPeakingOverlay.peakingColor = focusPeakingColor
                refresh()
                setStatus("PEAKING • ${focusPeakingColor.label}")
            }
        })
        content.addView(sectionDesc("Overlays, logs, and capture debug output live under the Debug tab."))
        content.addView(sectionTitle("Settings backup"))
        content.addView(sectionDesc("One JSON file with every setting: camera, JPEG, video, exposure, burst, denoise, lenses, DNG calibration, and PROGRAM profiles. Importing replaces all settings and asks for a restart."))
        content.addView(Button(this).apply {
            text = "Export settings to JSON"
            setOnClickListener { settingsTransfer.export() }
        })
        content.addView(Button(this).apply {
            text = "Import settings from JSON"
            setOnClickListener {
                AlertDialog.Builder(this@showGeneralSettings)
                    .setTitle("Import settings?")
                    .setMessage("This replaces ALL current settings with the file contents. Export first to keep a backup.")
                    .setPositiveButton("Choose file") { _, _ -> settingsTransfer.import() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        markActive(generalTab)
        polish()
}
