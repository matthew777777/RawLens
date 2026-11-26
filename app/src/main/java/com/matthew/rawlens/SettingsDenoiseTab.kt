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

        content.addView(sectionTitle("RAW denoise"))
        content.addView(p.kit.sectionDesc("Cleans noise from the RAW before demosaicing in JPEG and JXL photos. Strength is automatic, from the noise measured in each photo. Merged photos and DNGs are not changed."))
        val denoiseViews = ArrayList<View>()
        val textureViews = ArrayList<View>()
        val classicViews = ArrayList<View>()   // shown for the classic engine
        val networkViews = ArrayList<View>()   // shown for the network engine
        val engineRow = p.kit.settingsButton("")
        val modeRow = p.kit.settingsButton("")
        val sliderRefresh = ArrayList<() -> Unit>()
        // Luma and chroma are independent; texture only acts on denoised luma.
        fun refreshRaw() {
            val raw = settings.rawDenoise
            val network = raw.engine == RawDenoiseSettings.Engine.NETWORK   // CLASSIC and CLASSIC_V1 share the controls
            engineRow.text = "Denoiser: " + raw.engine.label
            modeRow.text = "Remove: " + if (raw.luma <= 0f && raw.chroma <= 0f) "Nothing" else raw.mode.label
            listOf(engineRow, modeRow).forEach { it.alpha = if (raw.enabled) 1f else 0.45f }
            denoiseViews.forEach { it.isEnabled = settings.rawDenoise.enabled }
            textureViews.forEach { it.isEnabled = settings.rawDenoise.enabled && settings.rawDenoise.luma > 0f }
            classicViews.forEach { it.visibility = if (network) View.GONE else View.VISIBLE }
            networkViews.forEach { it.visibility = if (network) View.VISIBLE else View.GONE }
        }
        content.addView(SettingsSwitch(this).apply {
            text = "Enable RAW denoise"
            setTextColor(getColor(R.color.text_primary))
            isChecked = settings.rawDenoise.enabled
            setOnCheckedChangeListener { button, value ->
                val proposed = settings.copy(rawDenoise = settings.rawDenoise.copy(enabled = value))
                if (persistDenoiseSettings(proposed)) {
                    settings = proposed
                    refreshRaw()
                    if (value) controllerIfReady?.prewarmRawDenoise()
                    if (value && proposed.rawDenoise.engine == RawDenoiseSettings.Engine.NETWORK) NetworkDenoiser.prewarm(context)
                } else if (button.isChecked != settings.rawDenoise.enabled) {
                    button.isChecked = settings.rawDenoise.enabled
                }
            }
        })
        engineRow.setOnClickListener {
            val engines = RawDenoiseSettings.Engine.entries
            p.kit.pick("Denoiser", engines.map { it.label }, settings.rawDenoise.engine.ordinal) { which ->
                val proposed = settings.copy(rawDenoise = settings.rawDenoise.copy(engine = engines[which]))
                if (persistDenoiseSettings(proposed)) {
                    settings = proposed
                    if (engines[which] == RawDenoiseSettings.Engine.NETWORK) NetworkDenoiser.prewarm(this)
                    refreshRaw()
                }
            }
        }
        content.addView(engineRow)
        denoiseViews += engineRow
        modeRow.setOnClickListener {
            val raw = settings.rawDenoise
            val modes = RawDenoiseSettings.Mode.entries
            // Both sliders off: nothing is marked, so every mode can be picked.
            val current = if (raw.luma <= 0f && raw.chroma <= 0f) -1 else raw.mode.ordinal
            p.kit.pick("Remove", modes.map { it.label }, current) { which ->
                val proposed = settings.copy(rawDenoise = settings.rawDenoise.withMode(modes[which]))
                if (persistDenoiseSettings(proposed)) {
                    settings = proposed
                    sliderRefresh.forEach { it() }
                    refreshRaw()
                }
            }
        }
        content.addView(modeRow)
        denoiseViews += modeRow
        classicViews += modeRow
        fun slider(
            views: MutableList<View>, maxPct: Int, text: (Int) -> String,
            get: (RawDenoiseSettings) -> Float, set: (RawDenoiseSettings, Float) -> RawDenoiseSettings
        ) {
            fun stored() = (get(settings.rawDenoise) * 100 + 0.5f).toInt()
            var pct = stored()
            val label = TextView(this).apply {
                this.text = text(pct)
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), 0)
            }
            val bar = SettingsSlider(this).apply {
                max = maxPct / 5
                progress = pct / 5
                setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                    override fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean) {
                        pct = progress * 5
                        label.text = text(pct)
                    }
                    override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit
                    override fun onStopTrackingTouch(seekBar: SettingsSlider) {
                        val proposed = settings.copy(rawDenoise = set(settings.rawDenoise, pct / 100f))
                        if (persistDenoiseSettings(proposed)) {
                            settings = proposed
                        } else {
                            pct = stored()
                            seekBar.progress = pct / 5
                            label.text = text(pct)
                        }
                        refreshRaw()
                    }
                })
            }
            sliderRefresh += {
                pct = stored()
                bar.progress = pct / 5
                label.text = text(pct)
            }
            content.addView(label)
            content.addView(bar)
            views += label
            views += bar
        }
        fun strength(name: String, pct: Int) = "$name: " + when (pct) { 0 -> "Off"; 100 -> "Auto"; else -> "$pct% of auto" }
        fun description(body: String) = TextView(this).apply {
            tag = "settings-description"
            text = body
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        val classicStart = content.childCount
        slider(denoiseViews, 100, { strength("Luma denoise", it) }, { it.luma }, { r, v -> r.copy(luma = v) })
        slider(denoiseViews, 100, { strength("Chroma denoise", it) }, { it.chroma }, { r, v -> r.copy(chroma = v) })
        slider(textureViews, (RawDenoiseSettings.MAX_TEXTURE * 100).toInt(),
            { "Texture: " + if (it == 100) "Auto" else "$it% of auto" }, { it.texture }, { r, v -> r.copy(texture = v) })
        content.addView(description("Luma and chroma are independent: Luma only is fastest and leaves color noise in; Chroma only removes color noise and keeps all the grain. Texture sets how much fine grain and detail stays when luma is denoised. Classic v1 keeps all the grain at every scale, as the first version did; Classic thins the coarse grain in night shots."))
        for (i in classicStart until content.childCount) classicViews += content.getChildAt(i)
        val networkStart = content.childCount
        slider(denoiseViews, 100, { "Network strength: " + if (it == 100) "Full" else if (it == 0) "Off" else "$it%" },
            { it.networkStrength }, { r, v -> r.copy(networkStrength = v) })
        content.addView(description("The network was trained on this phone's RAW photos and removes luma and color noise together, on the NPU. Lower strength blends the original back in. Phones without a supported NPU use the classic denoiser."))
        for (i in networkStart until content.childCount) networkViews += content.getChildAt(i)
        refreshRaw()

        content.addView(sectionTitle("Capture sharpening"))
        content.addView(p.kit.sectionDesc("Restores detail softened by the lens or demosaicing in developed photos."))
        val sharpToggle = SettingsSwitch(this).apply {
            text = "Enable capture sharpening"
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

        markActive(denoiseTab)
        polish()
}
