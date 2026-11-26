// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** Host hooks for the shared JPEG look controls (camera Photo settings and the develop queue). */
internal interface JpegLookHost {
    fun jpegOutputSettings(): JpegOutputSettings
    fun applyPhotoOutputSettings(settings: JpegOutputSettings): Boolean
    fun lensPreferences(): SharedPreferences
    fun setStatus(message: String)
    fun showPhotoLutSettings()
    /** Rebuild the page in place after a control changes the look. */
    fun reshow()
    fun onMonoChanged(mono: MonoSettings) {}
    /** The camera shows JPEG-only controls (Ultra HDR, JPEG quality) only when developing JPEG. */
    val developedIsJpeg: Boolean get() = true
    /** Live VF preview while a slider moves (camera only; the queue has none). */
    fun previewLive(settings: JpegOutputSettings) {}
}

/**
 * JPEG/AgX tone, colour and highlight controls shared by Settings → Photo and
 * the develop queue. Each control writes through [JpegLookHost]; the camera
 * keeps B&W, portrait and Photo LUT ([cameraOnly]), which the queue hides.
 */
internal class JpegLookSettingsSection(
    private val kit: SettingsKit,
    private val host: JpegLookHost,
    private val cameraOnly: Boolean,
) {
    private val activity get() = kit.activity

    fun show() {
        kit.content.addView(kit.sectionTitle("Photo output"))
        kit.content.addView(kit.sectionDesc("Ultra HDR and Display P3 targets, JPEG quality and chroma, Basic / AgX / Sigmoid / ACES 2.0 tone mapping, adaptive exposure and highlight handling."))
        var currentJpegSettings = host.jpegOutputSettings()
        if (cameraOnly) kit.content.addView(kit.navRow("Photo LUT", currentJpegSettings.photoLut?.title ?: "Off") {
            host.showPhotoLutSettings()
        })
        if (cameraOnly) kit.content.addView(kit.sectionDesc("Use a display-referred 3D .cube with the photo output color space. JPEG and JPEG XL save the look. DNG uses it in the viewfinder."))
        fun applyJpegOutputSettings(settings: JpegOutputSettings): Boolean {
            val resolved = settings.resolvedForPlatform()
            if (!host.applyPhotoOutputSettings(resolved)) {
                host.setStatus("SETTINGS AFTER SAVES")
                return false
            }
            currentJpegSettings = resolved
            host.lensPreferences().edit()
                .putString(ActiveDLighting.PREFERENCE_KEY, resolved.activeDLighting.name)
                .putBoolean(MainActivity.KEY_JPEG_ULTRA_HDR, resolved.ultraHdr)
                .putBoolean(MainActivity.KEY_JPEG_DISPLAY_P3, resolved.displayP3)
                .putInt(MainActivity.KEY_JPEG_QUALITY, resolved.jpegQuality)
                .putString(MainActivity.KEY_JPEG_CHROMA_SUBSAMPLING, resolved.chromaSubsampling.name)
                .putString(MainActivity.KEY_JPEG_DEMOSAIC, resolved.demosaic.name)
                .putBoolean(ChromaCleanup.KEY_FALSE_COLOR, resolved.chromaCleanup.falseColorSuppression)
                .putFloat(ChromaCleanup.KEY_DEFRINGE, resolved.chromaCleanup.defringeStrength)
                .putFloat(MainActivity.KEY_JPEG_AGX_PURITY, resolved.agxPurityBoost)
                .putString(PhotoToneMapper.PREFERENCE_KEY, resolved.toneMapper.name)
                .putString(AgxLook.PREFERENCE_KEY, resolved.agxLook.name)
                .putFloat(MainActivity.KEY_JPEG_AGX_CONTRAST, resolved.agxContrast)
                .putFloat(MainActivity.KEY_JPEG_AGX_SATURATION, resolved.agxSaturation)
                .putFloat(MainActivity.KEY_JPEG_AGX_HUE, resolved.agxHuePreservation)
                .putFloat(MainActivity.KEY_JPEG_AGX_SHADOW_EV, resolved.agxShadowEv)
                .putFloat(MainActivity.KEY_JPEG_AGX_HIGHLIGHT_EV, resolved.agxHighlightEv)
                .putFloat(MainActivity.KEY_JPEG_AGX_GAMUT, resolved.agxGamutCompression)
                .putBoolean(MainActivity.KEY_JPEG_ADAPTIVE_EXPOSURE, resolved.adaptiveExposureAuto)
                .putFloat(MainActivity.KEY_JPEG_ADAPTIVE_PROGRAM, resolved.adaptiveExposureProgramStrength)
                .putFloat(MainActivity.KEY_JPEG_HIGHLIGHT_HEADROOM, resolved.highlightHeadroom)
                .putFloat(MainActivity.KEY_JPEG_SKY_PROTECTION, resolved.highlightSoftHeadroom)
                .putFloat(MainActivity.KEY_JPEG_HIGHLIGHT_SHOULDER, resolved.highlightShoulder)
                .putFloat(CameraToneBalance.KEY_SHADOW_LIFT, resolved.shadowLift)
                .putFloat(CameraToneBalance.KEY_HIGHLIGHT_RECOVERY, resolved.highlightRecovery)
                .putBoolean(MainActivity.KEY_JPEG_HL_RECONSTRUCTION, resolved.hlReconstruction)
                .putBoolean(HighlightReconstructionDefaults.APPLIED, true)
                .also { resolved.toneLook.save(it) }
                .also { resolved.portrait.save(it) }
                .apply()
            host.onMonoChanged(resolved.mono)
            return true
        }
        if (cameraOnly) {
        kit.content.addView(kit.sectionTitle("Black & white"))
        kit.content.addView(Button(activity).apply {
            fun refresh() { text = "B&W: ${currentJpegSettings.mono.mode.label}" }
            refresh()
            setOnClickListener {
                kit.chooseEnum("Black & white", currentJpegSettings.mono.mode, { it.label }) { next ->
                    if (applyJpegOutputSettings(currentJpegSettings.copy(
                            mono = currentJpegSettings.mono.copy(mode = next)))) refresh()
                }
            }
        })
        kit.content.addView(kit.sectionDesc("Builds the picture straight from the sensor photosites, no demosaic (after rwmono). Detail: full resolution, colour checkerboard removed. Clean: 2x2 binned, half the width and height, about 1 stop cleaner. Clean is the default. Single frame and HDR+; the viewfinder previews in B&W at no extra cost. DNG becomes a monochrome linear DNG."))
        kit.content.addView(SettingsSwitch(activity).apply {
            text = "B&W: also keep colour DNG"
            isChecked = currentJpegSettings.mono.keepColorDng
            setOnCheckedChangeListener { _, checked ->
                if (!applyJpegOutputSettings(currentJpegSettings.copy(
                        mono = currentJpegSettings.mono.copy(keepColorDng = checked)))) isChecked = !checked
            }
        })
        if (PortraitSettings.AVAILABLE) {
            kit.content.addView(kit.sectionTitle("Portrait"))
            kit.content.addView(SettingsSwitch(activity).apply {
                text = "Portrait background blur"
                isChecked = currentJpegSettings.portrait.enabled
                isEnabled = PortraitNetsHolder.available(activity)
                setOnCheckedChangeListener { _, checked ->
                    if (!applyJpegOutputSettings(currentJpegSettings.copy(
                            portrait = currentJpegSettings.portrait.copy(enabled = checked)))) isChecked = !checked
                }
            })
            kit.content.addView(Button(activity).apply {
                fun refresh() { text = "Portrait aperture: ${PortraitSettings.label(currentJpegSettings.portrait.fNumber)}" }
                refresh()
                setOnClickListener {
                    kit.choose("Portrait aperture", PortraitSettings.F_STOPS.toList(),
                        PortraitSettings.F_STOPS.minByOrNull { kotlin.math.abs(it - currentJpegSettings.portrait.fNumber) }!!,
                        { PortraitSettings.label(it) }) { next ->
                        if (applyJpegOutputSettings(currentJpegSettings.copy(
                                portrait = currentJpegSettings.portrait.copy(fNumber = next)))) refresh()
                    }
                }
            })
            kit.content.addView(Button(activity).apply {
                val levels = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
                fun refresh() { text = "Cat's-eye bokeh: ${(currentJpegSettings.portrait.catEye * 100).toInt()} %" }
                refresh()
                setOnClickListener {
                    kit.choose("Cat's-eye bokeh", levels,
                        levels.minByOrNull { kotlin.math.abs(it - currentJpegSettings.portrait.catEye) }!!,
                        { "${(it * 100).toInt()} %" }) { next ->
                        if (applyJpegOutputSettings(currentJpegSettings.copy(
                                portrait = currentJpegSettings.portrait.copy(catEye = next)))) refresh()
                    }
                }
            })
            kit.content.addView(kit.sectionDesc(if (PortraitNetsHolder.available(activity))
                "DSLR-style background blur on the JPEG (single frame and HDR+). An on-device depth network (MiDaS, NPU) plus a person cutout put the subject on the focal plane; the blur is a layered lens render in scene-linear light, so highlights become bright bokeh discs. Aperture is the look of an 85 mm full-frame lens at portrait distance. DNG stays untouched."
            else "Not available in this build (no on-device network runtime)."))
        }
        }
        kit.content.addView(kit.sectionTitle("Light & color"))
        kit.content.addView(Button(activity).apply {
            fun refresh() { text = "Active D-Lighting: ${currentJpegSettings.activeDLighting.label}" }
            refresh()
            setOnClickListener {
                kit.chooseEnum("Active D-Lighting", currentJpegSettings.activeDLighting, { it.label }) { next ->
                    if (applyJpegOutputSettings(currentJpegSettings.copy(activeDLighting = next))) refresh()
                }
            }
        })
        kit.content.addView(kit.sectionDesc("Protects highlights in PROGRAM/ETTR and lifts JPEG/JXL shadows and midtones. RAW exposure changes; RAW pixel development stays untouched. Off preserves the existing look."))
        if (host.developedIsJpeg) {
        kit.content.addView(SettingsSwitch(activity).apply {
            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                "Ultra HDR JPEG (Android 14+ gainmap)"
            } else {
                "Ultra HDR JPEG (requires Android 14+)"
            }
            setTextColor(activity.getColor(R.color.text_primary))
            isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            isChecked = currentJpegSettings.ultraHdr
            setOnCheckedChangeListener { button, enabled ->
                if (enabled == currentJpegSettings.ultraHdr) return@setOnCheckedChangeListener
                if (!applyJpegOutputSettings(currentJpegSettings.copy(ultraHdr = enabled))) {
                    button.isChecked = currentJpegSettings.ultraHdr
                }
            }
        })
        kit.content.addView(SettingsSwitch(activity).apply {
            text = "Display P3 JPEG"
            setTextColor(activity.getColor(R.color.text_primary))
            isChecked = currentJpegSettings.displayP3
            setOnCheckedChangeListener { button, enabled ->
                if (enabled == currentJpegSettings.displayP3) return@setOnCheckedChangeListener
                if (!applyJpegOutputSettings(currentJpegSettings.copy(displayP3 = enabled))) {
                    button.isChecked = currentJpegSettings.displayP3
                }
            }
        })
        }
        kit.content.addView(kit.sectionTitle("Processing"))
        kit.content.addView(SettingsSwitch(activity).apply {
            text = "Highlight reconstruction (inpaint opposed)"
            setTextColor(activity.getColor(R.color.text_primary))
            isChecked = currentJpegSettings.hlReconstruction
            setOnCheckedChangeListener { button, enabled ->
                if (enabled == currentJpegSettings.hlReconstruction) return@setOnCheckedChangeListener
                if (!applyJpegOutputSettings(currentJpegSettings.copy(hlReconstruction = enabled))) {
                    button.isChecked = currentJpegSettings.hlReconstruction
                }
            }
        })
        val chromaButton = Button(activity).apply {
            fun refresh() {
                text = "JPEG chroma subsampling: ${currentJpegSettings.chromaSubsampling.label}"
            }
            refresh()
            setOnClickListener { kit.chooseEnum("JPEG chroma subsampling", currentJpegSettings.chromaSubsampling, { it.label }) { next ->
                if (applyJpegOutputSettings(currentJpegSettings.copy(chromaSubsampling = next))) {
                    refresh()
                    host.setStatus("JPEG CHROMA • ${next.label}")
                }
            } }
        }
        if (host.developedIsJpeg) kit.content.addView(chromaButton)
        val demosaicButton = Button(activity).apply {
            fun refresh() {
                text = "Demosaic: ${currentJpegSettings.demosaic.label}"
            }
            refresh()
            setOnClickListener { kit.chooseEnum("Demosaic", currentJpegSettings.demosaic, { it.label }) { next ->
                if (applyJpegOutputSettings(currentJpegSettings.copy(demosaic = next))) {
                    refresh()
                    host.setStatus("JPEG DEMOSAIC • ${next.label}")
                }
            } }
        }
        kit.content.addView(demosaicButton)
        kit.content.addView(kit.sectionDesc("AMaZE (GLES) is the default still demosaic; RCD runs the Vulkan RCD modes and imports the result back. Quad-Bayer always uses AMaZE."))
        val falseColorButton = Button(activity).apply {
            fun refresh() {
                text = "False-color suppression: ${if (currentJpegSettings.chromaCleanup.falseColorSuppression) "ON" else "OFF"}"
            }
            refresh()
            setOnClickListener {
                val cleanup = currentJpegSettings.chromaCleanup
                kit.choose("False-color suppression", listOf(false, true), cleanup.falseColorSuppression,
                    { if (it) "ON" else "OFF" }) { enabled ->
                    val next = cleanup.copy(falseColorSuppression = enabled)
                    if (applyJpegOutputSettings(currentJpegSettings.copy(chromaCleanup = next))) {
                        refresh()
                        host.setStatus("FALSE COLOR • ${if (next.falseColorSuppression) "ON" else "OFF"}")
                    }
                }
            }
        }
        kit.content.addView(falseColorButton)
        val defringeSteps = floatArrayOf(0f, 0.5f, 1f)
        val defringeButton = Button(activity).apply {
            fun label(v: Float) = when { v <= 0f -> "OFF"; v < 1f -> "LOW"; else -> "HIGH" }
            fun refresh() {
                text = "Purple defringe: ${label(currentJpegSettings.chromaCleanup.defringeStrength)}"
            }
            refresh()
            setOnClickListener {
                val cleanup = currentJpegSettings.chromaCleanup
                val index = defringeSteps.indexOfFirst { it >= cleanup.defringeStrength - 1e-3f }.coerceAtLeast(0)
                kit.pick("Purple defringe", defringeSteps.map { label(it) }, index) { which ->
                    val next = cleanup.copy(defringeStrength = defringeSteps[which])
                    if (applyJpegOutputSettings(currentJpegSettings.copy(chromaCleanup = next))) {
                        refresh()
                        host.setStatus("DEFRINGE • ${label(next.defringeStrength)}")
                    }
                }
            }
        }
        kit.content.addView(defringeButton)
        kit.content.addView(kit.sectionDesc("Fused into the demosaic's final pass (no extra memory). False-color suppression: two-radius color-difference median against moire and color speckles on fine detail. Defringe: removes purple/magenta edges only at high-contrast edges."))
        if (host.developedIsJpeg) {
        kit.content.addView(kit.sectionTitle("JPEG quality"))
        var jpegQuality = currentJpegSettings.jpegQuality.coerceIn(1, 100)
        val jpegQualityLabel = TextView(activity).apply {
            text = "JPEG quality: $jpegQuality%"
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 14f
            setPadding(kit.dp(12), kit.dp(8), kit.dp(12), 0)
        }
        kit.content.addView(jpegQualityLabel)
        kit.content.addView(SettingsSlider(activity).apply {
            max = 99
            progress = jpegQuality - 1
            setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                override fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean) {
                    jpegQuality = progress + 1
                    jpegQualityLabel.text = "JPEG quality: $jpegQuality%"
                }
                override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit
                override fun onStopTrackingTouch(seekBar: SettingsSlider) {
                    if (applyJpegOutputSettings(currentJpegSettings.copy(jpegQuality = jpegQuality))) {
                        host.setStatus("JPEG QUALITY • $jpegQuality%")
                    } else {
                        jpegQuality = currentJpegSettings.jpegQuality
                        seekBar.progress = jpegQuality - 1
                    }
                }
            })
        })
        kit.content.addView(TextView(activity).apply {
            tag = "settings-description"
            text = "Native libjpeg-turbo is used for SDR JPEG. Ultra HDR uses Android JPEG/R; quality still applies there, while chroma subsampling is controlled by the platform encoder."
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(kit.dp(12), kit.dp(4), kit.dp(12), kit.dp(8))
        })
        }
        fun addAgxSlider(
            title: String,
            maximum: Int,
            initial: Int,
            format: (Int) -> String,
            update: (JpegOutputSettings, Int) -> JpegOutputSettings
        ) {
            var selected = initial.coerceIn(0, maximum)
            val label = TextView(activity).apply {
                text = "$title: ${format(selected)}"
                setTextColor(activity.getColor(R.color.text_primary))
                textSize = 14f
                setPadding(kit.dp(12), kit.dp(8), kit.dp(12), 0)
            }
            kit.content.addView(label)
            kit.content.addView(SettingsSlider(activity).apply {
                max = maximum
                progress = selected
                setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                    override fun onProgressChanged(
                        seekBar: SettingsSlider,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        selected = progress
                        label.text = "$title: ${format(selected)}"
                        // Smooth the drag: live-push the candidate look to the VF
                        // (preview-only; the saved contract commits on release).
                        if (fromUser) host.previewLive(update(currentJpegSettings, selected))
                    }

                    override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit

                    override fun onStopTrackingTouch(seekBar: SettingsSlider) {
                        if (!applyJpegOutputSettings(update(currentJpegSettings, selected))) {
                            selected = initial.coerceIn(0, maximum)
                            seekBar.progress = selected
                            // Commit refused (saves in flight): drop the live
                            // candidate from the VF so it tracks the contract.
                            host.previewLive(currentJpegSettings)
                        }
                    }
                })
            })
        }
        kit.content.addView(kit.sectionTitle("Tone mapper"))
        kit.content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            for (mode in PhotoToneMapper.selectable) {
                addView(Button(activity).apply {
                    text = mode.label
                    isAllCaps = false
                    val selected = currentJpegSettings.toneMapper == mode
                    isSelected = selected
                    contentDescription = "${mode.label} tone mapper${if (selected) ", selected" else ""}"
                    setTextColor(if (selected) android.graphics.Color.BLACK else activity.getColor(R.color.text_primary))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = kit.dp(10).toFloat()
                        setColor(if (selected) android.graphics.Color.WHITE else activity.getColor(R.color.surface_container))
                    }
                    setOnClickListener {
                        if (mode != currentJpegSettings.toneMapper &&
                            applyJpegOutputSettings(currentJpegSettings.copy(toneMapper = mode))) {
                            host.reshow()
                        }
                    }
                }, LinearLayout.LayoutParams(0, kit.dp(48), 1f).apply {
                    setMargins(kit.dp(4), 0, kit.dp(4), 0)
                })
            }
        })
        if (currentJpegSettings.toneMapper == PhotoToneMapper.ACES_2) {
            kit.content.addView(kit.sectionDesc("ACES 2.0 perceptual tone and gamut mapping. 100 nit SDR rendering for sRGB or Display P3. Look controls are under Advanced tone & color."))
        }
        val advancedLookStart = kit.content.childCount
        if (currentJpegSettings.toneMapper == PhotoToneMapper.AGX) {
            kit.content.addView(SettingsSwitch(activity).apply {
                text = "AgX look: " + if (currentJpegSettings.agxLook == AgxLook.BASE) "Base (default)" else "Punchy"
                setTextColor(activity.getColor(R.color.text_primary))
                isChecked = currentJpegSettings.agxLook == AgxLook.PUNCHY
                setOnCheckedChangeListener { button, enabled ->
                    val look = if (enabled) AgxLook.PUNCHY else AgxLook.BASE
                    if (look != currentJpegSettings.agxLook &&
                        !applyJpegOutputSettings(currentJpegSettings.copy(agxLook = look))) {
                        button.isChecked = currentJpegSettings.agxLook == AgxLook.PUNCHY
                    }
                    text = "AgX look: " + if (currentJpegSettings.agxLook == AgxLook.BASE) "Base (default)" else "Punchy"
                }
            })
            var purityPercent = (currentJpegSettings.agxPurityBoost * 100f).toInt().coerceIn(0, 200)
            val purityLabel = TextView(activity).apply {
                text = "AgX purity boost: $purityPercent%"
                setTextColor(activity.getColor(R.color.text_primary))
                textSize = 14f
                setPadding(kit.dp(12), kit.dp(8), kit.dp(12), 0)
            }
            kit.content.addView(purityLabel)
            kit.content.addView(SettingsSlider(activity).apply {
                max = 200
                progress = purityPercent
                setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                    override fun onProgressChanged(
                        seekBar: SettingsSlider,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        purityPercent = progress
                        purityLabel.text = "AgX purity boost: $purityPercent%"
                        if (fromUser) host.previewLive(
                            currentJpegSettings.copy(agxPurityBoost = purityPercent / 100f)
                        )
                    }

                    override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit

                    override fun onStopTrackingTouch(seekBar: SettingsSlider) {
                        if (applyJpegOutputSettings(
                            currentJpegSettings.copy(agxPurityBoost = purityPercent / 100f)
                        )) {
                            host.setStatus("AGX PURITY • $purityPercent%")
                        } else {
                            // Commit refused (saves in flight): revert the label
                            // and drop the live candidate from the VF.
                            purityPercent = (currentJpegSettings.agxPurityBoost * 100f).toInt()
                            seekBar.progress = purityPercent
                            host.previewLive(currentJpegSettings)
                        }
                    }
                })
            })
            addAgxSlider(
                "AgX contrast", 100,
                ((currentJpegSettings.agxContrast - 0.5f) * 100f).toInt(),
                { "${it + 50}%" },
                { settings, value -> settings.copy(agxContrast = 0.5f + value / 100f) }
            )
            addAgxSlider(
                "AgX saturation", 200,
                (currentJpegSettings.agxSaturation * 100f).toInt(),
                { "$it%" },
                { settings, value -> settings.copy(agxSaturation = value / 100f) }
            )
            addAgxSlider(
                "Preserve hue", 100,
                (currentJpegSettings.agxHuePreservation * 100f).toInt(),
                { "$it%" },
                { settings, value -> settings.copy(agxHuePreservation = value / 100f) }
            )
            addAgxSlider(
                "Highlight range", 70,
                ((currentJpegSettings.agxHighlightEv - 3f) * 10f).toInt(),
                { String.format(Locale.US, "+%.1f EV", 3f + it / 10f) },
                { settings, value -> settings.copy(agxHighlightEv = 3f + value / 10f) }
            )
            addAgxSlider(
                "Shadow range", 100,
                ((currentJpegSettings.agxShadowEv - 4f) * 10f).toInt(),
                { String.format(Locale.US, "%.1f EV", 4f + it / 10f) },
                { settings, value -> settings.copy(agxShadowEv = 4f + value / 10f) }
            )
            addAgxSlider(
                "Gamut compression", 100,
                (currentJpegSettings.agxGamutCompression * 100f).toInt(),
                { "$it%" },
                { settings, value -> settings.copy(agxGamutCompression = value / 100f) }
            )
        }
        fun addLookSlider(title: String, maximum: Int, toProgress: (ToneLookSettings) -> Float, fromProgress: (Int) -> Float,
                          format: (Int) -> String, apply: (ToneLookSettings, Float) -> ToneLookSettings) =
            addAgxSlider(title, maximum, toProgress(currentJpegSettings.toneLook).roundToInt(), format) { settings, value ->
                settings.copy(toneLook = apply(settings.toneLook, fromProgress(value)))
            }
        val pct: (Int) -> String = { "$it%" }
        when (currentJpegSettings.toneMapper) {
            PhotoToneMapper.SIGMOID -> {
                kit.content.addView(kit.sectionDesc("darktable sigmoid: same curve and parameters as darktable's sigmoid module. Defaults equal darktable's defaults; colorfulness 100% = off."))
                addLookSlider("Sigmoid contrast", 350, { (it.sigmoidContrast - 0.5f) * 100f }, { 0.5f + it / 100f },
                    { String.format(Locale.US, "%.2f", 0.5f + it / 100f) }) { t, v -> t.copy(sigmoidContrast = v) }
                addLookSlider("Sigmoid skew", 200, { (it.sigmoidSkew + 1f) * 100f }, { it / 100f - 1f },
                    { String.format(Locale.US, "%+.2f", it / 100f - 1f) }) { t, v -> t.copy(sigmoidSkew = v) }
                addLookSlider("Target white", 50, { (it.sigmoidWhite - 0.5f) * 100f }, { 0.5f + it / 100f },
                    { "${50 + it}%" }) { t, v -> t.copy(sigmoidWhite = v) }
                addLookSlider("Target black", 200, { it.sigmoidBlack * 10000f }, { it / 10000f },
                    { String.format(Locale.US, "%.2f%%", it / 100f) }) { t, v -> t.copy(sigmoidBlack = v) }
                addLookSlider("Preserve hue", 100, { it.sigmoidHue * 100f }, { it / 100f }, pct) { t, v -> t.copy(sigmoidHue = v) }
                addLookSlider("Colorfulness", 200, { it.sigmoidColorfulness * 100f }, { it / 100f }, pct) { t, v -> t.copy(sigmoidColorfulness = v) }
            }
            PhotoToneMapper.ACES_2 -> {
                kit.content.addView(kit.sectionDesc("ACES 2.0 look: exposure, contrast (around 18% grey) and saturation are applied in ACEScg before the 100 nit output transform."))
                addLookSlider("ACES exposure", 80, { (it.acesExposureEv + 2f) * 20f }, { it / 20f - 2f },
                    { String.format(Locale.US, "%+.2f EV", it / 20f - 2f) }) { t, v -> t.copy(acesExposureEv = v) }
                addLookSlider("ACES contrast", 100, { (it.acesContrast - 0.5f) * 100f }, { 0.5f + it / 100f },
                    { "${50 + it}%" }) { t, v -> t.copy(acesContrast = v) }
                addLookSlider("ACES saturation", 200, { it.acesSaturation * 100f }, { it / 100f }, pct) { t, v -> t.copy(acesSaturation = v) }
            }
            PhotoToneMapper.BASIC -> {
                addLookSlider("Basic contrast", 100, { (it.basicContrast - 0.5f) * 100f }, { 0.5f + it / 100f },
                    { "${50 + it}%" }) { t, v -> t.copy(basicContrast = v) }
                addLookSlider("Basic saturation", 200, { it.basicSaturation * 100f }, { it / 100f }, pct) { t, v -> t.copy(basicSaturation = v) }
            }
            PhotoToneMapper.OPENDRT -> {
                kit.content.addView(kit.sectionDesc("OpenDRT v1.1.0 by Jed Smith (GPL-3.0). Pick a look or tonescale preset to load its upstream values, then fine-tune every OpenDRT parameter below. SDR 100 nit, sRGB or Display P3 output."))
                fun setOdrt(next: OpenDrtSettings) {
                    if (applyJpegOutputSettings(currentJpegSettings.copy(toneLook = currentJpegSettings.toneLook.copy(openDrt = next)))) host.reshow()
                }
                fun choose(title: String, items: List<String>, pick: (Int) -> Unit) {
                    android.app.AlertDialog.Builder(activity).setTitle(title)
                        .setItems(items.toTypedArray()) { _, i -> pick(i) }.show()
                }
                val odrt = currentJpegSettings.toneLook.openDrt.bounded()
                kit.content.addView(Button(activity).apply {
                    isAllCaps = false
                    text = "Look preset: ${OpenDrt.LOOKS[odrt.look].first}"
                    setOnClickListener { choose("OpenDRT look preset", OpenDrt.LOOKS.map { it.first }) { setOdrt(currentJpegSettings.toneLook.openDrt.withLook(it)) } }
                })
                kit.content.addView(Button(activity).apply {
                    isAllCaps = false
                    text = "Apply tonescale preset…"
                    setOnClickListener { choose("OpenDRT tonescale preset", OpenDrt.TONESCALES.map { it.first }) { setOdrt(currentJpegSettings.toneLook.openDrt.withTonescale(it)) } }
                })
                var group = ""
                for (p in OpenDrtParam.entries) {
                    if (p.hidden) continue
                    if (p.group != group) { group = p.group; kit.content.addView(kit.sectionDesc("OpenDRT · $group")) }
                    val choices = p.choices
                    when {
                        p.toggle -> kit.content.addView(SettingsSwitch(activity).apply {
                            text = p.label
                            setTextColor(activity.getColor(R.color.text_primary))
                            isChecked = odrt[p] > 0.5f
                            setOnCheckedChangeListener { button, on ->
                                val next = currentJpegSettings.toneLook.openDrt.with(p, if (on) 1f else 0f)
                                if (!applyJpegOutputSettings(currentJpegSettings.copy(toneLook = currentJpegSettings.toneLook.copy(openDrt = next))))
                                    button.isChecked = currentJpegSettings.toneLook.openDrt[p] > 0.5f
                            }
                        })
                        choices != null -> kit.content.addView(Button(activity).apply {
                            isAllCaps = false
                            text = "${p.label}: ${choices[odrt[p].toInt()]}"
                            setOnClickListener { choose(p.label, choices) { setOdrt(currentJpegSettings.toneLook.openDrt.with(p, it.toFloat())) } }
                        })
                        else -> {
                            val steps = 1000
                            val span = p.max - p.min
                            addAgxSlider(p.label, steps, ((odrt[p] - p.min) / span * steps).roundToInt(),
                                { String.format(Locale.US, "%.3f", p.min + span * it / steps) }) { settings, v ->
                                settings.copy(toneLook = settings.toneLook.copy(openDrt = settings.toneLook.openDrt.with(p, p.min + span * v / steps)))
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
        if (currentJpegSettings.toneMapper != PhotoToneMapper.AGX) {
            kit.content.addView(Button(activity).apply {
                text = "Reset ${currentJpegSettings.toneMapper.label} look"
                setOnClickListener {
                    val look = currentJpegSettings.toneLook
                    val reset = if (currentJpegSettings.toneMapper == PhotoToneMapper.OPENDRT) look.copy(openDrt = OpenDrtSettings().withLook(look.openDrt.look))
                        else ToneLookSettings(openDrt = look.openDrt)
                    if (applyJpegOutputSettings(currentJpegSettings.copy(toneLook = reset))) host.reshow()
                }
            })
        }
        kit.content.addView(SettingsSwitch(activity).apply {
            text = "Adaptive development exposure (AUTO and ZSL)"
            setTextColor(activity.getColor(R.color.text_primary))
            isChecked = currentJpegSettings.adaptiveExposureAuto
            setOnCheckedChangeListener { button, enabled ->
                if (enabled == currentJpegSettings.adaptiveExposureAuto) return@setOnCheckedChangeListener
                if (!applyJpegOutputSettings(
                    currentJpegSettings.copy(adaptiveExposureAuto = enabled)
                )) button.isChecked = currentJpegSettings.adaptiveExposureAuto
            }
        })
        addAgxSlider(
            "PROGRAM adaptive exposure", 100,
            (currentJpegSettings.adaptiveExposureProgramStrength * 100f).toInt(),
            { "$it%" },
            { settings, value -> settings.copy(adaptiveExposureProgramStrength = value / 100f) }
        )
        kit.content.addView(TextView(activity).apply {
            tag = "settings-description"
            text = "Highlights: headroom protects the brightest pixels; sky protection protects broad bright areas."
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(kit.dp(12), kit.dp(8), kit.dp(12), 0)
        })
        addAgxSlider(
            "Shadow lift", 100,
            (currentJpegSettings.shadowLift * 100f).toInt(),
            { "$it%" },
            { settings, value -> settings.copy(shadowLift = value / 100f) }
        )
        addAgxSlider(
            "Highlight recovery", 100,
            (currentJpegSettings.highlightRecovery * 100f).toInt(),
            { "$it%" },
            { settings, value -> settings.copy(highlightRecovery = value / 100f) }
        )
        kit.content.addView(TextView(activity).apply {
            tag = "settings-description"
            text = "Shadow lift protects deep blacks. Highlight recovery is RapidRAW 1.7 Highlights (100% = slider -100): pulls bright tones while holding white. Set either to 0% to bypass it."
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(kit.dp(12), kit.dp(8), kit.dp(12), 0)
        })
        kit.content.addView(Button(activity).apply {
            text = "Reset shadow and highlight adjustments"
            setOnClickListener {
                if (applyJpegOutputSettings(currentJpegSettings.copy(
                    shadowLift = CameraToneBalance.DEFAULT_SHADOW_LIFT, highlightRecovery = 0f))) host.reshow()
            }
        })
        addAgxSlider(
            "Highlight headroom", 100,
            ((currentJpegSettings.highlightHeadroom - 0.5f) * 100f).toInt(),
            { String.format(Locale.US, "%.2f", 0.5f + it / 100f) },
            { settings, value -> settings.copy(highlightHeadroom = 0.5f + value / 100f) }
        )
        addAgxSlider(
            "Sky protection", 40,
            ((currentJpegSettings.highlightSoftHeadroom - 0.6f) * 100f).toInt(),
            { String.format(Locale.US, "%.2f", 0.6f + it / 100f) },
            { settings, value -> settings.copy(highlightSoftHeadroom = 0.6f + value / 100f) }
        )
        addAgxSlider(
            "Highlight shoulder", 100,
            (currentJpegSettings.highlightShoulder * 100f).toInt(),
            { "$it%" },
            { settings, value -> settings.copy(highlightShoulder = value / 100f) }
        )
        kit.content.addView(Button(activity).apply {
            text = "Reset official AgX Base"
            setOnClickListener {
                val official = JpegOutputSettings(
                    activeDLighting = currentJpegSettings.activeDLighting,
                    ultraHdr = currentJpegSettings.ultraHdr,
                    displayP3 = currentJpegSettings.displayP3,
                    adaptiveExposureAuto = currentJpegSettings.adaptiveExposureAuto,
                    adaptiveExposureProgramStrength = currentJpegSettings.adaptiveExposureProgramStrength,
                    highlightHeadroom = currentJpegSettings.highlightHeadroom,
                    highlightSoftHeadroom = currentJpegSettings.highlightSoftHeadroom,
                    highlightShoulder = currentJpegSettings.highlightShoulder,
                    shadowLift = currentJpegSettings.shadowLift,
                    highlightRecovery = currentJpegSettings.highlightRecovery,
                    demosaic = currentJpegSettings.demosaic,
                    chromaCleanup = currentJpegSettings.chromaCleanup,
                    toneLook = currentJpegSettings.toneLook,
                    photoLut = currentJpegSettings.photoLut
                )
                if (applyJpegOutputSettings(official)) {
                    host.setStatus("AGX RESET • OFFICIAL BASE")
                    host.reshow()
                }
            }
        })
        kit.polish()
        kit.collapseRange(advancedLookStart, kit.content.childCount, "Advanced tone & color",
            "Tone mapping, color and highlight controls")
        kit.collapseSection("Black & white", "Black & white", "Mode and colour DNG")
        kit.collapseSection("Processing", "Processing", "Demosaic, highlight reconstruction and color cleanup")
    }
}
