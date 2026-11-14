// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.content.SharedPreferences
import com.rawlens.srpipeline.stages.SrTuning

/**
 * "KB-SR Lab": the Wronski 2019 / IPOL tuning values of the KB-SR (MFSR)
 * engine, in Settings -> Merge behind a hidden-by-default switch, like the
 * LK-SR Lab ([LkrTuning]). Defaults come from [SrTuning] (the code, single
 * source of truth, shared with the desktop tuning sweeps); only values the
 * user changed are stored (prefs `mfsr_lab_<key>`). The SNR-tuned kernel
 * values are multipliers of the automatic per-burst value. The paper's kernel
 * values act on the Wronski kernel only; KernelNet has its own width values.
 */
internal object MfsrTuning {
    enum class Kind { SWITCH, INT, FLOAT }

    enum class Entry(
        val label: String,
        val help: String,
        val kind: Kind,
        val min: Float,
        val max: Float,
        val step: Float,
        val default: (SrTuning) -> Float,
        val apply: (SrTuning, Float) -> SrTuning,
    ) {
        K_DETAIL("Kernel detail ×", "Wronski kernel only. Base kernel width (paper k_detail) times the automatic value. Lower = sharper, noisier.",
            Kind.FLOAT, .5f, 2f, .05f, { it.kDetailScale }, { t, v -> t.copy(kDetailScale = v) }),
        K_DENOISE("Kernel denoise ×", "Wronski kernel only. How much flat areas widen the kernel (k_denoise). Higher = smoother, cleaner flats.",
            Kind.FLOAT, .5f, 2f, .05f, { it.kDenoiseScale }, { t, v -> t.copy(kDenoiseScale = v) }),
        D_TH("Denoise threshold ×", "Wronski kernel only. Where flat areas start getting the denoise kernel (D_th).",
            Kind.FLOAT, .5f, 1.5f, .05f, { it.dThScale }, { t, v -> t.copy(dThScale = v) }),
        D_TR("Denoise transition ×", "Wronski kernel only. How fast detail switches denoising off (D_tr).",
            Kind.FLOAT, .5f, 2f, .05f, { it.dTrScale }, { t, v -> t.copy(dTrScale = v) }),
        K_STRETCH("Edge stretch", "Wronski kernel only. Kernel stretch along edges (k_stretch). Higher = smoother edges, fewer zippers.",
            Kind.FLOAT, 1f, 8f, .25f, { it.kStretch }, { t, v -> t.copy(kStretch = v) }),
        K_SHRINK("Edge shrink", "Wronski kernel only. Kernel shrink across edges (k_shrink). Higher = crisper edges.",
            Kind.FLOAT, 1f, 4f, .1f, { it.kShrink }, { t, v -> t.copy(kShrink = v) }),
        K_MIN("Minimum kernel width", "Wronski kernel only. Smallest kernel std (px). Too small gives a checker pattern in texture.",
            Kind.FLOAT, 0f, 1.2f, .02f, { it.kMin }, { t, v -> t.copy(kMin = v) }),
        ROBUST_T("Rejection threshold t", "Robustness offset (Eq. 5). Higher rejects more (less ghosting, more noise).",
            Kind.FLOAT, 0f, .5f, .01f, { it.t }, { t, v -> t.copy(t = v) }),
        ROBUST_S1("Rejection scale, moving", "Scale s where the alignment varies (moving objects).",
            Kind.FLOAT, 1f, 20f, .5f, { it.s1 }, { t, v -> t.copy(s1 = v) }),
        ROBUST_S2("Rejection scale, static", "Scale s elsewhere. Higher merges more (more detail, risk of ghosts).",
            Kind.FLOAT, 1f, 20f, .5f, { it.s2 }, { t, v -> t.copy(s2 = v) }),
        ROBUST_MT("Motion threshold (px)", "Alignment spread that counts as motion (M_th).",
            Kind.FLOAT, .1f, 3f, .05f, { it.mt }, { t, v -> t.copy(mt = v) }),
        S3_RHO("High-frequency guard ratio", "S.3: variance-loss ratio that marks hard-to-align fine patterns.",
            Kind.FLOAT, 0f, .5f, .01f, { it.s3Rho }, { t, v -> t.copy(s3Rho = v) }),
        S3_K("High-frequency guard level", "S.3: minimum variance, in noise units.",
            Kind.FLOAT, 2f, 64f, 1f, { it.s3K }, { t, v -> t.copy(s3K = v) }),
        NOISE_CORRECTION("Noise-corrected rejection", "Use the noise model in the rejection test (paper Eq. 6).",
            Kind.SWITCH, 0f, 1f, 1f, { if (it.noiseCorrection) 1f else 0f }, { t, v -> t.copy(noiseCorrection = v >= .5f) }),
        ICA_ITER("Alignment refinement steps", "Lucas-Kanade iterations per level (paper: 3).",
            Kind.INT, 1f, 6f, 1f, { it.icaIterations.toFloat() }, { t, v -> t.copy(icaIterations = v.toInt()) }),
        KN_SCALE("KernelNet width", "KernelNet kernel only. KernelNet kernel width scale.",
            Kind.FLOAT, .1f, .6f, .01f, { it.knSigmaScale }, { t, v -> t.copy(knSigmaScale = v) }),
        KN_CAP("KernelNet width cap (px)", "KernelNet kernel only. Largest KernelNet kernel width.",
            Kind.FLOAT, .9f, 2.5f, .05f, { it.knSigmaCap }, { t, v -> t.copy(knSigmaCap = v) });

        val key: String get() = "mfsr_lab_" + name.lowercase()
        fun defaultValue(): Float = default(SrTuning())
    }

    const val KEY_SHOW_LAB = "mfsr_lab_visible"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    fun isOverridden(prefs: SharedPreferences, e: Entry) = prefs.contains(e.key)

    fun value(prefs: SharedPreferences, e: Entry): Float =
        if (prefs.contains(e.key)) prefs.getFloat(e.key, e.defaultValue()) else e.defaultValue()

    fun set(prefs: SharedPreferences, e: Entry, v: Float) {
        // Back at the code default: forget the override so future default changes apply.
        if (kotlin.math.abs(v - e.defaultValue()) < e.step / 2) prefs.edit().remove(e.key).apply()
        else prefs.edit().putFloat(e.key, v).apply()
    }

    fun resetAll(prefs: SharedPreferences) =
        prefs.edit().apply { Entry.entries.forEach { remove(it.key) } }.apply()

    /** Effective tuning: code defaults plus the user's overrides. */
    fun resolve(context: Context): SrTuning {
        val p = prefs(context)
        var t = SrTuning()
        for (e in Entry.entries) if (p.contains(e.key)) t = e.apply(t, p.getFloat(e.key, e.defaultValue()))
        return t
    }

    fun format(e: Entry, v: Float): String = when (e.kind) {
        Kind.SWITCH -> if (v >= .5f) "on" else "off"
        Kind.INT -> v.toInt().toString()
        Kind.FLOAT -> if (e.step < .05f) String.format(java.util.Locale.US, "%.2f", v)
            else String.format(java.util.Locale.US, "%.2f", v)
    }
}
