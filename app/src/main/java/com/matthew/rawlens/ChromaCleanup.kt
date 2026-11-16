// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * Capture-frozen JPEG cleanup controls. False-color/defringe are fused into the
 * AMaZE / RCD / MFSR final passes (shaders/amaze/cleanup.glsl).
 * [rapidRawPreprocessing] enables the separate chroma/detail GPU stage.
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
    val rapidRawPreprocessing: Boolean = false
) {
    init {
        require(defringeStrength.isFinite() && defringeStrength in 0f..1f) {
            "defringeStrength must be in [0,1], was $defringeStrength"
        }
    }

    companion object {
        const val DEFAULT_DEFRINGE = 1f
        const val KEY_FALSE_COLOR = "jpeg_false_color_suppression"
        const val KEY_DEFRINGE = "jpeg_defringe_strength"
        const val KEY_RAPIDRAW = "jpeg_rapidraw_preprocessing"

        fun read(prefs: android.content.SharedPreferences) = ChromaCleanup(
            falseColorSuppression = prefs.getBoolean(KEY_FALSE_COLOR, true),
            defringeStrength = prefs.getFloat(KEY_DEFRINGE, DEFAULT_DEFRINGE).coerceIn(0f, 1f),
            rapidRawPreprocessing = prefs.getBoolean(KEY_RAPIDRAW, false)
        )
    }
}
