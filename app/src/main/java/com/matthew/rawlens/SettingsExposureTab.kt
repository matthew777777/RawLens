// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.MainActivity.Companion.KEY_DYNAMIC_EXPOSURE_AUTO_SHUTTER
import com.matthew.rawlens.MainActivity.Companion.KEY_DYNAMIC_EXPOSURE_SHUTTER_LIMIT
import com.matthew.rawlens.MainActivity.Companion.KEY_DYNAMIC_EXPOSURE_ISO_LIMIT
import com.matthew.rawlens.MainActivity.Companion.KEY_DYNAMIC_EXPOSURE_BALANCE
import com.matthew.rawlens.MainActivity.Companion.KEY_DYNAMIC_EXPOSURE

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
    content.addView(p.kit.sectionDesc(FlashStillPolicy.AUTO_UNAVAILABLE_NOTE))
    fun sectionTitle(text: String): TextView = p.kit.sectionTitle(text)
    fun sectionDesc(text: String): TextView = p.kit.sectionDesc(text)
    fun polish() = p.kit.polish()
    fun markActive(active: Button) = p.markActive(active)
    val exposureTab = p.tabs[3]
        rawZslSettingsStatus = null
        sidecarSettingsStatus = null
        content.removeAllViews()
        content.addView(sectionTitle("PROGRAM custom AE"))
        content.addView(sectionDesc("RAW-driven per-lens sensor ISO + shutter. Full per-lens editor (priority, locks, bounds, bias) mirrors the PROGRAM panel; ISO/S chips open it too."))
        var dynamicSettings = dynamicExposureSettings()
        fun applyDynamicSettings() {
            lensPreferences().edit()
                .putBoolean(KEY_DYNAMIC_EXPOSURE, dynamicSettings.enabled)
                .putFloat(KEY_DYNAMIC_EXPOSURE_BALANCE, dynamicSettings.balance)
                .putInt(KEY_DYNAMIC_EXPOSURE_ISO_LIMIT, dynamicSettings.isoLimit)
                .putLong(KEY_DYNAMIC_EXPOSURE_SHUTTER_LIMIT, dynamicSettings.shutterLimitNanos)
                .putBoolean(KEY_DYNAMIC_EXPOSURE_AUTO_SHUTTER, dynamicSettings.useAutoSafeShutter)
                .apply()
            controller.setDynamicExposureSettings(dynamicSettings)
        }
        content.addView(CheckBox(this).apply {
            text = "PROGRAM custom AE (RAW-driven)"
            setTextColor(getColor(R.color.text_primary))
            isChecked = dynamicSettings.enabled
            setOnCheckedChangeListener { _, enabled ->
                dynamicSettings = dynamicSettings.copy(enabled = enabled)
                applyDynamicSettings()
            }
        })
        content.addView(TextView(this).apply {
            text = "PROGRAM custom AE is RAW-driven per-lens (sensor ISO + shutter). " +
                "Hold ISO/SHUTTER chips to lock, tap for slider, Auto releases both. " +
                "Cameras without manual sensor control keep using Android AE. " +
                "Use ISO/S chips for the full editor; ETTR overrides PROGRAM when converged."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        if (!controller.hasManualSensorControl()) {
            content.addView(TextView(this).apply {
                text = "Android AE • this camera has no manual sensor control; " +
                    "PROGRAM locks and limits are unavailable."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            })
        }
        var programSettings = programProfile()
        fun applyProgramSettings() = saveProgramProfile(programSettings)
        val programBalanceLabel = TextView(this).apply {
            text = programBalanceText(programSettings.balance)
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        content.addView(programBalanceLabel)
        content.addView(SeekBar(this).apply {
            max = 100
            progress = (programSettings.balance * 100).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    programSettings = programSettings.copy(balance = progress / 100f)
                    programBalanceLabel.text = programBalanceText(programSettings.balance)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = applyProgramSettings()
            })
        })
        val programIsoMinButton = Button(this)
        val programIsoMaxButton = Button(this)
        val programShutterMinButton = Button(this)
        val programShutterMaxButton = Button(this)
        val programLockButton = Button(this)
        val programMeteringButton = Button(this)
        val programBiasLabel = TextView(this).apply {
            text = programEvBiasText(programSettings.evBias)
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
        }
        fun refreshProgramButtons() {
            programIsoMinButton.text = programIsoBoundText(programSettings.isoMin, isMin = true)
            programIsoMaxButton.text = programIsoBoundText(programSettings.isoMax, isMin = false)
            programShutterMinButton.text = programShutterBoundText(
                programSettings.shutterMinNanos, isMin = true, autoSafe = false)
            programShutterMaxButton.text = programShutterBoundText(
                programSettings.shutterMaxNanos, isMin = false,
                autoSafe = programSettings.useAutoSafeShutter)
            programLockButton.text = programLockText(programSettings.lockMode)
            programBiasLabel.text = programEvBiasText(programSettings.evBias)
            programMeteringButton.text =
                "Metering: ${programMeteringLabel(programSettings.metering)}"
            val manualSensor = controller.hasManualSensorControl()
            programLockButton.isEnabled = manualSensor
            programIsoMinButton.isEnabled = manualSensor
            programIsoMaxButton.isEnabled = manualSensor
            programShutterMinButton.isEnabled = manualSensor
            programShutterMaxButton.isEnabled = manualSensor
            programMeteringButton.isEnabled = manualSensor
        }
        programLockButton.setOnClickListener {
            programSettings = seedLockedValuesFor(programSettings.copy(lockMode = when (programSettings.lockMode) {
                ProgramLockMode.NONE -> ProgramLockMode.ISO_LOCK
                ProgramLockMode.ISO_LOCK -> ProgramLockMode.SHUTTER_LOCK
                ProgramLockMode.SHUTTER_LOCK -> ProgramLockMode.NONE
            }))
            refreshProgramButtons(); applyProgramSettings()
        }
        programIsoMinButton.setOnClickListener {
            val values = listOf(0, 100, 200, 400, 800)
            programSettings = programSettings.copy(isoMin = values[
                (values.indexOf(programSettings.isoMin).takeIf { it >= 0 } ?: 0).let { (it + 1) % values.size }])
            refreshProgramButtons(); applyProgramSettings()
        }
        programIsoMaxButton.setOnClickListener {
            val values = listOf(0, 400, 800, 1600, 3200, 6400)
            programSettings = programSettings.copy(isoMax = values[
                (values.indexOf(programSettings.isoMax).takeIf { it >= 0 } ?: 0).let { (it + 1) % values.size }])
            refreshProgramButtons(); applyProgramSettings()
        }
        programShutterMinButton.setOnClickListener {
            val values = listOf(0L, 1_000_000_000L / 1000, 1_000_000_000L / 500,
                1_000_000_000L / 250, 1_000_000_000L / 125, 1_000_000_000L / 60)
            programSettings = programSettings.copy(shutterMinNanos = values[
                (values.indexOf(programSettings.shutterMinNanos).takeIf { it >= 0 } ?: 0).let { (it + 1) % values.size }])
            refreshProgramButtons(); applyProgramSettings()
        }
        programShutterMaxButton.setOnClickListener {
            val values = listOf(0L, 1_000_000_000L / 15, 1_000_000_000L / 30,
                1_000_000_000L / 60, 1_000_000_000L / 125, 1_000_000_000L / 250)
            if (programSettings.useAutoSafeShutter) {
                programSettings = programSettings.copy(useAutoSafeShutter = false, shutterMaxNanos = 0L)
            } else {
                val next = values[(values.indexOf(programSettings.shutterMaxNanos).takeIf { it >= 0 } ?: 0)
                    .let { (it + 1) % values.size }]
                programSettings = programSettings.copy(
                    shutterMaxNanos = next, useAutoSafeShutter = next == 0L)
            }
            refreshProgramButtons(); applyProgramSettings()
        }
        refreshProgramButtons()
        content.addView(programLockButton)
        content.addView(programMeteringButton)
        programMeteringButton.setOnClickListener {
            programSettings = programSettings.copy(metering = nextProgramMetering(programSettings.metering))
            refreshProgramButtons(); applyProgramSettings()
        }
        content.addView(programIsoMinButton)
        content.addView(programIsoMaxButton)
        content.addView(programShutterMinButton)
        content.addView(programShutterMaxButton)
        content.addView(programBiasLabel)
        content.addView(SeekBar(this).apply {
            max = 60
            progress = ((programSettings.evBias - ProgramAeProfile.MIN_EV_BIAS) * 20f).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    programSettings = programSettings.copy(
                        evBias = ProgramAeProfile.MIN_EV_BIAS + progress / 20f)
                    programBiasLabel.text = programEvBiasText(programSettings.evBias)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = applyProgramSettings()
            })
        })
        var ettr = ettrSettings()
        fun applyEttr() = saveEttrSettings(ettr)
        content.addView(CheckBox(this).apply {
            text = "ETTR single exposure (RAW-measured)"
            setTextColor(getColor(R.color.text_primary))
            isChecked = ettr.enabled
            setOnCheckedChangeListener { _, enabled ->
                ettr = ettr.copy(enabled = enabled)
                applyEttr()
            }
        })
        content.addView(TextView(this).apply {
            text = "When the gain ceiling binds, ETTR keeps the hand-motion " +
                "shutter cap and accepts a darker frame instead of trading noise for blur."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        val ettrHeadroomButton = Button(this)
        val ettrIsoLimitButton = Button(this)
        fun refreshEttrButtons() {
            ettrHeadroomButton.text = ettrHeadroomText(ettr.headroomEv)
            ettrIsoLimitButton.text = ettrIsoLimitText(ettr.isoLimit)
        }
        ettrHeadroomButton.setOnClickListener {
            // Magic Lantern auto-ETTR levels: never closer than -0.5 EV, default -0.5 EV.
            val values = floatArrayOf(0.5f, 1f, 1.5f, 2f)
            val next = (values.indexOfFirst { it == ettr.headroomEv }.takeIf { it >= 0 } ?: 0)
            ettr = ettr.copy(headroomEv = values[(next + 1) % values.size])
            applyEttr(); refreshEttrButtons()
        }
        ettrIsoLimitButton.setOnClickListener {
            val values = intArrayOf(0, 400, 800, 1600, 3200, 6400)
            val next = (values.indexOf(ettr.isoLimit).takeIf { it >= 0 } ?: 0)
            ettr = ettr.copy(isoLimit = values[(next + 1) % values.size])
            applyEttr(); refreshEttrButtons()
        }
        refreshEttrButtons()
        content.addView(ettrHeadroomButton)
        content.addView(ettrIsoLimitButton)
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
