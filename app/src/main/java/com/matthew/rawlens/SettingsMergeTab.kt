// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Settings -> Merge: every burst-merge engine in one place (MFSR, LK-R,
 * HDR+). MFSR / HDR+ sections moved verbatim from Settings -> Burst, which
 * keeps the capture side (offline merges, ZSL ring, sidecars).
 */
internal fun MainActivity.showMergeSettings(p: SettingsPage) {
    val content = p.content
    fun sectionTitle(text: String): TextView = p.kit.sectionTitle(text)
    fun sectionDesc(text: String): TextView = p.kit.sectionDesc(text)
    fun settingsButton(label: String, active: Boolean = false): Button = p.kit.settingsButton(label, active)
    fun settingsCheck(label: String, checked: Boolean): SettingsSwitch = p.kit.settingsCheck(label, checked)
    val mergeTab = p.tabs[5]
        content.removeAllViews()
        content.addView(sectionTitle("MFSR"))
        content.addView(sectionDesc(
            "Auto, Program and Manual capture a fresh burst; ZSL merges buffered frames. " +
                "Wronski supplement S.3 high-frequency artefact rejection for the " +
                "sharp merge (live tile and offline merges). S.3 off gives 1:1 " +
                "parity with the IPOL reference, which has no S.3."
        ))
        content.addView(settingsCheck("S.3 artefact rejection", mfsrSettings.s3).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.s3) {
                    if (applyMfsrSettings(mfsrSettings.copy(s3 = checked))) {
                        setStatus(if (checked) "MFSR S.3 • ON" else "MFSR S.3 • OFF")
                    } else {
                        isChecked = mfsrSettings.s3
                    }
                }
            }
        })
        content.addView(settingsCheck("Zipper artefact correction", mfsrSettings.zipperFix).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.zipperFix) {
                    if (applyMfsrSettings(mfsrSettings.copy(zipperFix = checked))) {
                        setStatus(if (checked) "MFSR ZIPPER FIX • ON" else "MFSR ZIPPER FIX • OFF")
                    } else {
                        isChecked = mfsrSettings.zipperFix
                    }
                }
            }
        })
        content.addView(settingsCheck("Green fit at 1×", mfsrSettings.greenFit).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.greenFit) {
                    if (applyMfsrSettings(mfsrSettings.copy(greenFit = checked))) {
                        setStatus(if (checked) "MFSR GREEN FIT • ON" else "MFSR GREEN FIT • OFF")
                    } else {
                        isChecked = mfsrSettings.greenFit
                    }
                }
            }
        })
        content.addView(sectionDesc("Uses the colour-difference merge at 1× with two-band reconstruction off."))
        content.addView(settingsCheck("RT capture sharpening", mfsrSettings.rtSharpen).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.rtSharpen) {
                    if (applyMfsrSettings(mfsrSettings.copy(rtSharpen = checked))) {
                        setStatus(if (checked) "MFSR RT SHARPENING • ON" else "MFSR RT SHARPENING • OFF")
                    } else {
                        isChecked = mfsrSettings.rtSharpen
                    }
                }
            }
        })
        content.addView(settingsCheck("Experimental two-band reconstruction", mfsrSettings.twoBand).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.twoBand) {
                    if (applyMfsrSettings(mfsrSettings.copy(twoBand = checked))) {
                        setStatus(if (checked) "MFSR TWO-BAND • ON" else "MFSR TWO-BAND • OFF")
                    } else {
                        isChecked = mfsrSettings.twoBand
                    }
                }
            }
        })
        content.addView(settingsCheck("Learned noise model", mfsrSettings.learnedNoise).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.learnedNoise) {
                    if (applyMfsrSettings(mfsrSettings.copy(learnedNoise = checked))) {
                        setStatus(if (checked) "MFSR LEARNED NOISE • ON" else "MFSR LEARNED NOISE • OFF")
                    } else {
                        isChecked = mfsrSettings.learnedNoise
                    }
                }
            }
        })
        content.addView(settingsCheck("2× super-resolution", mfsrSettings.mfsrFullRes).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.mfsrFullRes) {
                    if (applyMfsrSettings(mfsrSettings.copy(mfsrFullRes = checked))) {
                        setStatus(if (checked) "MFSR 2× • ON" else "MFSR 2× • OFF")
                        updateLkrZoomChip()
                    } else {
                        isChecked = mfsrSettings.mfsrFullRes
                    }
                }
            }
        })
        content.addView(sectionDesc(
            "Off (default): output at the sensor size. On: Wronski super-resolution at 2× " +
                "(a 12.5 MP sensor gives 50 MP). Same as the round MP button in the viewfinder."
        ))
        val mfsrLabPrefs = MfsrTuning.prefs(this)
        val mfsrLabBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(settingsCheck("Show MFSR Lab",
            mfsrLabPrefs.getBoolean(MfsrTuning.KEY_SHOW_LAB, false)).apply {
            setOnCheckedChangeListener { _, checked ->
                mfsrLabPrefs.edit().putBoolean(MfsrTuning.KEY_SHOW_LAB, checked).apply()
                mfsrLabBox.visibility = if (checked) View.VISIBLE else View.GONE
            }
        })
        mfsrLabBox.visibility = if (mfsrLabPrefs.getBoolean(MfsrTuning.KEY_SHOW_LAB, false)) View.VISIBLE else View.GONE
        content.addView(mfsrLabBox)
        buildMfsrLab(p, mfsrLabBox)
        content.addView(sectionTitle("LK-R"))
        content.addView(sectionDesc(
            "RawLens adaptation of Lucas–Kanade Reloaded (Lecouat et al., 2021). " +
                "RawLens improvements: DSLR look, motion and water rejection, local motion, " +
                "shadow cleanup. Own quick tile; same shutter, ZSL and save path as MFSR."
        ))
        content.addView(settingsCheck("Bypass denoising", mfsrSettings.lkrNoPrior).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.lkrNoPrior &&
                    !applyMfsrSettings(mfsrSettings.copy(lkrNoPrior = checked))) {
                    isChecked = mfsrSettings.lkrNoPrior
                }
            }
        })
        content.addView(sectionDesc("Bypasses the networks, DCT and additional denoising."))
        content.addView(settingsCheck("Network denoise", mfsrSettings.lkrNetwork).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.lkrNetwork &&
                    !applyMfsrSettings(mfsrSettings.copy(lkrNetwork = checked))) {
                    isChecked = mfsrSettings.lkrNetwork
                }
            }
        })
        content.addView(sectionDesc(
            "A small learned network removes colour dots and colour zipper after the merge; " +
                "brightness, detail and grain stay as merged. Default on for phones with an NPU (MediaTek " +
                "APU, Qualcomm Hexagon), where it is nearly free; elsewhere it runs on the GPU and adds time."
        ))
        content.addView(settingsCheck("NPU denoise", mfsrSettings.lkrNpuDenoise).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.lkrNpuDenoise &&
                    !applyMfsrSettings(mfsrSettings.copy(lkrNpuDenoise = checked))) {
                    isChecked = mfsrSettings.lkrNpuDenoise
                }
            }
        })
        content.addView(sectionDesc(
            "Uses a shorter merge for regular bursts and denoises on the NPU while Vulkan fits the next tile. " +
                "Takes precedence over Network and falls back to DCT if needed."
        ))
        content.addView(settingsCheck("Shadow luminance denoise", mfsrSettings.lkrDctPrior).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.lkrDctPrior &&
                    !applyMfsrSettings(mfsrSettings.copy(lkrDctPrior = checked))) {
                    isChecked = mfsrSettings.lkrDctPrior
                }
            }
        })
        content.addView(sectionDesc(
            "Colour noise is always removed, following the measured noise, without colour bleeding. " +
                "On: also cleans luma noise in shadows down to a fine, even grain; mid-tones and highlights " +
                "are left untouched. Off: luma is not touched."
        ))
        content.addView(settingsButton("LK-R HDR").apply {
            fun refresh() { text = "LK-R HDR: ${mfsrSettings.lkrHdr.label}" }
            refresh()
            setOnClickListener {
                p.kit.chooseEnum("LK-R HDR", mfsrSettings.lkrHdr, { it.label }) { picked ->
                    applyMfsrSettings(mfsrSettings.copy(lkrHdr = picked))
                    refresh()
                }
            }
        })
        content.addView(sectionDesc(
            "Auto: when the moment you press shows blown highlights, the last frames of the burst are " +
                "taken 2 EV darker (and one 1.5 EV brighter when the shadows need it) and merged with " +
                "the ZSL frames: highlights keep detail, the JPEG / Ultra HDR is tone-mapped. On: always. " +
                "Off: a normal burst. Same frame count, same merge time."
        ))
        content.addView(settingsCheck("Use middle frame as reference", mfsrSettings.lkrMiddleReference).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.lkrMiddleReference &&
                    !applyMfsrSettings(mfsrSettings.copy(lkrMiddleReference = checked))) {
                    isChecked = mfsrSettings.lkrMiddleReference
                }
            }
        })
        content.addView(sectionDesc(
            "On (default): the burst is taken around the shutter press (half before, half " +
                "after, up to 0.3 s after) and the sharpest of the three middle frames is the " +
                "reference, so moving subjects keep more frames. Off: frames before the press " +
                "only, reference = sharpest of the newest three."
        ))
        val labPrefs = LkrTuning.prefs(this)
        val labBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(settingsCheck("Show LK-R Lab",
            labPrefs.getBoolean(LkrTuning.KEY_SHOW_LAB, false)).apply {
            setOnCheckedChangeListener { _, checked ->
                labPrefs.edit().putBoolean(LkrTuning.KEY_SHOW_LAB, checked).apply()
                labBox.visibility = if (checked) View.VISIBLE else View.GONE
            }
        })
        labBox.visibility = if (labPrefs.getBoolean(LkrTuning.KEY_SHOW_LAB, false)) View.VISIBLE else View.GONE
        content.addView(labBox)
        buildLkrLab(p, labBox)
        content.addView(sectionTitle("HDR+ merge"))
        content.addView(sectionDesc(
            "HDR+ takes a burst of RAW frames on one shutter press and merges them into a " +
                "single photo. Combining the aligned frames lowers noise, so shadows are " +
                "cleaner and more of the scene's dynamic range is usable than in one frame.\n\n" +
                "Strength (${HdrPlusSettings.MIN_STRENGTH.toInt()}–${HdrPlusSettings.MAX_STRENGTH.toInt()}) " +
                "sets how hard the frames are merged: higher is cleaner on still scenes but " +
                "can ghost on motion. Auto strength picks it from the measured noise and " +
                "motion and ignores the slider.\n\n" +
                "Auto, Program and Manual capture a fresh burst; ZSL merges the frames " +
                "already buffered."
        ))
        fun strengthRowText(value: Float): String =
            HdrPlusSettings.strengthSliderText(value) +
                if (hdrPlusSettings.autoStrength) " • AUTO" else ""
        var selectedStrength = hdrPlusSettings.strength
        val strengthLabel = TextView(this).apply {
            text = strengthRowText(selectedStrength)
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        content.addView(settingsCheck("Auto strength", hdrPlusSettings.autoStrength).apply {
            setOnCheckedChangeListener { _, checked ->
                val applied = applyHdrPlusSettings(
                    hdrPlusSettings.copy(autoStrength = checked)
                )
                if (applied) {
                    strengthLabel.text = strengthRowText(selectedStrength)
                    setStatus(if (checked) "HDR+ STRENGTH • AUTO" else "HDR+ STRENGTH • MANUAL")
                } else {
                    isChecked = hdrPlusSettings.autoStrength
                }
            }
        })
        content.addView(strengthLabel)
        content.addView(SettingsSlider(this).apply {
            max = HdrPlusSettings.STRENGTH_SLIDER_STEPS
            progress = HdrPlusSettings.strengthToProgress(selectedStrength)
            setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                override fun onProgressChanged(
                    seekBar: SettingsSlider,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    selectedStrength = HdrPlusSettings.progressToStrength(progress)
                    strengthLabel.text = strengthRowText(selectedStrength)
                }

                override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit

                override fun onStopTrackingTouch(seekBar: SettingsSlider) {
                    val applied = applyHdrPlusSettings(
                        hdrPlusSettings.copy(autoStrength = false, strength = selectedStrength)
                    )
                    if (applied) {
                        strengthLabel.text = strengthRowText(selectedStrength)
                        setStatus(
                            String.format(Locale.US, "HDR+ STRENGTH • %.1f", selectedStrength)
                        )
                    } else {
                        selectedStrength = hdrPlusSettings.strength
                        seekBar.progress =
                            HdrPlusSettings.strengthToProgress(selectedStrength)
                        strengthLabel.text = strengthRowText(selectedStrength)
                    }
                }
            })
        })
        p.markActive(mergeTab)
        p.kit.polish()
        p.kit.collapseSection("MFSR", "MFSR", "Multi-frame super-resolution and Lab controls")
        p.kit.collapseSection("LK-R", "LK-R", "Lucas–Kanade merge, denoising and Lab controls")
        p.kit.collapseSection("HDR+ merge", "HDR+ merge", "Automatic or manual merge strength")
}

/**
 * LK-R Lab rows: one slider (or switch) per [LkrTuning.Entry]. Values start at
 * the native code defaults; a changed value is stored and applies to the next
 * LK-R merge (live and offline). "Reset" forgets every override.
 */
/** MFSR Lab: Wronski 2019 / IPOL tuning values ([MfsrTuning]); same layout as the LK-R Lab. */
private fun MainActivity.buildMfsrLab(p: SettingsPage, box: LinearLayout) {
    val prefs = MfsrTuning.prefs(this)
    box.addView(p.kit.sectionDesc(
        "Paper / reference tuning of MFSR. Defaults are the tuned values in code; values marked × " +
            "multiply the automatic per-burst (light level) value. Changes apply to the next MFSR merge."
    ))
    val refreshers = ArrayList<() -> Unit>()
    for (e in MfsrTuning.Entry.entries) {
        fun labelText(v: Float): String {
            val mark = if (MfsrTuning.isOverridden(prefs, e))
                " • changed (default ${MfsrTuning.format(e, e.defaultValue())})" else ""
            return "${e.label}: ${MfsrTuning.format(e, v)}$mark"
        }
        when (e.kind) {
            MfsrTuning.Kind.SWITCH -> {
                val sw = p.kit.settingsCheck(e.label, MfsrTuning.value(prefs, e) >= .5f)
                sw.setOnCheckedChangeListener { _, checked -> MfsrTuning.set(prefs, e, if (checked) 1f else 0f) }
                box.addView(sw)
                refreshers += { sw.isChecked = MfsrTuning.value(prefs, e) >= .5f }
            }
            else -> {
                val steps = Math.round((e.max - e.min) / e.step)
                fun toProgress(v: Float) = Math.round((v.coerceIn(e.min, e.max) - e.min) / e.step)
                fun fromProgress(pr: Int) = e.min + pr * e.step
                var current = MfsrTuning.value(prefs, e)
                val label = TextView(this).apply {
                    text = labelText(current)
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 14f
                    setPadding(dp(12), dp(8), dp(12), 0)
                }
                box.addView(label)
                val slider = SettingsSlider(this).apply {
                    max = steps
                    progress = toProgress(current)
                    setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                        override fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean) {
                            current = fromProgress(progress)
                            label.text = labelText(current)
                        }
                        override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit
                        override fun onStopTrackingTouch(seekBar: SettingsSlider) {
                            MfsrTuning.set(prefs, e, current)
                            label.text = labelText(current)
                        }
                    })
                }
                box.addView(slider)
                refreshers += {
                    current = MfsrTuning.value(prefs, e)
                    slider.progress = toProgress(current)
                    label.text = labelText(current)
                }
            }
        }
        box.addView(TextView(this).apply {
            text = e.help
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(dp(12), 0, dp(12), dp(6))
        })
    }
    box.addView(p.kit.settingsButton("Reset MFSR Lab to defaults").apply {
        setOnClickListener {
            MfsrTuning.resetAll(prefs)
            refreshers.forEach { it() }
            setStatus("MFSR LAB • DEFAULTS")
        }
    })
}

private fun MainActivity.buildLkrLab(p: SettingsPage, box: LinearLayout) {
    val prefs = LkrTuning.prefs(this)
    box.addView(p.kit.sectionDesc(
        "Defaults are the tuned values in code. Changes apply to the next LK-R merge; " +
            "a value set back to its default follows future code updates again."
    ))
    val defaults = LkrTuning.defaults()
    if (defaults == null) {
        box.addView(p.kit.sectionDesc("LK-R library unavailable on this build."))
        return
    }
    val refreshers = ArrayList<() -> Unit>()
    for (e in LkrTuning.Entry.entries) {
        fun labelText(v: Float): String {
            val d = defaults[e.index]
            val mark = if (LkrTuning.isOverridden(prefs, e)) " • changed (default ${LkrTuning.format(e, d)})" else ""
            return "${e.label}: ${LkrTuning.format(e, v)}$mark"
        }
        when (e.kind) {
            LkrTuning.Kind.SWITCH -> {
                val sw = p.kit.settingsCheck(e.label, (LkrTuning.value(prefs, e) ?: 0f) >= .5f)
                sw.setOnCheckedChangeListener { _, checked -> LkrTuning.set(prefs, e, if (checked) 1f else 0f) }
                box.addView(sw)
                refreshers += { sw.isChecked = (LkrTuning.value(prefs, e) ?: 0f) >= .5f }
            }
            else -> {
                val steps = Math.round((e.max - e.min) / e.step)
                fun toProgress(v: Float) = Math.round((v.coerceIn(e.min, e.max) - e.min) / e.step)
                fun fromProgress(pr: Int) = e.min + pr * e.step
                var current = LkrTuning.value(prefs, e) ?: e.min
                val label = TextView(this).apply {
                    text = labelText(current)
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 14f
                    setPadding(dp(12), dp(8), dp(12), 0)
                }
                box.addView(label)
                val slider = SettingsSlider(this).apply {
                    max = steps
                    progress = toProgress(current)
                    setOnSettingsSliderChangeListener(object : SettingsSlider.OnSettingsSliderChangeListener {
                        override fun onProgressChanged(seekBar: SettingsSlider, progress: Int, fromUser: Boolean) {
                            current = fromProgress(progress)
                            label.text = labelText(current)
                        }
                        override fun onStartTrackingTouch(seekBar: SettingsSlider) = Unit
                        override fun onStopTrackingTouch(seekBar: SettingsSlider) {
                            LkrTuning.set(prefs, e, current)
                            label.text = labelText(current)
                        }
                    })
                }
                box.addView(slider)
                refreshers += {
                    current = LkrTuning.value(prefs, e) ?: e.min
                    slider.progress = toProgress(current)
                    label.text = labelText(current)
                }
            }
        }
        box.addView(TextView(this).apply {
            text = e.help
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(dp(12), 0, dp(12), dp(6))
        })
    }
    box.addView(p.kit.settingsButton("Reset LK-R Lab to defaults").apply {
        setOnClickListener {
            LkrTuning.resetAll(prefs)
            refreshers.forEach { it() }
            setStatus("LK-R LAB • DEFAULTS")
        }
    })
}
