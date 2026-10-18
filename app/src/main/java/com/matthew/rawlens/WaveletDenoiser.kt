// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer

/**
 * Post-demosaic profiled-wavelet denoise (luma + chroma) via the vendored
 * wavelet core. Input/output are pre-WB camera-linear RGBA16F direct buffers
 * (W*H*8 bytes). Noise comes from SENSOR_NOISE_PROFILE's green plane,
 * normalized exactly like GreenNoiseToNormalized; WB gains derive
 * from SENSOR_NEUTRAL_COLOR_POINT with green == 1.
 */
class WaveletDenoiser(context: Context) {
    private val assets = context.applicationContext.assets
    private var handle = 0L

    /** Last native GPU time in ms (-1 when unavailable). */
    var lastGpuMs = -1.0
        private set

    @Synchronized
    fun ensureInit(): Boolean {
        if (handle != 0L) return true
        if (!WaveletVulkan.ensureLoaded()) return false
        handle = try {
            WaveletVulkan.init(assets)
        } catch (failure: Exception) {
            Log.w(TAG, "wavelet init failed", failure)
            0L
        }
        return handle != 0L
    }

    @Synchronized
    fun release() {
        if (handle != 0L) {
            try {
                WaveletVulkan.release(handle)
            } catch (_: Exception) {
            }
            handle = 0L
        }
    }

    /**
     * Runs one denoise. Returns false (leaving [output] untouched) when the
     * native library is unavailable; throws RuntimeException on GPU failure.
     */
    fun process(
        input: ByteBuffer,
        output: ByteBuffer,
        width: Int,
        height: Int,
        settings: WaveletSettings,
        noiseA: Float,
        noiseB: Float,
        wbGains: FloatArray
    ): Boolean {
        if (!ensureInit()) return false
        require(wbGains.size >= 3) { "wbGains needs 3 entries" }
        val startNs = System.nanoTime()
        WaveletVulkan.process(
            handle, input, output, width, height,
            settings.strength, settings.shadows, noiseA, noiseB, wbGains,
            settings.maxScale, settings.forceY, settings.forceUv
        )
        lastGpuMs = try {
            WaveletVulkan.lastGpuMs(handle)
        } catch (_: Exception) {
            -1.0
        }
        val wallMs = (System.nanoTime() - startNs) / 1e6
        Log.i(TAG, "wavelet ${width}x${height} strength=${settings.strength} " +
            "scales=${settings.maxScale} wall=${"%.0f".format(wallMs)}ms " +
            "gpu=${if (lastGpuMs >= 0) "%.0f".format(lastGpuMs) + "ms" else "n/a"}")
        return true
    }

    companion object {
        /**
         * Normalized green (a, b) from SENSOR_NOISE_PROFILE. Falls back to
         * the canonical uncalibrated constants (already normalized) when
         * the profile or range is unusable — never throws for metadata
         * reasons, so the capture can still develop.
         */
        fun noiseAB(noiseProfile: DoubleArray?, whiteMinusBlack: Double): Pair<Float, Float> {
            val raw = noiseProfile?.takeIf {
                it.size == 6 && it.all { v -> v.isFinite() && v >= 0.0 }
            }
            if (raw != null && whiteMinusBlack > 0.0 && whiteMinusBlack.isFinite()) {
                return greenNoiseNormalized(raw[2], raw[3], whiteMinusBlack)
            }
            return CfaNoiseModel.FALLBACK_SCALE to CfaNoiseModel.FALLBACK_OFFSET
        }

        private const val TAG = "WaveletDenoiser"

        /**
         * GreenNoiseToNormalized: green-plane (slope, offset) from
         * SENSOR_NOISE_PROFILE into the normalized variance line
         * var(x) = a*x + b over black-subtracted, white-normalized data.
         */
        fun greenNoiseNormalized(
            slopeRaw: Double, offsetRaw: Double, whiteMinusBlack: Double
        ): Pair<Float, Float> {
            require(whiteMinusBlack > 0.0) { "whiteMinusBlack must be positive" }
            val a = slopeRaw / whiteMinusBlack
            val b = offsetRaw / (whiteMinusBlack * whiteMinusBlack)
            return a.toFloat() to b.toFloat()
        }

        /**
         * Camera WB multipliers with green == 1 from SENSOR_NEUTRAL_COLOR_POINT
         * (white-as-shot in camera RGB). Falls back to unity on bad input.
         */
        fun wbGainsGreen1(neutral: DoubleArray?): FloatArray {
            if (neutral == null || neutral.size < 3 ||
                neutral.any { !it.isFinite() || it <= 0.0 }
            ) {
                return floatArrayOf(1f, 1f, 1f)
            }
            val g = neutral[1]
            return floatArrayOf((g / neutral[0]).toFloat(), 1f, (g / neutral[2]).toFloat())
        }
    }
}

/**
 * Wavelet stage settings. Defaults mirror the validated WaveletDenoise
 * (strength/detail 1, luma 0.25, shadows 1, 7 scales, chroma 0.75);
 * [enabled] stays false until the user opts in.
 */
data class WaveletSettings(
    val enabled: Boolean = false,
    val strength: Float = 1.0f,
    val detail: Float = 1.0f,
    val luma: Float = 0.25f,
    val shadows: Float = 1.0f,
    val maxScale: Int = 7,
    val forceUv: Float = 0.75f
) {
    /** forceY mapping: detail gate × luma. */
    val forceY: Float get() = if (detail >= 1.0f) luma else 0.0f

    fun bounded(): WaveletSettings = copy(
        strength = strength.coerceIn(0.0f, 8.0f),
        detail = detail.coerceIn(0.0f, 1.0f),
        luma = luma.coerceIn(0.0f, 1.0f),
        shadows = shadows.coerceIn(0.0f, 1.0f),
        maxScale = maxScale.coerceIn(1, 7),
        forceUv = forceUv.coerceIn(0.0f, 1.0f)
    )
}
