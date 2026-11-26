// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.content.res.AssetManager
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Trained RAW denoiser (PMRID-style, INT8) on the NPU: a hand-built NNAPI graph from assets
 * models/pmrid.bin (tools/raw-denoise/pmrid). Bayer in, Bayer out in [RawDenoiser]'s encoding.
 * It removes luma and chroma noise together. Only a dedicated accelerator is used: without one
 * [get] returns null and the classic Vulkan denoiser runs instead.
 */
class NetworkDenoiser private constructor(private val handle: Long, val device: String) {

    /** {NPU ms, native total ms}. noise: variance = a * x + b, x normalized to black..white. */
    @Synchronized
    fun process(
        input: ByteBuffer, strideBytes: Int, out: ByteBuffer, w: Int, h: Int, cfa: Int, black: FloatArray, white: Float,
        a: Float, b: Float, strength: Float
    ): FloatArray = nativeProcess(handle, input, strideBytes, out, w, h, cfa, black, white, a, b, strength)
        ?: throw IllegalStateException("network denoise failed")

    /** Device parity test: one graph tile (uint8 NHWC in the graph's input scale). */
    @Synchronized
    internal fun runTile(u: ByteArray, sigma: Float, out: ByteArray): Boolean = nativeRunTile(handle, u, sigma, out)

    companion object {
        private const val TAG = "NetworkDenoiser"
        @Volatile private var instance: NetworkDenoiser? = null
        @Volatile private var failed = false

        /** Process-wide: the first compile for the NPU takes minutes (then cached on disk). Null when this phone has no NPU that runs the graph. */
        @Synchronized
        fun get(context: Context): NetworkDenoiser? {
            instance?.let { return it }
            if (failed) return null
            val created = try {
                System.loadLibrary("rawLensRawDn")
                val start = System.nanoTime()
                val cache = java.io.File(context.applicationContext.codeCacheDir, "pmrid-nnapi").apply { mkdirs() }
                val h = nativeCreate(context.applicationContext.assets, cache.absolutePath + "/")
                if (h == 0L) null else NetworkDenoiser(h, nativeDevice(h)).also {
                    Log.i(TAG, "compiled for ${it.device} in ${(System.nanoTime() - start) / 1_000_000} ms")
                }
            } catch (failure: UnsatisfiedLinkError) {
                Log.w(TAG, "library unavailable", failure)
                null
            }
            if (created == null) Log.i(TAG, "no NPU runs the network on this phone")
            failed = created == null
            instance = created
            return created
        }

        /** Compiles in the background so the first photo does not wait. */
        fun prewarm(context: Context) {
            if (instance != null || failed) return
            val app = context.applicationContext
            thread(name = "network-denoise-prewarm", isDaemon = true) { get(app) }
        }

        @JvmStatic private external fun nativeCreate(assets: AssetManager, cacheDir: String): Long
        @JvmStatic private external fun nativeDevice(handle: Long): String
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeProcess(
            handle: Long, input: ByteBuffer, strideBytes: Int, out: ByteBuffer, w: Int, h: Int, cfa: Int,
            black: FloatArray, white: Float, a: Float, b: Float, strength: Float
        ): FloatArray?
        @JvmStatic private external fun nativeRunTile(handle: Long, u: ByteArray, sigma: Float, out: ByteArray): Boolean
    }
}
