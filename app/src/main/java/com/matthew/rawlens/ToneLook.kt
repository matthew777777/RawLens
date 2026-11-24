// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.SharedPreferences
import kotlin.math.ln
import kotlin.math.pow

/**
 * Per-mapper look controls for Sigmoid, ACES 2.0 and Basic. The defaults are neutral:
 * Sigmoid reproduces darktable's sigmoid defaults, and ACES/Basic are left unchanged.
 */
data class ToneLookSettings(
    /** darktable sigmoid "contrast" (middle_grey_contrast). */
    val sigmoidContrast: Float = 1.5f,
    /** darktable sigmoid "skew" (contrast_skewness), -1..1. */
    val sigmoidSkew: Float = 0f,
    /** darktable "target white" as a display-linear fraction (1 = 100 %). */
    val sigmoidWhite: Float = 1f,
    /** darktable "target black" as a display-linear fraction (0.000152 = 0.0152 %). */
    val sigmoidBlack: Float = 0.000152f,
    /** darktable "preserve hue", 0..1 (RGB ratio processing). */
    val sigmoidHue: Float = 1f,
    /** Display-linear chroma scale after sigmoid (colour balance rgb's role in darktable). */
    val sigmoidColorfulness: Float = 1f,
    val acesExposureEv: Float = 0f,
    val acesContrast: Float = 1f,
    val acesSaturation: Float = 1f,
    val basicContrast: Float = 1f,
    val basicSaturation: Float = 1f,
    /** Full OpenDRT v1.1.0 parameter set (used when the tone mapper is OpenDRT). */
    val openDrt: OpenDrtSettings = OpenDrtSettings()
) {
    fun bounded(): ToneLookSettings {
        fun b(v: Float, lo: Float, hi: Float, d: Float) = v.takeIf(Float::isFinite)?.coerceIn(lo, hi) ?: d
        return ToneLookSettings(
            b(sigmoidContrast, 0.5f, 4f, 1.5f), b(sigmoidSkew, -1f, 1f, 0f),
            b(sigmoidWhite, 0.5f, 1f, 1f), b(sigmoidBlack, 0f, 0.02f, 0.000152f),
            b(sigmoidHue, 0f, 1f, 1f), b(sigmoidColorfulness, 0f, 2f, 1f),
            b(acesExposureEv, -2f, 2f, 0f), b(acesContrast, 0.5f, 1.5f, 1f), b(acesSaturation, 0f, 2f, 1f),
            b(basicContrast, 0.5f, 1.5f, 1f), b(basicSaturation, 0f, 2f, 1f),
            openDrt.bounded()
        )
    }

    fun save(editor: SharedPreferences.Editor): SharedPreferences.Editor = bounded().let {
        editor.putFloat(K_SC, it.sigmoidContrast).putFloat(K_SS, it.sigmoidSkew)
            .putFloat(K_SW, it.sigmoidWhite).putFloat(K_SB, it.sigmoidBlack)
            .putFloat(K_SH, it.sigmoidHue).putFloat(K_SCF, it.sigmoidColorfulness)
            .putFloat(K_AE, it.acesExposureEv).putFloat(K_AC, it.acesContrast).putFloat(K_AS, it.acesSaturation)
            .putFloat(K_BC, it.basicContrast).putFloat(K_BS, it.basicSaturation)
            .also { e -> it.openDrt.save(e) }
    }

    companion object {
        private const val K_SC = "tone_sigmoid_contrast"
        private const val K_SS = "tone_sigmoid_skew"
        private const val K_SW = "tone_sigmoid_white"
        private const val K_SB = "tone_sigmoid_black"
        private const val K_SH = "tone_sigmoid_hue"
        private const val K_SCF = "tone_sigmoid_colorfulness"
        private const val K_AE = "tone_aces_exposure"
        private const val K_AC = "tone_aces_contrast"
        private const val K_AS = "tone_aces_saturation"
        private const val K_BC = "tone_basic_contrast"
        private const val K_BS = "tone_basic_saturation"

        fun load(p: SharedPreferences): ToneLookSettings {
            val d = ToneLookSettings()
            return ToneLookSettings(
                p.getFloat(K_SC, d.sigmoidContrast), p.getFloat(K_SS, d.sigmoidSkew),
                p.getFloat(K_SW, d.sigmoidWhite), p.getFloat(K_SB, d.sigmoidBlack),
                p.getFloat(K_SH, d.sigmoidHue), p.getFloat(K_SCF, d.sigmoidColorfulness),
                p.getFloat(K_AE, d.acesExposureEv), p.getFloat(K_AC, d.acesContrast), p.getFloat(K_AS, d.acesSaturation),
                p.getFloat(K_BC, d.basicContrast), p.getFloat(K_BS, d.basicSaturation),
                OpenDrtSettings.load(p)
            ).bounded()
        }
    }
}

/** Packs [ToneLookSettings] into `uniform vec4 u_tone_look[3]`, shared by stills and viewfinders. */
internal object ToneLook {
    const val UNIFORM = "u_tone_look"

    /**
     * [0] sigmoid magnitude, paper exposure, film fog, film power
     * [1] sigmoid paper power, hue preservation, colorfulness, highlight white level (log2)
     * [2] pre-map exposure gain, contrast, saturation (ACES 2.0 or Basic, by mode), unused
     * [3..23] OpenDRT ([OpenDrt.pack])
     */
    fun values(settings: JpegOutputSettings): FloatArray {
        val r = settings.resolvedForPlatform()
        val t = r.toneLook.bounded()
        val p = SigmoidDisplayTransform.Params.of(t)
        val aces = r.toneMapper == PhotoToneMapper.ACES_2
        return floatArrayOf(
            p.magnitude, p.paperExposure, p.filmFog, p.filmPower,
            p.paperPower, t.sigmoidHue, t.sigmoidColorfulness,
            (ln(r.highlightHeadroom.toDouble()) / ln(2.0)).toFloat(),
            if (aces) 2f.pow(t.acesExposureEv) else 1f,
            if (aces) t.acesContrast else t.basicContrast,
            if (aces) t.acesSaturation else t.basicSaturation,
            0f
        ) + OpenDrt.pack(t.openDrt)
    }

    const val VEC4_COUNT = 3 + OpenDrt.VEC4_COUNT

    /** CPU twin of tl_pre (scopes, meters, tests). */
    fun pre(rgb: FloatArray, settings: JpegOutputSettings): FloatArray =
        pre(rgb, values(settings), settings.toneMapper == PhotoToneMapper.ACES_2)

    /** [pre] with [values] already packed (hot loops such as the VF LUT bake). */
    fun pre(rgb: FloatArray, v: FloatArray, aces: Boolean): FloatArray {
        val lum = if (aces) ACES_LUM else REC2020_LUM
        val x = FloatArray(3) { rgb[it].coerceAtLeast(0f) * v[8] }
        val y = x[0] * lum[0] + x[1] * lum[1] + x[2] * lum[2]
        if (y <= 1e-7f) return x
        val yc = (.18 * (y / .18).pow(v[9].toDouble())).toFloat()
        return FloatArray(3) { (yc + (x[it] * yc / y - yc) * v[10]).coerceAtLeast(0f) }
    }

    private val ACES_LUM = floatArrayOf(.2722287f, .6740818f, .0536895f)
    private val REC2020_LUM = floatArrayOf(.2627f, .6780f, .0593f)

    val DEFAULT: FloatArray by lazy { values(JpegOutputSettings()) }

    /** Contrast in log2 around 0.18 and saturation around luminance, in the given space. */
    val glsl = """
uniform highp vec4 u_tone_look[24];
vec3 tl_pre(vec3 rgb, vec3 lum) {
    rgb = max(rgb, vec3(0.0)) * u_tone_look[2].x;
    float y = max(dot(rgb, lum), 0.0);
    if (y <= 1e-7) return rgb;
    float yc = 0.18 * pow(y / 0.18, u_tone_look[2].y);
    rgb *= yc / y;
    return max(vec3(yc) + (rgb - vec3(yc)) * u_tone_look[2].z, vec3(0.0));
}
vec3 tl_aces_pre(vec3 ap1) { return tl_pre(ap1, vec3(0.2722287, 0.6740818, 0.0536895)); }
vec3 tl_basic_pre(vec3 rec2020) { return tl_pre(rec2020, vec3(0.2627, 0.6780, 0.0593)); }
""".trimIndent() + "\n" + OpenDrt.glsl
}
