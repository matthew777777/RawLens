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
    val denoiseTab = p.tabs[6]
        rawZslSettingsStatus = null
        sidecarSettingsStatus = null
        content.removeAllViews()
        var settings = denoiseSettings()
        val aiSubordinate = ArrayList<View>()

        content.addView(sectionTitle("Capture sharpening"))
        val sharpToggle = SettingsSwitch(this).apply {
            text = "Capture sharpening\nRestores detail lost to the lens and demosaic. Developed photos only."
            isChecked = settings.captureSharpening.enabled
        }
        // Strength is one row with a list picker, like every other value in Settings.
        val sharpChoices = p.kit.settingsButton("")
        fun refreshSharp() {
            sharpChoices.text = "Sharpening strength: ${settings.captureSharpening.level.label}"
            sharpChoices.isEnabled = settings.captureSharpening.enabled
            sharpChoices.alpha = if (sharpChoices.isEnabled) 1f else 0.45f
        }
        sharpChoices.setOnClickListener {
            p.kit.chooseEnum("Sharpening strength", settings.captureSharpening.level, { it.label }) { level ->
                val next = settings.copy(captureSharpening = settings.captureSharpening.copy(level = level))
                if (persistDenoiseSettings(next)) { settings = next; refreshSharp() }
            }
        }
        sharpToggle.setOnCheckedChangeListener { button, enabled ->
            val next = settings.copy(captureSharpening = settings.captureSharpening.copy(enabled = enabled))
            if (persistDenoiseSettings(next)) { settings = next; refreshSharp() }
            else button.isChecked = settings.captureSharpening.enabled
        }
        content.addView(sharpToggle)
        content.addView(sharpChoices)
        refreshSharp()

        content.addView(sectionTitle("Profiled wavelet (Vulkan)"))
        val waveSubordinate = ArrayList<View>()
        val waveMaster = SettingsSwitch(this).apply {
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
            val bar = SettingsSlider(this).apply {
                max = maximum
                progress = selected
                setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                    override fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean) {
                        selected = progress
                        label.text = "$title: ${format(selected)}"
                    }
                    override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit
                    override fun onStopTrackingTouch(seekBar: SettingsSlider) {
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
        val waveAlgorithm = SettingsSwitch(this).apply {
            text = "RawLens Wiener algorithm\nOff: RAWR (default). On: RawLens Wiener."
            isChecked = settings.wavelet.algorithm == WaveletAlgorithm.RAWLENS
            setOnCheckedChangeListener { button, enabled ->
                val algorithm = if (enabled) WaveletAlgorithm.RAWLENS else WaveletAlgorithm.RAWR
                val proposed = settings.copy(wavelet = settings.wavelet.copy(algorithm = algorithm))
                if (persistDenoiseSettings(proposed)) {
                    settings = proposed
                    setStatus("WAVELET ALGORITHM • ${if (enabled) "RawLens Wiener" else "RAWR"}")
                } else {
                    button.isChecked = settings.wavelet.algorithm == WaveletAlgorithm.RAWLENS
                }
            }
        }
        content.addView(waveAlgorithm)
        waveSubordinate += waveAlgorithm
        addWaveSlider("Strength", 80, (settings.wavelet.strength * 10 + 0.5f).toInt(),
            { "%.1f".format(Locale.US, it / 10f) }) { s, v -> s.copy(strength = v / 10f) }
        addWaveSlider("Detail", 36, (settings.wavelet.detail * 20 + 0.5f).toInt(),
            { "%.2f".format(Locale.US, it / 20f) }) { s, v -> s.copy(detail = v / 20f) }
        addWaveSlider("Luma", 20, (settings.wavelet.luma * 20 + 0.5f).toInt(),
            { "%.2f".format(Locale.US, it / 20f) }) { s, v -> s.copy(luma = v / 20f) }
        addWaveSlider("Grain", 20, (settings.wavelet.grain * 20 + 0.5f).toInt(),
            { "%.2f".format(Locale.US, it / 20f) }) { s, v -> s.copy(grain = v / 20f) }
        addWaveSlider("Scales", 6, settings.wavelet.maxScale - 1,
            { val scales = it + 1; if (scales == 1) "1 scale" else "$scales scales" }
        ) { s, v -> s.copy(maxScale = v + 1) }
        content.addView(TextView(this).apply {
            text = "RawLens Wiener removes chroma blotches while keeping colour edges, and turns luma noise into fine, even grain; Grain sets how much of that fine grain stays (RawLens only). RAWR (default) uses the RAWR wavelet shrinkage rule with our learned noise model. Defaults: strength 0.5, detail 1.00, luma 0.25, grain 0.00, 7 scales; chroma is fixed at 0.75. Strength supports 0–8; Detail supports 0–1.80 and controls shadow texture preservation independently of Luma. Both modes learn noise per camera and raw mode, using measurements from each photo and the sensor profile as fallbacks. RawLens also denoises luma more gently when noise is barely visible (base ISO)."
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
        val aiMaster = SettingsSwitch(this).apply {
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
        val keepOriginal = SettingsSwitch(this).apply {
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
        val aiStrengthBar = SettingsSlider(this).apply {
            max = 100
            progress = aiStrengthPct
            setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                override fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean) {
                    aiStrengthPct = progress
                    aiStrengthLabel.text = "AI strength: $aiStrengthPct%"
                }
                override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit
                override fun onStopTrackingTouch(seekBar: SettingsSlider) {
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
