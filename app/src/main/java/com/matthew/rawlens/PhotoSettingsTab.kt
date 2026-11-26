// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

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
    interface Host : JpegLookHost {
        val controller: RawCameraController
        var developedFormat: DevelopedFormat
        fun jxlOutputSettings(): JxlOutputSettings
        fun refreshCaptureFormatControl()
        /** Forget other tabs' live status views before this page replaces them. */
        fun clearTabStatusViews()
        override val developedIsJpeg: Boolean get() = developedFormat == DevelopedFormat.JPEG
        override fun previewLive(settings: JpegOutputSettings) = controller.previewAgxLive(settings)
    }

    private val activity get() = kit.activity

    fun show() {
        host.clearTabStatusViews()
        kit.content.removeAllViews()
        if (XiaomiUltraRaw50.supported()) {
            kit.content.addView(kit.sectionTitle("RAW capture"))
            kit.content.addView(kit.settingsCheck("50 MP RAW", XiaomiUltraRaw50.enabled(activity)).apply {
                setOnCheckedChangeListener { _, checked ->
                    host.controller.setXiaomiRaw50Enabled(checked)
                }
            })
            kit.content.addView(kit.sectionDesc("Experimental. Uses the full-resolution RAW mode for single photos on supported rear lenses."))
        }
        if (VivoCameraGate.supported()) {
            kit.content.addView(kit.sectionTitle("vivo"))
            kit.content.addView(kit.settingsButton("High-res access: ${VivoCameraGate.mode(activity).label}").apply {
                setOnClickListener {
                    val modes = VivoCameraGate.Mode.entries
                    AlertDialog.Builder(activity).setTitle("vivo high-res access")
                        .setSingleChoiceItems(modes.map { it.label }.toTypedArray(),
                            VivoCameraGate.mode(activity).ordinal) { dialog, which ->
                            VivoCameraGate.setMode(activity, modes[which])
                            host.controller.setVivoGateMode(modes[which])
                            dialog.dismiss()
                            host.reshow()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            kit.content.addView(kit.sectionDesc("Experimental. Opens the camera with vivo's own-camera permission for full-size RAW. Used only by the H and F lenses, or by a Custom RAW reader of about 50 MP or more."))
        }
        kit.content.addView(kit.sectionTitle("RAW & DNG tools"))
        kit.content.addView(kit.navRow("Develop DNGs", "Import photos and export developed images") {
            activity.startActivity(android.content.Intent(activity, DevelopQueueActivity::class.java))
        })
        host.controller.activeSettingsKey()?.let { key ->
            val size = host.controller.customRawReaderSizeSetting()
            kit.content.addView(kit.settingsButton("Custom RAW reader: ${size ?: "Off"}").apply {
                setOnClickListener { showCustomRawReader(key, size) }
            })
        }
        if (LapisRootProfile.supported()) {
            val profile = LapisRootProfile.preferences(activity)
            kit.content.addView(kit.sectionTitle("Redmi Note 15 Pro 5G"))
            kit.content.addView(kit.settingsCheck("Redmi sensor modes", LapisRootProfile.enabled(activity)).apply {
                setOnCheckedChangeListener { _, checked ->
                    profile.edit().putBoolean(LapisRootProfile.ENABLED, checked).apply()
                    (activity as? MainActivity)?.let { it.updateQuickControls(); it.refreshCaptureFormatControl() }
                    host.controller.reopenCamera()
                    host.setStatus(if (checked) "REDMI SENSOR MODES ON" else "REDMI SENSOR MODES OFF")
                    host.reshow()
                }
            })
            if (LapisRootProfile.enabled(activity)) {
                kit.content.addView(kit.navRow("Lenses shown in switcher", null) {
                    val lenses = host.controller.lensOptions(includeHidden = true)
                    val checked = lenses.map { LapisRootProfile.lensVisible(activity, it) }.toBooleanArray()
                    android.app.AlertDialog.Builder(activity)
                        .setTitle("Lenses shown in switcher")
                        .setMultiChoiceItems(lenses.map { it.label }.toTypedArray(), checked) { dialog, index, visible ->
                            checked[index] = visible
                            if (checked.none { it }) {
                                checked[index] = true
                                (dialog as android.app.AlertDialog).listView.setItemChecked(index, true)
                            }
                        }
                        .setPositiveButton("Save") { _, _ ->
                            lenses.forEachIndexed { index, lens ->
                                LapisRootProfile.setLensVisible(activity, lens, checked[index])
                            }
                            (activity as? MainActivity)?.updateQuickControls()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                })
                kit.content.addView(kit.settingsCheck("200→50 MP phase correction",
                    profile.getBoolean("hexa_phase_fit", false)).apply {
                    setOnCheckedChangeListener { _, checked ->
                        profile.edit().putBoolean("hexa_phase_fit", checked).apply()
                    }
                })
                kit.content.addView(kit.sectionDesc("Choose 12.5 MP, 50 MP, 200→50 MP, 2× / 4× sensor RAW or DCG14 in the lens switcher. Root must be active and granted to RawLens. Sensor setup is automatic; camera settings are restored when the session closes."))
                kit.content.addView(kit.sectionDesc("Phase correction accounts for differences in pixel response during 200→50 MP RAW development."))
                kit.content.addView(kit.sectionDesc("DCG14: 12.5 MP DCG/DSG RAW14 output at 30 fps; automatic exposure is required. Manual ISO/shutter and flash are unavailable in this mode. 50 MP: verified RAW10. 2× / 4×: RAW14 sensor interface, delivered as 10-bit RAW; extra precision is unverified. 200→50 MP: one-shot DNG; the shutter reads the 200 MP sensor mode (about 20 s with camera restarts) and uses rawr same-colour sums + Quad Detail remosaic, saving a 50 MP Bayer CFA DNG. Preview stays at 12.5 MP; burst, HDR and ZSL are unavailable. Camera libraries are hash-checked before every root session; an OTA that changes them disables these modes safely."))
            }
        }
        // JPEG XL container options, mirroring darktable's export panel:
        // bit depth, 16-bit pixel type, quality, encoding effort, and
        // decoding-speed tier. The develop look (AgX) is shared with JPEG.
        kit.content.addView(kit.sectionTitle("File format"))
        kit.content.addView(Button(activity).apply {
            fun refresh() {
                text = "Format: ${host.developedFormat.label}"
            }
            refresh()
            setOnClickListener { kit.chooseEnum("Format", host.developedFormat, { it.label }) { next ->
                if (!host.controller.setDevelopedFormat(next)) {
                    host.setStatus("SETTINGS AFTER SAVES")
                    return@chooseEnum
                }
                host.developedFormat = next
                host.lensPreferences().edit().putString(MainActivity.KEY_DEVELOPED_FORMAT, next.name).apply()
                refresh()
                host.refreshCaptureFormatControl()
                host.setStatus("DEVELOPED • ${next.label}")
                host.reshow()
            } }
        })
        if (host.developedFormat == DevelopedFormat.JXL) {
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
            setOnClickListener { kit.chooseEnum("HDR transfer", currentJxlSettings.hdrTransfer, { it.label }) { picked ->
                if (applyJxlOutputSettings(currentJxlSettings.copy(hdrTransfer = picked))) {
                    refresh()
                    host.setStatus("JXL TRANSFER • ${currentJxlSettings.hdrTransfer.label}")
                }
            } }
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
            setOnClickListener { kit.chooseEnum("JXL pixel type", currentJxlSettings.pixelType, { it.label }) { picked ->
                if (applyJxlOutputSettings(currentJxlSettings.copy(pixelType = picked))) {
                    refresh()
                    host.setStatus("JXL PIXEL TYPE • ${currentJxlSettings.pixelType.label}")
                }
            } }
        }
        val depthButton = Button(activity).apply {
            fun refresh() {
                text = "JXL bit depth: ${currentJxlSettings.bitDepth.label}"
            }
            refresh()
            setOnClickListener { kit.chooseEnum("JXL bit depth", currentJxlSettings.bitDepth, { it.label }) { picked ->
                if (applyJxlOutputSettings(currentJxlSettings.copy(bitDepth = picked))) {
                    refresh()
                    pixelTypeButton.visibility =
                        if (currentJxlSettings.bitDepth == JxlBitDepth.BIT_16) Button.VISIBLE
                        else Button.GONE
                    host.setStatus("JXL DEPTH • ${currentJxlSettings.bitDepth.label}")
                }
            } }
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
        kit.content.addView(SettingsSlider(activity).apply {
            max = JxlOutputSettings.MAX_QUALITY - JxlOutputSettings.MIN_QUALITY
            progress = jxlQuality - JxlOutputSettings.MIN_QUALITY
            setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                override fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean) {
                    jxlQuality = progress + JxlOutputSettings.MIN_QUALITY
                    jxlQualityLabel.text = jxlQualityLabel(jxlQuality)
                }
                override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit
                override fun onStopTrackingTouch(seekBar: SettingsSlider) {
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
                    }

                    override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit

                    override fun onStopTrackingTouch(seekBar: SettingsSlider) {
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
        kit.content.addView(SettingsSwitch(activity).apply {
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
        }
        JpegLookSettingsSection(kit, host, cameraOnly = true).show()
        kit.collapseSection("RAW & DNG tools", "RAW & DNG tools", "Develop DNGs and custom RAW reader")

    }

    private fun showCustomRawReader(cameraKey: String, currentSize: String?) {
        val input = EditText(activity).apply {
            hint = "Width × height, e.g. 8192x6144"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setText(currentSize.orEmpty())
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Custom RAW reader")
            .setMessage("Enter a photo RAW resolution for this lens. For full-size RAW (50 MP and more) the known keys for this phone are added by themselves. The camera HAL must accept the size.")
            .setView(input)
            .setPositiveButton("Apply", null)
            .setNeutralButton("Turn off") { _, _ ->
                host.controller.setCustomRawReaderSize(cameraKey, null)
                host.reshow()
            }
            .setNegativeButton("Cancel", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val size = RawSizePreferenceStore.normalizeReaderSize(input.text.toString())
            if (size == null) {
                input.error = "Enter a valid RAW size as width × height"
                return@setOnClickListener
            }
            host.controller.setCustomRawReaderSize(cameraKey, size)
            dialog.dismiss()
            host.reshow()
        }
    }
}
