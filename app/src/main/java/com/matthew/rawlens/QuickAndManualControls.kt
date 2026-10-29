// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.MainActivity.Companion.MANUAL_PANEL_TIMEOUT_MS

import com.matthew.rawlens.MainActivity.Companion.KEY_LAST_CAMERA_ID

import com.matthew.rawlens.MainActivity.Companion.HISTOGRAM_INTERVAL_MS

import com.matthew.rawlens.MainActivity.Companion.HISTOGRAM_RAW_HOLD_MS

import com.matthew.rawlens.MainActivity.Companion.KEY_SCOPE_MODE

import com.matthew.rawlens.MainActivity.Companion.KEY_HISTOGRAM

import com.matthew.rawlens.MainActivity.Companion.KEY_LEVEL

import com.matthew.rawlens.MainActivity.Companion.KEY_AE_METERING_MODE

import com.matthew.rawlens.MainActivity.Companion.SLIDER_STEPS

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

// Quick panel tiles, scopes, level, lens switcher and the manual control ruler. Moved verbatim from MainActivity.

/**
 * Dual-function AE metering button: in PROGRAM it cycles the RAW exposure
 * metering (center → average → spot); in every other mode it cycles the
 * hardware AE metering exactly as before.
 */
internal fun MainActivity.cycleAeMeteringMode() {
    if (captureExposureMode == CaptureExposureMode.PROGRAM) {
        if (!controller.hasManualSensorControl()) {
            setStatus("ANDROID AE • NO MANUAL SENSOR")
            return
        }
        val next = nextProgramMetering(programProfile().metering)
        saveProgramProfile(programProfile().copy(metering = next))
        setStatus("PROGRAM METER ${next.name}")
        updateQuickControls()
        return
    }
    val modes = AeMeteringMode.entries
    val next = modes[(modes.indexOf(aeMeteringMode) + 1) % modes.size]
    if (!controller.setAeMeteringMode(next)) return
    aeMeteringMode = next
    lensPreferences().edit().putInt(KEY_AE_METERING_MODE, next.preferenceValue).apply()
    updateQuickControls()
}

internal fun MainActivity.toggleQuickControls() {
    if (quickPanel.visibility == View.VISIBLE) hideQuickControls() else showQuickControls()
}

internal fun MainActivity.showQuickControls() {
    hideManualControl()
    updateQuickControls()
    quickPanel.alpha = 0f
    quickPanel.translationY = dp(18).toFloat()
    quickPanel.visibility = View.VISIBLE
    quickPanel.animate().alpha(1f).translationY(0f).setDuration(180L).start()
    quickPanel.post { updateOverlayStack() }
    armQuickAutoHide()
}


internal fun MainActivity.closeFloatingPanels() {
    hideManualControl()
    hideQuickControls()
}


internal fun MainActivity.setQuickTileState(tile: TextView, active: Boolean) {
    if (quickTileStates.put(tile, active) == active) return
    tile.background = getDrawable(if (active) R.drawable.control_chip_active else R.drawable.control_chip)
    tile.setTextColor(getColor(if (active) R.color.accent_dark else R.color.text_primary))
}

/** Top-bar gold tags: only photo features that are on right now. */
internal fun MainActivity.refreshFeatureTags(ettrEnabled: Boolean) {
    val tags = if (isVideoMode) emptyList() else listOfNotNull(
        "HDR+".takeIf { hdrPlusSettings.enabled },
        "MFSR".takeIf { mfsrSettings.enabled },
        "ETTR".takeIf { ettrEnabled },
        "HDR".takeIf { hdrEnabled },
        "BURST".takeIf { releaseMode == 1 },
        "${timerSeconds}S".takeIf { timerSeconds > 0 },
    )
    featureTags.setTextIfChanged(tags.joinToString(" · "))
    featureTags.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
}

/** Exposure tiles: gold value when the user set it, primary when auto. */
internal fun MainActivity.refreshManualTileAccents() {
    fun tint(tile: TextView, manual: Boolean) =
        tile.setTextColor(getColor(if (manual) R.color.accent else R.color.text_primary))
    fun manual(control: ManualControl) =
        runCatching { manualRangeFor(control)?.automatic == false }.getOrDefault(false)
    tint(isoControl, manual(ManualControl.ISO))
    tint(shutterControl, manual(ManualControl.SHUTTER))
    tint(wbControl, manual(ManualControl.WHITE_BALANCE))
    tint(focusControl, manual(ManualControl.FOCUS_DISTANCE))
    val ev = evControl.text.lines().lastOrNull()?.trim()
    tint(evControl, ev != null && ev != "+0.0" && ev != "0.0" && ev != "--")
}

/** Both scope views share one bottom slot; only the active mode is visible. */
internal fun MainActivity.scopeViews(): List<View> = listOf(histogramView, waveformView)

internal fun MainActivity.activeScopeView(): View =
    if (scopeMode == ScopeMode.WAVEFORM) waveformView else histogramView

internal fun MainActivity.syncScopeVisibility() {
    histogramView.visibility =
        if (histogramEnabled && scopeMode == ScopeMode.HISTOGRAM) View.VISIBLE else View.GONE
    waveformView.visibility =
        if (histogramEnabled && scopeMode == ScopeMode.WAVEFORM) View.VISIBLE else View.GONE
    // A GONE -> VISIBLE scope has no measured size yet: seat it now from
    // the fixed slot (plus a deferred measured correction) so showing it
    // in landscape never parks it behind the panel until next rotation.
    if (activeScopeView().visibility == View.VISIBLE) seatScopeSlot()
}

internal fun MainActivity.toggleLevel() {
    levelEnabled = !levelEnabled
    lensPreferences().edit().putBoolean(KEY_LEVEL, levelEnabled).apply()
    syncLevelVisibility()
    updateQuickControls()
    setStatus(if (levelEnabled) "LEVEL ON" else "LEVEL OFF")
}



internal fun MainActivity.scopeTileLabel(): String = when {
    !histogramEnabled -> "OFF"
    scopeMode == ScopeMode.WAVEFORM -> "WAVE"
    else -> "HISTO"
}

internal fun MainActivity.persistScopeSelection() {
    lensPreferences().edit()
        .putBoolean(KEY_HISTOGRAM, histogramEnabled)
        .putString(KEY_SCOPE_MODE, scopeMode.name)
        .apply()
}



internal fun MainActivity.scopeStatusLabel(): String {
    val mode = if (scopeMode == ScopeMode.WAVEFORM) "WAVE" else "HISTO"
    val source = if (scopeAgxApplied()) "JPEG" else "RAW"
    return "$mode $source"
}


/** Freshness changes the accessibility status, never the selected mode or source. */
internal fun MainActivity.rawScopeStalled(): Boolean =
    RawHistogramThrottle.isStalled(SystemClock.elapsedRealtime(), lastRawScopeMs, HISTOGRAM_RAW_HOLD_MS)


internal fun MainActivity.controlText(label: String, rawValue: String): String {
    val value = rawValue.removePrefix(label).trim().ifEmpty { "--" }
    return "$label\n$value"
}

internal fun TextView.setTextIfChanged(value: String) {
    if (!android.text.TextUtils.equals(text, value)) text = value
}

internal fun MainActivity.refreshLensSwitcher() {
    val options = controller.lensOptions()
    if (options.isEmpty()) return
    // Rebuilding the switcher view hierarchy on every controls publication is
    // wasted layout work; only rebuild when the lens set or selection changed.
    val signature = options.joinToString("|") { "${it.cameraId}:${it.selected}" }
    if (signature == lastLensSwitcherSignature && lensSwitcher.childCount > 0) return
    lastLensSwitcherSignature = signature
    lensSwitcher.removeAllViews()
    // Round discs share the panel row (screen width minus the panel's 16dp
    // sides): 44dp each when they fit, shrinking to a 36dp floor; past
    // that the row scrolls (lensScroll). Idle discs sit slightly smaller
    // so the selected lens reads as the one in front.
    val gap = dp(6)
    // Panel row minus the track's 4dp padding on each side.
    val rowWidth = resources.displayMetrics.widthPixels - dp(32) - dp(8)
    val discSize = ((rowWidth - gap * (options.size - 1)) / options.size)
        .coerceIn(dp(36), dp(44))
    var selectedButton: View? = null
    options.forEachIndexed { index, option ->
        val button = RotatingTextView(this).apply {
            text = option.label
            setTextColor(getColor(if (option.selected) R.color.accent else R.color.text_secondary))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (option.selected) 13f else 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = lensDisc(option.selected, discSize)
            minWidth = 0
            minHeight = 0
            minimumWidth = discSize
            minimumHeight = discSize
            setPadding(0, 0, 0, 0)
            maxLines = 1
            if (!option.selected) {
                scaleX = 0.88f
                scaleY = 0.88f
            }
            contentDescription = "Switch to ${option.label} lens"
            setOnClickListener {
                // The recorder owns the camera mid-take: lens is fixed until stop.
                if (videoRecording) {
                    setStatus("STOP TO SWITCH LENS")
                    return@setOnClickListener
                }
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                hideManualControl()
                lensPreferences().edit().putString(KEY_LAST_CAMERA_ID, option.cameraId).apply()
                controller.selectLens(option.cameraId)
            }
        }
        lensSwitcher.addView(button, LinearLayout.LayoutParams(discSize, discSize).apply {
            if (index > 0) marginStart = gap
        })
        button.contentRotation = controlRotationDegrees
        if (option.selected) {
            selectedButton = button
            // Settle-in: the newly selected disc grows from idle size.
            button.scaleX = 0.88f
            button.scaleY = 0.88f
            button.animate().scaleX(1f).scaleY(1f).setDuration(180L)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }
    }
    // Keep the selected lens centered when the row scrolls.
    val scroll = findViewById<android.widget.HorizontalScrollView>(R.id.lensScroll)
    selectedButton?.let { selected ->
        scroll.post {
            scroll.smoothScrollTo(selected.left + selected.width / 2 - scroll.width / 2, 0)
        }
    }
}

/**
 * Lens disc drawn as a true circle: a rounded rectangle whose radius is half
 * the exact square [size], so it can never stretch into an ellipse. The
 * selected disc is top-lit accent glass with an accent rim; idle discs are
 * faint top-lit glass with a hairline rim.
 */
private fun MainActivity.lensDisc(selected: Boolean, size: Int): android.graphics.drawable.Drawable =
    android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
        if (selected) intArrayOf(0x47DDC08D, 0x1FDDC08D) else intArrayOf(0x1FFFFFFF, 0x0AFFFFFF),
    ).apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = size / 2f
        setSize(size, size)
        if (selected) setStroke(dp(1) * 3 / 2, getColor(R.color.accent))
        else setStroke(dp(1), 0x24FFFFFF)
    }

/** Video lock-on-record switches (default off: continuous AE/AWB/AF). */
internal fun MainActivity.videoRecordLocks(): VideoRecordLocks =
    VideoRecordLocks.load { key -> lensPreferences().getBoolean(key, false) }

/** Range source follows the camera owner: recorder while rolling, controller otherwise. */
internal fun MainActivity.manualRangeFor(control: ManualControl): ManualControlRange? {
    if (videoRecording) {
        runCatching { videoRecorder?.videoControlRange(control) }.getOrNull()?.let { return it }
        runCatching { logRecorder?.videoControlRange(control) }.getOrNull()?.let { return it }
        return null
    }
    return runCatching { controller.manualControlRange(control) }.getOrNull()
}

internal fun MainActivity.setManualValueFor(control: ManualControl, value: Long?) {
    if (videoRecording) {
        if (videoRecorder != null) {
            videoRecorder?.setVideoManualControl(control, value)
            refreshVideoChip(control)
            return
        }
        if (logRecorder != null) {
            logRecorder?.setVideoManualControl(control, value)
            refreshVideoChip(control)
            return
        }
    }
    controller.setManualControl(control, value)
}

/** Optimistic chip refresh while rolling (the stills controller is stopped). */
internal fun MainActivity.refreshVideoChip(control: ManualControl) {
    val range = manualRangeFor(control) ?: return
    when (control) {
        ManualControl.ISO -> isoControl.text = controlText("ISO", range.current.toString())
        ManualControl.SHUTTER -> shutterControl.text =
            controlText("S", formatShutter(range.current))
        ManualControl.WHITE_BALANCE -> wbControl.text =
            controlText("WB", "${range.current}K")
        ManualControl.FOCUS_DISTANCE -> focusControl.text =
            controlText("AF", if (range.automatic) "AF" else formatFocusDistance(range.current))
        ManualControl.EXPOSURE_COMPENSATION -> {
            val isManualExposure = runCatching {
                (videoRecorder?.videoManual ?: logRecorder?.videoManual)?.let {
                    VideoManualPolicy.wantsManualExposure(it)
                } ?: false
            }.getOrDefault(false)
            evControl.text = if (isManualExposure) {
                controlText("EV", "EV --")
            } else {
                controlText("EV", formatVideoEvStops(range.current))
            }
        }
    }
}

internal fun MainActivity.formatVideoEvStops(index: Long): String {
    val step = runCatching {
        val manager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val cameraId = videoCameraInfo(manager)?.first ?: return@runCatching null
        manager.getCameraCharacteristics(cameraId)
            .get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?.toFloat()
    }.getOrNull() ?: return "EV $index"
    if (step <= 0f) return "EV $index"
    return String.format(Locale.US, "EV %+.1f", index * step)
}

internal fun MainActivity.showManualControl(control: ManualControl, allowToggle: Boolean = true) {
    hideQuickControls()
    if (allowToggle && activeManualControl == control && manualPanel.visibility == View.VISIBLE) {
        hideManualControl()
        return
    }
    val range = manualRangeFor(control)
    if (range == null) {
        setStatus("${shortControlName(control)} N/A")
        return
    }
    activeManualControl = control
    activeProgramAxis = null
    // Tick-only ruler with no text label: values live in the exposure chips, never
    // in the slider strip, so landscape stays compact. Full values remain in
    // accessibility descriptions.
    manualLimits.visibility = View.GONE
    manualSlider.label = shortControlName(control)
    manualSlider.max = SLIDER_STEPS
    manualSlider.setProgressFromUser(sliderProgress(control, range), fromUser = false)
    manualAuto.text = if (control == ManualControl.EXPOSURE_COMPENSATION) "RESET" else "AUTO"
    manualAuto.isEnabled = !range.automatic
    refreshRuleSliderAccessibility()
    manualPanel.visibility = View.VISIBLE
    updateOverlayStack()
    manualPanel.post { updateOverlayStack() }
    scheduleManualPanelHide()
}

/** Full value stays available to accessibility even though the ruler draws ticks only. */
internal fun MainActivity.refreshRuleSliderAccessibility() {
    activeProgramAxis?.let { axis ->
        val range = manualRangeFor(axis) ?: return
        val value = sliderValue(axis, range, manualSlider.progress)
        val valueText = formatManualValue(axis, value)
        manualSlider.valueText = rulerValueText(axis, value)
        manualSlider.minText = rulerValueText(axis, range.minimum)
        manualSlider.maxText = rulerValueText(axis, range.maximum)
        manualSlider.contentDescription =
            "${controlName(axis)} lock slider, $valueText. Current value also shown in the ${shortControlName(axis)} chip."
        return
    }
    val control = activeManualControl ?: return
    val range = manualRangeFor(control) ?: return
    val value = sliderValue(control, range, manualSlider.progress)
    val valueText = formatManualValue(control, value)
    manualSlider.valueText = rulerValueText(control, value)
    manualSlider.minText = rulerValueText(control, range.minimum)
    manualSlider.maxText = rulerValueText(control, range.maximum)
    manualSlider.contentDescription =
        "${controlName(control)} slider, $valueText. Current value also shown in the ${shortControlName(control)} chip."
}

/**
 * Compact value for the floating ruler: the axis code already sits above it,
 * so "ISO 7964" becomes "7964" and "+0.2 EV" becomes "+0.2".
 */
internal fun MainActivity.rulerValueText(control: ManualControl, value: Long): String {
    val full = formatManualValue(control, value)
    return when (control) {
        ManualControl.ISO -> full.removePrefix("ISO ").trim()
        ManualControl.EXPOSURE_COMPENSATION -> full.removeSuffix(" EV").trim()
        else -> full
    }.ifEmpty { "--" }
}

internal fun MainActivity.scheduleManualPanelHide() {
    cancelManualPanelHide()
    manualPanelHide = Runnable { hideManualControl() }.also {
        manualPanel.postDelayed(it, MANUAL_PANEL_TIMEOUT_MS)
    }
}

internal fun MainActivity.cancelManualPanelHide() {
    manualPanelHide?.let(manualPanel::removeCallbacks)
    manualPanelHide = null
}


internal fun MainActivity.sliderProgress(control: ManualControl, range: ManualControlRange): Int {
    if (range.maximum <= range.minimum) return 0
    val fraction = if (control == ManualControl.SHUTTER) {
        val minLog = kotlin.math.ln(range.minimum.toDouble())
        (kotlin.math.ln(range.current.toDouble()) - minLog) /
            (kotlin.math.ln(range.maximum.toDouble()) - minLog)
    } else {
        (range.current - range.minimum).toDouble() / (range.maximum - range.minimum)
    }
    return (fraction.coerceIn(0.0, 1.0) * SLIDER_STEPS).toInt()
}

internal fun MainActivity.sliderValue(
    control: ManualControl,
    range: ManualControlRange,
    progress: Int
): Long {
    val fraction = progress.toDouble() / SLIDER_STEPS
    return if (control == ManualControl.SHUTTER) {
        kotlin.math.exp(
            kotlin.math.ln(range.minimum.toDouble()) + fraction *
                (kotlin.math.ln(range.maximum.toDouble()) - kotlin.math.ln(range.minimum.toDouble()))
        ).toLong().coerceIn(range.minimum, range.maximum)
    } else {
        (range.minimum + fraction * (range.maximum - range.minimum)).toLong()
            .coerceIn(range.minimum, range.maximum)
    }
}

internal fun MainActivity.updateAutomaticPanelValue(iso: Int, shutter: Long, wb: Int) {
    val control = activeManualControl ?: return
    if (manualPanel.visibility != View.VISIBLE) return
    val range = manualRangeFor(control) ?: return
    if (!range.automatic) return
    val value = when (control) {
        ManualControl.ISO -> iso.toLong()
        ManualControl.SHUTTER -> shutter
        ManualControl.WHITE_BALANCE -> wb.toLong()
        ManualControl.FOCUS_DISTANCE -> range.current
        ManualControl.EXPOSURE_COMPENSATION -> range.current
    }
    if (value > 0 || control == ManualControl.EXPOSURE_COMPENSATION) {
        manualSlider.setProgressFromUser(
            sliderProgress(control, range.copy(current = value.coerceIn(range.minimum, range.maximum))),
            fromUser = false
        )
        refreshRuleSliderAccessibility()
    }
}

internal fun MainActivity.controlName(control: ManualControl): String = when (control) {
    ManualControl.ISO -> "ISO sensitivity"
    ManualControl.SHUTTER -> "Shutter speed"
    ManualControl.WHITE_BALANCE -> "White balance"
    ManualControl.FOCUS_DISTANCE -> "Manual focus"
    ManualControl.EXPOSURE_COMPENSATION -> "Exposure compensation"
}

/** Abbreviated chip label for the top-right status line. */
internal fun MainActivity.shortControlName(control: ManualControl): String = when (control) {
    ManualControl.ISO -> "ISO"
    ManualControl.SHUTTER -> "S"
    ManualControl.WHITE_BALANCE -> "WB"
    ManualControl.FOCUS_DISTANCE -> "MF"
    ManualControl.EXPOSURE_COMPENSATION -> "EV"
}

internal fun MainActivity.formatManualValue(control: ManualControl, value: Long): String = when (control) {
    ManualControl.ISO -> "ISO $value"
    ManualControl.SHUTTER -> formatShutter(value)
    ManualControl.WHITE_BALANCE -> "${value}K"
    ManualControl.FOCUS_DISTANCE -> formatFocusDistance(value)
    ManualControl.EXPOSURE_COMPENSATION -> String.format(
        Locale.US, "%+.1f EV", controller.exposureCompensationStops(value)
    )
}

internal fun MainActivity.formatFocusDistance(scaledDiopters: Long): String {
    if (scaledDiopters <= 1L) return "∞"
    val meters = 1_000.0 / scaledDiopters
    return if (meters >= 10.0) String.format(Locale.US, "%.0f m", meters)
    else String.format(Locale.US, "%.1f m", meters)
}

/** Share the photo settings level with PROGRAM metering and ETTR protection. */
internal fun MainActivity.cycleQuickDLighting() {
    if (isVideoMode || (captureExposureMode != CaptureExposureMode.PROGRAM &&
            !(captureExposureMode == CaptureExposureMode.AUTO && controller.getEttrSettings().enabled))) return
    val current = jpegOutputSettings()
    val levels = ActiveDLighting.entries
    val next = levels[(current.activeDLighting.ordinal + 1) % levels.size]
    if (!controller.setJpegOutputSettings(current.copy(activeDLighting = next).resolvedForPlatform())) {
        setStatus("SETTINGS AFTER SAVES")
        return
    }
    lensPreferences().edit().putString(ActiveDLighting.PREFERENCE_KEY, next.name).apply()
    updateQuickControls()
    setStatus("D-LIGHTING • ${next.label.uppercase(Locale.US)}")
}
