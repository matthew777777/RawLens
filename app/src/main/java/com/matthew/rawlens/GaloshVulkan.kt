// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.res.AssetManager
import java.nio.FloatBuffer

/**
 * GALOSH raw-denoise Vulkan host: one-shot capability probe, context init
 * (creates every manifest SPIR-V module on the real driver), and the full
 * o32 [denoise] pipeline (69 dB parity-gated on Mali-G615; see
 * docs/galosh-phase2-parity.md). Native failures throw [RuntimeException]
 * with the core's message.
 */
object GaloshVulkan {
    init {
        System.loadLibrary("galosh")
    }

    /** One-shot probe; JSON with loader/instance version and per-device caps. */
    fun probeCapsJson(): String = nativeProbeCaps()

    /**
     * Creates instance + device + compute queue and loads every manifest
     * SPIR-V module. Returns an opaque handle for [release].
     */
    fun init(assetManager: AssetManager): Long {
        val handle = nativeInit(assetManager)
        require(handle != 0L) { "galosh init returned a null handle" }
        return handle
    }

    fun release(handle: Long) = nativeRelease(handle)

    fun loadedShaderCount(handle: Long): Int = nativeLoadedShaderCount(handle)

    /** Manifest size; [loadedShaderCount] must equal this after [init]. */
    fun expectedShaderCount(): Int = nativeExpectedShaderCount()

    /** Blind-fit noise model of the last [denoise] run (SYNC#1 values). */
    fun lastAlpha(handle: Long): Float = nativeLastAlpha(handle)
    fun lastSigmaSq(handle: Long): Float = nativeLastSigmaSq(handle)

    /** Phase-plane capture (CPU cross-verification); arm before [denoise]. */
    fun setDump(handle: Long, enable: Boolean) = nativeSetDump(handle, if (enable) 1 else 0)
    fun dumpCount(handle: Long): Int = nativeDumpCount(handle)
    fun dumpName(handle: Long, index: Int): String = nativeDumpName(handle, index)
    fun dumpData(handle: Long, index: Int): FloatArray = nativeDumpData(handle, index)

    /**
     * Full o32 denoise. Buffers must be direct with at least W*H floats;
     * input is single-channel Bayer float32, row-major, even W/H,
     * normalized like the parity fixtures (values above 1 clip at output).
     * [phaseStride] 1 runs all 16 WHT phases (parity config); 2 runs the
     * fast 4-phase subset (~4x faster Phase 5, slightly less smooth).
     * Returns the timestamp-derived GPU time in ms.
     */
    fun denoise(
        handle: Long,
        input: FloatBuffer,
        output: FloatBuffer,
        width: Int,
        height: Int,
        strength: Float = 1f,
        luma: Float = 1f,
        chroma: Float = 1f,
        alpha: Float = 0f,
        sigma: Float = 0f,
        wht: Int = 8,
        upsampleFast: Boolean = false,
        phaseStride: Int = 1
    ): Double = nativeDenoise(handle, input, output, width, height, strength, luma, chroma,
        alpha, sigma, wht, if (upsampleFast) 1 else 0, phaseStride)

    private external fun nativeLastAlpha(handle: Long): Float
    private external fun nativeLastSigmaSq(handle: Long): Float
    private external fun nativeSetDump(handle: Long, enable: Int)
    private external fun nativeDumpCount(handle: Long): Int
    private external fun nativeDumpName(handle: Long, index: Int): String
    private external fun nativeDumpData(handle: Long, index: Int): FloatArray
    private external fun nativeProbeCaps(): String
    private external fun nativeInit(assetManager: AssetManager): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeLoadedShaderCount(handle: Long): Int
    private external fun nativeExpectedShaderCount(): Int
    private external fun nativeDenoise(
        handle: Long, input: FloatBuffer, output: FloatBuffer, width: Int, height: Int,
        strength: Float, luma: Float, chroma: Float, alpha: Float, sigma: Float,
        wht: Int, upsampleFast: Int, phaseStride: Int
    ): Double
}
