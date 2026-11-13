// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.os.SystemClock
import com.rawlens.srpipeline.SrpBridge
import java.nio.ByteOrder

/**
 * LK-Reloaded burst merge (third method next to MFSR and HDR+), offline
 * over decoded takes. Frames go to native as the runner's direct uint16
 * buffers (no Java-heap unpack); the result reuses [MfsrMerge.MfsrResult]
 * so the DNG / JPEG / JXL tail is shared with MFSR.
 *
 * Noise is estimated natively from the burst (never the Camera2/DNG
 * NoiseProfile). Reference = first frame of [frames].
 */
internal object LkrMerge {
    private const val LOG_TAG = "RawLensCamera"
    /** Sensors deeper than this are staged as plain 16-bit: packing 14 bits saves only an eighth. */
    private const val MAX_PACKED_BITS = 12

    data class Settings(
        val scale: Int = 1,
        val tile: Int = 512,
        val overlap: Int = 32,
        /** Regularise with the Vulkan sliding-DCT x-step; false = no prior. */
        val dctPrior: Boolean = false,
        /** Sensor sub-rectangle `[x, y, w, h]`, or null for full frame. */
        val crop: IntArray? = null,
        /** LK-R Lab overrides (Settings -> Merge), NaN = code default; null = all defaults. */
        val tuning: FloatArray? = null,
        /** Zoom: 1.5x / 2x solve the centre crop at x2; output stays the sensor size. Overrides scale / crop. */
        val zoom: LkrZoom = LkrZoom.X1,
        /** Keep the x2 solve: 1x -> whole frame at x2; 1.5x / 2x -> crop at x2, no downsample. */
        val fullRes: Boolean = false
    )

    fun mergeDecoded(
        context: Context,
        frames: List<OfflineBurstFrame>,
        settings: Settings = Settings(),
        stats: FloatArray = FloatArray(LkrBridge.STATS_SIZE)
    ): MfsrMerge.MfsrResult? {
        if (frames.size < 2) return null
        val ref = frames[0]
        if (frames.any { it.width != ref.width || it.height != ref.height || it.rowStride != ref.rowStride }) {
            Log.w(LOG_TAG, "mixed frame geometries, aborting")
            return null
        }
        return mergeBuffers(context, frames.map { it.pixels.duplicate().also { b -> b.rewind() } },
            ref.width, ref.height, ref.rowStride, ref.normalization, ref.metadata, null, settings, stats)
    }

    /**
     * Live ZSL / burst merge over Camera2 RAW16 frames (reference first, as
     * [MfsrMerge.prepare] orders them). Reads each Image plane in place;
     * the caller releases the Images after this returns.
     *
     * With [releaseInputs] the frames are first copied into native memory, packed
     * losslessly at the sensor's bit depth when that is 12 bits or less (10-bit
     * sensor: 1.25 instead of 2 bytes per sample), and [releaseInputs] is called
     * before the merge starts: the camera gets its buffers back for the whole
     * merge instead of after it. The merge reads the same sample values, so the
     * result is bit-identical. If staging fails the Images are merged in place.
     */
    fun mergeLive(
        context: Context,
        ordered: List<MfsrMerge.MfsrFrame>,
        settings: Settings = Settings(),
        stats: FloatArray = FloatArray(LkrBridge.STATS_SIZE),
        releaseInputs: (() -> Unit)? = null
    ): MfsrMerge.MfsrResult? {
        if (ordered.size < 2) return null
        val ref = ordered[0]
        val w = ref.metadata.imageWidth
        val h = ref.metadata.imageHeight
        val planes = ordered.map { it.frame.image.planes[0] }
        val stride = planes[0].rowStride
        if (ordered.any { it.metadata.imageWidth != w || it.metadata.imageHeight != h } ||
            planes.any { it.rowStride != stride || it.pixelStride != 2 }) {
            Log.w(LOG_TAG, "live merge: mixed or unsupported RAW16 layouts, aborting")
            return null
        }
        val sources = planes.map { it.buffer.duplicate().also { b -> b.rewind() } }
        if (releaseInputs != null) {
            val t0 = SystemClock.elapsedRealtime()
            val staged = stageFrames(sources, w, h, stride / 2, stageBits(ref.norm.whiteLevel))
            if (staged != null) {
                Log.i(LOG_TAG, "lkr staged ${staged.buffers.size} frames at ${staged.bits} bits, " +
                    "${staged.buffers.sumOf { it.capacity().toLong() } / (1024 * 1024)} MB in " +
                    "${SystemClock.elapsedRealtime() - t0} ms; camera buffers released")
                releaseInputs()
                try {
                    return mergeBuffers(context, staged.buffers, w, h, w * 2, ref.norm, ref.metadata,
                        ref.frame.result, settings, stats, packedBits = if (staged.bits < 16) staged.bits else 0)
                } finally {
                    staged.buffers.forEach(SrpBridge::nativeFreeDirect)
                }
            }
            Log.w(LOG_TAG, "lkr staging failed; merging the camera buffers in place")
        }
        return mergeBuffers(context, sources,
            w, h, stride, ref.norm, ref.metadata, ref.frame.result, settings, stats)
    }

    private class Staged(val buffers: List<java.nio.ByteBuffer>, val bits: Int)

    /**
     * Bits per sample for staging: what the white level needs, when that is at most
     * [MAX_PACKED_BITS] (above it packing saves little); otherwise plain 16.
     */
    internal fun stageBits(whiteLevel: Float): Int {
        val white = whiteLevel.toInt()
        if (white <= 0) return 16
        val bits = 32 - Integer.numberOfLeadingZeros(white)
        return if (bits <= MAX_PACKED_BITS) bits else 16
    }

    /** All frames at [bits]; if a sample does not fit (or memory is short) once more at 16; else null. */
    private fun stageFrames(sources: List<java.nio.ByteBuffer>, w: Int, h: Int, stridePx: Int, bits: Int): Staged? {
        for (attempt in if (bits < 16) listOf(bits, 16) else listOf(16)) {
            val out = ArrayList<java.nio.ByteBuffer>(sources.size)
            for (src in sources) {
                val staged = LkrBridge.nativeStage(src, w, h, stridePx, attempt) ?: break
                out.add(staged)
            }
            if (out.size == sources.size) return Staged(out, attempt)
            out.forEach(SrpBridge::nativeFreeDirect)
        }
        return null
    }

    private fun mergeBuffers(
        context: Context,
        buffers: List<java.nio.ByteBuffer>,
        w: Int, h: Int, rowStrideBytes: Int,
        norm: RawNormalization,
        metadata: RawFrameMetadata,
        result: android.hardware.camera2.CaptureResult?,
        requested: Settings,
        stats: FloatArray,
        packedBits: Int = -1
    ): MfsrMerge.MfsrResult? {
        // Zoom: centre crop solved at x2; the native side area-resamples the
        // solve to the sensor size (1.5x), 2x already lands on it.
        val zoomed = requested.zoom == LkrZoom.X1_5 || requested.zoom == LkrZoom.X2
        val settings = when {
            zoomed -> requested.copy(scale = 2, crop = requested.zoom.crop(w, h))
            requested.zoom == LkrZoom.FULL_2X || requested.fullRes -> requested.copy(scale = 2, crop = null)
            else -> requested
        }
        val outW = if (zoomed && !requested.fullRes) w else 0
        val outH = if (zoomed && !requested.fullRes) h else 0
        val cfa = MfsrMerge.dngCodesFor(norm.sensorPattern)
        val neutral = metadata.neutralColorPoint?.toDoubleArray()
        val wb = if (neutral != null && neutral.size == 3 && neutral[0] > 0 && neutral[2] > 0)
            floatArrayOf((neutral[1] / neutral[0]).toFloat(), 1f, (neutral[1] / neutral[2]).toFloat())
        else floatArrayOf(1f, 1f, 1f)
        LkrBridge.nativeSetCacheDir(context.codeCacheDir.absolutePath)
        // Learned per-device noise model as prior for the burst's own fit (as HDR+);
        // the native side declines when it is untrained for this ISO.
        val noiseSource = WaveletDenoiser.NoiseSource.of(metadata)
        LkrBridge.nativeSetNoiseModel(
            noiseSource?.let { WaveletDenoiser.storePath(WaveletDenoiser.noiseDir(context), it) },
            noiseSource?.iso?.toFloat() ?: 0f)
        val t0 = SystemClock.elapsedRealtime()
        // packedBits >= 0: frames from LkrBridge.nativeStage (tight rows, packed when > 0).
        val diagnostics = LkrMergeDiagnostics(context)
        val rgb = try {
            if (packedBits >= 0) LkrBridge.nativeMergeStaged(
                buffers.toTypedArray(), w, h, packedBits, cfa, norm.blackLevels.toFloatArray(),
                norm.whiteLevel, wb, settings.scale, settings.tile, settings.overlap, settings.crop,
                settings.dctPrior,
                stats,
                settings.tuning,
                outW, outH
            ) else LkrBridge.nativeMerge(
                buffers.toTypedArray(), w, h, rowStrideBytes / 2, cfa, norm.blackLevels.toFloatArray(),
                norm.whiteLevel, wb, settings.scale, settings.tile, settings.overlap, settings.crop,
                settings.dctPrior,
                stats,
                settings.tuning,
                outW, outH
            )
        } finally {
            diagnostics.close()
            LkrBridge.nativeLastProfile().lineSequence().filter { it.isNotBlank() }.forEach {
                Log.i(LOG_TAG, "LK-R native: $it")
            }
        } ?: return null
        val mergeMs = SystemClock.elapsedRealtime() - t0
        rgb.order(ByteOrder.nativeOrder())
        val ow = stats[LkrBridge.STAT_OUT_W].toInt()
        val oh = stats[LkrBridge.STAT_OUT_H].toInt()
        // Lens shading on the merged RGB, as MFSR does (sensor-resolution maps).
        // Zoomed output maps each pixel back to its sensor position in the crop.
        val region = settings.crop ?: intArrayOf(0, 0, w, h)
        RawPreDemosaicPipeline.lensShadingModel(metadata)?.let {
            if (region[0] == 0 && region[1] == 0 && ow == w && oh == h)
                MfsrMerge.applyShadingAfterMerge(rgb.asFloatBuffer(), ow, oh, it)
            else
                MfsrMerge.applyShadingMapped(rgb.asFloatBuffer(), ow, oh, it,
                    region[0], region[1], region[2].toFloat() / ow, region[3].toFloat() / oh)
        }
        rgb.rewind()
        Log.i(LOG_TAG, "lkr ${ow}x$oh s=${settings.scale} K=${buffers.size} tiles=${stats[LkrBridge.STAT_TILES].toInt()} " +
            "sigma=%.5f merge %d ms (coarse %.0f tiles %.0f gpu %.0f prior %.0f)".format(
                stats[LkrBridge.STAT_SIGMA], mergeMs, stats[LkrBridge.STAT_MS_COARSE],
                stats[LkrBridge.STAT_MS_TILES], stats[LkrBridge.STAT_MS_GPU], stats[LkrBridge.STAT_MS_PRIOR]))
        return MfsrMerge.MfsrResult(rgb, ow, oh, buffers.size, mergeMs, metadata, result,
            buildString {
                append("- Tile size: ${settings.tile}, overlap: ${settings.overlap}\n")
                append("- Tiles: ${stats[LkrBridge.STAT_TILES].toInt()}\n")
                append("- Prior: ${if (settings.dctPrior) "Sliding DCT" else "None"}\n")
                append("- Crop (x, y, width, height): ${region.joinToString()}\n")
                append("- Noise sigma: ${stats[LkrBridge.STAT_SIGMA]}\n")
                append("- Coarse alignment: ${stats[LkrBridge.STAT_MS_COARSE]} ms\n")
                append("- Tile processing: ${stats[LkrBridge.STAT_MS_TILES]} ms\n")
                append("- GPU: ${stats[LkrBridge.STAT_MS_GPU]} ms\n")
                append("- Prior time: ${stats[LkrBridge.STAT_MS_PRIOR]} ms\n")
            })
    }
}
