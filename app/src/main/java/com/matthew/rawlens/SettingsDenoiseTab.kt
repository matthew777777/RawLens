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

/** Settings → Denoise. Moved verbatim from MainActivity.showSettings (showDenoiseTab). */
internal fun MainActivity.showDenoiseSettings(p: SettingsPage) {
    val content = p.content
    fun sectionTitle(text: String): TextView = p.kit.sectionTitle(text)
    fun polish() = p.kit.polish()
    fun markActive(active: Button) = p.markActive(active)
    val denoiseTab = p.tabs[5]
        rawZslSettingsStatus = null
        sidecarSettingsStatus = null
        content.removeAllViews()
        var settings = denoiseSettings()
        val aiSubordinate = ArrayList<View>()

        content.addView(sectionTitle("Profiled wavelet (Vulkan)"))
        val waveSubordinate = ArrayList<View>()
        val waveMaster = CheckBox(this).apply {
            text = "Wavelet luma+chroma denoise (Vulkan)\nPost-demosaic darktable-profiled wavelets on camera-linear RGB; JPEG only, never the DNG."
            setTextColor(getColor(R.color.text_primary))
            isChecked = settings.wavelet.enabled
            setOnCheckedChangeListener { button, value ->
                val proposed = settings.copy(wavelet = settings.wavelet.copy(enabled = value))
                if (persistDenoiseSettings(proposed)) {
                    settings = proposed
                    waveSubordinate.forEach { it.isEnabled = settings.wavelet.enabled }
                    if (settings.wavelet.enabled) controllerIfReady?.prewarmWavelet()
                } else if (button.isChecked != settings.wavelet.enabled) {
                    button.isChecked = settings.wavelet.enabled
                }
            }
        }
        content.addView(waveMaster)
        fun addWaveSlider(
            title: String,
            maximum: Int,
            initial: Int,
            format: (Int) -> String,
            update: (WaveletSettings, Int) -> WaveletSettings
        ) {
            var selected = initial.coerceIn(0, maximum)
            val label = TextView(this).apply {
                text = "$title: ${format(selected)}"
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), 0)
            }
            content.addView(label)
            val bar = SeekBar(this).apply {
                max = maximum
                progress = selected
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        selected = progress
                        label.text = "$title: ${format(selected)}"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        val proposed = settings.copy(wavelet = update(settings.wavelet, selected))
                        if (persistDenoiseSettings(proposed)) {
                            settings = proposed
                            setStatus("WAVELET ${title.uppercase(Locale.US)} • ${format(selected)}")
                        } else {
                            selected = initial.coerceIn(0, maximum)
                            seekBar.progress = selected
                        }
                    }
                })
            }
            content.addView(bar)
            waveSubordinate += label
            waveSubordinate += bar
        }
        addWaveSlider("Strength", 200, (settings.wavelet.strength * 100 + 0.5f).toInt(),
            { "$it%" }) { s, v -> s.copy(strength = v / 100f) }
        addWaveSlider("Luma", 100, (settings.wavelet.luma * 100 + 0.5f).toInt(),
            { "$it%" }) { s, v -> s.copy(luma = v / 100f) }
        addWaveSlider("Scales", 7, settings.wavelet.maxScale,
            { if (it <= 1) "$it scale" else "$it scales" }) { s, v -> s.copy(maxScale = v.coerceAtLeast(1)) }
        content.addView(TextView(this).apply {
            text = "Strength 100% and 7 scales are the validated defaults; luma 25% keeps fine detail while chroma (fixed 75%) does the heavy lifting. Noise comes from the sensor profile, so higher ISO denoises harder automatically."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(dp(12), dp(4), dp(12), dp(4))
        })
        waveSubordinate.forEach { it.isEnabled = settings.wavelet.enabled }


        content.addView(sectionTitle("AI RAW denoise (RawNIND Bayer)"))

        val aiStatus = TextView(this).apply {
            text = aiModelStatus()
            setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            setPadding(dp(12), dp(10), dp(12), 0)
        }
        val aiMaster = CheckBox(this).apply {
            text = "AI RAW denoise (RawNIND Bayer)\nOn writes a denoised DNG and develops the JPEG from it. Needs the trained model files in assets."
            setTextColor(getColor(R.color.text_primary))
            isChecked = settings.aiEnabled
            setOnCheckedChangeListener { button, value ->
                val proposed = settings.copy(aiEnabled = value)
                if (persistDenoiseSettings(proposed)) {
                    settings = proposed
                    if (value) preloadRawNindIfEnabled()
                    aiStatus.text = aiModelStatus()
                    aiSubordinate.forEach { it.isEnabled = settings.aiEnabled }
                } else if (button.isChecked != settings.aiEnabled) {
                    button.isChecked = settings.aiEnabled
                }
            }
        }
        val keepOriginal = CheckBox(this).apply {
            text = "Save original DNG alongside\nOff halves storage; the untouched sensor DNG is skipped."
            setTextColor(getColor(R.color.text_primary))
            isChecked = settings.saveOriginalDng
            setOnCheckedChangeListener { button, value ->
                val proposed = settings.copy(saveOriginalDng = value)
                if (persistDenoiseSettings(proposed)) {
                    settings = proposed
                } else if (button.isChecked != settings.saveOriginalDng) {
                    button.isChecked = settings.saveOriginalDng
                }
            }
        }
        content.addView(aiMaster); content.addView(aiStatus); content.addView(keepOriginal)
        aiSubordinate += keepOriginal
        var aiStrengthPct = (settings.aiStrength * 100 + 0.5f).toInt().coerceIn(0, 100)
        val aiStrengthLabel = TextView(this).apply {
            text = "AI strength: $aiStrengthPct%"
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        content.addView(aiStrengthLabel)
        val aiStrengthBar = SeekBar(this).apply {
            max = 100
            progress = aiStrengthPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    aiStrengthPct = progress
                    aiStrengthLabel.text = "AI strength: $aiStrengthPct%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val proposed = settings.copy(aiStrength = aiStrengthPct / 100f)
                    if (persistDenoiseSettings(proposed)) {
                        settings = proposed
                        setStatus("AI STRENGTH • $aiStrengthPct%")
                    } else {
                        aiStrengthPct = (settings.aiStrength * 100 + 0.5f).toInt()
                        seekBar.progress = aiStrengthPct
                    }
                }
            })
        }
        content.addView(aiStrengthBar)
        content.addView(TextView(this).apply {
            text = "0% = untouched source, 100% = full AI output (darktable neural-restore semantics; moving the slider never re-runs the model)."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(dp(12), dp(4), dp(12), dp(8))
        })
        aiSubordinate += aiStrengthLabel
        aiSubordinate += aiStrengthBar
        aiSubordinate.forEach { it.isEnabled = settings.aiEnabled }

        markActive(denoiseTab)
        polish()
}
