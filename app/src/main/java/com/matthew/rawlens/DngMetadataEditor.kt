// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

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

// Per-lens DNG calibration overrides (levels, noise, matrices). Moved verbatim from MainActivity.

internal fun MainActivity.showDngMetadataOverrideEditor() {
    val cameraId = controller.activeCameraId()
    if (cameraId == null) {
        setStatus("WAIT FOR CAMERA")
        return
    }
    val current = dngMetadataOverrideStore.get(cameraId)
    val defaults = controller.dngMetadataDefaults()
    val configured = listOf(
        current.blackLevels != null || current.whiteLevel != null,
        current.noiseProfile != null,
        current.colorMatrix1 != null || current.colorMatrix2 != null,
        current.cameraCalibration1 != null || current.cameraCalibration2 != null,
        current.forwardMatrix1 != null || current.forwardMatrix2 != null
    ).count { it }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        addView(TextView(this@showDngMetadataOverrideEditor).apply {
            text = "Camera $cameraId • $configured override group${if (configured == 1) "" else "s"} active\n" +
                "Device default preserves the DNG tags reported by this sensor. Custom replaces only the selected tag."
            setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })
    }
    fun row(title: String, detail: String, active: Boolean, action: () -> Unit) =
        content.addView(twoLineChipButton(title, detail, active, action))
    row("Sensor levels", "Black RGGB and white level",
        current.blackLevels != null || current.whiteLevel != null) { showDngLevelsEditor(cameraId, defaults) }
    row("Noise model", "R, G and B scale / offset pairs", current.noiseProfile != null) { showDngNoiseEditor(cameraId, defaults) }
    row("Color matrices", "ColorMatrix 1 and 2",
        current.colorMatrix1 != null || current.colorMatrix2 != null) {
        showDngMatrixEditor(cameraId, "Color matrices", "ColorMatrix", defaults.colorMatrix1, defaults.colorMatrix2,
            { it.colorMatrix1 }, { it.colorMatrix2 }, { profile, first, second -> profile.copy(colorMatrix1 = first, colorMatrix2 = second) })
    }
    row("Camera calibration", "CalibrationMatrix 1 and 2",
        current.cameraCalibration1 != null || current.cameraCalibration2 != null) {
        showDngMatrixEditor(cameraId, "Camera calibration", "CameraCalibration", defaults.cameraCalibration1, defaults.cameraCalibration2,
            { it.cameraCalibration1 }, { it.cameraCalibration2 }, { profile, first, second -> profile.copy(cameraCalibration1 = first, cameraCalibration2 = second) })
    }
    row("Forward matrices", "ForwardMatrix 1 and 2",
        current.forwardMatrix1 != null || current.forwardMatrix2 != null) {
        showDngMatrixEditor(cameraId, "Forward matrices", "ForwardMatrix", defaults.forwardMatrix1, defaults.forwardMatrix2,
            { it.forwardMatrix1 }, { it.forwardMatrix2 }, { profile, first, second -> profile.copy(forwardMatrix1 = first, forwardMatrix2 = second) })
    }
    row("PROGRAM AE limits", "ISO/shutter bounds for this lens",
        programAeProfileStore.hasProfile(cameraId)) { showProgramPanel() }
    row("Vendor keys", "Session + request tags for this lens",
        vendorKeyStore.get(cameraId).activeEntries().isNotEmpty()) { showVendorKeysEditor() }
    AlertDialog.Builder(this).setTitle("RAW DNG calibration")
        .setView(ScrollView(this).apply { addView(content) })
        .setNeutralButton("Reset all", null).setNegativeButton("Close", null)
        .show().also { dialog ->
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dngMetadataOverrideStore.clear(cameraId)
                dialog.dismiss()
                setStatus("CALIB RESET")
            }
        }
}

internal fun MainActivity.showDngLevelsEditor(cameraId: String, defaults: DngMetadataDefaults) {
    val current = dngMetadataOverrideStore.get(cameraId)
    val black = current.blackLevels ?: defaults.blackLevels
    val white = current.whiteLevel ?: defaults.whiteLevel
    val available = black != null && white != null
    val inputs = listOf("Black R", "Black G1", "Black G2", "Black B").mapIndexed { index, label ->
        metadataField(label, black?.get(index) ?: 0.0)
    } + metadataField("White level", white ?: 0.0)
    showDngGroupDialog(
        "Sensor levels", cameraId, "Uses the camera's declared black RGGB pattern and white level. " +
            "Custom levels affect how RAW editors decode the file.", available,
        current.blackLevels != null || current.whiteLevel != null, inputs
    ) { custom ->
        if (custom) {
            val values = metadataValues(inputs) ?: return@showDngGroupDialog false
            val updated = current.copy(blackLevels = values.take(4), whiteLevel = values[4])
            saveDngMetadata(cameraId, updated)
        } else saveDngMetadata(cameraId, current.copy(blackLevels = null, whiteLevel = null))
    }
}

internal fun MainActivity.showDngNoiseEditor(cameraId: String, defaults: DngMetadataDefaults) {
    val current = dngMetadataOverrideStore.get(cameraId)
    val values = current.noiseProfile ?: defaults.noiseProfile
    val available = values != null && values.size in listOf(6, 8)
    val labels = if (values?.size == 8) listOf("R scale", "R offset", "G1 scale", "G1 offset", "G2 scale", "G2 offset", "B scale", "B offset")
        else listOf("R scale", "R offset", "G scale", "G offset", "B scale", "B offset")
    val inputs = labels.mapIndexed { index, label -> metadataField(label, values?.get(index) ?: 0.0) }
    showDngGroupDialog(
        "Noise profile", cameraId, "Every channel has a scale and offset. Keep Device default unless you have a measured sensor noise profile.",
        available, current.noiseProfile != null, inputs
    ) { custom ->
        if (custom) saveDngMetadata(cameraId, current.copy(noiseProfile = metadataValues(inputs) ?: return@showDngGroupDialog false))
        else saveDngMetadata(cameraId, current.copy(noiseProfile = null))
    }
}

internal fun MainActivity.showDngMatrixEditor(
    cameraId: String, title: String, tagName: String, defaultFirst: List<Double>?, defaultSecond: List<Double>?,
    firstOverride: (DngMetadataOverrides) -> List<Double>?, secondOverride: (DngMetadataOverrides) -> List<Double>?,
    update: (DngMetadataOverrides, List<Double>?, List<Double>?) -> DngMetadataOverrides
) {
    val current = dngMetadataOverrideStore.get(cameraId)
    val first = firstOverride(current) ?: defaultFirst
    val second = secondOverride(current) ?: defaultSecond
    val available = first?.size == 9 && second?.size == 9
    val inputs = (first ?: List(9) { 0.0 }).mapIndexed { index, value -> metadataField("$tagName 1 • ${index / 3 + 1},${index % 3 + 1}", value) } +
        (second ?: List(9) { 0.0 }).mapIndexed { index, value -> metadataField("$tagName 2 • ${index / 3 + 1},${index % 3 + 1}", value) }
    showDngGroupDialog(
        title, cameraId, "Two 3×3 matrices. Values are shown row by row, directly matching DNG $tagName tags.", available,
        firstOverride(current) != null || secondOverride(current) != null, inputs
    ) { custom ->
        if (custom) {
            val values = metadataValues(inputs) ?: return@showDngGroupDialog false
            saveDngMetadata(cameraId, update(current, values.take(9), values.drop(9)))
        } else saveDngMetadata(cameraId, update(current, null, null))
    }
}

internal fun MainActivity.showDngGroupDialog(
    title: String, cameraId: String, description: String, available: Boolean, customInitially: Boolean,
    inputs: List<EditText>, save: (Boolean) -> Boolean
) {
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        addView(TextView(this@showDngGroupDialog).apply {
            text = if (available) description else "This camera does not currently declare this DNG tag, so it cannot be overridden safely."
            setTextColor(getColor(R.color.text_secondary)); textSize = 12f
        })
    }
    val toggle = CheckBox(this).apply {
        text = "Use custom $title"
        isChecked = customInitially
        isEnabled = available
        setTextColor(getColor(R.color.text_primary))
    }
    container.addView(toggle)
    inputs.forEach { input ->
        container.addView(TextView(this).apply {
            text = input.hint
            setTextColor(getColor(R.color.text_secondary)); textSize = 11f
            setPadding(0, dp(8), 0, 0)
        })
        input.hint = ""
        container.addView(input)
    }
    fun updateEnabled() = inputs.forEach { it.isEnabled = toggle.isChecked && available }
    toggle.setOnCheckedChangeListener { _, _ -> updateEnabled() }
    updateEnabled()
    val dialog = AlertDialog.Builder(this).setTitle(title)
        .setView(ScrollView(this).apply { addView(container) })
        .setNegativeButton("Cancel", null).setPositiveButton("Save", null).show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        if (save(toggle.isChecked)) dialog.dismiss()
    }
}

internal fun MainActivity.metadataField(label: String, value: Double): EditText = EditText(this).apply {
    hint = label
    setText(formatMetadataNumber(value))
    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    setSelectAllOnFocus(true)
}

internal fun MainActivity.metadataValues(inputs: List<EditText>): List<Double>? {
    val values = inputs.map { input -> input.text.toString().trim().toDoubleOrNull()?.takeIf(Double::isFinite) }
    if (values.any { it == null }) {
        inputs.zip(values).firstOrNull { it.second == null }?.first?.error = "Enter a finite number"
        return null
    }
    return values.filterNotNull()
}

internal fun MainActivity.formatMetadataNumber(value: Double): String = String.format(Locale.US, "%.8f", value)
    .trimEnd('0').trimEnd('.').ifEmpty { "0" }

internal fun MainActivity.saveDngMetadata(cameraId: String, overrides: DngMetadataOverrides): Boolean {
    try {
        dngMetadataOverrideStore.save(cameraId, overrides)
        setStatus(if (overrides.isEmpty()) "CALIB DEFAULT" else "CALIB SAVED")
        return true
    } catch (failure: Exception) {
        setStatus("CALIB NOT SAVED")
        return false
    }
}
