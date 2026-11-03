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
    findViewById<View>(R.id.videoFpsQuick).setOnClickListener { cycleEncodedVideoFps() }
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
        if (isVideoMode && encodedVideoEnabled) View.VISIBLE else View.GONE
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
    val res = encodedVideoResPref()
    tile(R.id.videoResolutionQuick, "Size\n${res.width}×${res.height}", false, idle)
    tile(R.id.videoFpsQuick, "Frame rate\n${encodedVideoFpsPref()}", false, idle)
    releaseQuick.isEnabled = if (isVideoMode) !encodedVideoEnabled || !videoRecording else !dualRawEnabled
    releaseQuick.alpha = if (releaseQuick.isEnabled) 1f else .4f
}
