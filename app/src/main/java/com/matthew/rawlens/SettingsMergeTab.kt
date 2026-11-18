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
        content.addView(sectionTitle("MFSR · Multi-Frame Super-Resolution"))
        content.addView(sectionDesc(
            "Auto, Program and Manual capture a fresh burst; ZSL merges buffered frames. " +
                "Reconstruction kernel and Wronski supplement S.3 high-frequency " +
                "artefact rejection for the sharp merge (live tile and offline " +
                "merges). KernelNet predicts one kernel map from the reference " +
                "frame; Wronski uses the paper's per-frame anisotropic kernels. " +
                "S.3 off gives 1:1 parity with the IPOL reference, which has no S.3."
        ))
        fun mfsrKernelText(kernel: MfsrKernel): String = when (kernel) {
            MfsrKernel.KERNELNET -> "Kernel: KernelNet"
            MfsrKernel.WRONSKI -> "Kernel: Wronski (paper S.1)"
        }
        content.addView(settingsButton(mfsrKernelText(mfsrSettings.kernel)).apply {
            setOnClickListener {
                p.kit.chooseEnum("Kernel", mfsrSettings.kernel, { mfsrKernelText(it) }) { next ->
                    if (applyMfsrSettings(mfsrSettings.copy(kernel = next))) {
                        text = mfsrKernelText(next)
                        setStatus("MFSR ${mfsrKernelText(next).uppercase(Locale.US)}")
                    }
                }
            }
        })
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
        content.addView(settingsCheck("Zipper fix (colour-difference merge)", mfsrSettings.zipperFix).apply {
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
        content.addView(settingsCheck("2× super-resolution (MFSR)", mfsrSettings.mfsrFullRes).apply {
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
        content.addView(settingsCheck("Show MFSR Lab (expert tuning)",
            mfsrLabPrefs.getBoolean(MfsrTuning.KEY_SHOW_LAB, false)).apply {
            setOnCheckedChangeListener { _, checked ->
                mfsrLabPrefs.edit().putBoolean(MfsrTuning.KEY_SHOW_LAB, checked).apply()
                mfsrLabBox.visibility = if (checked) View.VISIBLE else View.GONE
            }
        })
        mfsrLabBox.visibility = if (mfsrLabPrefs.getBoolean(MfsrTuning.KEY_SHOW_LAB, false)) View.VISIBLE else View.GONE
        content.addView(mfsrLabBox)
        buildMfsrLab(p, mfsrLabBox)
        content.addView(sectionTitle("LK-R · Lucas–Kanade Reloaded"))
        content.addView(sectionDesc(
            "RawLens adaptation of Lucas–Kanade Reloaded (Lecouat et al., 2021). " +
                "RawLens improvements: DSLR look, motion and water rejection, local motion, " +
                "shadow cleanup. Own quick tile; same shutter, ZSL and save path as MFSR."
        ))
        content.addView(settingsCheck("LK-R denoise: automatic KernelNet", mfsrSettings.lkrKernelNetDenoise).apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked != mfsrSettings.lkrKernelNetDenoise &&
                    !applyMfsrSettings(mfsrSettings.copy(lkrKernelNetDenoise = checked))) {
                    isChecked = mfsrSettings.lkrKernelNetDenoise
                }
            }
        })
        content.addView(sectionDesc(
            "On by default. PhotonCamera-based KernelNet denoising increases in noisy, " +
                "low-light scenes. Low-noise scenes skip it."
        ))
        content.addView(settingsCheck("LK-R: Network", mfsrSettings.lkrNetwork).apply {
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
        content.addView(settingsCheck("LK-R shadow luma denoise", mfsrSettings.lkrDctPrior).apply {
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
                "are left untouched. Off: luma is not touched. KernelNet denoise is controlled separately."
        ))
        content.addView(settingsCheck("LK-R reference: middle of the burst", mfsrSettings.lkrMiddleReference).apply {
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
        content.addView(settingsCheck("Show LK-R Lab (expert tuning)",
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
