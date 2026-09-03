// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Capture-frozen controls for the denoise pipeline.
 *
 * The old RawLens denoiser has intentionally been removed.  The only denoise
 * path is now a scene-linear Y0U0V0 a-trous wavelet chroma denoiser modelled on
 * darktable's "denoise (profiled)" / "wavelets: chroma only" preset.
 */
data class DenoiseSettings(
    val enabled: Boolean = false,
    val strength: Float = 0.20f
) {
    init { require(strength in 0f..4f) }
}

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
