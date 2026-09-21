// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Capture-frozen controls for the denoise pipeline.
 *
 * Single stage: AI Bayer denoise ([aiEnabled]): RawNIND-tiny single-frame
 * inference on the normalized pre-demosaic CFA via NCNN (GPU-first).
 * Produces the CFA that both the developed JPEG and the denoised DNG are
 * built from. Unavailable model/inference failure falls back silently (then
 * behaves as if AI were off for that capture).
 *
 * [saveOriginalDng] only matters when [aiEnabled]: the denoised DNG is
 * always written (subject to the capture format including DNG); this flag
 * additionally keeps the untouched archival sensor DNG.
 */
data class DenoiseSettings(
    val aiEnabled: Boolean = false,
    val saveOriginalDng: Boolean = true
)

/** Camera2/DNG normalized Poisson-Gaussian model, in CFA order R, Gr, Gb, B. */
data class CfaNoiseModel(val scale: FloatArray, val offset: FloatArray) {
    init { require(scale.size == 4 && offset.size == 4) }

    val averageScale: Float get() = scale.average().toFloat()
    val averageOffset: Float get() = offset.average().toFloat()

    companion object {
        private const val FALLBACK_SCALE = 2.5e-4f
        private const val FALLBACK_OFFSET = 2.5e-6f

        fun from(values: ImmutableDoubleValues?): CfaNoiseModel {
            val raw = values?.toDoubleArray()
            val pairs = when (raw?.size) {
                8 -> raw
                6 -> doubleArrayOf(raw[0], raw[1], raw[2], raw[3], raw[2], raw[3], raw[4], raw[5])
                else -> null
            }
            if (pairs == null || pairs.any { !it.isFinite() || it < 0.0 }) {
                return CfaNoiseModel(FloatArray(4) { FALLBACK_SCALE }, FloatArray(4) { FALLBACK_OFFSET })
            }
            return CfaNoiseModel(
                FloatArray(4) { pairs[it * 2].toFloat().coerceAtLeast(1e-10f) },
                FloatArray(4) { pairs[it * 2 + 1].toFloat().coerceAtLeast(1e-12f) }
            )
        }
    }
}
