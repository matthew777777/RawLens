// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.MainActivity.Companion.KEY_DYNAMIC_EXPOSURE_AUTO_SHUTTER
import com.matthew.rawlens.MainActivity.Companion.KEY_DYNAMIC_EXPOSURE_SHUTTER_LIMIT
import com.matthew.rawlens.MainActivity.Companion.KEY_DYNAMIC_EXPOSURE_ISO_LIMIT
import com.matthew.rawlens.MainActivity.Companion.KEY_DYNAMIC_EXPOSURE_BALANCE
import com.matthew.rawlens.MainActivity.Companion.KEY_DYNAMIC_EXPOSURE

import com.matthew.rawlens.MainActivity.Companion.KEY_FACE_PRIORITY


import com.matthew.rawlens.MainActivity.Companion.KEY_HDR_BRACKET_STOPS
import com.matthew.rawlens.MainActivity.Companion.KEY_LKR_HDR_DEBUG




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
        content.addView(SettingsSwitch(this).apply {
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
        val curveControls = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        content.addView(Button(this).apply {
            fun refresh() { text = "Scene brightness: ${programSettings.brightnessTuning.label}" }
            refresh()
            setOnClickListener {
                p.kit.choose("Scene brightness", ProgramBrightnessTuning.values().toList(),
                    programSettings.brightnessTuning, { it.label }) { mode ->
                    programSettings = programSettings.copy(brightnessTuning = mode)
                    applyProgramSettings()
                    curveControls.visibility = if (programSettings.brightnessTuning == ProgramBrightnessTuning.FIXED) View.GONE else View.VISIBLE
                    refresh()
                }
            }
        })
        content.addView(sectionDesc("Natural preserves darker scenes by adapting the exposure target to scene brightness. Fixed uses the same target in every scene. Protect highlights also limits bright areas. Adaptive curves are experimental. Saved per lens."))
        content.addView(curveControls)
        curveControls.visibility = if (programSettings.brightnessTuning == ProgramBrightnessTuning.FIXED) View.GONE else View.VISIBLE
        curveControls.addView(sectionDesc("Exposure targets as a percentage of linear RAW brightness. Lower values keep the scene darker; higher values increase sensor exposure. Bias applies on top. The curve blends smoothly between these four scene levels."))
        val targetSliders = mutableListOf<SettingsSlider>()
        val targetNames = listOf("Dark scene", "Dim scene", "Normal scene", "Bright scene")
        fun target(index: Int): Double = when (index) {
            0 -> programSettings.darkTarget
            1 -> programSettings.dimTarget
            2 -> programSettings.normalTarget
            else -> programSettings.brightTarget
        }
        targetNames.forEachIndexed { index, name ->
            val label = sectionDesc("")
            fun refreshLabel() { label.text = String.format(Locale.US, "%s target: %.0f%%", name, target(index) * 100) }
            refreshLabel()
            curveControls.addView(label)
            val slider = SettingsSlider(this).apply {
                max = 49
                progress = Math.round(target(index) * 100).toInt() - 1
                setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                    override fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean) {
                        val value = (progress + 1) / 100.0
                        programSettings = when (index) {
                            0 -> programSettings.copy(darkTarget = value)
                            1 -> programSettings.copy(dimTarget = value)
                            2 -> programSettings.copy(normalTarget = value)
                            else -> programSettings.copy(brightTarget = value)
                        }
                        refreshLabel()
                        if (fromUser) applyProgramSettings()
                    }
                    override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit
                    override fun onStopTrackingTouch(seekBar: SettingsSlider) = applyProgramSettings()
                })
            }
            targetSliders.add(slider)
            curveControls.addView(slider)
        }
        curveControls.addView(Button(this).apply {
            text = "Reset brightness targets"
            setOnClickListener {
                programSettings = programSettings.copy(darkTarget = 0.06, dimTarget = 0.10, normalTarget = 0.16, brightTarget = 0.18)
                targetSliders.forEachIndexed { index, slider -> slider.progress = Math.round(target(index) * 100).toInt() - 1 }
                applyProgramSettings()
            }
        })
        val programBalanceLabel = TextView(this).apply {
            text = programBalanceText(programSettings.balance)
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        content.addView(programBalanceLabel)
        content.addView(SettingsSlider(this).apply {
            max = 100
            progress = (programSettings.balance * 100).toInt().coerceIn(0, max)
            setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                override fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean) {
                    programSettings = programSettings.copy(balance = progress / 100f)
                    programBalanceLabel.text = programBalanceText(programSettings.balance)
                }
                override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit
                override fun onStopTrackingTouch(seekBar: SettingsSlider) = applyProgramSettings()
            })
        })
        val programIsoMinButton = Button(this)
        val programIsoMaxButton = Button(this)
        val programDigitalGainButton = Button(this)
        fun digitalGainText(ev: Float) = "Digital gain: " + when {
            ev <= 0f -> "Off"
            ev >= ProgramAeProfile.MAX_DIGITAL_GAIN_EV -> "Maximum"
            else -> "+${ev.toInt()} EV"
        }
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
            programDigitalGainButton.text = digitalGainText(programSettings.digitalGainEv)
            programDigitalGainButton.isEnabled = manualSensor
            programShutterMinButton.isEnabled = manualSensor
            programShutterMaxButton.isEnabled = manualSensor
            programMeteringButton.isEnabled = manualSensor
        }
        programLockButton.setOnClickListener {
            p.kit.choose("Lock", listOf(ProgramLockMode.NONE, ProgramLockMode.ISO_LOCK, ProgramLockMode.SHUTTER_LOCK),
                programSettings.lockMode, { programLockText(it) }) { mode ->
                programSettings = seedLockedValuesFor(programSettings.copy(lockMode = mode))
                refreshProgramButtons(); applyProgramSettings()
            }
        }
        programIsoMinButton.setOnClickListener {
            p.kit.choose("ISO min", listOf(0, 100, 200, 400, 800), programSettings.isoMin,
                { programIsoBoundText(it, isMin = true) }) { value ->
                programSettings = programSettings.copy(isoMin = value)
                refreshProgramButtons(); applyProgramSettings()
            }
        }
        programDigitalGainButton.setOnClickListener {
            // rawr-style: brightening past the highest real ISO and shutter, applied after
            // RAW; DNGs carry it as BaselineExposure.
            p.kit.choose("Digital gain past max ISO",
                listOf(0f, 1f, 2f, 3f, 4f, ProgramAeProfile.MAX_DIGITAL_GAIN_EV),
                programSettings.digitalGainEv, { digitalGainText(it) }) { value ->
                programSettings = programSettings.copy(digitalGainEv = value)
                refreshProgramButtons(); applyProgramSettings()
            }
        }
        programIsoMaxButton.setOnClickListener {
            p.kit.choose("ISO max", listOf(0, 400, 800, 1600, 3200, 6400), programSettings.isoMax,
                { programIsoBoundText(it, isMin = false) }) { value ->
                programSettings = programSettings.copy(isoMax = value)
                refreshProgramButtons(); applyProgramSettings()
            }
        }
        programShutterMinButton.setOnClickListener {
            val values = listOf(0L, 1_000_000_000L / 1000, 1_000_000_000L / 500,
                1_000_000_000L / 250, 1_000_000_000L / 125, 1_000_000_000L / 60)
            p.kit.choose("Shutter min", values, programSettings.shutterMinNanos,
                { programShutterBoundText(it, isMin = true, autoSafe = false) }) { value ->
                programSettings = programSettings.copy(shutterMinNanos = value)
                refreshProgramButtons(); applyProgramSettings()
            }
        }
        programShutterMaxButton.setOnClickListener {
            val values = listOf(0L, 1_000_000_000L / 15, 1_000_000_000L / 30,
                1_000_000_000L / 60, 1_000_000_000L / 125, 1_000_000_000L / 250)
            // Auto-safe first, then the fixed limits (0 = no limit), as (autoSafe, nanos).
            val options = listOf(true to 0L) + values.map { false to it }
            p.kit.choose("Shutter max", options,
                programSettings.useAutoSafeShutter to (if (programSettings.useAutoSafeShutter) 0L else programSettings.shutterMaxNanos),
                { (autoSafe, nanos) -> programShutterBoundText(nanos, isMin = false, autoSafe = autoSafe) }) { (autoSafe, nanos) ->
                programSettings = programSettings.copy(useAutoSafeShutter = autoSafe, shutterMaxNanos = nanos)
                refreshProgramButtons(); applyProgramSettings()
            }
        }
        refreshProgramButtons()
        content.addView(programLockButton)
        content.addView(programMeteringButton)
        programMeteringButton.setOnClickListener {
            val modes = listOf(ProgramMetering.CENTER_WEIGHTED, ProgramMetering.AVERAGE,
                ProgramMetering.SPOT, ProgramMetering.HIGHLIGHT_WEIGHTED)
            p.kit.choose("Metering", modes, programSettings.metering, { programMeteringLabel(it) }) { mode ->
                programSettings = programSettings.copy(metering = mode)
                refreshProgramButtons(); applyProgramSettings()
            }
        }
        content.addView(programIsoMinButton)
        content.addView(programIsoMaxButton)
        content.addView(programDigitalGainButton)
        content.addView(programShutterMinButton)
        content.addView(programShutterMaxButton)
        content.addView(programBiasLabel)
        content.addView(SettingsSlider(this).apply {
            max = 60
            progress = ((programSettings.evBias - ProgramAeProfile.MIN_EV_BIAS) * 20f).toInt().coerceIn(0, max)
            setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                override fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean) {
                    programSettings = programSettings.copy(
                        evBias = ProgramAeProfile.MIN_EV_BIAS + progress / 20f)
                    programBiasLabel.text = programEvBiasText(programSettings.evBias)
                }
                override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit
                override fun onStopTrackingTouch(seekBar: SettingsSlider) = applyProgramSettings()
            })
        })
        var ettr = ettrSettings()
        fun applyEttr() = saveEttrSettings(ettr)
        content.addView(SettingsSwitch(this).apply {
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
            p.kit.choose("Highlight headroom", listOf(0.5f, 1f, 1.5f, 2f), ettr.headroomEv, { ettrHeadroomText(it) }) { value ->
                ettr = ettr.copy(headroomEv = value)
                applyEttr(); refreshEttrButtons()
            }
        }
        ettrIsoLimitButton.setOnClickListener {
            p.kit.choose("ISO ceiling", listOf(0, 400, 800, 1600, 3200, 6400), ettr.isoLimit, { ettrIsoLimitText(it) }) { value ->
                ettr = ettr.copy(isoLimit = value)
                applyEttr(); refreshEttrButtons()
            }
        }
        refreshEttrButtons()
        content.addView(ettrHeadroomButton)
        content.addView(ettrIsoLimitButton)
        content.addView(sectionTitle("Two-exposure RAW HDR (experimental)"))
        content.addView(SettingsSwitch(this).apply {
            text = "Experimental two-exposure RAW HDR"
            setTextColor(getColor(R.color.text_primary))
            isChecked = dualRawEnabled
            isEnabled = controller.supportsDualRaw() || dualRawEnabled
            setOnCheckedChangeListener { button, enabled ->
                if (enabled && !controller.supportsDualRaw()) {
                    button.isChecked = false
                    setStatus("RAW HDR TEST NEEDS MANUAL RAW CAPTURE")
                } else {
                    dualRawEnabled = enabled
                    setStatus(if (enabled) "TWO-EXPOSURE RAW HDR ON" else "TWO-EXPOSURE RAW HDR OFF")
                    updateQuickControls()
                }
            }
        })
        content.addView(TextView(this).apply {
            text = "Available on any lens with manual RAW capture, or rooted Redmi DCG14 with AE always on. " +
                "DCG14 meters each exposure separately using AE compensation; ISO may differ. " +
                "Saves a 16-bit DNG from two sequential exposures, " +
                "using the short exposure to recover clipped highlights. " +
                "Does not reduce shadow noise. No alignment or motion correction; " +
                "keep the phone and scene still. Overrides the release mode " +
                "while enabled. Resets when the app restarts or you select an unsupported camera."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        content.addView(sectionTitle("HDR brackets"))
        content.addView(sectionDesc("HDR captures a 3-frame bracket and saves every frame as its own DNG (IMG_<time>_F00/F01/F02). No merge."))
        content.addView(SettingsSwitch(this).apply {
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
        content.addView(SettingsSwitch(this).apply {
            text = "Debug: LK-R HDR bracket (up to 8 frames: 0 EV frames, 2 × −2, 1 × +1.5 EV)"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lkrHdrDebugBracket()
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(KEY_LKR_HDR_DEBUG, enabled).apply()
                setStatus(if (enabled) "HDR DEBUG" else "HDR ±${hdrBracketStops()} EV")
                updateQuickControls()
            }
        })
        content.addView(sectionDesc("With the HDR toggle on, the shutter saves up to 8 DNGs (as many as the RAW queue holds, 6 on most phones: 3 × 0, 2 × −2, 1 × +1.5 EV; F00 = 0 EV reference, long frame last, max 1/8 s) for testing the LK-R HDR merge. Hold still-ish; no merge yet."))
        content.addView(sectionTitle("Face priority"))
        content.addView(sectionDesc("HAL face detection steers AF/AE to the largest face (sand box). Tap targets override faces; manual sensor modes ignore the regions but still show boxes."))
        content.addView(SettingsSwitch(this).apply {
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
