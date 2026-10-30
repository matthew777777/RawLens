// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.rawlens.MainActivity.Companion.KEY_VIDEO_STAB_OUTPUT

import com.matthew.rawlens.MainActivity.Companion.KEY_VIDEO_BINNING

import com.matthew.rawlens.MainActivity.Companion.KEY_VIDEO_AUDIO

import com.matthew.rawlens.MainActivity.Companion.KEY_DIRECT_LOG_GAIN

import com.matthew.rawlens.MainActivity.Companion.KEY_VIDEO_DEMOSAIC

import com.matthew.rawlens.MainActivity.Companion.KEY_DIRECT_LOG


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

/** Settings → Video. Moved verbatim from MainActivity.showSettings (showVideoTab). */
internal fun MainActivity.showVideoSettings(p: SettingsPage) {
    val content = p.content
    fun sectionTitle(text: String): TextView = p.kit.sectionTitle(text)
    fun sectionDesc(text: String): TextView = p.kit.sectionDesc(text)
    fun polish() = p.kit.polish()
    fun markActive(active: Button) = p.markActive(active)
    val videoTab = p.tabs[2]
        content.removeAllViews()
        content.addView(sectionTitle("Recording folder"))
        recordingFolderSettingsStatus = sectionDesc(recordingFolderText()).also { content.addView(it) }
        content.addView(sectionDesc("Record directly to Documents, DCIM, or a connected USB drive. One file is written in the selected folder, with no export copy. With no folder selected, record directly to Downloads/RawLens. Reconnect an unavailable drive before recording."))
        content.addView(Button(this).apply {
            text = "Choose recording folder"
            isEnabled = !videoRecording
            setOnClickListener { pickRecordingFolder() }
        })
        content.addView(Button(this).apply {
            text = "Use Downloads/RawLens"
            isEnabled = !videoRecording
            setOnClickListener {
                RecordingFolderAccess.clear(this@showVideoSettings)
                recordingFolderSettingsStatus?.text = recordingFolderText()
            }
        })
        content.addView(Button(this).apply {
            text = "Recover older RAW recordings"
            isEnabled = !videoRecording
            setOnClickListener { runVideoExportRetry() }
        })
        content.addView(sectionTitle("Direct Log"))
        content.addView(sectionDesc("ACES fitted is a baked filmic approximation in Rec.709 gamma 2.4, not ACES scene data. S-Log3 uses S-Gamut3.Cine primaries; interpret data levels as Video/limited."))
        content.addView(Button(this).apply {
            text = "S-Log3 LUT library: choose assist / LUT"
            isEnabled = !videoRecording
            setOnClickListener { chooseDirectLogMonitorLut() }
        })
        content.addView(Button(this).apply {
            text = "Import LUT (.cube)"
            isEnabled = !videoRecording
            setOnClickListener { pickDirectLogLut() }
        })
        content.addView(Button(this).apply {
            text = "Selected LUT intensity"
            isEnabled = !videoRecording
            setOnClickListener {
                val library = DirectLogLutLibrary(this@showVideoSettings)
                AlertDialog.Builder(this@showVideoSettings).setTitle(library.label())
                    .setSingleChoiceItems(arrayOf("0%", "25%", "50%", "75%", "100%"),
                        (library.intensity * 4).toInt()) { dialog, which ->
                        library.intensity = which / 4f
                        dialog.dismiss()
                    }.setNegativeButton("Close", null).show()
            }
        })
        content.addView(Button(this).apply {
            text = "Remove imported LUT"
            isEnabled = !videoRecording
            setOnClickListener { removeDirectLogMonitorLut() }
        })
        content.addView(CheckBox(this).apply {
            text = "Bake selected LUT into S-Log3 recordings (SDR Rec.709 output)"
            setTextColor(getColor(R.color.text_primary))
            isEnabled = !videoRecording
            isChecked = DirectLogLutLibrary(this@showVideoSettings).bakeIntoRecording
            setOnCheckedChangeListener { _, checked ->
                DirectLogLutLibrary(this@showVideoSettings).bakeIntoRecording = checked
            }
        })
        content.addView(sectionDesc("With baking off, the take monitor shows the selected assist/LUT and the MP4 stays S-Gamut3.Cine/S-Log3. With baking on, select an imported S-Gamut3.Cine/S-Log3-to-Rec.709 cube; its look is saved in a _LUT709 MP4 and the monitor shows that output. Other profiles ignore this option. The built-in assist is monitor-only. Extract ZIP packs before importing. No Sony LUTs are bundled."))
        content.addView(Button(this).apply {
            isEnabled = !videoRecording
            fun refresh() { text = "LOG crop: ${directLogCrop().label}" }
            refresh()
            setOnClickListener { cycleDirectLogCrop(); refresh() }
        })
        content.addView(CheckBox(this).apply {
            text = "Direct Log video"
            setTextColor(getColor(R.color.text_primary))
            isEnabled = !videoRecording
            isChecked = directLogEnabled
            setOnCheckedChangeListener { _, enabled ->
                setDirectLogMode(enabled)
                if (isChecked != directLogEnabled) isChecked = directLogEnabled
            }
        })
        content.addView(sectionDesc("HEVC 10-bit MP4 instead of RAW .craw: BT.709, ACES fitted BT.709, S-Log3, or HLG in this tab or the LOG quick tile. Selectable recording crop, live RAW viewfinder; sound follows the Sound toggle."))
        content.addView(Button(this).apply {
            fun refresh() {
                text = "LOG profile: ${directLogProfile().description}"
            }
            refresh()
            setOnClickListener {
                cycleDirectLogProfile()
                refresh()
            }
        })
        content.addView(Button(this).apply {
            fun refresh() {
                text = "LOG frame rate: ${directLogFpsPref()}FPS"
            }
            refresh()
            setOnClickListener {
                cycleDirectLogFps()
                refresh()
            }
        })
        content.addView(Button(this).apply {
            fun refresh() {
                val res = directLogResPref()
                text = "LOG resolution: ${res.width}x${res.height}"
            }
            refresh()
            setOnClickListener {
                cycleDirectLogRes()
                refresh()
            }
        })
        content.addView(Button(this).apply {
            fun refresh() {
                text = "LOG demosaic: ${videoDemosaic().label}"
            }
            refresh()
            setOnClickListener {
                val next = videoDemosaic().next()
                lensPreferences().edit().putString(KEY_VIDEO_DEMOSAIC, next.name).apply()
                refresh()
                setStatus("LOG DEMOSAIC • ${next.label} — NEXT TAKE")
            }
        })
        content.addView(sectionDesc("RCD (default) favors fine detail and may record below the selected frame rate, depending on the device and resolution; it falls back to MHC if RCD cannot start. MHC favors realtime recording. Super pixel bins each 2x2 Bayer quad into one pixel: faster processing with half the detail."))
        var logGainEv = directLogGainPref()
        val logGainLabel = TextView(this).apply {
            text = logGainText(logGainEv)
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        content.addView(logGainLabel)
        content.addView(SeekBar(this).apply {
            max = 60 // -2.0..+4.0 EV stops in 0.1 steps, shown as ×gain
            progress = ((logGainEv + 2f) * 10).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    logGainEv = progress / 10f - 2f
                    logGainLabel.text = logGainText(logGainEv)
                    // Live: the camera thread reads exposureEv per frame.
                    logRecorder?.exposureEv = logGainEv
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    lensPreferences().edit().putFloat(KEY_DIRECT_LOG_GAIN, logGainEv).apply()
                    setStatus("LOG GAIN • ${formatDirectLogGain(logGainEv)}")
                }
            })
        })
        content.addView(sectionDesc("Digital gain folded into the grade (×1.0 = neutral): brightens the log image without touching sensor exposure. Applies live, including mid-take."))
        content.addView(CheckBox(this).apply {
            text = "Video sound"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true)
            setOnCheckedChangeListener { button, enabled ->
                if (enabled == lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true)) return@setOnCheckedChangeListener
                toggleVideoSound()
                button.isChecked = lensPreferences().getBoolean(KEY_VIDEO_AUDIO, true)
            }
        })
        content.addView(sectionTitle("RAW video"))
        content.addView(Button(this).apply {
            isEnabled = !videoRecording
            fun refresh() {
                text = "Compression: " + CrawWriter.codecDisplayName(
                    CrawWriter.normalizeCodec(lensPreferences().getInt(MainActivity.KEY_VIDEO_CODEC, CrawWriter.CODEC_LIVE)))
            }
            refresh()
            setOnClickListener {
                val current = CrawWriter.normalizeCodec(lensPreferences().getInt(MainActivity.KEY_VIDEO_CODEC, CrawWriter.CODEC_LIVE))
                // Live (default) -> Live v1 — Legacy -> Balanced -> Live
                val next = when (current) {
                    CrawWriter.CODEC_LIVE -> CrawWriter.CODEC_LIVE_V1
                    CrawWriter.CODEC_LIVE_V1 -> CrawWriter.CODEC_FAST
                    else -> CrawWriter.CODEC_LIVE
                }
                lensPreferences().edit().putInt(MainActivity.KEY_VIDEO_CODEC, next).apply()
                refresh()
            }
        })
        content.addView(sectionDesc("All modes preserve every RAW sample. CRAW Live is the default recording mode. CRAW Balanced trades more CPU for potentially smaller files. CRAW Live v1 — Legacy is retained for compatibility. Speed and size depend on the scene and device. Applies to the next take."))
        content.addView(Button(this).apply {
            fun refresh() {
                text = if (directLogEnabled) "LOG crop: ${directLogCrop().label}" else "CRAW crop: ${videoCropShort()}"
            }
            refresh()
            setOnClickListener {
                cycleVideoCrop()
                refresh()
            }
        })
        content.addView(sectionDesc("Recording crop. Direct Log: 16:9, 2.39:1, or 2.00:1; changes apply to the next take."))
        content.addView(CheckBox(this).apply {
            text = "Binning (half resolution)"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(KEY_VIDEO_BINNING, false)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(KEY_VIDEO_BINNING, enabled).apply()
                if (isVideoMode && !videoRecording) refreshVideoSensorInfo()
                updateQuickControls()
                setStatus(if (enabled) "BINNING ON — NEXT TAKE" else "BINNING OFF")
            }
        })
        content.addView(Button(this).apply {
            fun refresh() {
                val cfaMode = lensPreferences().getString(MainActivity.KEY_VIDEO_BINNING_MODE, MainActivity.BINNING_RGB) == MainActivity.BINNING_CFA
                text = if (cfaMode) "Binning output: CFA (cheap)" else "Binning output: RGB (best)"
            }
            refresh()
            setOnClickListener {
                val cfaMode = lensPreferences().getString(MainActivity.KEY_VIDEO_BINNING_MODE, MainActivity.BINNING_RGB) == MainActivity.BINNING_CFA
                lensPreferences().edit().putString(MainActivity.KEY_VIDEO_BINNING_MODE,
                    if (cfaMode) MainActivity.BINNING_RGB else MainActivity.BINNING_CFA).apply()
                refresh()
            }
        })
        content.addView(sectionDesc("RGB (best): each 2x2 block is demosaiced once on the phone (edge-directed), so editors never re-demosaic a binned mosaic: no magenta/green fringes, ~90 % of full-res detail. CFA (cheap): half-res Bayer remosaiced from the RGB bin (as camera ISPs rescale raw), a quarter of the samples of RGB and smaller files; any half-res mosaic still fringes a little once your editor demosaics it. Applies to the next take; N/A in Direct Log."))
        content.addView(sectionTitle("Locks on record"))
        content.addView(CheckBox(this).apply {
            text = "Lock exposure on record"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(VideoRecordLocks.KEY_LOCK_EXPOSURE, false)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(VideoRecordLocks.KEY_LOCK_EXPOSURE, enabled).apply()
                setStatus(if (enabled) "AE LOCK ON RECORD" else "AE CONTINUOUS IN TAKES")
            }
        })
        content.addView(CheckBox(this).apply {
            text = "Lock white balance on record"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(VideoRecordLocks.KEY_LOCK_WB, false)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(VideoRecordLocks.KEY_LOCK_WB, enabled).apply()
                setStatus(if (enabled) "AWB LOCK ON RECORD" else "AWB CONTINUOUS IN TAKES")
            }
        })
        content.addView(CheckBox(this).apply {
            text = "Lock focus on record"
            setTextColor(getColor(R.color.text_primary))
            isChecked = lensPreferences().getBoolean(VideoRecordLocks.KEY_LOCK_FOCUS, false)
            setOnCheckedChangeListener { _, enabled ->
                lensPreferences().edit().putBoolean(VideoRecordLocks.KEY_LOCK_FOCUS, enabled).apply()
                setStatus(if (enabled) "AF LOCK ON RECORD" else "AF CONTINUOUS IN TAKES")
            }
        })
        content.addView(sectionDesc("Freeze the preview AE/AWB/AF at REC (manual tiles always hold). Off = continuous auto during takes. SS/ISO/WB/focus/EV tiles stay live before and during takes."))
        content.addView(sectionTitle("Stabilization"))
        content.addView(sectionDesc("Post-record stabilization is unavailable for single-file recording because it creates a second video. Recording keeps the original take."))

        markActive(videoTab)
        polish()
}
