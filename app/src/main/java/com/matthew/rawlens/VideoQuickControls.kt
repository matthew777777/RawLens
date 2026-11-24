// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.view.View
import android.widget.TextView

internal fun MainActivity.setEncodedVideoMode(enabled: Boolean) {
    if (videoRecording || videoRecorder != null || encodedVideoRecorder != null) {
        setStatus("STOP RECORDING TO SWITCH VIDEO FORMAT")
        return
    }
    recordingTools.cancelFocusPull()
    recordingTools.wakePreview()
    encodedVideoEnabled = enabled
    lensPreferences().edit().putBoolean(MainActivity.KEY_ENCODED_VIDEO, enabled).apply()
    if (isVideoMode) {
        findViewById<CameraGuideOverlay>(R.id.guideOverlay).videoCrop = if (enabled) encodedVideoCrop() else videoCrop
        refreshCaptureFormatControl()
        refreshVideoSensorInfo()
        refreshVideoAudioDescription()
        findViewById<View>(R.id.shutter).contentDescription =
            if (enabled) "Record Encoded Video" else "Record RAW Video"
    }
    updateQuickControls()
    setStatus(if (enabled) "${encodedVideoProfile().hudLabel} • HEVC 10-BIT" else "VIDEO READY")
}

internal fun MainActivity.wireVideoQuickControls() {
    findViewById<View>(R.id.videoModeQuick).setOnClickListener {
        setEncodedVideoMode(!encodedVideoEnabled)
    }
    findViewById<View>(R.id.videoBinningQuick).setOnClickListener {
        if (videoRecording || encodedVideoEnabled) return@setOnClickListener
        val enabled = !lensPreferences().getBoolean(MainActivity.KEY_VIDEO_BINNING, false)
        lensPreferences().edit().putBoolean(MainActivity.KEY_VIDEO_BINNING, enabled).apply()
        refreshVideoSensorInfo()
        updateQuickControls()
        setStatus(if (enabled) "BINNING ON" else "BINNING OFF")
    }
    findViewById<View>(R.id.encodedVideoCropQuick).setOnClickListener { cycleEncodedVideoCrop() }
    findViewById<View>(R.id.videoResolutionQuick).setOnClickListener { cycleEncodedVideoRes() }
    findViewById<View>(R.id.videoFpsQuick).setOnClickListener {
        if (encodedVideoEnabled) cycleEncodedVideoFps() else chooseRawVideoFrameRate()
    }
}

/** Seat the shared profile/crop control beside format; restore its photo position. */
internal fun MainActivity.arrangeQuickControlRows() {
    val photoRow = findViewById<android.widget.LinearLayout>(R.id.photoCaptureQuickRow)
    val videoRow = findViewById<android.widget.LinearLayout>(R.id.videoModeQuickRow)
    val destination = if (isVideoMode) videoRow else photoRow
    if (releaseQuick.parent !== destination) {
        (releaseQuick.parent as? android.view.ViewGroup)?.removeView(releaseQuick)
        destination.addView(releaseQuick, if (isVideoMode) destination.childCount else 1)
    }
    (releaseQuick.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
        marginStart = dp(8)
        releaseQuick.layoutParams = this
    }
}

internal fun MainActivity.updateVideoQuickControls() {
    findViewById<View>(R.id.videoModeQuickRow).visibility = if (isVideoMode) View.VISIBLE else View.GONE
    findViewById<View>(R.id.videoFormatQuickRow).visibility =
        if (isVideoMode) View.VISIBLE else View.GONE
    val idle = !videoRecording && videoRecorder == null && encodedVideoRecorder == null
    findViewById<View>(R.id.recordingPreviewSleep).visibility = if (isVideoMode) View.VISIBLE else View.GONE
    findViewById<View>(R.id.videoQuickHeading).visibility =
        if (isVideoMode && !idle) View.VISIBLE else View.GONE
    fun tile(id: Int, label: String, active: Boolean, enabled: Boolean) {
        findViewById<QuickControlTileView>(id).apply {
            valueFirst = true
            setTextSize(14f)
            setTextIfChanged(label)
            isEnabled = enabled
            alpha = if (enabled) 1f else .4f
            contentDescription = label.replace('\n', ' ')
            setQuickTileState(this, active)
        }
    }
    tile(R.id.videoModeQuick, "Format\n${if (encodedVideoEnabled) "HEVC 10-bit" else "CRAW"}", encodedVideoEnabled, idle)
    findViewById<View>(R.id.videoBinningQuick).visibility = if (encodedVideoEnabled) View.GONE else View.VISIBLE
    findViewById<View>(R.id.encodedVideoCropQuick).visibility =
        if (isVideoMode && encodedVideoEnabled) View.VISIBLE else View.GONE
    val crop = encodedVideoCrop()
    val cropLabel = when (crop) {
        VideoCrop.OPEN_GATE -> "GATE"
        VideoCrop.WIDE_16_9 -> "16:9"
        VideoCrop.SCOPE_2_39 -> "2.39"
        VideoCrop.UNIVISIUM_2_00 -> "2.00"
    }
    tile(R.id.encodedVideoCropQuick, "CROP\n$cropLabel", crop != VideoCrop.WIDE_16_9, idle)
    val binning = lensPreferences().getBoolean(MainActivity.KEY_VIDEO_BINNING, false)
    tile(R.id.videoBinningQuick, "BINNING\n${if (binning) "ON" else "OFF"}", binning, idle)
    findViewById<View>(R.id.videoResolutionQuick).visibility = if (encodedVideoEnabled) View.VISIBLE else View.GONE
    val res = encodedVideoResPref()
    val size = when (res.width) {
        3840 -> "4K"
        2560 -> "QHD"
        1920 -> "FHD"
        else -> "${res.width}×${res.height}"
    }
    tile(R.id.videoResolutionQuick, "Size\n$size", false, idle)
    findViewById<View>(R.id.videoResolutionQuick).contentDescription = "Size ${res.width} by ${res.height}"
    tile(R.id.videoFpsQuick, "Frame rate\n${if (encodedVideoEnabled) encodedVideoFpsPref().toString() else rawVideoFrameRateLabel()}", false, idle)
    (releaseQuick as QuickControlTileView).apply {
        valueFirst = isVideoMode
        setTextSize(if (isVideoMode) 14f else 13f)
    }
    releaseQuick.isEnabled = if (isVideoMode) !encodedVideoEnabled || idle else !dualRawEnabled
    releaseQuick.alpha = if (releaseQuick.isEnabled) 1f else .4f
}

internal fun MainActivity.rawVideoFpsPref(): Int {
    val id=controller.activeSettingsKey() ?: return 30
    return vendorKeyStore.get(id).rawPreviewFps.takeIf { it > 0 } ?: 30
}

/** Shares the per-lens sensor AE range with the vendor-key editor. */
internal fun MainActivity.rawVideoFrameRateLabel(): String {
    val cameraId = controller.activeSettingsKey() ?: return "Default"
    val fps = vendorKeyStore.get(cameraId).rawPreviewFps
    return if (fps == 0) "Default" else "$fps FPS"
}

internal fun MainActivity.chooseRawVideoFrameRate(onChanged: () -> Unit = {}) {
    if (videoRecording || videoRecorder != null || encodedVideoRecorder != null) return
    val cameraId = controller.activeSettingsKey() ?: return
    val profile = vendorKeyStore.get(cameraId)
    val rates = if(redmiRootMode()==LapisRootProfile.Mode.VIDEO60) listOf(0,24,30,60)
        else (listOf(0,24,30,60) + controller.vendorRawPreviewFpsOptions()).distinct()
    val labels = rates.map { fps ->
        if (fps == 0) "Default" else {
            val ranges=controller.vendorRawPreviewFpsRanges().map { it.first to it.last }
            val range=EncodedVideoProbe.fixedFpsRange(fps,ranges)
            if (redmiRootMode()==LapisRootProfile.Mode.VIDEO60) "$fps FPS (AE $fps,$fps)"
            else range?.let { "$fps FPS (AE ${it.first},${it.second})" } ?: "$fps FPS (not advertised)"
        }
    }.toTypedArray()
    showRawFrameRateDialog(labels, rates.indexOf(profile.rawPreviewFps)) { dialog, which ->
            if (videoRecording || videoRecorder != null || encodedVideoRecorder != null ||
                controller.activeSettingsKey() != cameraId) {
                dialog.dismiss()
                return@showRawFrameRateDialog
            }
            val fps = rates[which]
            if (fps == profile.rawPreviewFps) {
                dialog.dismiss()
                return@showRawFrameRateDialog
            }
            try {
                vendorKeyStore.update(cameraId) { it.copy(rawPreviewFps = fps) }
                if(redmiRootMode()==LapisRootProfile.Mode.VIDEO60) LapisRootProfile.preferences(this).edit().putInt("video_fps",fps.takeIf { it>0 } ?: 60).apply()
            } catch (failure: Exception) {
                setStatus("FRAME RATE NOT SAVED: ${failure.message.orEmpty()}")
                return@showRawFrameRateDialog
            }
            dialog.dismiss()
            updateQuickControls()
            onChanged()
            controller.reopenCamera()
            setStatus("RAW ${rawVideoFrameRateLabel()} • RESTARTING CAMERA")
        }
}

/** The camera activity stays portrait; rotate the whole picker to match its controls. */
private fun MainActivity.showRawFrameRateDialog(
    labels: Array<String>, selected: Int,
    onSelected: (android.app.Dialog, Int) -> Unit
) {
    val dialog = android.app.Dialog(this)
    val root = android.widget.FrameLayout(this)
    val column = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundResource(R.drawable.quick_panel)
    }
    column.addView(TextView(this).apply {
        text = "RAW video frame rate"
        textSize = 20f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(getColor(R.color.text_primary))
        setPadding(dp(8), dp(8), dp(8), dp(16))
    })
    val choices = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
    }
    labels.forEachIndexed { index, label ->
        val active = index == selected
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            val shape = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(getColor(if (active) R.color.accent_soft else R.color.surface_container))
                setStroke(dp(1), getColor(if (active) R.color.accent else R.color.outline))
            }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(getColor(R.color.accent_soft)), shape, null)
            isFocusable = true
            contentDescription = label
            isSelected = active
            setOnClickListener { onSelected(dialog, index) }
        }
        val copy = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        copy.addView(TextView(this).apply {
            text = label.substringBefore(" (")
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(getColor(if (active) R.color.accent else R.color.text_primary))
        })
        val detail = if (label == "Default") "Use camera default" else label.substringAfter(" (", "").removeSuffix(")")
        if (detail.isNotEmpty()) copy.addView(TextView(this).apply {
            text = detail
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(copy, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(android.widget.RadioButton(this).apply {
            isChecked = active
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            buttonTintList = android.content.res.ColorStateList.valueOf(
                getColor(if (active) R.color.accent else R.color.text_muted))
        }, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)))
        choices.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
    }
    column.addView(android.widget.ScrollView(this).apply {
        isFillViewport = false
        addView(choices)
    }, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
    column.addView(TextView(this).apply {
        text = "Cancel"
        textSize = 14f
        gravity = android.view.Gravity.CENTER
        setTextColor(getColor(R.color.accent))
        minimumHeight = dp(48)
        isFocusable = true
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        setBackgroundResource(value.resourceId)
        setOnClickListener { dialog.dismiss() }
    }, android.widget.LinearLayout.LayoutParams(-1, -2))
    root.addView(column, android.widget.FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
    dialog.setContentView(root)
    root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            val quarterTurn = kotlin.math.abs(controlRotationDegrees.toInt() % 180) == 90
            val availableWidth = if (quarterTurn) root.height else root.width
            val availableHeight = if (quarterTurn) root.width else root.height
            val width = minOf(dp(380), availableWidth - dp(32)).coerceAtLeast(1)
            val height = minOf(dp(124 + labels.size * 68), availableHeight - dp(32)).coerceAtLeast(1)
            column.rotation = controlRotationDegrees
            if (column.layoutParams.width != width || column.layoutParams.height != height) {
                column.layoutParams = android.widget.FrameLayout.LayoutParams(width, height, android.view.Gravity.CENTER)
                return false
            }
            return true
        }
    })
    val orientationListener = object : android.view.OrientationEventListener(this) {
        override fun onOrientationChanged(orientation: Int) {
            root.postInvalidateDelayed(100L)
        }
    }
    dialog.setOnDismissListener { orientationListener.disable() }
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.show()
    orientationListener.enable()
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(-1, -1)
        ImmersiveMode.apply(this)
    }
}
