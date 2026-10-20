// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * Settings → Photo: developed format (JPEG / JPEG XL), JPEG XL container
 * options (bit depth, pixel type, quality, effort, decoding speed, HDR
 * transfer, Display P3), and the shared JPEG/AgX develop look. Moved
 * verbatim from MainActivity.showSettings; JPEG XL options mirror
 * darktable's export panel (see in-line notes).
 */
internal class PhotoSettingsTab(
    private val kit: SettingsKit,
    private val host: Host,
) {
    /** MainActivity state the Photo tab reads and writes. */
    interface Host {
        val controller: RawCameraController
        var developedFormat: DevelopedFormat
        fun jpegOutputSettings(): JpegOutputSettings
        fun jxlOutputSettings(): JxlOutputSettings
        fun lensPreferences(): SharedPreferences
        fun setStatus(message: String)
        fun refreshCaptureFormatControl()
        /** Forget other tabs' live status views before this page replaces them. */
        fun clearTabStatusViews()
        /** Rebuild this page in place (after a reset). */
        fun reshow()
    }

    private val activity get() = kit.activity

    fun show() {
        host.clearTabStatusViews()
        kit.content.removeAllViews()
        // JPEG XL container options, mirroring darktable's export panel:
        // bit depth, 16-bit pixel type, quality, encoding effort, and
        // decoding-speed tier. The develop look (AgX) is shared with JPEG.
        kit.content.addView(kit.sectionTitle("Photo format & JPEG XL quality"))
        kit.content.addView(kit.sectionDesc("Up to 16-bit integer (plus 16/32-bit float), like darktable. Quality 100 is lossless on integer depths. Higher effort compresses better but encodes slower."))
        kit.content.addView(Button(activity).apply {
            fun refresh() {
                text = "Developed format: ${host.developedFormat.label}"
            }
            refresh()
            setOnClickListener {
                val next = host.developedFormat.next()
                if (!host.controller.setDevelopedFormat(next)) {
                    host.setStatus("SETTINGS AFTER SAVES")
                    return@setOnClickListener
                }
                host.developedFormat = next
                host.lensPreferences().edit().putString(MainActivity.KEY_DEVELOPED_FORMAT, next.name).apply()
                refresh()
                host.refreshCaptureFormatControl()
                host.setStatus("DEVELOPED • ${next.label}")
            }
        })
        kit.content.addView(kit.sectionDesc("JPEG writes .jpg (8-bit); JXL writes .jxl at the bit depth below. Applies to new captures and offline merges."))
        var currentJxlSettings = host.jxlOutputSettings()
        fun applyJxlOutputSettings(settings: JxlOutputSettings): Boolean {
            val resolved = settings.resolved()
            if (!host.controller.setJxlOutputSettings(resolved)) {
                host.setStatus("SETTINGS AFTER SAVES")
                return false
            }
            currentJxlSettings = resolved
            host.lensPreferences().edit()
                .putString(MainActivity.KEY_JXL_BIT_DEPTH, resolved.bitDepth.name)
                .putString(MainActivity.KEY_JXL_PIXEL_TYPE, resolved.pixelType.name)
                .putInt(MainActivity.KEY_JXL_QUALITY, resolved.quality)
                .putInt(MainActivity.KEY_JXL_EFFORT, resolved.effort)
                .putInt(MainActivity.KEY_JXL_DECODING_SPEED, resolved.decodingSpeed)
                .putBoolean(MainActivity.KEY_JXL_DISPLAY_P3, resolved.displayP3)
                .putString(MainActivity.KEY_JXL_HDR_TRANSFER, resolved.hdrTransfer.name)
                .apply()
            return true
        }
        if (!NativeJxlEncoder.isAvailable()) {
            kit.content.addView(kit.sectionDesc("The JXL encoder library is not bundled in this build (offline or source-only build): developed-format JXL saves fail with an explanatory error. A networked build bundles libjxl automatically; see app/src/main/cpp/CMakeLists.txt."))
        }
        kit.content.addView(Button(activity).apply {
            fun refresh() {
                text = "HDR transfer: ${currentJxlSettings.hdrTransfer.label}"
            }
            refresh()
            setOnClickListener {
                if (applyJxlOutputSettings(
                    currentJxlSettings.copy(hdrTransfer = currentJxlSettings.hdrTransfer.next())
                )) {
                    refresh()
                    host.setStatus("JXL TRANSFER • ${currentJxlSettings.hdrTransfer.label}")
                }
            }
        })
        kit.content.addView(kit.sectionDesc("SDR is the classic still. HDR PQ keeps scene highlights above SDR white as true HDR (ST2084, 10+ bit, needs an HDR display); the AgX look stays shared."))
        kit.content.addView(kit.sectionDesc("Stills carry EXIF/XMP: Ultra HDR gainmaps apply to JPEG output only, and the AgX photo look below is shared."))
        // Declared pixel-type-first: the depth cycler below toggles its
        // visibility (darktable shows it for 16-bit only).
        val pixelTypeButton = Button(activity).apply {
            fun refresh() {
                text = "JXL pixel type: ${currentJxlSettings.pixelType.label}"
            }
            refresh()
            visibility =
                if (currentJxlSettings.bitDepth == JxlBitDepth.BIT_16) Button.VISIBLE
                else Button.GONE
            setOnClickListener {
                if (applyJxlOutputSettings(
                    currentJxlSettings.copy(pixelType = currentJxlSettings.pixelType.next())
                )) {
                    refresh()
                    host.setStatus("JXL PIXEL TYPE • ${currentJxlSettings.pixelType.label}")
                }
            }
        }
        val depthButton = Button(activity).apply {
            fun refresh() {
                text = "JXL bit depth: ${currentJxlSettings.bitDepth.label}"
            }
            refresh()
            setOnClickListener {
                if (applyJxlOutputSettings(
                    currentJxlSettings.copy(bitDepth = currentJxlSettings.bitDepth.next())
                )) {
                    refresh()
                    pixelTypeButton.visibility =
                        if (currentJxlSettings.bitDepth == JxlBitDepth.BIT_16) Button.VISIBLE
                        else Button.GONE
                    host.setStatus("JXL DEPTH • ${currentJxlSettings.bitDepth.label}")
                }
            }
        }
        kit.content.addView(depthButton)
        kit.content.addView(pixelTypeButton)
        kit.content.addView(kit.sectionDesc("Pixel type applies to 16-bit only (darktable parity): unsigned integer, or half-precision float. 32-bit is always float."))
        fun jxlQualityLabel(quality: Int): String =
            if (quality >= 100) "JXL quality: lossless" else "JXL quality: $quality%"
        var jxlQuality = currentJxlSettings.quality.coerceIn(
            JxlOutputSettings.MIN_QUALITY, JxlOutputSettings.MAX_QUALITY
        )
        val jxlQualityLabel = TextView(activity).apply {
            text = jxlQualityLabel(jxlQuality)
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 14f
            setPadding(kit.dp(12), kit.dp(8), kit.dp(12), 0)
        }
        kit.content.addView(jxlQualityLabel)
        kit.content.addView(SeekBar(activity).apply {
            max = JxlOutputSettings.MAX_QUALITY - JxlOutputSettings.MIN_QUALITY
            progress = jxlQuality - JxlOutputSettings.MIN_QUALITY
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    jxlQuality = progress + JxlOutputSettings.MIN_QUALITY
                    jxlQualityLabel.text = jxlQualityLabel(jxlQuality)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    if (applyJxlOutputSettings(currentJxlSettings.copy(quality = jxlQuality))) {
                        host.setStatus(
                            if (jxlQuality >= 100) "JXL QUALITY • LOSSLESS"
                            else "JXL QUALITY • $jxlQuality%"
                        )
                    } else {
                        jxlQuality = currentJxlSettings.quality
                        seekBar.progress = jxlQuality - JxlOutputSettings.MIN_QUALITY
                    }
                }
            })
        })
        fun addJxlSlider(
            title: String,
            maximum: Int,
            initial: Int,
            format: (Int) -> String,
            update: (JxlOutputSettings, Int) -> JxlOutputSettings,
            status: (Int) -> String
        ) {
            var selected = initial.coerceIn(0, maximum)
            val label = TextView(activity).apply {
                text = "$title: ${format(selected)}"
                setTextColor(activity.getColor(R.color.text_primary))
                textSize = 14f
                setPadding(kit.dp(12), kit.dp(8), kit.dp(12), 0)
            }
            kit.content.addView(label)
            kit.content.addView(SeekBar(activity).apply {
                max = maximum
                progress = selected
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        selected = progress
                        label.text = "$title: ${format(selected)}"
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        if (applyJxlOutputSettings(update(currentJxlSettings, selected))) {
                            host.setStatus(status(selected))
                        } else {
                            selected = initial.coerceIn(0, maximum)
                            seekBar.progress = selected
                        }
                    }
                })
            })
        }
        addJxlSlider(
            "JXL encoding effort", 8, currentJxlSettings.effort - 1,
            { "${it + 1}" },
            { settings, value -> settings.copy(effort = value + 1) },
            { "JXL EFFORT • ${it + 1}" }
        )
        addJxlSlider(
            "JXL decoding speed", 4, currentJxlSettings.decodingSpeed,
            { "$it" },
            { settings, value -> settings.copy(decodingSpeed = value) },
            { "JXL DECODING SPEED • $it" }
        )
        kit.content.addView(CheckBox(activity).apply {
            text = "Display P3 JXL"
            setTextColor(activity.getColor(R.color.text_primary))
            isChecked = currentJxlSettings.displayP3
            setOnCheckedChangeListener { button, enabled ->
                if (enabled == currentJxlSettings.displayP3) return@setOnCheckedChangeListener
                if (!applyJxlOutputSettings(currentJxlSettings.copy(displayP3 = enabled))) {
                    button.isChecked = currentJxlSettings.displayP3
                }
            }
        })
        kit.content.addView(kit.sectionTitle("Photo look & JPEG quality"))
        kit.content.addView(kit.sectionDesc("Ultra HDR and Display P3 targets, JPEG quality and chroma, AgX look, adaptive exposure and highlight handling."))
        var currentJpegSettings = host.jpegOutputSettings()
        fun applyJpegOutputSettings(settings: JpegOutputSettings): Boolean {
            val resolved = settings.resolvedForPlatform()
            if (!host.controller.setJpegOutputSettings(resolved)) {
                host.setStatus("SETTINGS AFTER SAVES")
                return false
            }
            currentJpegSettings = resolved
            host.lensPreferences().edit()
                .putBoolean(MainActivity.KEY_JPEG_ULTRA_HDR, resolved.ultraHdr)
                .putBoolean(MainActivity.KEY_JPEG_DISPLAY_P3, resolved.displayP3)
                .putInt(MainActivity.KEY_JPEG_QUALITY, resolved.jpegQuality)
                .putString(MainActivity.KEY_JPEG_CHROMA_SUBSAMPLING, resolved.chromaSubsampling.name)
                .putString(MainActivity.KEY_JPEG_DEMOSAIC, resolved.demosaic.name)
                .putFloat(MainActivity.KEY_JPEG_AGX_PURITY, resolved.agxPurityBoost)
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
                .putBoolean(MainActivity.KEY_JPEG_HL_RECONSTRUCTION, resolved.hlReconstruction)
                .apply()
            return true
        }
        kit.content.addView(CheckBox(activity).apply {
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
        kit.content.addView(CheckBox(activity).apply {
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
        kit.content.addView(CheckBox(activity).apply {
            text = "Highlight reconstruction (color propagation)"
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
            setOnClickListener {
                val next = currentJpegSettings.chromaSubsampling.next()
                if (applyJpegOutputSettings(currentJpegSettings.copy(chromaSubsampling = next))) {
                    refresh()
                    host.setStatus("JPEG CHROMA • ${next.label}")
                }
            }
        }
        kit.content.addView(chromaButton)
        val demosaicButton = Button(activity).apply {
            fun refresh() {
                text = "Demosaic: ${currentJpegSettings.demosaic.label}"
            }
            refresh()
            setOnClickListener {
                val next = currentJpegSettings.demosaic.next()
                if (applyJpegOutputSettings(currentJpegSettings.copy(demosaic = next))) {
                    refresh()
                    host.setStatus("JPEG DEMOSAIC • ${next.label}")
                }
            }
        }
        kit.content.addView(demosaicButton)
        kit.content.addView(kit.sectionDesc("AMaZE (GLES) is the default still demosaic; RCD runs the Vulkan RCD modes and imports the result back. Quad-Bayer always uses AMaZE."))
        var jpegQuality = currentJpegSettings.jpegQuality.coerceIn(1, 100)
        val jpegQualityLabel = TextView(activity).apply {
            text = "JPEG quality: $jpegQuality%"
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 14f
            setPadding(kit.dp(12), kit.dp(8), kit.dp(12), 0)
        }
        kit.content.addView(jpegQualityLabel)
        kit.content.addView(SeekBar(activity).apply {
            max = 99
            progress = jpegQuality - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    jpegQuality = progress + 1
                    jpegQualityLabel.text = "JPEG quality: $jpegQuality%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) {
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
            text = "Native libjpeg-turbo is used for SDR JPEG. Ultra HDR uses Android JPEG/R; quality still applies there, while chroma subsampling is controlled by the platform encoder."
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(kit.dp(12), kit.dp(4), kit.dp(12), kit.dp(8))
        })
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
            kit.content.addView(SeekBar(activity).apply {
                max = maximum
                progress = selected
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        selected = progress
                        label.text = "$title: ${format(selected)}"
                        // Smooth the drag: live-push the candidate look to the VF
                        // (preview-only; the saved contract commits on release).
                        if (fromUser) host.controller.previewAgxLive(update(currentJpegSettings, selected))
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        if (!applyJpegOutputSettings(update(currentJpegSettings, selected))) {
                            selected = initial.coerceIn(0, maximum)
                            seekBar.progress = selected
                            // Commit refused (saves in flight): drop the live
                            // candidate from the VF so it tracks the contract.
                            host.controller.previewAgxLive(currentJpegSettings)
                        }
                    }
                })
            })
        }
        var purityPercent = (currentJpegSettings.agxPurityBoost * 100f).toInt().coerceIn(0, 200)
        val purityLabel = TextView(activity).apply {
            text = "AgX purity boost: $purityPercent%"
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 14f
            setPadding(kit.dp(12), kit.dp(8), kit.dp(12), 0)
        }
        kit.content.addView(purityLabel)
        kit.content.addView(SeekBar(activity).apply {
            max = 200
            progress = purityPercent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    purityPercent = progress
                    purityLabel.text = "AgX purity boost: $purityPercent%"
                    if (fromUser) host.controller.previewAgxLive(
                        currentJpegSettings.copy(agxPurityBoost = purityPercent / 100f)
                    )
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    if (applyJpegOutputSettings(
                        currentJpegSettings.copy(agxPurityBoost = purityPercent / 100f)
                    )) {
                        host.setStatus("AGX PURITY • $purityPercent%")
                    } else {
                        // Commit refused (saves in flight): revert the label
                        // and drop the live candidate from the VF.
                        purityPercent = (currentJpegSettings.agxPurityBoost * 100f).toInt()
                        seekBar.progress = purityPercent
                        host.controller.previewAgxLive(currentJpegSettings)
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
        kit.content.addView(CheckBox(activity).apply {
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
            text = "Highlights: headroom caps the p99.5 spike at white; sky protection caps broad p95 skies; shoulder rolls off near-white into AgX."
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(kit.dp(12), kit.dp(8), kit.dp(12), 0)
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
                    ultraHdr = currentJpegSettings.ultraHdr,
                    displayP3 = currentJpegSettings.displayP3,
                    adaptiveExposureAuto = currentJpegSettings.adaptiveExposureAuto,
                    adaptiveExposureProgramStrength = currentJpegSettings.adaptiveExposureProgramStrength,
                    highlightHeadroom = currentJpegSettings.highlightHeadroom,
                    highlightSoftHeadroom = currentJpegSettings.highlightSoftHeadroom,
                    highlightShoulder = currentJpegSettings.highlightShoulder,
                    demosaic = currentJpegSettings.demosaic
                )
                if (applyJpegOutputSettings(official)) {
                    host.setStatus("AGX RESET • OFFICIAL BASE")
                    host.reshow()
                }
            }
        })

    }
}
