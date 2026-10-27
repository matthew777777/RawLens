// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

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

// PROGRAM AE ruler panel: axis sliders, limits/lock/metering editor and its text helpers. Moved verbatim from MainActivity.

/**
 * Spec chip contract: tap ISO/SHUTTER for that axis's slider. Moving the slider
 * engages the axis lock; the slider's Auto button releases both locks.
 * Uses the same bottom transparent ruler as MANUAL (tick-only; values in chips).
 */
internal fun MainActivity.showProgramAxisSlider(isIso: Boolean) {
    hideQuickControls()
    // While rolling the take request owns the sensor: PROGRAM editor needs preview.
    if (videoRecording) {
        showManualControl(if (isIso) ManualControl.ISO else ManualControl.SHUTTER)
        return
    }
    if (!controller.hasManualSensorControl()) {
        setStatus("ANDROID AE • NO MANUAL SENSOR")
        return
    }
    val axis = if (isIso) ManualControl.ISO else ManualControl.SHUTTER
    val range = manualRangeFor(axis)
    if (range == null) {
        setStatus("${if (isIso) "ISO" else "S"} N/A")
        return
    }
    if (activeProgramAxis == axis && manualPanel.visibility == View.VISIBLE) {
        hideManualControl()
        return
    }
    val profile = programProfile()
    val current = if (isIso) {
        (profile.lockedIso.takeIf { it > 0 }?.toLong() ?: range.current)
            .coerceIn(range.minimum, range.maximum)
    } else {
        (profile.lockedShutterNanos.takeIf { it > 0L } ?: range.current)
            .coerceIn(range.minimum, range.maximum)
    }
    activeManualControl = null
    activeProgramAxis = axis
    // No text label under the histogram: the ruler tick code (ISO/S) plus the
    // exposure chips carry the values. LIMITS opens the full PROGRAM editor.
    manualLimits.visibility = View.VISIBLE
    manualLimits.contentDescription = if (isIso) {
        "ISO lock limits and metering editor."
    } else {
        "Shutter lock limits and metering editor."
    }
    manualSlider.label = shortControlName(axis)
    manualSlider.max = SLIDER_STEPS
    manualSlider.setProgressFromUser(sliderProgress(axis, range.copy(current = current)), fromUser = false)
    manualAuto.text = "AUTO"
    manualAuto.isEnabled = true
    refreshRuleSliderAccessibility()
    manualPanel.visibility = View.VISIBLE
    updateOverlayStack()
    manualPanel.post { updateOverlayStack() }
    scheduleManualPanelHide()
}

/** Live-scrub a PROGRAM axis lock: engage the lock immediately, persist on release. */
internal fun MainActivity.onProgramAxisScrubbed(axis: ManualControl, progress: Int) {
    val range = manualRangeFor(axis) ?: return
    val value = sliderValue(axis, range, progress)
    val current = programProfile()
    val next = when (axis) {
        ManualControl.ISO -> current.copy(
            lockMode = ProgramLockMode.ISO_LOCK, lockedIso = value.toInt()
        )
        else -> current.copy(
            lockMode = ProgramLockMode.SHUTTER_LOCK, lockedShutterNanos = value
        )
    }
    saveProgramProfile(next)
    manualAuto.isEnabled = true
}

internal fun MainActivity.commitProgramAxis(axis: ManualControl, progress: Int) {
    onProgramAxisScrubbed(axis, progress)
    updateProgramChipStates()
    setStatus(
        if (axis == ManualControl.ISO) "ISO LOCK • SHUTTER AUTO"
        else "SHUTTER LOCK • ISO AUTO"
    )
}


internal fun MainActivity.openManualModeThen(control: ManualControl) {
    // While rolling the stills mode switch would only affect the next preview;
    // the take request already owns the sensor, so open the tile directly.
    if (videoRecording) {
        isoControl.post { showManualControl(control) }
        return
    }
    if (captureExposureMode != CaptureExposureMode.MANUAL) applyCaptureExposureMode(CaptureExposureMode.MANUAL)
    isoControl.post { showManualControl(control) }
}

internal fun MainActivity.showProgramPanelHintOnce() {
    if (programHintShown) return
    programHintShown = true
    val profile = programProfile()
    setStatus("P ${programLockText(profile.lockMode).substringAfter("Lock: ")} " +
        "${String.format(Locale.US, "%.2f", profile.balance)} " +
        "${programEvBiasText(profile.evBias)} • ISO/S edits")
}

internal fun MainActivity.programBalanceText(balance: Float): String = when {
    balance > 0.55f -> "Priority: ${String.format(Locale.US, "%.2f", balance)} • faster shutter"
    balance < 0.45f -> "Priority: ${String.format(Locale.US, "%.2f", balance)} • lower ISO"
    else -> "Priority: ${String.format(Locale.US, "%.2f", balance)} • balanced"
}

internal fun MainActivity.programIsoBoundText(value: Int, isMin: Boolean): String =
    (if (isMin) "ISO min: " else "ISO max: ") +
        (value.takeIf { it > 0 }?.toString() ?: if (isMin) "SENSOR MIN" else "SENSOR MAX")

internal fun MainActivity.programShutterBoundText(value: Long, isMin: Boolean, autoSafe: Boolean): String =
    (if (isMin) "Shutter min: " else "Shutter max: ") + when {
        value > 0L -> formatShutter(value)
        !isMin && autoSafe -> "AUTO HANDHELD"
        isMin -> "SENSOR MIN"
        else -> "SENSOR MAX"
    }

internal fun MainActivity.programLockText(mode: ProgramLockMode): String = when (mode) {
    ProgramLockMode.NONE -> "Lock: NONE (both auto)"
    ProgramLockMode.ISO_LOCK -> "Lock: ISO (shutter auto)"
    ProgramLockMode.SHUTTER_LOCK -> "Lock: SHUTTER (ISO auto)"
}

/**
 * Seeds a freshly engaged lock from the live sensor values so it holds a real
 * exposure instead of 0 (which would make the "locked" axis follow the scene).
 */
internal fun MainActivity.seedLockedValuesFor(profile: ProgramAeProfile): ProgramAeProfile {
    var seeded = profile
    if (profile.lockMode == ProgramLockMode.ISO_LOCK && profile.lockedIso <= 0) {
        val range = controller.manualControlRange(ManualControl.ISO)
        seeded = seeded.copy(lockedIso = (range?.current?.toInt() ?: 0))
    }
    if (profile.lockMode == ProgramLockMode.SHUTTER_LOCK && profile.lockedShutterNanos <= 0L) {
        val range = controller.manualControlRange(ManualControl.SHUTTER)
        seeded = seeded.copy(lockedShutterNanos = range?.current ?: 0L)
    }
    return seeded
}

internal fun MainActivity.programMeteringLabel(metering: ProgramMetering): String = when (metering) {
    ProgramMetering.CENTER_WEIGHTED -> "CENTER"
    ProgramMetering.AVERAGE -> "AVERAGE"
    ProgramMetering.SPOT -> "SPOT"
    ProgramMetering.HIGHLIGHT_WEIGHTED -> "HIGHLIGHT"
}

internal fun MainActivity.nextProgramMetering(metering: ProgramMetering): ProgramMetering = when (metering) {
    ProgramMetering.CENTER_WEIGHTED -> ProgramMetering.AVERAGE
    ProgramMetering.AVERAGE -> ProgramMetering.SPOT
    ProgramMetering.SPOT -> ProgramMetering.HIGHLIGHT_WEIGHTED
    ProgramMetering.HIGHLIGHT_WEIGHTED -> ProgramMetering.CENTER_WEIGHTED
}

internal fun MainActivity.programEvBiasText(bias: Float): String =
    String.format(Locale.US, "Brightness bias: %+.1f EV", bias)

/** Viewfinder PROGRAM editor: priority slider, locks, per-lens min/max, brightness bias. */
internal fun MainActivity.showProgramPanel() {
    closeFloatingPanels()
    var profile = programProfile()
    fun apply() = saveProgramProfile(profile)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    val lensLabel = TextView(this).apply {
        text = "PROGRAM • per-lens ${activeProfileLensId() ?: "default"} • RAW-driven (sensor ISO + shutter)"
        setTextColor(getColor(R.color.text_secondary))
        textSize = 11f
    }
    container.addView(lensLabel)
    if (!controller.hasManualSensorControl()) {
        container.addView(TextView(this).apply {
            text = "Android AE • this camera has no manual sensor control; " +
                "locks and limits are unavailable."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })
    }
    container.addView(TextView(this).apply {
        text = "Hold ISO / SHUTTER chips to lock • tap for slider • Auto releases both • " +
            "lock both for manual"
        setTextColor(getColor(R.color.text_secondary))
        textSize = 11f
        setPadding(0, dp(4), 0, 0)
    })
    fun styleDialogButton(button: Button, active: Boolean = false) {
        button.background = getDrawable(
            if (active) R.drawable.control_chip_active else R.drawable.control_chip
        )
        button.setTextColor(
            getColor(if (active) R.color.accent_dark else R.color.text_primary)
        )
        button.textSize = 12f
        button.setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    val balanceLabel = TextView(this).apply {
        text = programBalanceText(profile.balance)
        setTextColor(getColor(R.color.text_primary))
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(12), 0, 0)
    }
    container.addView(balanceLabel)
    container.addView(TextView(this).apply {
        text = "← ISO priority   |   balanced   |   shutter priority →"
        setTextColor(getColor(R.color.text_secondary))
        textSize = 11f
    })
    val balanceSlider = RuleSliderView(this).apply {
        label = "BAL"
        contentRotation = controlRotationDegrees
        max = 100
        setProgressFromUser((profile.balance * 100).toInt().coerceIn(0, max), fromUser = false)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (64f * resources.displayMetrics.density).toInt()
        )
    }
    container.addView(balanceSlider)
    balanceSlider.onProgressChanged = { progress, fromUser ->
        if (fromUser) {
            profile = profile.copy(balance = progress / 100f)
            balanceLabel.text = programBalanceText(profile.balance)
        }
    }
    balanceSlider.onStopTracking = { apply() }
    val lockButton = Button(this).also { styleDialogButton(it) }
    val lockedIsoButton = Button(this).also { styleDialogButton(it) }
    val lockedShutterButton = Button(this).also { styleDialogButton(it) }
    fun refreshLock() {
        lockButton.text = programLockText(profile.lockMode) + " • tap to cycle"
        styleDialogButton(lockButton, active = profile.lockMode != ProgramLockMode.NONE)
    }
    fun refreshLockedButtons() {
        val isoRange = controller.manualControlRange(ManualControl.ISO)
        val shutterRange = controller.manualControlRange(ManualControl.SHUTTER)
        lockedIsoButton.text = "Locked ISO: " + when {
            profile.lockMode != ProgramLockMode.ISO_LOCK -> "-- (ISO lock off)"
            profile.lockedIso > 0 -> "ISO ${profile.lockedIso}"
            isoRange != null -> "ISO ${isoRange.current} (live)"
            else -> "--"
        }
        lockedIsoButton.isEnabled = profile.lockMode == ProgramLockMode.ISO_LOCK
        styleDialogButton(lockedIsoButton, active = profile.lockMode == ProgramLockMode.ISO_LOCK)
        lockedIsoButton.alpha = if (profile.lockMode == ProgramLockMode.ISO_LOCK) 1f else 0.6f
        lockedShutterButton.text = "Locked shutter: " + when {
            profile.lockMode != ProgramLockMode.SHUTTER_LOCK -> "-- (shutter lock off)"
            profile.lockedShutterNanos > 0L -> formatShutter(profile.lockedShutterNanos)
            shutterRange != null -> "${formatShutter(shutterRange.current)} (live)"
            else -> "--"
        }
        lockedShutterButton.isEnabled = profile.lockMode == ProgramLockMode.SHUTTER_LOCK
        styleDialogButton(lockedShutterButton, active = profile.lockMode == ProgramLockMode.SHUTTER_LOCK)
        lockedShutterButton.alpha = if (profile.lockMode == ProgramLockMode.SHUTTER_LOCK) 1f else 0.6f
    }
    refreshLock()
    refreshLockedButtons()
    lockButton.setOnClickListener {
        profile = seedLockedValuesFor(profile.copy(lockMode = when (profile.lockMode) {
            ProgramLockMode.NONE -> ProgramLockMode.ISO_LOCK
            ProgramLockMode.ISO_LOCK -> ProgramLockMode.SHUTTER_LOCK
            ProgramLockMode.SHUTTER_LOCK -> ProgramLockMode.NONE
        }))
        refreshLock(); refreshLockedButtons(); apply()
        setStatus("PROGRAM ${profile.lockMode.name}")
    }
    container.addView(lockButton)
    lockedIsoButton.setOnClickListener {
        val range = controller.manualControlRange(ManualControl.ISO) ?: return@setOnClickListener
        val steps = listOf(100, 200, 400, 800, 1600, 3200, 6400)
            .filter { it in range.minimum..range.maximum }
        if (steps.isEmpty()) return@setOnClickListener
        val current = profile.lockedIso.takeIf { it > 0 } ?: range.current.toInt()
        val next = steps[(steps.indexOfClosest(current) + 1) % steps.size]
        profile = profile.copy(lockedIso = next)
        refreshLockedButtons(); apply()
    }
    lockedShutterButton.setOnClickListener {
        val range = controller.manualControlRange(ManualControl.SHUTTER) ?: return@setOnClickListener
        val steps = listOf(
            1_000_000_000L / 1000, 1_000_000_000L / 500, 1_000_000_000L / 250,
            1_000_000_000L / 125, 1_000_000_000L / 60, 1_000_000_000L / 30,
            1_000_000_000L / 15, 1_000_000_000L / 8, 1_000_000_000L / 4
        ).filter { it in range.minimum..range.maximum }
        if (steps.isEmpty()) return@setOnClickListener
        val current = profile.lockedShutterNanos.takeIf { it > 0L } ?: range.current
        val next = steps[(steps.indexOfClosest(current) + 1) % steps.size]
        profile = profile.copy(lockedShutterNanos = next)
        refreshLockedButtons(); apply()
    }
    refreshLockedButtons()
    container.addView(lockedIsoButton)
    container.addView(lockedShutterButton)
    val isoMinButton = Button(this).also { styleDialogButton(it) }
    val isoMaxButton = Button(this).also { styleDialogButton(it) }
    val shutterMinButton = Button(this).also { styleDialogButton(it) }
    val shutterMaxButton = Button(this).also { styleDialogButton(it) }
    fun refreshBounds() {
        isoMinButton.text = programIsoBoundText(profile.isoMin, isMin = true)
        isoMaxButton.text = programIsoBoundText(profile.isoMax, isMin = false)
        shutterMinButton.text = programShutterBoundText(profile.shutterMinNanos, isMin = true, autoSafe = false)
        shutterMaxButton.text = programShutterBoundText(
            profile.shutterMaxNanos, isMin = false, autoSafe = profile.useAutoSafeShutter
        )
        styleDialogButton(isoMinButton, active = profile.isoMin > 0)
        styleDialogButton(isoMaxButton, active = profile.isoMax > 0)
        styleDialogButton(shutterMinButton, active = profile.shutterMinNanos > 0L)
        styleDialogButton(
            shutterMaxButton,
            active = profile.shutterMaxNanos > 0L || profile.useAutoSafeShutter
        )
    }
    isoMinButton.setOnClickListener {
        val range = controller.manualControlRange(ManualControl.ISO)
        val sensorMin = range?.minimum?.toInt() ?: 100
        val values = listOf(0, sensorMin, 100, 200, 400, 800).distinct()
        profile = profile.copy(isoMin = values[(values.indexOf(profile.isoMin).takeIf { it >= 0 } ?: 0)
            .let { (it + 1) % values.size }])
        if (profile.isoMax > 0 && profile.isoMin > profile.isoMax) profile = profile.copy(isoMax = 0)
        refreshBounds(); apply()
    }
    isoMaxButton.setOnClickListener {
        val values = listOf(0, 400, 800, 1600, 3200, 6400)
        profile = profile.copy(isoMax = values[(values.indexOf(profile.isoMax).takeIf { it >= 0 } ?: 0)
            .let { (it + 1) % values.size }])
        if (profile.isoMin > 0 && profile.isoMax > 0 && profile.isoMin > profile.isoMax) {
            profile = profile.copy(isoMin = 0)
        }
        refreshBounds(); apply()
    }
    shutterMinButton.setOnClickListener {
        val values = listOf(0L, 1_000_000_000L / 1000, 1_000_000_000L / 500,
            1_000_000_000L / 250, 1_000_000_000L / 125, 1_000_000_000L / 60)
        profile = profile.copy(shutterMinNanos = values[
            (values.indexOf(profile.shutterMinNanos).takeIf { it >= 0 } ?: 0).let { (it + 1) % values.size }])
        refreshBounds(); apply()
    }
    shutterMaxButton.setOnClickListener {
        val values = listOf(0L, 1_000_000_000L / 15, 1_000_000_000L / 30,
            1_000_000_000L / 60, 1_000_000_000L / 125, 1_000_000_000L / 250)
        if (profile.useAutoSafeShutter) {
            profile = profile.copy(useAutoSafeShutter = false, shutterMaxNanos = 0L)
        } else {
            val next = values[(values.indexOf(profile.shutterMaxNanos).takeIf { it >= 0 } ?: 0)
                .let { (it + 1) % values.size }]
            profile = profile.copy(
                shutterMaxNanos = next,
                useAutoSafeShutter = next == 0L
            )
        }
        refreshBounds(); apply()
    }
    refreshBounds()
    container.addView(isoMinButton)
    container.addView(isoMaxButton)
    container.addView(shutterMinButton)
    container.addView(shutterMaxButton)
    val biasLabel = TextView(this).apply {
        text = programEvBiasText(profile.evBias)
        setTextColor(getColor(R.color.text_primary))
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(12), 0, 0)
    }
    container.addView(biasLabel)
    val biasSlider = RuleSliderView(this).apply {
        label = "EV"
        max = 60
        setProgressFromUser(
            ((profile.evBias - ProgramAeProfile.MIN_EV_BIAS) * 20f).toInt().coerceIn(0, max),
            fromUser = false
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (64f * resources.displayMetrics.density).toInt()
        )
    }
    container.addView(biasSlider)
    biasSlider.onProgressChanged = { progress, fromUser ->
        if (fromUser) {
            profile = profile.copy(evBias = ProgramAeProfile.MIN_EV_BIAS + progress / 20f)
            biasLabel.text = programEvBiasText(profile.evBias)
        }
    }
    biasSlider.onStopTracking = { apply() }
    container.addView(TextView(this).apply {
        text = "ETTR overrides PROGRAM still exposure when converged."
        setTextColor(getColor(R.color.text_secondary))
        textSize = 11f
        setPadding(0, dp(8), 0, 0)
    })
    val meteringButton = Button(this).also { styleDialogButton(it) }
    fun refreshMetering() {
        meteringButton.text = "Metering: ${programMeteringLabel(profile.metering)} • tap to switch"
    }
    refreshMetering()
    meteringButton.setOnClickListener {
        profile = profile.copy(metering = nextProgramMetering(profile.metering))
        refreshMetering(); apply()
        setStatus("PROGRAM METER ${profile.metering.name}")
    }
    container.addView(meteringButton)
    container.addView(Button(this).also { styleDialogButton(it) }.apply {
        text = "Reset per-lens defaults"
        setOnClickListener {
            profile = ProgramAeProfile()
            balanceSlider.setProgressFromUser((profile.balance * 100).toInt(), fromUser = false)
            biasSlider.setProgressFromUser(
                ((profile.evBias - ProgramAeProfile.MIN_EV_BIAS) * 20f).toInt().coerceIn(0, 60),
                fromUser = false
            )
            balanceLabel.text = programBalanceText(profile.balance)
            biasLabel.text = programEvBiasText(profile.evBias)
            refreshLock(); refreshLockedButtons(); refreshBounds(); refreshMetering(); apply()
        }
    })
    AlertDialog.Builder(this)
        .setTitle("PROGRAM AE • custom")
        .setView(ScrollView(this).apply { addView(container) })
        .setPositiveButton("Done", null)
        .show()
}

internal fun List<Int>.indexOfClosest(value: Int): Int {
    if (isEmpty()) return 0
    return indices.minByOrNull { kotlin.math.abs(this[it] - value) } ?: 0
}

internal fun List<Long>.indexOfClosest(value: Long): Int {
    if (isEmpty()) return 0
    return indices.minByOrNull { kotlin.math.abs(this[it] - value) } ?: 0
}
