// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.view.View
import android.widget.TextView

internal fun MainActivity.setDirectLogMode(enabled: Boolean) {
    if (videoRecording || videoRecorder != null || logRecorder != null) {
        setStatus("STOP RECORDING TO SWITCH VIDEO FORMAT")
        return
    }
    directLogEnabled = enabled
    lensPreferences().edit().putBoolean(MainActivity.KEY_DIRECT_LOG, enabled).apply()
    if (isVideoMode) {
        findViewById<CameraGuideOverlay>(R.id.guideOverlay).videoCrop = if (enabled) directLogCrop() else videoCrop
        refreshCaptureFormatControl()
        refreshVideoSensorInfo()
        refreshVideoAudioDescription()
        findViewById<View>(R.id.shutter).contentDescription =
            if (enabled) "Record Direct Log video" else "Record RAW video"
    }
    updateQuickControls()
    setStatus(if (enabled) "${directLogProfile().hudLabel} • HEVC 10-BIT" else "VIDEO READY")
}

internal fun MainActivity.wireVideoQuickControls() {
    findViewById<View>(R.id.videoModeQuick).setOnClickListener {
        setDirectLogMode(!directLogEnabled)
    }
    findViewById<View>(R.id.videoBinningQuick).setOnClickListener {
        if (videoRecording || directLogEnabled) return@setOnClickListener
        val enabled = !lensPreferences().getBoolean(MainActivity.KEY_VIDEO_BINNING, false)
        lensPreferences().edit().putBoolean(MainActivity.KEY_VIDEO_BINNING, enabled).apply()
        refreshVideoSensorInfo()
        updateQuickControls()
        setStatus(if (enabled) "BINNING ON" else "BINNING OFF")
    }
    findViewById<View>(R.id.directLogCropQuick).setOnClickListener { cycleDirectLogCrop() }
    findViewById<View>(R.id.videoResolutionQuick).setOnClickListener { cycleDirectLogRes() }
    findViewById<View>(R.id.videoFpsQuick).setOnClickListener { cycleDirectLogFps() }
}

internal fun MainActivity.updateVideoQuickControls() {
    findViewById<View>(R.id.videoModeQuickRow).visibility = if (isVideoMode) View.VISIBLE else View.GONE
    findViewById<View>(R.id.videoFormatQuickRow).visibility =
        if (isVideoMode && directLogEnabled) View.VISIBLE else View.GONE
    val idle = !videoRecording && videoRecorder == null && logRecorder == null
    fun tile(id: Int, label: String, active: Boolean, enabled: Boolean) {
        findViewById<TextView>(id).apply {
            text = label
            isEnabled = enabled
            alpha = if (enabled) 1f else .4f
            contentDescription = label.replace('\n', ' ')
            setQuickTileState(this, active)
        }
    }
    tile(R.id.videoModeQuick, "VIDEO\n${if (directLogEnabled) "DIRECT LOG" else "RAW"}", directLogEnabled, idle)
    findViewById<View>(R.id.videoBinningQuick).visibility = if (directLogEnabled) View.GONE else View.VISIBLE
    findViewById<View>(R.id.directLogCropQuick).visibility =
        if (isVideoMode && directLogEnabled) View.VISIBLE else View.GONE
    val crop = directLogCrop()
    val cropLabel = when (crop) {
        VideoCrop.OPEN_GATE -> "GATE"
        VideoCrop.WIDE_16_9 -> "16:9"
        VideoCrop.SCOPE_2_39 -> "2.39"
        VideoCrop.UNIVISIUM_2_00 -> "2.00"
    }
    tile(R.id.directLogCropQuick, "CROP\n$cropLabel", crop != VideoCrop.WIDE_16_9, idle)
    val binning = lensPreferences().getBoolean(MainActivity.KEY_VIDEO_BINNING, false)
    tile(R.id.videoBinningQuick, "BINNING\n${if (binning) "ON" else "OFF"}", binning, idle)
    val res = directLogResPref()
    tile(R.id.videoResolutionQuick, "RESOLUTION\n${res.width}×${res.height}", false, idle)
    tile(R.id.videoFpsQuick, "FPS\n${directLogFpsPref()}", false, idle)
    releaseQuick.isEnabled = if (isVideoMode) !directLogEnabled || !videoRecording else !dualRawEnabled
    releaseQuick.alpha = if (releaseQuick.isEnabled) 1f else .4f
}
