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
    val destination = if (isVideoMode && !encodedVideoEnabled)
        findViewById<android.widget.LinearLayout>(R.id.videoFormatQuickRow)
    else if (isVideoMode) videoRow else photoRow
    if (releaseQuick.parent !== destination) {
        (releaseQuick.parent as? android.view.ViewGroup)?.removeView(releaseQuick)
        destination.addView(releaseQuick, if (isVideoMode && !encodedVideoEnabled) 0 else if (isVideoMode) destination.childCount else 1)
    }
    (releaseQuick.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
        marginStart = if (isVideoMode && !encodedVideoEnabled) 0 else dp(8)
        releaseQuick.layoutParams = this
    }
}

internal fun MainActivity.updateVideoQuickControls() {
    findViewById<View>(R.id.videoModeQuickRow).visibility = if (isVideoMode) View.VISIBLE else View.GONE
    findViewById<View>(R.id.videoFormatQuickRow).visibility =
        if (isVideoMode) View.VISIBLE else View.GONE
    val idle = !videoRecording && videoRecorder == null && encodedVideoRecorder == null
    fun tile(id: Int, label: String, active: Boolean, enabled: Boolean) {
        findViewById<TextView>(id).apply {
            text = label
            isEnabled = enabled
            alpha = if (enabled) 1f else .4f
            contentDescription = label.replace('\n', ' ')
            setQuickTileState(this, active)
        }
    }
    tile(R.id.videoModeQuick, "Format\n${if (encodedVideoEnabled) "Encoded Video" else "RAW Video"}", encodedVideoEnabled, idle)
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
    tile(R.id.videoResolutionQuick, "Size\n${res.width}×${res.height}", false, idle)
    tile(R.id.videoFpsQuick, "Frame rate\n${if (encodedVideoEnabled) encodedVideoFpsPref().toString() else rawVideoFrameRateLabel()}", false, idle)
    releaseQuick.isEnabled = if (isVideoMode) !encodedVideoEnabled || !videoRecording else !dualRawEnabled
    releaseQuick.alpha = if (releaseQuick.isEnabled) 1f else .4f
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
    val rates = listOf(0) + controller.vendorRawPreviewFpsOptions()
    val labels = rates.map { fps ->
        if (fps == 0) "Default" else {
            val range = profile.copy(rawPreviewFps = fps).rawPreviewRange(
                controller.vendorRawPreviewFpsRanges())
            "$fps FPS (AE ${range?.first},${range?.last})"
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
        setTextColor(getColor(R.color.text_primary))
        setPadding(dp(8), dp(8), dp(8), dp(12))
    })
    val choices = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
    }
    labels.forEachIndexed { index, label ->
        choices.addView(android.widget.Button(this).apply {
            text = if (index == selected) "● $label" else label
            setOnClickListener { onSelected(dialog, index) }
        })
    }
    column.addView(android.widget.ScrollView(this).apply { addView(choices) },
        android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
    column.addView(android.widget.Button(this).apply {
        text = "Cancel"
        setOnClickListener { dialog.dismiss() }
    })
    root.addView(column, android.widget.FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
    dialog.setContentView(root)
    root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            val quarterTurn = kotlin.math.abs(controlRotationDegrees.toInt() % 180) == 90
            val availableWidth = if (quarterTurn) root.height else root.width
            val availableHeight = if (quarterTurn) root.width else root.height
            val width = minOf(dp(380), availableWidth - dp(32)).coerceAtLeast(1)
            val height = minOf(dp(460), availableHeight - dp(32)).coerceAtLeast(1)
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
