// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

/**
 * Capture-frozen controls for the denoise pipeline.
 *
 * Single stage: AI Bayer denoise ([aiEnabled]): RawNIND single-frame
 * inference on the normalized pre-demosaic CFA via NCNN (GPU-first).
 * Produces the CFA that both the developed JPEG and the denoised DNG are
 * built from. Unavailable model/inference failure falls back silently (then
 * behaves as if AI were off for that capture).
 *
 * Two model variants share the flag: tiny (5ch+sigma in, packed Bayer out,
 * via RawDevelopmentCoordinator.denoiseCaptureCfa) and bayer/UtNet2
 * (4ch packed in, full-res camRGB out, via
 * RawDevelopmentCoordinator.denoiseCaptureCfaBayerRgb, bypassing AMaZE).
 *
 * [aiStrength] ports darktable neural-restore's raw-denoise strength
 * slider (src/common/ai/restore_raw_bayer.h): the blind model itself takes
 * no parameters, so strength is a post-process uniform blend,
 * `out = s*denoised + (1-s)*source`, applied per sample with no
 * re-inference. 0 = untouched source, 1 = full model output (darktable
 * 100%). UI exposes 0–100%.
 *
 * [saveOriginalDng] only matters when [aiEnabled]: the denoised DNG is
 * always written (subject to the capture format including DNG); this flag
 * additionally keeps the untouched archival sensor DNG.
 *
 * Post-demosaic stage ([wavelet]): profiled à-trous wavelet luma+chroma
 * denoise on pre-WB camera-linear RGB via the vendored RAWR core (a
 * darktable denoiseprofile port). Unlike the pre-demosaic stages it feeds
 * the developed JPEG only, never the DNG. See [WaveletSettings].
 *
 * Stage arbitration lives in [resolveDenoise] (rawr resolve() parity).
 */
data class DenoiseSettings(
    val aiEnabled: Boolean = false,
    val saveOriginalDng: Boolean = true,
    val aiStrength: Float = 1f,
    val wavelet: WaveletSettings = WaveletSettings()
) {
    init {
        require(aiStrength.isFinite() && aiStrength in 0f..1f) {
            "aiStrength must be in [0,1], was $aiStrength"
        }
    }
}

/** Pre-demosaic stage surviving arbitration for one capture. */
enum class PreDemosaicStage { OFF, AI, GALOSH }

/** Per-capture denoise plan. */
data class ResolvedDenoise(
    val preDemosaic: PreDemosaicStage,
    /** Post-demosaic wavelet runs (JPEG only). */
    val wavelet: Boolean
)

/**
 * Per-capture stage arbitration, rawr DenoiseConfig.resolve() parity:
 *
 * - Merged path ([mergedPath], HDR+/SR output): everything off — the
 *   merge output is pre-denoised, exactly like rawr's multiframe rule.
 * - Galosh wins over AI (existing RawLens precedent: single denoise
 *   stage) and over wavelet (rawr's mutual exclusion: profiled wavelet
 *   and Galosh both denoise; stacking over-smooths and doubles GPU cost).
 * - AI and wavelet may stack (pre- + post-demosaic, as the old wavelet
 *   did on AI output).
 */
fun resolveDenoise(
    denoise: DenoiseSettings,
    galosh: GaloshSettings,
    mergedPath: Boolean
): ResolvedDenoise {
    if (mergedPath) return ResolvedDenoise(PreDemosaicStage.OFF, false)
    val galoshOn = galosh.enabled && galosh.strength > 0f
    val pre = when {
        galoshOn -> PreDemosaicStage.GALOSH
        denoise.aiEnabled -> PreDemosaicStage.AI
        else -> PreDemosaicStage.OFF
    }
    val wave = denoise.wavelet.enabled && denoise.wavelet.strength > 0f && !galoshOn
    return ResolvedDenoise(pre, wave)
}

/** Camera2/DNG normalized Poisson-Gaussian model, in CFA order R, Gr, Gb, B. */
data class CfaNoiseModel(val scale: FloatArray, val offset: FloatArray) {
    init { require(scale.size == 4 && offset.size == 4) }

    val averageScale: Float get() = scale.average().toFloat()
    val averageOffset: Float get() = offset.average().toFloat()

    companion object {
        /** Canonical uncalibrated fallback (normalized units, mid-ISO mobile RAW ballpark). */
        const val FALLBACK_SCALE = 2.5e-4f
        const val FALLBACK_OFFSET = 2.5e-6f

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
