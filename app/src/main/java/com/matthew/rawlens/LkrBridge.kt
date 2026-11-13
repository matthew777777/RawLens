// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer

/**
 * JNI surface of `liblkrpipeline.so` (tools/lkr-pipeline): LK-Reloaded burst
 * super-resolution (Lecouat, Ponce, Mairal, ICCV 2021) on Vulkan.
 *
 * [nativeMerge] reads the caller's direct uint16 RAW buffers in place and
 * returns a malloc'd direct buffer of interleaved float RGB in sensor
 * coordinates, or null on failure. Free it with [SrpBridge.nativeFreeDirect]
 * (the [MfsrMerge.MfsrResult.release] contract).
 */
internal object LkrBridge {
    init {
        System.loadLibrary("srpipeline")
        System.loadLibrary("lkrpipeline")
    }

    /** Consumes diagnostics of the last merge on this calling thread, including failures. */
    @JvmStatic external fun nativeLastProfile(): String

    /** Indices into the `stats` array filled by [nativeMerge]. */
    const val STAT_TILES = 0
    const val STAT_SIGMA = 1
    const val STAT_NOISE_A = 2
    const val STAT_NOISE_B = 3
    const val STAT_MS_NOISE = 4
    const val STAT_MS_COARSE = 5
    const val STAT_MS_TILES = 6
    const val STAT_MS_GPU = 7
    const val STAT_MS_PRIOR = 8
    const val STAT_MS_TOTAL = 9
    const val STAT_PRIOR_CALLS = 10
    const val STAT_OUT_W = 11
    const val STAT_OUT_H = 12
    const val STAT_BACKEND = 13
    const val STATS_SIZE = 16

    /** Same full-resolution green-gradient score as BurstSharpness.measure; NaN for unsupported buffers. */
    @JvmStatic external fun nativeSharpness(
        src: ByteBuffer, width: Int, height: Int, rowStridePx: Int, cfa: IntArray, black: FloatArray
    ): Double

    /**
     * @param frames reference first; each holds `rowStridePx * height` uint16.
     * @param cfa LibRaw codes of the 2x2 cell, row-major (0 R, 1 G, 2 B).
     * @param black per CFA cell in the same order; [white] the white level.
     * @param wb R, G, B gains with G = 1 (1 / AsShotNeutral).
     * @param crop `[x, y, w, h]` sensor sub-rectangle, or null for full frame.
     * @param dctPrior regularise with the Vulkan sliding-DCT x-step; false = no prior.
     * @param tuning LK-R Lab overrides ([LkrTuning.overrides]): NaN = code default; null = all defaults.
     * @param outWidth / [outHeight] requested output size (area-resampled if the solve differs), 0 = as solved.
     */
    @JvmStatic external fun nativeMerge(
        frames: Array<ByteBuffer>, width: Int, height: Int, rowStridePx: Int,
        cfa: IntArray, black: FloatArray, white: Float, wb: FloatArray,
        scale: Int, tile: Int, overlap: Int, crop: IntArray?,
        dctPrior: Boolean, stats: FloatArray,
        tuning: FloatArray?, outWidth: Int, outHeight: Int
    ): ByteBuffer?

    /**
     * Folder for the compiled Vulkan pipelines (use the app's code cache directory): without it
     * every new process recompiles the shaders on its first merge. Call before [nativeMerge].
     */
    /**
     * Copies one RAW16 frame out of its camera buffer into native memory, rows tight
     * ([width] samples). [bits] 1..15 packs losslessly at that depth and returns null
     * when a sample needs more bits (or memory is short); 16 keeps plain uint16.
     * Free the result with [SrpBridge.nativeFreeDirect].
     */
    @JvmStatic external fun nativeStage(src: ByteBuffer, width: Int, height: Int, rowStridePx: Int, bits: Int): ByteBuffer?

    /** [nativeMerge] over frames from [nativeStage]; [packedBits] 0 = plain uint16 rows. */
    @JvmStatic external fun nativeMergeStaged(
        frames: Array<ByteBuffer>, width: Int, height: Int, packedBits: Int,
        cfa: IntArray, black: FloatArray, white: Float, wb: FloatArray,
        scale: Int, tile: Int, overlap: Int, crop: IntArray?,
        dctPrior: Boolean, stats: FloatArray,
        tuning: FloatArray?, outWidth: Int, outHeight: Int
    ): ByteBuffer?

    @JvmStatic external fun nativeSetCacheDir(dir: String)

    /**
     * Learned per-device noise model (WaveletDenoiser store file, [iso]) for the next
     * [nativeMerge] / [nativeMergeStaged] on the calling thread: it refines the burst's
     * own noise fit as in HDR+. Null path = burst fit only. Consumed by that merge.
     */
    @JvmStatic external fun nativeSetNoiseModel(path: String?, iso: Float)

    /** Native defaults of the LK-R Lab table (index layout: lkr_tuning.h / [LkrTuning.Entry]). */
    @JvmStatic external fun nativeTuningDefaults(): FloatArray
}
