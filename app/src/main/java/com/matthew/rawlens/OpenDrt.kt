// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.SharedPreferences
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * OpenDRT v1.1.0 by Jed Smith, https://github.com/jedypod/open-display-transform (GPL-3.0).
 * Full "stickshift" parameter space of OpenDRT.dctl transform(): every preset parameter is a
 * user slider; look and tonescale presets load the upstream values verbatim. SDR (100 nit peak),
 * input = RawLens scene-linear Rec.2020 D65, output = sRGB/Rec.709 or Display P3 (D65).
 * Display encoding defaults to OpenDRT's "sRGB Display - 2.2 Power" preset (Bright surround).
 */

/** One OpenDRT parameter. [toggle] params are 0/1, [choices] params are an index. */
enum class OpenDrtParam(val label: String, val min: Float, val max: Float, val group: String,
                        val toggle: Boolean = false, val choices: List<String>? = null, val hidden: Boolean = false) {
    TN_CON("Contrast", 1f, 2f, "Tonescale"),
    TN_SH("Shoulder clip", 0f, 1f, "Tonescale"),
    TN_TOE("Toe", 0f, 0.1f, "Tonescale"),
    TN_OFF("Offset", 0f, 0.02f, "Tonescale"),
    TN_HCON_ENABLE("Contrast high", 0f, 1f, "Tonescale", toggle = true),
    TN_HCON("Contrast high amount", -1f, 1f, "Tonescale"),
    TN_HCON_PV("Contrast high pivot", 0f, 4f, "Tonescale"),
    TN_HCON_ST("Contrast high strength", 0f, 4f, "Tonescale"),
    TN_LCON_ENABLE("Contrast low", 0f, 1f, "Tonescale", toggle = true),
    TN_LCON("Contrast low amount", 0f, 3f, "Tonescale"),
    TN_LCON_W("Contrast low width", 0f, 2f, "Tonescale"),
    CWP("Creative white", 0f, 5f, "Creative white", choices = listOf("D93", "D75", "D65", "D60", "D55", "D50")),
    CWP_LM("Creative white limit", 0f, 1f, "Creative white"),
    RS_SA("Render space strength", 0f, 0.6f, "Render space"),
    RS_RW("Render space weight R", 0f, 0.8f, "Render space"),
    RS_BW("Render space weight B", 0f, 0.8f, "Render space"),
    PT_ENABLE("Purity compress high", 0f, 1f, "Purity", toggle = true, hidden = true), // unused upstream
    PT_LML("Purity limit low", 0f, 1f, "Purity"),
    PT_LML_R("Purity limit low R", 0f, 1f, "Purity"),
    PT_LML_G("Purity limit low G", 0f, 1f, "Purity"),
    PT_LML_B("Purity limit low B", 0f, 1f, "Purity"),
    PT_LMH("Purity limit high", 0f, 1f, "Purity"),
    PT_LMH_R("Purity limit high R", 0f, 1f, "Purity"),
    PT_LMH_B("Purity limit high B", 0f, 1f, "Purity"),
    PTL_ENABLE("Purity softclip", 0f, 1f, "Purity", toggle = true),
    PTL_C("Purity softclip C", 0f, 0.25f, "Purity"),
    PTL_M("Purity softclip M", 0f, 0.25f, "Purity"),
    PTL_Y("Purity softclip Y", 0f, 0.25f, "Purity"),
    PTM_ENABLE("Mid purity", 0f, 1f, "Mid purity", toggle = true),
    PTM_LOW("Mid purity low", 0f, 2f, "Mid purity"),
    PTM_LOW_RNG("Mid purity low range", 0f, 1f, "Mid purity"),
    PTM_LOW_ST("Mid purity low strength", 0.1f, 1f, "Mid purity"),
    PTM_HIGH("Mid purity high", -0.9f, 0f, "Mid purity"),
    PTM_HIGH_RNG("Mid purity high range", 0f, 1f, "Mid purity"),
    PTM_HIGH_ST("Mid purity high strength", 0.1f, 1f, "Mid purity"),
    BRL_ENABLE("Brilliance", 0f, 1f, "Brilliance", toggle = true),
    BRL("Brilliance amount", -6f, 2f, "Brilliance"),
    BRL_R("Brilliance R", -6f, 2f, "Brilliance"),
    BRL_G("Brilliance G", -6f, 2f, "Brilliance"),
    BRL_B("Brilliance B", -6f, 2f, "Brilliance"),
    BRL_RNG("Brilliance range", 0f, 1f, "Brilliance"),
    BRL_ST("Brilliance strength", 0f, 1f, "Brilliance"),
    BRLP_ENABLE("Post brilliance", 0f, 1f, "Post brilliance", toggle = true),
    BRLP("Post brilliance amount", -1f, 0f, "Post brilliance"),
    BRLP_R("Post brilliance R", -3f, 0f, "Post brilliance"),
    BRLP_G("Post brilliance G", -3f, 0f, "Post brilliance"),
    BRLP_B("Post brilliance B", -3f, 0f, "Post brilliance"),
    HC_ENABLE("Hue contrast", 0f, 1f, "Hue contrast", toggle = true),
    HC_R("Hue contrast R", 0f, 2f, "Hue contrast"),
    HC_R_RNG("Hue contrast R range", 0f, 1f, "Hue contrast"),
    HS_RGB_ENABLE("Hue shift RGB", 0f, 1f, "Hue shift", toggle = true),
    HS_R("Hue shift R", 0f, 1f, "Hue shift"),
    HS_R_RNG("Hue shift R range", 0f, 2f, "Hue shift"),
    HS_G("Hue shift G", 0f, 1f, "Hue shift"),
    HS_G_RNG("Hue shift G range", 0f, 2f, "Hue shift"),
    HS_B("Hue shift B", 0f, 1f, "Hue shift"),
    HS_B_RNG("Hue shift B range", 0f, 4f, "Hue shift"),
    HS_CMY_ENABLE("Hue shift CMY", 0f, 1f, "Hue shift", toggle = true),
    HS_C("Hue shift C", 0f, 1f, "Hue shift"),
    HS_C_RNG("Hue shift C range", 0f, 1f, "Hue shift"),
    HS_M("Hue shift M", 0f, 1f, "Hue shift"),
    HS_M_RNG("Hue shift M range", 0f, 1f, "Hue shift"),
    HS_Y("Hue shift Y", 0f, 1f, "Hue shift"),
    HS_Y_RNG("Hue shift Y range", 0f, 1f, "Hue shift"),
    // Display (not part of look presets).
    TN_LG("Display grey luminance (nits)", 3f, 25f, "Display"),
    TN_SU("Surround", 0f, 2f, "Display", choices = listOf("Dark", "Dim", "Bright")),
    EOTF22("2.2 power display encoding", 0f, 1f, "Display", toggle = true);

    val key get() = "odrt_" + name.lowercase()
}

data class OpenDrtSettings(val values: List<Float> = OpenDrt.preset(0), val look: Int = 0) {
    operator fun get(p: OpenDrtParam) = values[p.ordinal]
    fun with(p: OpenDrtParam, v: Float) = copy(values = values.toMutableList().also { it[p.ordinal] = v }).bounded()

    fun bounded(): OpenDrtSettings {
        val d = OpenDrt.preset(0)
        val v = OpenDrtParam.entries.map { p ->
            val x = values.getOrNull(p.ordinal)?.takeIf(Float::isFinite) ?: d[p.ordinal]
            val c = x.coerceIn(p.min, p.max)
            if (p.toggle) (if (c >= 0.5f) 1f else 0f) else if (p.choices != null) kotlin.math.round(c) else c
        }
        return OpenDrtSettings(v, look.coerceIn(0, OpenDrt.LOOKS.size - 1))
    }

    /** Loads a look preset (all look parameters), keeping display settings. */
    fun withLook(index: Int): OpenDrtSettings {
        val p = OpenDrt.preset(index)
        return OpenDrtSettings(OpenDrtParam.entries.map { if (it.group == "Display") this[it] else p[it.ordinal] }, index).bounded()
    }

    /** Applies a tonescale preset (upstream tonescale_preset 1..13) over the current look. */
    fun withTonescale(index: Int): OpenDrtSettings {
        val t = OpenDrt.TONESCALES[index].second
        val v = values.toMutableList()
        for (i in t.indices) v[OpenDrt.TONESCALE_PARAMS[i].ordinal] = t[i]
        return copy(values = v).bounded()
    }

    fun save(e: SharedPreferences.Editor): SharedPreferences.Editor = bounded().let { s ->
        OpenDrtParam.entries.forEach { e.putFloat(it.key, s[it]) }
        e.putInt(KEY_LOOK, s.look)
    }

    companion object {
        private const val KEY_LOOK = "odrt_look"
        fun load(p: SharedPreferences): OpenDrtSettings {
            val d = OpenDrtSettings()
            return OpenDrtSettings(OpenDrtParam.entries.map { p.getFloat(it.key, d[it]) }, p.getInt(KEY_LOOK, 0)).bounded()
        }
    }
}

internal object OpenDrt {
    /** Upstream look presets, parameters in [OpenDrtParam] order (generated from OpenDRT.dctl). */
    val LOOKS: List<Pair<String, FloatArray>> = listOf(
        "Standard" to floatArrayOf(1.66f, 0.5f, 0.003f, 0.005f, 0.0f, 0.0f, 1.0f, 4.0f, 0.0f, 0.0f, 0.5f, 2.0f, 0.25f, 0.35f, 0.25f, 0.55f, 1.0f, 0.25f, 0.5f, 0.0f, 0.1f, 0.25f, 0.5f, 0.0f, 1.0f, 0.06f, 0.08f, 0.06f, 1.0f, 0.4f, 0.25f, 0.5f, -0.8f, 0.35f, 0.4f, 1.0f, 0.0f, -2.5f, -1.5f, -1.5f, 0.5f, 0.35f, 1.0f, -0.5f, -1.25f, -1.25f, -0.25f, 1.0f, 1.0f, 0.3f, 1.0f, 0.6f, 0.6f, 0.35f, 1.0f, 0.66f, 1.0f, 1.0f, 0.25f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f),
        "Arriba" to floatArrayOf(1.05f, 0.5f, 0.1f, 0.01f, 0.0f, 0.0f, 1.0f, 4.0f, 1.0f, 1.5f, 0.2f, 2.0f, 0.25f, 0.35f, 0.25f, 0.55f, 1.0f, 0.25f, 0.45f, 0.0f, 0.1f, 0.25f, 0.25f, 0.0f, 1.0f, 0.06f, 0.08f, 0.06f, 1.0f, 1.0f, 0.4f, 0.5f, -0.8f, 0.66f, 0.6f, 1.0f, 0.0f, -2.5f, -1.5f, -1.5f, 0.5f, 0.35f, 1.0f, 0.0f, -1.7f, -2.0f, -0.5f, 1.0f, 1.0f, 0.3f, 1.0f, 0.6f, 0.8f, 0.35f, 1.0f, 0.66f, 1.0f, 1.0f, 0.15f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f),
        "Sylvan" to floatArrayOf(1.6f, 0.5f, 0.01f, 0.01f, 0.0f, 0.0f, 1.0f, 4.0f, 1.0f, 0.25f, 0.75f, 2.0f, 0.25f, 0.25f, 0.25f, 0.55f, 1.0f, 0.15f, 0.5f, 0.15f, 0.1f, 0.25f, 0.15f, 0.15f, 1.0f, 0.05f, 0.08f, 0.05f, 1.0f, 0.5f, 0.5f, 0.5f, -0.8f, 0.5f, 0.5f, 1.0f, -1.0f, -2.0f, -2.0f, 0.0f, 0.25f, 0.25f, 1.0f, -1.0f, -0.5f, -0.25f, -0.25f, 1.0f, 1.0f, 0.4f, 1.0f, 0.6f, 1.15f, 0.8f, 1.25f, 0.6f, 1.0f, 1.0f, 0.25f, 0.25f, 0.25f, 0.5f, 0.35f, 0.5f),
        "Colorful" to floatArrayOf(1.5f, 0.5f, 0.003f, 0.003f, 0.0f, 0.0f, 1.0f, 4.0f, 1.0f, 0.4f, 0.5f, 2.0f, 0.25f, 0.35f, 0.25f, 0.55f, 1.0f, 0.5f, 1.0f, 0.0f, 0.5f, 0.15f, 0.15f, 0.15f, 1.0f, 0.05f, 0.06f, 0.05f, 1.0f, 0.8f, 0.5f, 0.4f, -0.8f, 0.4f, 0.4f, 1.0f, 0.0f, -1.25f, -1.25f, -0.25f, 0.3f, 0.5f, 1.0f, -0.5f, -1.25f, -1.25f, -0.5f, 1.0f, 1.0f, 0.4f, 1.0f, 0.5f, 0.8f, 0.35f, 1.0f, 0.5f, 1.0f, 1.0f, 0.25f, 1.0f, 0.0f, 1.0f, 0.25f, 1.0f),
        "Aery" to floatArrayOf(1.15f, 0.5f, 0.04f, 0.006f, 0.0f, 0.0f, 0.0f, 0.5f, 1.0f, 0.5f, 2.0f, 1.0f, 0.25f, 0.25f, 0.2f, 0.5f, 1.0f, 0.0f, 0.5f, 0.15f, 0.1f, 0.0f, 0.1f, 0.0f, 1.0f, 0.05f, 0.08f, 0.05f, 1.0f, 0.8f, 0.35f, 0.5f, -0.9f, 0.5f, 0.3f, 1.0f, -3.0f, 0.0f, 0.0f, 1.0f, 0.8f, 0.15f, 1.0f, -1.0f, -1.0f, -1.0f, 0.0f, 1.0f, 0.5f, 0.25f, 1.0f, 0.6f, 1.0f, 0.35f, 2.0f, 0.5f, 1.5f, 1.0f, 0.35f, 1.0f, 0.25f, 1.0f, 0.35f, 0.5f),
        "Dystopic" to floatArrayOf(1.6f, 0.5f, 0.01f, 0.008f, 1.0f, 0.25f, 0.0f, 1.0f, 1.0f, 1.0f, 0.75f, 3.0f, 0.25f, 0.2f, 0.25f, 0.55f, 1.0f, 0.15f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.05f, 0.08f, 0.05f, 1.0f, 0.25f, 0.25f, 0.8f, -0.8f, 0.6f, 0.25f, 1.0f, -2.0f, -2.0f, -2.0f, 0.0f, 0.35f, 0.35f, 1.0f, 0.0f, -1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 0.25f, 1.0f, 0.7f, 1.33f, 1.0f, 2.0f, 0.75f, 2.0f, 1.0f, 1.0f, 0.5f, 1.0f, 1.0f, 1.0f, 0.765f),
        "Umbra" to floatArrayOf(1.8f, 0.5f, 0.001f, 0.015f, 0.0f, 0.0f, 1.0f, 4.0f, 1.0f, 1.0f, 1.0f, 5.0f, 0.25f, 0.35f, 0.25f, 0.55f, 1.0f, 0.0f, 0.5f, 0.0f, 0.15f, 0.25f, 0.25f, 0.0f, 1.0f, 0.05f, 0.06f, 0.05f, 1.0f, 0.4f, 0.35f, 0.66f, -0.6f, 0.45f, 0.45f, 1.0f, -2.0f, -4.5f, -3.0f, -4.0f, 0.35f, 0.3f, 1.0f, 0.0f, -2.0f, -1.0f, -0.5f, 1.0f, 1.0f, 0.35f, 1.0f, 0.66f, 1.0f, 0.5f, 2.0f, 0.85f, 2.0f, 1.0f, 0.0f, 1.0f, 0.25f, 1.0f, 0.66f, 0.66f),
        "Base" to floatArrayOf(1.66f, 0.5f, 0.003f, 0.005f, 0.0f, 0.0f, 1.0f, 4.0f, 0.0f, 0.0f, 0.5f, 2.0f, 0.25f, 0.35f, 0.25f, 0.55f, 1.0f, 0.5f, 0.5f, 0.15f, 0.15f, 0.8f, 0.5f, 0.0f, 1.0f, 0.05f, 0.06f, 0.05f, 0.0f, 0.0f, 0.5f, 0.5f, 0.0f, 0.5f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.35f, 1.0f, -0.5f, -1.6f, -1.6f, -0.8f, 0.0f, 0.0f, 0.25f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f),
    )
    val TONESCALE_PARAMS = listOf(
        OpenDrtParam.TN_CON, OpenDrtParam.TN_SH, OpenDrtParam.TN_TOE, OpenDrtParam.TN_OFF,
        OpenDrtParam.TN_HCON_ENABLE, OpenDrtParam.TN_HCON, OpenDrtParam.TN_HCON_PV, OpenDrtParam.TN_HCON_ST,
        OpenDrtParam.TN_LCON_ENABLE, OpenDrtParam.TN_LCON, OpenDrtParam.TN_LCON_W
    )
    val TONESCALES: List<Pair<String, FloatArray>> = listOf(
        "Low Contrast" to floatArrayOf(1.4f, 0.5f, 0.003f, 0.005f, 0.0f, 0.0f, 1.0f, 4.0f, 0.0f, 0.0f, 0.5f),
        "Medium Contrast" to floatArrayOf(1.66f, 0.5f, 0.003f, 0.005f, 0.0f, 0.0f, 1.0f, 4.0f, 0.0f, 0.0f, 0.5f),
        "High Contrast" to floatArrayOf(1.4f, 0.5f, 0.003f, 0.005f, 0.0f, 0.0f, 1.0f, 4.0f, 1.0f, 1.0f, 0.5f),
        "Arriba Tonescale" to floatArrayOf(1.05f, 0.5f, 0.1f, 0.01f, 0.0f, 0.0f, 1.0f, 4.0f, 1.0f, 1.5f, 0.2f),
        "Sylvan Tonescale" to floatArrayOf(1.6f, 0.5f, 0.01f, 0.01f, 0.0f, 0.0f, 1.0f, 4.0f, 1.0f, 0.25f, 0.75f),
        "Colorful Tonescale" to floatArrayOf(1.5f, 0.5f, 0.003f, 0.003f, 0.0f, 0.0f, 1.0f, 4.0f, 1.0f, 0.4f, 0.5f),
        "Aery Tonescale" to floatArrayOf(1.15f, 0.5f, 0.04f, 0.006f, 0.0f, 0.0f, 0.0f, 0.5f, 1.0f, 0.5f, 2.0f),
        "Dystopic Tonescale" to floatArrayOf(1.6f, 0.5f, 0.01f, 0.008f, 1.0f, 0.25f, 0.0f, 1.0f, 1.0f, 1.0f, 0.75f),
        "Umbra Tonescale" to floatArrayOf(1.8f, 0.5f, 0.001f, 0.015f, 0.0f, 0.0f, 1.0f, 4.0f, 1.0f, 1.0f, 1.0f),
        "ACES-1.x" to floatArrayOf(1.0f, 0.35f, 0.02f, 0.0f, 1.0f, 0.55f, 0.0f, 2.0f, 1.0f, 1.13f, 1.0f),
        "ACES-2.0" to floatArrayOf(1.15f, 0.5f, 0.04f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 1.0f, 0.6f),
        "Marvelous Tonescape" to floatArrayOf(1.5f, 0.5f, 0.003f, 0.01f, 1.0f, 0.25f, 0.0f, 4.0f, 1.0f, 1.0f, 1.0f),
        "DaGrinchi Tonegroan" to floatArrayOf(1.2f, 0.5f, 0.02f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.6f),
    )
    private val DISPLAY_DEFAULTS = floatArrayOf(10f, 2f, 1f)

    fun preset(index: Int): List<Float> = LOOKS[index].second.toList() + DISPLAY_DEFAULTS.toList()

    const val VEC4_OFFSET = 3
    const val VEC4_COUNT = 21

    // Column-major 3x3 matrices.
    private val P3_TO_XYZ = doubleArrayOf(0.486570948648216151, 0.228974564069748754, -4.0e-17, 0.265667693169093, 0.691738521836506193, 0.0451133818589026167, 0.198217285234362467, 0.079286914093744984, 1.04394436890097575)
    private val XYZ_TO_P3 = doubleArrayOf(2.49349691194142542, -0.829488969561574696, 0.0358458302437844531, -0.93138361791912383, 1.76266406031834655, -0.0761723892680418041, -0.402710784450716841, 0.0236246858419435941, 0.956884524007687309)
    private val CATS = arrayOf(
        doubleArrayOf(0.95703423023223877, -0.0179296955466270447, 0.00127589143812656403, -0.0247171502560377121, 0.990019857883453369, 0.00427919067442417058, 0.0624028593301773071, 0.0248119533061981201, 1.29345715045928955),
        doubleArrayOf(0.981001079082489014, -0.00843488052487373352, 0.000552809564396739006, -0.0116619253531098366, 0.996506094932556152, 0.00179840810596942902, 0.0265614092350006104, 0.0105696544051170349, 1.12374722957611084),
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        doubleArrayOf(1.01182246208190918, 0.00561682833358645439, -0.000335735734552145004, 0.00778879318386316299, 1.00150644779205322, -0.0010509500280022619, -0.0157783031463623047, -0.00628517568111419678, 0.927366673946380615),
        doubleArrayOf(1.02585089206695557, 0.0129133854061365128, -0.000719940289855003032, 0.0179439820349216461, 1.00214779376983643, -0.00218106806278228803, -0.0332137793302536011, -0.0132421031594276428, 0.84868013858795166),
        doubleArrayOf(1.04257404804229736, 0.0221935361623764038, -0.00116488314233720303, 0.03089117631316185, 1.00185668468475342, -0.00342052709311246915, -0.052812620997428894, -0.0210737623274326324, 0.761789083480834961)
    )
    private val NORM_709 = floatArrayOf(0.744192699063f, 0.873470832146f, 1f, 0.955936992163f, 0.905671332781f, 0.850004385027f)
    private val NORM_P3 = floatArrayOf(0.762687057298f, 0.884054083328f, 1f, 0.964320186739f, 0.923076518860f, 0.876572837784f)
    private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i -> val c = i / 3; val r = i % 3; (0..2).sumOf { a[it * 3 + r] * b[c * 3 + it] } }

    private fun toeQuadInv(x: Double, toe: Double) = if (toe == 0.0) x else (x + sqrt(x * (4 * toe + x))) / 2
    private fun toeCubicInv(x: Double, m: Double, w: Double): Double {
        if (m == 1.0) return x
        val x2 = x * x; val p0 = x2 - 3 * m * w; val p1 = 2 * x2 + 27 * w - 9 * m * w
        val p2 = (sqrt(x2 * p1 * p1 - 4 * p0 * p0 * p0) / 2 + x * p1 / 2).pow(1.0 / 3.0)
        return p0 / (3 * p2) + p2 / 3 + x / 3
    }

    /** Packs the 21 vec4 that follow ToneLook's first 3 (u_tone_look[3..23]). */
    fun pack(settings: OpenDrtSettings): FloatArray {
        val s = settings.bounded()
        fun g(p: OpenDrtParam) = s[p].toDouble()
        fun inv(p: OpenDrtParam) = (1.0 / max(g(p), 1e-3)).toFloat()
        val con = g(OpenDrtParam.TN_CON); val toe = g(OpenDrtParam.TN_TOE); val off = g(OpenDrtParam.TN_OFF)
        val lg = g(OpenDrtParam.TN_LG); val su = g(OpenDrtParam.TN_SU)
        // Tonescale constraints, SDR Lp = 100 nits (grey boost and HDR purity are no-ops there).
        val x1 = 2.0.pow(6 * g(OpenDrtParam.TN_SH) + 4); val y1 = 1.0; val x0 = 0.18 + off
        val y0 = lg / 100.0; val s0 = toeQuadInv(y0, toe); val p = con / (1 + su * 0.05)
        val s10 = x0 * (s0.pow(-1 / con) - 1); val m1 = y1 / (x1 / (x1 + s10)).pow(con)
        val m2 = toeQuadInv(m1, toe); val ts = x0 * ((s0 / m2).pow(-1 / con) - 1)
        val sLp100 = x0 * ((lg / 100).pow(-1 / con) - 1)
        val lconM = 2.0.pow(-g(OpenDrtParam.TN_LCON)); val lconW = (g(OpenDrtParam.TN_LCON_W) / 4).let { it * it }
        val lconSc = toeCubicInv(x0, lconM, lconW) / x0
        val hp = 2.0.pow(g(OpenDrtParam.TN_HCON)); val hx0 = 0.18 * 2.0.pow(g(OpenDrtParam.TN_HCON_PV))
        val ho = hx0 - hx0 / hp; val hs0 = hx0.pow(1 - hp) / hp; val hx1 = hx0 * 2.0.pow(g(OpenDrtParam.TN_HCON_ST))
        val hk1 = hp * hs0 * hx1.pow(hp) / hx1; val hy1 = hs0 * hx1.pow(hp) + ho
        val hconOn = s[OpenDrtParam.TN_HCON_ENABLE] > 0.5f && hp != 1.0
        val rw = g(OpenDrtParam.RS_RW); val bw = g(OpenDrtParam.RS_BW)
        val ptmLow = if (g(OpenDrtParam.PTM_LOW_ST) == 0.0 || g(OpenDrtParam.PTM_LOW_RNG) == 0.0) 0.0 else g(OpenDrtParam.PTM_LOW)
        val ptmHigh = if (g(OpenDrtParam.PTM_HIGH_ST) == 0.0 || g(OpenDrtParam.PTM_HIGH_RNG) == 0.0) 0.0 else g(OpenDrtParam.PTM_HIGH)
        val cwp = s[OpenDrtParam.CWP].toInt()
        val cat = mul(XYZ_TO_P3, mul(CATS[cwp], P3_TO_XYZ))
        fun f(vararg d: Double) = d.map { it.toFloat() }
        return (f(ts, p, sLp100, sLp100) +
            f(m2, toe, off, g(OpenDrtParam.RS_SA)) +
            f(rw, 1 - rw - bw, bw, g(OpenDrtParam.EOTF22)) +
            f(g(OpenDrtParam.BRL_ENABLE), g(OpenDrtParam.BRL), 1 - g(OpenDrtParam.BRL_RNG), inv(OpenDrtParam.BRL_ST).toDouble()) +
            f(g(OpenDrtParam.BRL_R), g(OpenDrtParam.BRL_G), g(OpenDrtParam.BRL_B), g(OpenDrtParam.TN_LCON_ENABLE)) +
            f(lconM, lconW, lconSc, if (hconOn) 1.0 else 0.0) +
            f(hx0, hp, ho, hs0) +
            f(hx1, hk1, hy1, g(OpenDrtParam.HC_ENABLE)) +
            f(g(OpenDrtParam.HC_R), inv(OpenDrtParam.HC_R_RNG).toDouble(), g(OpenDrtParam.HS_RGB_ENABLE), g(OpenDrtParam.HS_CMY_ENABLE)) +
            f(g(OpenDrtParam.HS_R), g(OpenDrtParam.HS_G), g(OpenDrtParam.HS_B), g(OpenDrtParam.PTM_ENABLE)) +
            listOf(inv(OpenDrtParam.HS_R_RNG), inv(OpenDrtParam.HS_G_RNG), inv(OpenDrtParam.HS_B_RNG), s[OpenDrtParam.PTL_ENABLE]) +
            f(g(OpenDrtParam.HS_C), g(OpenDrtParam.HS_M), g(OpenDrtParam.HS_Y), g(OpenDrtParam.BRLP_ENABLE)) +
            listOf(inv(OpenDrtParam.HS_C_RNG), inv(OpenDrtParam.HS_M_RNG), inv(OpenDrtParam.HS_Y_RNG), 0f) +
            f(g(OpenDrtParam.PT_LML), g(OpenDrtParam.PT_LML_R), g(OpenDrtParam.PT_LML_G), g(OpenDrtParam.PT_LML_B)) +
            f(g(OpenDrtParam.PT_LMH), g(OpenDrtParam.PT_LMH_R), g(OpenDrtParam.PT_LMH_B), ptmLow) +
            f(1 / max(g(OpenDrtParam.PTM_LOW_RNG), 1e-3), max(g(OpenDrtParam.PTM_LOW_ST), 1e-3), ptmHigh, 1 / max(4 * g(OpenDrtParam.PTM_HIGH_RNG), 1e-3)) +
            f(max(g(OpenDrtParam.PTM_HIGH_ST), 1e-3), g(OpenDrtParam.PTL_C), g(OpenDrtParam.PTL_M), g(OpenDrtParam.PTL_Y)) +
            f(g(OpenDrtParam.BRLP), g(OpenDrtParam.BRLP_R), g(OpenDrtParam.BRLP_G), g(OpenDrtParam.BRLP_B)) +
            f(cat[0], cat[1], cat[2], NORM_709[cwp].toDouble()) +
            f(cat[3], cat[4], cat[5], NORM_P3[cwp].toDouble()) +
            f(cat[6], cat[7], cat[8], 2 * g(OpenDrtParam.CWP_LM))).toFloatArray().also { check(it.size == VEC4_COUNT * 4) }
    }

    // ---- CPU twin of odrt() (scopes, tests). u = full u_tone_look array. ----
    private val R2020_TO_P3 = floatArrayOf(1.3435782526f, -0.0652974528f, 0.0028217873f, -0.2821796705f, 1.0757879158f, -0.0195984945f, -0.0613985821f, -0.0104904631f, 1.0167767073f)
    private val P3_TO_709 = floatArrayOf(1.2249401763f, -0.0420569547f, -0.0196375546f, -0.2249401763f, 1.0420569547f, -0.0786360456f, 0f, 0f, 1.0982736001f)
    private fun mv(m: FloatArray, v: FloatArray) = FloatArray(3) { r -> m[r] * v[0] + m[3 + r] * v[1] + m[6 + r] * v[2] }
    private fun spow(x: Float, p: Float) = if (x <= 0f) x else x.toDouble().pow(p.toDouble()).toFloat()
    private fun pw(x: Float, p: Float) = max(x, 0f).toDouble().pow(p.toDouble()).toFloat()
    private fun hyp(x: Float, s: Float, p: Float) = spow(x / (x + s), p)
    private fun gw(x: Float, w: Float) = exp(-x * x / w)
    private fun hoff(h: Float, o: Float): Float { val a = h - o + PI.toFloat(); val t = 2f * PI.toFloat(); return a - t * kotlin.math.floor(a / t) - PI.toFloat() }
    private fun softplus(x: Float, s: Float) = if (x > 10f * s || s < 1e-4f) x else s * ln(max(0f, 1f + exp(x / s)))
    fun srgbEotf(x: Float) = if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    /** Rec.2020 scene-linear -> display-linear (sRGB or P3 primaries) for RawLens' sRGB OETF. */
    fun map(rec2020: FloatArray, u: FloatArray, displayP3: Boolean): FloatArray {
        fun U(i: Int, c: Int) = u[(VEC4_OFFSET + i) * 4 + c]
        var rgb = mv(R2020_TO_P3, rec2020)
        val rsSa = U(1, 3); val w = floatArrayOf(U(2, 0), U(2, 1), U(2, 2))
        var L = rgb[0] * w[0] + rgb[1] * w[1] + rgb[2] * w[2]
        rgb = FloatArray(3) { L * rsSa + rgb[it] * (1 - rsSa) + U(1, 2) }
        var tsn = sqrt(max(0f, rgb[0] * rgb[0] + rgb[1] * rgb[1] + rgb[2] * rgb[2])) / sqrt(3f)
        rgb = FloatArray(3) { if (tsn == 0f) 0f else rgb[it] / tsn }
        val ox = rgb[0] - rgb[2]; val oy = rgb[1] - (rgb[0] + rgb[2]) / 2
        var ach = sqrt(ox * ox + oy * oy) / 2
        ach = 1.25f * (ach * ach / (ach + 0.25f))
        val twoPi = 2f * PI.toFloat()
        val hRaw = atan2(ox, oy) + PI.toFloat() + 1.10714931f
        val hue = hRaw - twoPi * kotlin.math.floor(hRaw / twoPi)
        val ha = floatArrayOf(gw(hoff(hue, 0.1f), 0.66f), gw(hoff(hue, 4.3f), 0.66f), gw(hoff(hue, 2.3f), 0.66f))
        val hah = floatArrayOf(gw(hoff(hue, -0.4f), 0.66f), ha[1], gw(hoff(hue, 2.5f), 0.66f))
        val hc = floatArrayOf(gw(hoff(hue, 3.3f), 0.5f), gw(hoff(hue, 1.3f), 0.5f), gw(hoff(hue, -1.15f), 0.5f))
        if (U(3, 0) > 0.5f) {
            val tsf = pw(max(tsn / (tsn + 1f), 1e-10f), U(3, 2))
            val exf = (U(3, 1) + U(4, 0) * ha[0] + U(4, 1) * ha[1] + U(4, 2) * ha[2]) * pw(ach, U(3, 3))
            tsn *= 2f.pow(exf * (if (exf < 0f) tsf else 1f - tsf))
        }
        if (U(4, 3) > 0.5f) { val m = U(5, 0); val lw = U(5, 1); val x = tsn * U(5, 2); tsn = if (m == 1f) x else x * (x * x + m * lw) / (x * x + lw) }
        if (U(5, 3) > 0.5f && tsn >= U(6, 0)) {
            tsn = if (tsn > U(7, 0)) U(7, 1) * (tsn - U(7, 0)) + U(7, 2) else U(6, 3) * pw(tsn, U(6, 1)) + U(6, 2)
        }
        val tsPt = hyp(tsn, U(0, 3), U(0, 1)); val tsConst = hyp(tsn, U(0, 2), U(0, 1)); tsn = hyp(tsn, U(0, 0), U(0, 1))
        if (U(7, 3) > 0.5f) {
            var hts = 1f - tsConst; var c = hts * (1 - ach) + ach * (1 - hts); c *= ach * ha[0]; hts = pw(hts, U(8, 1))
            val f = U(8, 0) * (c - 2f * c * hts) + 1f; rgb[1] *= f; rgb[2] *= f
        }
        if (U(8, 2) > 0.5f) {
            val s = floatArrayOf(hah[0] * ach * pw(tsPt, U(10, 0)) * U(9, 0), -hah[1] * ach * pw(tsPt, U(10, 1)) * U(9, 1), -hah[2] * ach * pw(tsPt, U(10, 2)) * U(9, 2))
            rgb = floatArrayOf(rgb[0] + s[2] - s[1], rgb[1] + s[0] - s[2], rgb[2] + s[1] - s[0])
        }
        if (U(8, 3) > 0.5f) {
            val cc = 1f - tsPt
            val s = floatArrayOf(-hc[0] * ach * pw(cc, U(12, 0)) * U(11, 0), hc[1] * ach * pw(cc, U(12, 1)) * U(11, 1), hc[2] * ach * pw(cc, U(12, 2)) * U(11, 2))
            rgb = floatArrayOf(rgb[0] + s[2] - s[1], rgb[1] + s[0] - s[2], rgb[2] + s[1] - s[0])
        }
        val lp = 1f + 4f * (1f - tsPt) * (U(13, 0) + U(13, 1) * hah[0] + U(13, 2) * hah[1] + U(13, 3) * hah[2])
        var ptf = 1f - pw(tsPt, lp)
        ptf = pw(ptf, (1f - ach * (U(14, 1) * hah[0] + U(14, 2) * hah[2])) * (1f - U(14, 0) * ach))
        if (U(9, 3) > 0.5f) {
            val lo = 1f + U(14, 3) * exp(-2f * ach * ach / U(15, 1)) * pw(1f - tsConst, U(15, 0))
            val hi = 1f + U(15, 2) * exp(-2f * ach * ach / U(16, 0)) * pw(tsPt, U(15, 3))
            ptf *= lo * hi
        }
        rgb = FloatArray(3) { rgb[it] * ptf + 1f - ptf }
        L = rgb[0] * w[0] + rgb[1] * w[1] + rgb[2] * w[2]
        rgb = FloatArray(3) { (L * rsSa - rgb[it]) / (rsSa - 1f) }
        // Creative white (in P3), then display gamut.
        val cf = pw(max(tsConst, 1e-10f), U(20, 3))
        val cat = floatArrayOf(U(18, 0), U(18, 1), U(18, 2), U(19, 0), U(19, 1), U(19, 2), U(20, 0), U(20, 1), U(20, 2))
        val cw = mv(cat, rgb)
        rgb = FloatArray(3) { cw[it] * cf + rgb[it] * (1 - cf) }
        if (!displayP3) rgb = mv(P3_TO_709, rgb)
        val norm = if (displayP3) U(19, 3) else U(18, 3)
        rgb = FloatArray(3) { rgb[it] * (norm * cf + 1f - cf) }
        if (U(11, 3) > 0.5f) {
            val px = rgb[0] - rgb[2]; val py = rgb[1] - (rgb[0] + rgb[2]) / 2
            var a = sqrt(px * px + py * py) / 4; a = 1.1f * (a * a / (a + 0.1f))
            val m = U(17, 0) + ach * (U(17, 1) * ha[0] + U(17, 2) * ha[1] + U(17, 3) * ha[2])
            val e = 2f.pow(m * a * tsn); rgb = FloatArray(3) { rgb[it] * e }
        }
        if (U(10, 3) > 0.5f) rgb = floatArrayOf(softplus(rgb[0], U(16, 1)), softplus(rgb[1], U(16, 2)), softplus(rgb[2], U(16, 3)))
        val toe = U(1, 1); var t = tsn * U(1, 0)
        if (toe != 0f) t = t * t / (t + toe)
        val out = FloatArray(3) { (rgb[it] * t).coerceIn(0f, 1f) }
        return if (U(2, 3) > 0.5f) FloatArray(3) { srgbEotf(spow(out[it], 1f / 2.2f)) } else out
    }

    val glsl = """
float odrt_pw(float x, float p) { return pow(max(x, 0.0), p); }
float odrt_hyp(float x, float s, float p) { float r = x / (x + s); return r <= 0.0 ? r : pow(r, p); }
float odrt_gw(float x, float w) { return exp(-x * x / w); }
float odrt_hoff(float h, float o) { return mod(h - o + 3.14159265, 6.28318531) - 3.14159265; }
float odrt_sp(float x, float s) { return (x > 10.0 * s || s < 1e-4) ? x : s * log(max(0.0, 1.0 + exp(x / s))); }
float odrt_srgb_eotf(float x) { return x <= 0.04045 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4); }
#define ODU(i) u_tone_look[3 + i]
// OpenDRT v1.1.0 (Jed Smith, GPL-3.0): Rec.2020 scene-linear -> display-linear sRGB/P3.
vec3 odrt(vec3 scene2020, int displayP3) {
    vec3 rgb = mat3(1.3435782526, -0.0652974528, 0.0028217873, -0.2821796705, 1.0757879158, -0.0195984945,
                    -0.0613985821, -0.0104904631, 1.0167767073) * scene2020;
    float rsSa = ODU(1).w; vec3 w = ODU(2).xyz;
    rgb = vec3(dot(rgb, w) * rsSa) + rgb * (1.0 - rsSa) + vec3(ODU(1).z);
    float tsn = length(rgb) / 1.7320508;
    rgb = tsn == 0.0 ? vec3(0.0) : rgb / tsn;
    vec2 op = vec2(rgb.r - rgb.b, rgb.g - (rgb.r + rgb.b) * 0.5);
    float ach = length(op) * 0.5;
    ach = 1.25 * (ach * ach / (ach + 0.25));
    float hue = mod(atan(op.x, op.y) + 3.14159265 + 1.10714931, 6.28318531);
    vec3 ha = vec3(odrt_gw(odrt_hoff(hue, 0.1), 0.66), odrt_gw(odrt_hoff(hue, 4.3), 0.66), odrt_gw(odrt_hoff(hue, 2.3), 0.66));
    vec3 hah = vec3(odrt_gw(odrt_hoff(hue, -0.4), 0.66), ha.g, odrt_gw(odrt_hoff(hue, 2.5), 0.66));
    vec3 hc = vec3(odrt_gw(odrt_hoff(hue, 3.3), 0.5), odrt_gw(odrt_hoff(hue, 1.3), 0.5), odrt_gw(odrt_hoff(hue, -1.15), 0.5));
    if (ODU(3).x > 0.5) {
        float tsf = odrt_pw(max(tsn / (tsn + 1.0), 1e-10), ODU(3).z);
        float exf = (ODU(3).y + dot(ODU(4).xyz, ha)) * odrt_pw(ach, ODU(3).w);
        tsn *= exp2(exf * (exf < 0.0 ? tsf : 1.0 - tsf));
    }
    if (ODU(4).w > 0.5) {
        float m = ODU(5).x; float lw = ODU(5).y; float x = tsn * ODU(5).z;
        tsn = m == 1.0 ? x : x * (x * x + m * lw) / (x * x + lw);
    }
    if (ODU(5).w > 0.5 && tsn >= ODU(6).x)
        tsn = tsn > ODU(7).x ? ODU(7).y * (tsn - ODU(7).x) + ODU(7).z : ODU(6).w * odrt_pw(tsn, ODU(6).y) + ODU(6).z;
    float tsPt = odrt_hyp(tsn, ODU(0).w, ODU(0).y);
    float tsConst = odrt_hyp(tsn, ODU(0).z, ODU(0).y);
    tsn = odrt_hyp(tsn, ODU(0).x, ODU(0).y);
    if (ODU(7).w > 0.5) {
        float hts = 1.0 - tsConst;
        float c = (hts * (1.0 - ach) + ach * (1.0 - hts)) * ach * ha.r;
        hts = odrt_pw(hts, ODU(8).y);
        float f = ODU(8).x * (c - 2.0 * c * hts) + 1.0;
        rgb.gb *= f;
    }
    if (ODU(8).z > 0.5) {
        vec3 s = vec3(hah.r * odrt_pw(tsPt, ODU(10).x) * ODU(9).x, -hah.g * odrt_pw(tsPt, ODU(10).y) * ODU(9).y,
                      -hah.b * odrt_pw(tsPt, ODU(10).z) * ODU(9).z) * ach;
        rgb += vec3(s.b - s.g, s.r - s.b, s.g - s.r);
    }
    if (ODU(8).w > 0.5) {
        float cc = 1.0 - tsPt;
        vec3 s = vec3(-hc.r * odrt_pw(cc, ODU(12).x) * ODU(11).x, hc.g * odrt_pw(cc, ODU(12).y) * ODU(11).y,
                      hc.b * odrt_pw(cc, ODU(12).z) * ODU(11).z) * ach;
        rgb += vec3(s.b - s.g, s.r - s.b, s.g - s.r);
    }
    float lp = 1.0 + 4.0 * (1.0 - tsPt) * (ODU(13).x + dot(ODU(13).yzw, hah));
    float ptf = 1.0 - odrt_pw(tsPt, lp);
    ptf = odrt_pw(ptf, (1.0 - ach * (ODU(14).y * hah.r + ODU(14).z * hah.b)) * (1.0 - ODU(14).x * ach));
    if (ODU(9).w > 0.5) {
        float lo = 1.0 + ODU(14).w * exp(-2.0 * ach * ach / ODU(15).y) * odrt_pw(1.0 - tsConst, ODU(15).x);
        float hi = 1.0 + ODU(15).z * exp(-2.0 * ach * ach / ODU(16).x) * odrt_pw(tsPt, ODU(15).w);
        ptf *= lo * hi;
    }
    rgb = rgb * ptf + vec3(1.0 - ptf);
    rgb = (vec3(dot(rgb, w) * rsSa) - rgb) / (rsSa - 1.0);
    float cf = odrt_pw(max(tsConst, 1e-10), ODU(20).w);
    rgb = mat3(ODU(18).xyz, ODU(19).xyz, ODU(20).xyz) * rgb * cf + rgb * (1.0 - cf);
    if (displayP3 == 0) rgb = mat3(1.2249401763, -0.0420569547, -0.0196375546, -0.2249401763, 1.0420569547,
                                   -0.0786360456, 0.0, 0.0, 1.0982736001) * rgb;
    float norm = displayP3 != 0 ? ODU(19).w : ODU(18).w;
    rgb *= norm * cf + 1.0 - cf;
    if (ODU(11).w > 0.5) {
        vec2 bo = vec2(rgb.r - rgb.b, rgb.g - (rgb.r + rgb.b) * 0.5);
        float a = length(bo) * 0.25; a = 1.1 * (a * a / (a + 0.1));
        float m = ODU(17).x + ach * dot(ODU(17).yzw, ha);
        rgb *= exp2(m * a * tsn);
    }
    if (ODU(10).w > 0.5) rgb = vec3(odrt_sp(rgb.r, ODU(16).y), odrt_sp(rgb.g, ODU(16).z), odrt_sp(rgb.b, ODU(16).w));
    float toe = ODU(1).y; float t = tsn * ODU(1).x;
    if (toe != 0.0) t = t * t / (t + toe);
    rgb = clamp(rgb * t, 0.0, 1.0);
    if (ODU(2).w > 0.5) {
        vec3 e = pow(rgb, vec3(1.0 / 2.2));
        rgb = vec3(odrt_srgb_eotf(e.r), odrt_srgb_eotf(e.g), odrt_srgb_eotf(e.b));
    }
    return rgb;
}
""".trimIndent()
}
