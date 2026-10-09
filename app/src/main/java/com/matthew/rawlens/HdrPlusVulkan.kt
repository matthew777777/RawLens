// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.res.AssetManager
import java.nio.ByteBuffer

/**
 * HDR+ burst-merge Vulkan host: context init (creates all 28 SPIR-V
 * modules on the real driver) and the full spatial/frequency merge
 * (1:1 port of RAWR's merge_hdrplus recorder; see
 * assets spirv/hdrplus/UPSTREAM.md). Native failures throw
 * [RuntimeException] with the core's message.
 */
object HdrPlusVulkan {
    init {
        System.loadLibrary("hdrplus")
    }

    /**
     * Creates instance + device + compute queue and loads every manifest
     * SPIR-V module. Returns an opaque handle for [release]. [cacheFile]
     * persists the pipeline cache across runs (null keeps it in-memory).
     */
    fun init(assetManager: AssetManager, cacheFile: java.io.File? = null): Long {
        val handle = nativeInit(assetManager, cacheFile?.absolutePath)
        require(handle != 0L) { "hdrplus init returned a null handle" }
        return handle
    }

    fun release(handle: Long) = nativeRelease(handle)

    fun loadedShaderCount(handle: Long): Int = nativeLoadedShaderCount(handle)

    /** Manifest size; [loadedShaderCount] must equal this after [init]. */
    fun expectedShaderCount(): Int = nativeExpectedShaderCount()

    /** Timestamp-derived GPU time (ms) of the last [merge] run. */
    fun lastGpuMs(handle: Long): Double = nativeLastGpuMs(handle)

    /**
     * Full burst merge. Each frame buffer must be direct with at least
     * W*H uint16 samples (row-major RAW codes, even W/H, 2..64 frames);
     * [blacks] holds 4 floats per frame in (y&1)*2+(x&1) phase order with
     * phase 0 at the frame's (0,0); [whites] one float per frame;
     * [hotPixels] is an optional flat xy int list (empty skips
     * concealment, like RAWR with no sensor list). [frameStrengths] is
     * an optional per-frame strength array (one float per frame, null
     * merges every companion at [strength]; the reference entry is
     * ignored). [strengthMaps] is an optional flat array of N row-major
     * mw*mh strength maps (mw/mh cover W/H in 32px blocks, reference
     * slice ignored); null keeps the frame/global path. Both paths
     * consume maps (Fast: robustness; HQ: Wiener triples). [output]
     * must be direct with at least W*H floats; it receives the merged
     * normalized CFA. Returns the GPU time in ms.
     */
    fun merge(
        handle: Long,
        frames: Array<ByteBuffer>,
        blacks: FloatArray,
        whites: FloatArray,
        refIndex: Int,
        hotPixels: IntArray,
        strength: Float,
        frameStrengths: FloatArray?,
        strengthMaps: FloatArray?,
        tileSize: Int,
        searchDistance: Int,
        highQuality: Boolean,
        alignOnce: Boolean,
        width: Int,
        height: Int,
        output: ByteBuffer
    ): Double = nativeMerge(
        handle, frames, blacks, whites, refIndex, hotPixels, strength,
        frameStrengths, strengthMaps, tileSize, searchDistance, highQuality,
        alignOnce, width, height, output
    )

    private external fun nativeInit(assetManager: AssetManager, cachePath: String?): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeLoadedShaderCount(handle: Long): Int
    private external fun nativeExpectedShaderCount(): Int
    private external fun nativeLastGpuMs(handle: Long): Double
    private external fun nativeMerge(
        handle: Long, frames: Array<ByteBuffer>, blacks: FloatArray, whites: FloatArray,
        refIndex: Int, hotPixels: IntArray, strength: Float, frameStrengths: FloatArray?,
        strengthMaps: FloatArray?, tileSize: Int, searchDistance: Int,
        highQuality: Boolean, alignOnce: Boolean,
        width: Int, height: Int, output: ByteBuffer
    ): Double
}
