// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Capture-frozen JPEG cleanup controls. False-color/defringe are fused into the
 * AMaZE / RCD / MFSR final passes (shaders/amaze/cleanup.glsl).
 * [rapidRawPreprocessing] enables the RapidRAW v1.7 develop stage ([RapidRawDevelop]):
 * [rapidRawColorNoiseReduction] and [rapidRawSharpening] are upstream's
 * raw_preprocessing_color_nr / raw_preprocessing_sharpening (defaults 0.5 / 0.35).
 *
 * [falseColorSuppression]: radius-1/radius-2 medians of (R-G, B-G) — removes demosaic
 * maze/moire false color on fine repeating detail.
 * [defringeStrength]: 0..1 removal of violet/magenta chroma at strong local
 * contrast edges (lateral CA + blooming against bright sky).
 */
data class ChromaCleanup(
    val falseColorSuppression: Boolean = true,
    val defringeStrength: Float = DEFAULT_DEFRINGE,
    /** Separate GPU chroma/detail stage before JPEG tone mapping. Frozen at capture. */
    val rapidRawPreprocessing: Boolean = false,
    val rapidRawColorNoiseReduction: Float = DEFAULT_RAPIDRAW_COLOR_NR,
    val rapidRawSharpening: Float = DEFAULT_RAPIDRAW_SHARPENING
) {
    internal val rapidRawAmounts get() = RapidRawDevelop.Amounts(rapidRawColorNoiseReduction, rapidRawSharpening)
    /** Enabled and doing something; zero amounts skip the stage and keep output fusion. */
    val rapidRawActive get() = rapidRawPreprocessing && rapidRawAmounts.active

    init {
        require(defringeStrength.isFinite() && defringeStrength in 0f..1f) {
            "defringeStrength must be in [0,1], was $defringeStrength"
        }
        require(rapidRawColorNoiseReduction.isFinite() && rapidRawColorNoiseReduction in 0f..1f)
        require(rapidRawSharpening.isFinite() && rapidRawSharpening in 0f..MAX_RAPIDRAW_SHARPENING)
    }

    companion object {
        const val DEFAULT_DEFRINGE = 1f
        const val KEY_FALSE_COLOR = "jpeg_false_color_suppression"
        const val KEY_DEFRINGE = "jpeg_defringe_strength"
        const val KEY_RAPIDRAW = "jpeg_rapidraw_preprocessing"
        const val KEY_RAPIDRAW_COLOR_NR = "jpeg_rapidraw_color_nr"
        const val KEY_RAPIDRAW_SHARPENING = "jpeg_rapidraw_sharpening"
        const val DEFAULT_RAPIDRAW_COLOR_NR = 0.5f
        const val DEFAULT_RAPIDRAW_SHARPENING = 0.35f
        const val MAX_RAPIDRAW_SHARPENING = 1f

        fun read(prefs: android.content.SharedPreferences) = ChromaCleanup(
            falseColorSuppression = prefs.getBoolean(KEY_FALSE_COLOR, true),
            defringeStrength = prefs.getFloat(KEY_DEFRINGE, DEFAULT_DEFRINGE).coerceIn(0f, 1f),
            rapidRawPreprocessing = prefs.getBoolean(KEY_RAPIDRAW, false),
            rapidRawColorNoiseReduction = prefs.getFloat(KEY_RAPIDRAW_COLOR_NR, DEFAULT_RAPIDRAW_COLOR_NR)
                .let { if (it.isFinite()) it.coerceIn(0f, 1f) else DEFAULT_RAPIDRAW_COLOR_NR },
            rapidRawSharpening = prefs.getFloat(KEY_RAPIDRAW_SHARPENING, DEFAULT_RAPIDRAW_SHARPENING)
                .let { if (it.isFinite()) it.coerceIn(0f, MAX_RAPIDRAW_SHARPENING) else DEFAULT_RAPIDRAW_SHARPENING }
        )
    }
}
