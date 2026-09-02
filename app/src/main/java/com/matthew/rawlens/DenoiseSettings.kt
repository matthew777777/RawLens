// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Capture-frozen controls for the deliberately chroma-first denoise pipeline.
 * Strengths are perceptual multipliers, not pixel radii: 1 is the calibrated default.
 */
data class DenoiseSettings(
    val enabled: Boolean = false,
    val rawPrefilterEnabled: Boolean = true,
    val rawPrefilterStrength: Float = 0.20f,
    val chromaEnabled: Boolean = true,
    val chromaStrength: Float = 1f,
    val lumaEnabled: Boolean = true,
    val lumaCleanup: Float = 0.55f,
    val grainRetention: Float = 0.85f,
    val edgeProtection: Float = 0.90f,
    val filmGrainEnabled: Boolean = true,
    val filmGrainAmount: Float = 0.22f,
    val filmGrainSize: Float = 0.35f
) {
    init {
        require(rawPrefilterStrength in 0f..1f)
        require(chromaStrength in 0f..2f)
        require(lumaCleanup in 0f..1f)
        require(grainRetention in 0f..1f)
        require(edgeProtection in 0f..1f)
        require(filmGrainAmount in 0f..1f)
        require(filmGrainSize in 0f..1f)
    }
}

/** Camera2/DNG normalized Poisson-Gaussian model, in CFA order R, Gr, Gb, B. */
data class CfaNoiseModel(val scale: FloatArray, val offset: FloatArray) {
    init {
        require(scale.size == 4 && offset.size == 4)
    }

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
