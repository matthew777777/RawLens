// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Classical-denoise bridge: [UnpackedRawCfa] in/out over the GALOSH Vulkan
 * pipeline ([GaloshVulkan]). Mirrors [RawNindDenoiser.denoise] semantics:
 * returns the denoised CFA (same dims/pattern/crop) or null when Galosh is
 * unavailable — failure, OOM, and unsupported geometry all fall back
 * silently. Never throws for Galosh reasons; callers use `?: cfa`.
 *
 * The Vulkan handle is process-lifetime (pipeline creation is too slow per
 * capture) and [denoise] is synchronized: one in-flight run at a time.
 */
class GaloshDenoiser(context: Context) {
    private val assets = context.applicationContext.assets

    @Volatile private var handle = 0L

    /** Per-run cost of the last [denoise] call (any thread). */
    data class Timings(val packMs: Long, val gpuMs: Double, val unpackMs: Long)
    @Volatile var lastTimings = Timings(0, 0.0, 0)
        private set

    /**
     * Creates the Vulkan device + loads all SPIR-V modules on a background
     * thread so the first capture's [denoise] does not pay that cost.
     * Safe to call repeatedly; the handle is created once.
     */
    fun prewarm() {
        if (handle != 0L) return
        Thread {
            try {
                synchronized(this) {
                    if (handle == 0L) handle = GaloshVulkan.init(assets)
                }
            } catch (failure: Exception) {
                Log.w(LOG_TAG, "Galosh prewarm failed, first capture will init lazily", failure)
            }
        }.apply { isDaemon = true; start() }
    }

    @Synchronized
    fun denoise(cfa: UnpackedRawCfa, settings: GaloshSettings): UnpackedRawCfa? {
        try {
            cfa.requireAmazeCompatible()
            if (!settings.enabled || settings.strength == 0f) return cfa
            // The o32 shaders hardcode RGGB; any other effective arrangement
            // is losslessly reshuffled there and restored after the run.
            val perm = GaloshBayerRemap.toRgbbPerm(cfa.pattern, cfa.sensorCropLeft, cfa.sensorCropTop)
            val remapped = !GaloshBayerRemap.isIdentity(perm)
            val work = if (remapped) {
                GaloshBayerRemap.toRgbb(cfa.values, cfa.width, cfa.height, perm)
            } else {
                cfa.values
            }
            if (handle == 0L) handle = GaloshVulkan.init(assets)
            var t = System.nanoTime()
            val n = cfa.width * cfa.height
            val input = ByteBuffer.allocateDirect(n * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            input.put(work).rewind()
            val output = ByteBuffer.allocateDirect(n * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            val packedAt = System.nanoTime()
            val gpuMs = GaloshVulkan.denoise(
                handle, input, output, cfa.width, cfa.height,
                settings.strength, settings.luma, settings.chroma,
                0f, 0f, 8, settings.fastUpsample, if (settings.fastMode) 2 else 1
            )
            val inferredAt = System.nanoTime()
            val result = FloatArray(n)
            output.rewind()
            output.get(result)
            val restored = if (remapped) {
                GaloshBayerRemap.fromRgbb(result, cfa.width, cfa.height, perm)
            } else {
                result
            }
            lastTimings = Timings(
                (packedAt - t) / 1_000_000, gpuMs, (System.nanoTime() - inferredAt) / 1_000_000
            )
            Log.i(LOG_TAG, "Galosh CFA denoise ${cfa.width}x${cfa.height} " +
                "gpu=${"%.1f".format(gpuMs)}ms strength=${settings.strength}" +
                (if (settings.fastMode) " fast" else "") +
                if (remapped) " remapped" else "")
            return cfa.copy(values = restored)
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Galosh CFA denoise failed, falling back", failure)
            return null
        } catch (oom: OutOfMemoryError) {
            Log.w(LOG_TAG, "Galosh CFA denoise OOM, falling back", oom)
            return null
        }
    }

    companion object {
        private const val LOG_TAG = "GaloshDenoiser"
    }
}
