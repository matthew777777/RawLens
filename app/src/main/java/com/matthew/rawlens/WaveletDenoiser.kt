// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
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
    @Synchronized
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
            settings.strength, settings.detail, noiseA, noiseB, wbGains,
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

    @Synchronized
    fun captureSharpen(pixels: ByteBuffer, width: Int, height: Int,
                       wb: FloatArray, pattern: BayerPattern, settings: CaptureSharpeningSettings) {
        if (!settings.enabled) return
        check(ensureInit()) { "Capture sharpening Vulkan initialization failed" }
        WaveletVulkan.captureSharpen(handle, pixels, width, height, wb, pattern.ordinal, settings.level.iterations)
        Log.i(TAG, "Capture sharpening ${width}x${height} ${settings.level.label} " +
            "iterations=${settings.level.iterations} cfa=$pattern gpuMs=${WaveletVulkan.lastGpuMs(handle)}")
    }

    companion object {
        private const val TAG = "WaveletDenoiser"

        /** Float operation order mirrors RAWR GreenNoiseToNormalized/CfaNoiseToNormalized.
         * Camera2 profile is row-major CFA (S0,O0,...,S3,O3). Invalid metadata
         * bypasses denoise, as in RAWR; no invented fallback noise model.
         */
        fun noiseAB(
            noiseProfile: DoubleArray?, pattern: BayerPattern, black: Float, white: Float
        ): Pair<Float, Float>? {
            if (noiseProfile == null || noiseProfile.size < 8 ||
                !(white > black) || !(black >= 0f)) return null
            val g0 = if (pattern == BayerPattern.GRBG || pattern == BayerPattern.GBRG) 0 else 1
            val g1 = if (g0 == 0) 3 else 2
            val slope = (noiseProfile[g0 * 2] + noiseProfile[g1 * 2]).toFloat() * 0.5f
            val offset = (noiseProfile[g0 * 2 + 1] + noiseProfile[g1 * 2 + 1]).toFloat() * 0.5f
            if (!slope.isFinite() || !offset.isFinite() || !(slope >= 0f)) return null
            return greenNoiseNormalized(slope, offset, black, white)
        }

        fun greenNoiseNormalized(
            slopeRaw: Float, offsetRaw: Float, black: Float, white: Float
        ): Pair<Float, Float> {
            val range = white - black
            val a = if (range > 0f) slopeRaw / range else 0f
            val b = if (range > 0f) (slopeRaw * black + offsetRaw) / (range * range) else 0f
            return a to b
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
 * (strength/detail 1, luma 0.25, 7 scales, chroma 0.75);
 * [enabled] stays false until the user opts in.
 */
data class WaveletSettings(
    val enabled: Boolean = false,
    val strength: Float = 1.0f,
    val detail: Float = 1.0f,
    val luma: Float = 0.25f,
    val maxScale: Int = 7,
    val forceUv: Float = 0.75f
) {
    /** RAWR: luma is independent of Detail (the VST shadow fulcrum). */
    val forceY: Float get() = luma

    fun bounded(): WaveletSettings = copy(
        strength = strength.coerceIn(0.0f, 8.0f),
        detail = detail.coerceIn(0.0f, 1.8f),
        luma = luma.coerceIn(0.0f, 1.0f),
        maxScale = maxScale.coerceIn(1, 7),
        forceUv = forceUv.coerceIn(0.0f, 1.0f)
    )
}
