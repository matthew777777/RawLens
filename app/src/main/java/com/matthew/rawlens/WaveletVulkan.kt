// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.res.AssetManager
import java.nio.ByteBuffer

/**
 * Profiled-wavelet Vulkan host (vendored RAWR raw_denoise, itself a port of
 * darktable denoiseprofile): post-demosaic luma+chroma denoise on pre-WB
 * camera-linear RGBA16F. Buffers are direct with at least W*H*8 bytes;
 * alpha is ignored on input and forced to 1 on output.
 * Native failures throw [RuntimeException] with the core's message.
 */
object WaveletVulkan {
    /** Inner tile extent; matches rawr's validated default. */
    const val TILE_SIZE = 1536

    private var loaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("rawLensWavelet")
            loaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Creates instance + device + compute queue and loads the 4 SPIR-V
     * modules. Returns an opaque handle for [release].
     */
    fun init(assetManager: AssetManager): Long {
        require(ensureLoaded()) { "rawLensWavelet unavailable" }
        val handle = nativeInit(assetManager)
        require(handle != 0L) { "wavelet init returned a null handle" }
        MemoryLeakDiagnostics.vulkanHandleAllocated()
        return handle
    }

    fun release(handle: Long) {
        if (handle != 0L) {
            nativeDestroy(handle)
            MemoryLeakDiagnostics.vulkanHandleReleased()
        }
    }

    /**
     * Full wavelet denoise. [noiseA]/[noiseB] are the normalized green
     * variance slope/offset (see [WaveletDenoiser.greenNoiseNormalized]);
     * [wbGains] are camera WB multipliers with green == 1.
     */
    fun process(
        handle: Long,
        input: ByteBuffer,
        output: ByteBuffer,
        width: Int,
        height: Int,
        strength: Float,
        shadows: Float,
        noiseA: Float,
        noiseB: Float,
        wbGains: FloatArray,
        maxScale: Int,
        forceY: Float,
        forceUv: Float,
        tileSize: Int = TILE_SIZE
    ): Int = nativeProcess(
        handle, input, output, width, height, strength, shadows,
        noiseA, noiseB, wbGains, maxScale, forceY, forceUv, tileSize
    )

    /** Timestamp-derived GPU ms of the last [process] call (-1 unavailable). */
    fun lastGpuMs(handle: Long): Double = nativeLastGpuMs(handle)

    private external fun nativeInit(assetManager: AssetManager): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(
        handle: Long, inHalf: ByteBuffer, outHalf: ByteBuffer, w: Int, h: Int,
        strength: Float, shadows: Float, noiseA: Float, noiseB: Float,
        wb: FloatArray, maxScale: Int, forceY: Float, forceUv: Float, tileSize: Int
    ): Int
    private external fun nativeLastGpuMs(handle: Long): Double
}
