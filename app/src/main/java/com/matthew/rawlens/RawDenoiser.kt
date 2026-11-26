// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.content.res.AssetManager
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * User controls of the RAW denoiser. Strength is automatic (measured noise);
 * [texture] scales the fine luma grain and detail kept, 1 = automatic.
 * [luma] and [chroma] scale each part on its own: 1 = automatic, 0 = not denoised.
 * [engine] NETWORK runs the trained network on the NPU instead ([NetworkDenoiser]): it removes luma and
 * chroma noise together, [networkStrength] blends toward the original; without an NPU the classic one runs.
 */
data class RawDenoiseSettings(
    val enabled: Boolean = false, val texture: Float = 1f, val luma: Float = 1f, val chroma: Float = 1f,
    val engine: Engine = Engine.CLASSIC_V1, val networkStrength: Float = 1f
) {
    init { require(texture.isFinite() && luma.isFinite() && chroma.isFinite() && networkStrength.isFinite()) }
    /** On, with something left to remove. */
    val active: Boolean get() = enabled && (luma > 0f || chroma > 0f || engine == Engine.NETWORK && networkStrength > 0f)
    fun bounded() = copy(texture = texture.coerceIn(0f, MAX_TEXTURE), luma = luma.coerceIn(0f, 1f), chroma = chroma.coerceIn(0f, 1f),
        networkStrength = networkStrength.coerceIn(0f, 1f))

    /** CLASSIC_V1 = the classic denoiser as frozen v1: all removed luma comes back as film grain (no night coarse cut). */
    enum class Engine(val label: String) {
        CLASSIC("Classic (GPU)"), CLASSIC_V1("Classic v1 (GPU, film grain)"), NETWORK("Network (NPU)")
    }

    /** What [luma] and [chroma] add up to; picking a mode turns the other part off. */
    enum class Mode(val label: String) { LUMA_CHROMA("Luma and chroma"), LUMA("Luma only"), CHROMA("Chroma only") }

    val mode: Mode get() = when {
        chroma <= 0f && luma > 0f -> Mode.LUMA
        luma <= 0f && chroma > 0f -> Mode.CHROMA
        else -> Mode.LUMA_CHROMA
    }

    /** [mode] with the parts it keeps at their strength, or Auto where they were off. */
    fun withMode(mode: Mode) = copy(
        luma = if (mode == Mode.CHROMA) 0f else if (luma > 0f) luma else 1f,
        chroma = if (mode == Mode.LUMA) 0f else if (chroma > 0f) chroma else 1f
    )

    companion object {
        const val MAX_TEXTURE = 1.5f
        const val KEY_ENABLED = "raw_denoise_enabled"
        const val KEY_TEXTURE_PCT = "raw_denoise_texture_pct"
        const val KEY_LUMA_PCT = "raw_denoise_luma_pct"
        const val KEY_CHROMA_PCT = "raw_denoise_chroma_pct"
        const val KEY_ENGINE = "raw_denoise_engine"
        const val KEY_NETWORK_PCT = "raw_denoise_network_pct"
        // Denoisers this one replaces: whoever had one of them on keeps denoising.
        private const val OLD_WAVELET_KEY = "wavelet_enabled"
        private const val OLD_AI_KEY = "ai_denoise_enabled"

        fun read(prefs: android.content.SharedPreferences) = RawDenoiseSettings(
            enabled = prefs.getBoolean(KEY_ENABLED,
                prefs.getBoolean(OLD_WAVELET_KEY, false) || prefs.getBoolean(OLD_AI_KEY, false)),
            texture = prefs.getInt(KEY_TEXTURE_PCT, 100) / 100f,
            luma = prefs.getInt(KEY_LUMA_PCT, 100) / 100f,
            chroma = prefs.getInt(KEY_CHROMA_PCT, 100) / 100f,
            engine = Engine.entries.firstOrNull { it.name == prefs.getString(KEY_ENGINE, null) } ?: Engine.CLASSIC_V1,
            networkStrength = prefs.getInt(KEY_NETWORK_PCT, 100) / 100f
        ).bounded()
    }
}

/**
 * Fine luma (grain and detail) the denoiser took out of the CFA, fp16, one value
 * per photosite of the RAW buffer, G-normalized white balance. Added to the
 * camera-linear RGB after the demosaic as `luma * lensShading / wb`.
 */
data class RawLumaResidual(
    val values: ByteBuffer, val stride: Int, val cropLeft: Int, val cropTop: Int,
    /** G-normalized camera WB gains R, G, B. */
    val wb: FloatArray,
    val sensorCropLeft: Int, val sensorCropTop: Int, val lensShading: LensShadingModel?
)

/** Denoised Bayer plane with its own tight layout and levels, plus what was needed to read the original. */
class DenoisedRaw(
    val plane: ByteBuffer, val layout: RawPlaneLayout, val normalization: RawNormalization,
    /** Grain and detail to add back after the demosaic; null when the denoiser returns none (network). */
    val luma: RawLumaResidual?,
    val sourcePlane: ByteBuffer, val sourceLayout: RawPlaneLayout,
    private val release: () -> Unit
) : Closeable {
    private var closed = false

    /** [plane] and [luma] must not be read after this. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        release()
    }
}

/**
 * Single-frame RAW-domain denoiser (Vulkan, native rawdn): Bayer plane in, Bayer
 * plane out, before the demosaic. Noise is measured on the frame itself and
 * combined with the learned per-device model; sensor noise profiles are not used.
 */
class RawDenoiser(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private var handle = 0L

    @Synchronized
    fun ensureInit(): Boolean {
        if (handle != 0L) return true
        return try {
            System.loadLibrary("rawLensRawDn")
            handle = nativeInit(appContext.assets)
            if (handle != 0L) MemoryLeakDiagnostics.vulkanHandleAllocated()
            handle != 0L
        } catch (failure: RuntimeException) {
            Log.w(TAG, "RAW denoise init failed", failure)
            false
        } catch (failure: UnsatisfiedLinkError) {
            Log.w(TAG, "RAW denoise library unavailable", failure)
            false
        }
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        nativeDestroy(handle)
        handle = 0L
        MemoryLeakDiagnostics.vulkanHandleReleased()
    }

    /** Null when the frame cannot be denoised (unsupported, no noise estimate, GPU failure): develop the original. */
    @Synchronized
    fun process(
        rawPlane: ByteBuffer, metadata: RawFrameMetadata, geometry: RawBufferGeometry.Supported,
        normalization: RawNormalization, lensShading: LensShadingModel?, settings: RawDenoiseSettings
    ): DenoisedRaw? {
        if (!settings.active) return null
        val bounded = settings.bounded()
        val w = metadata.imageWidth
        val h = metadata.imageHeight
        if (metadata.quadBayer || w < 64 || h < 64 || (w and 1) != 0 || (h and 1) != 0) return null
        if (!ensureInit()) return null
        var out: ByteBuffer? = null
        var luma: ByteBuffer? = null
        var output = 0L
        var corrected: ByteBuffer? = null
        try {
            val start = System.nanoTime()
            val packed = PackedRawFormat.ofInt(metadata.rawImageFormat)
            val source: ByteBuffer
            val sourceLayout: RawPlaneLayout
            if (packed == null) {
                val stride = metadata.rawPlaneRowStride ?: return null
                if (metadata.rawPlanePixelStride != 2) return null
                // The native side reads from the buffer's base address, so drop any position.
                source = rawPlane.slice().order(ByteOrder.nativeOrder())
                sourceLayout = RawPlaneLayout(w, h, stride, 2, geometry.sensorOriginX, geometry.sensorOriginY)
            } else {
                val shorts = RawPackedUnpacker.decodePlaneToShorts(rawPlane, w, h,
                    metadata.rawPlaneRowStride ?: return null, 0, packed, ByteOrder.nativeOrder())
                source = ByteBuffer.allocateDirect(shorts.size * 2).order(ByteOrder.nativeOrder())
                source.asShortBuffer().put(shorts)
                sourceLayout = RawPlaneLayout(w, h, w * 2, 2, geometry.sensorOriginX, geometry.sensorOriginY)
            }
            if (!source.isDirect) return null
            val pattern = normalization.sensorPattern.shifted(geometry.sensorOriginX, geometry.sensorOriginY)
            val black = FloatArray(4) {
                normalization.blackAt(geometry.sensorOriginX + (it and 1), geometry.sensorOriginY + (it shr 1))
            }
            // Known hot pixels go before denoising, or they smear into their neighbours.
            var input = source
            var inputStride = sourceLayout.rowStride
            if (metadata.hotPixels.isNotEmpty()) {
                corrected = HdrPlusVulkan.allocBuffer(w.toLong() * h * 2).order(ByteOrder.nativeOrder())
                copyWithoutHotPixels(source, sourceLayout, corrected, metadata.hotPixels)
                input = corrected
                inputStride = w * 2
            }
            val estimateAt = System.nanoTime()
            val measured = nativeEstimate(input, inputStride, w, h, pattern.ordinal, black, normalization.whiteLevel)
            val identity = RawNoiseModel.Source.of(metadata)
            val store = identity?.let { RawNoiseModel.storePath(RawNoiseModel.noiseDir(appContext), it) }
            // Learned model as prior, this shot as evidence; the shot is recorded only afterwards.
            val combined = if (identity != null && store != null) RawNoiseModel.combineNoise(store, identity.iso.toFloat(),
                measured?.get(0) ?: -1f, measured?.get(1) ?: -1f, measured?.get(2)?.toInt() ?: 0) else null
            if (identity != null && measured != null) RawNoiseModel.record(appContext, identity,
                RawNoiseEstimate(measured[0].toDouble(), measured[1].toDouble(), measured[2].toInt(), DoubleArray(0)))
            val noise = combined ?: measured
            val modelAt = System.nanoTime()
            if (noise == null || !(noise[0] > 0f) || !(noise[1] >= 0f)) {
                Log.i(TAG, "RAW denoise skipped: no noise estimate for this frame")
                return null
            }
            if (bounded.engine == RawDenoiseSettings.Engine.NETWORK) {
                val net = NetworkDenoiser.get(appContext)
                if (net != null) {
                    val plane = HdrPlusVulkan.allocBuffer(w.toLong() * h * 2).order(ByteOrder.nativeOrder())
                    try {
                        val stats = net.process(input, inputStride, plane, w, h, pattern.ordinal, black, normalization.whiteLevel,
                            noise[0], noise[1], bounded.networkStrength)
                        Log.i(TAG, "RAW denoise network ${w}x$h on ${net.device} a=${noise[0]} b=${noise[1]} " +
                            "strength=${bounded.networkStrength} npu=${"%.0f".format(stats[0])}ms native=${"%.0f".format(stats[1])}ms " +
                            "wall=${"%.0f".format((System.nanoTime() - start) / 1e6)}ms")
                        return DenoisedRaw(
                            plane, RawPlaneLayout(w, h, w * 2, 2, geometry.sensorOriginX, geometry.sensorOriginY),
                            RawNormalization(normalization.sensorPattern, List(4) { PEDESTAL }, OUTPUT_WHITE),
                            null, source, sourceLayout
                        ) { HdrPlusVulkan.freeBuffer(plane) }
                    } catch (failure: RuntimeException) {
                        HdrPlusVulkan.freeBuffer(plane)
                        Log.w(TAG, "RAW denoise network failed, using the classic denoiser", failure)
                    }
                } else {
                    Log.i(TAG, "RAW denoise network unavailable (no NPU), using the classic denoiser")
                }
            }
            val gains = CaptureSharpeningSettings.collapseWhiteBalance(metadata.wbGains?.toFloatArray()) ?: run {
                Log.i(TAG, "RAW denoise skipped: no white balance gains")
                return null
            }
            val wb = floatArrayOf(gains[0] / gains[1], 1f, gains[2] / gains[1])
            // Classic v1 (the default; also what Network falls back to): frozen-v1 grain, night coarse cut off
            val v1Grain = bounded.engine != RawDenoiseSettings.Engine.CLASSIC
            // The result is read in place from the GPU's buffer; frames too large for one pass are copied out in strips.
            val mapped = LongArray(2)
            val allocAt = System.nanoTime()
            var nativeAt = allocAt
            var stats = nativeProcess(handle, input, inputStride, null, null, w, h, pattern.ordinal, black,
                normalization.whiteLevel, noise[0], noise[1], wb, bounded.texture, bounded.luma, bounded.chroma, v1Grain, mapped)
            output = mapped[0]
            var rowSamples = w
            if (output != 0L) {
                rowSamples = mapped[1].toInt()
                out = nativeOutputBuffer(output, 0, rowSamples, h)?.order(ByteOrder.nativeOrder())
                luma = nativeOutputBuffer(output, 1, rowSamples, h)?.order(ByteOrder.nativeOrder())
                if (out == null || luma == null) throw IllegalStateException("RAW denoise output unavailable")
            } else {
                out = HdrPlusVulkan.allocBuffer(w.toLong() * h * 2).order(ByteOrder.nativeOrder())
                luma = HdrPlusVulkan.allocBuffer(w.toLong() * h * 2).order(ByteOrder.nativeOrder())
                nativeAt = System.nanoTime()
                stats = nativeProcess(handle, input, inputStride, out, luma, w, h, pattern.ordinal, black,
                    normalization.whiteLevel, noise[0], noise[1], wb, bounded.texture, bounded.luma, bounded.chroma, v1Grain, null)
            }
            Log.i(TAG, "RAW denoise ${if (v1Grain) "v1 " else ""}${w}x$h a=${noise[0]} b=${noise[1]} " +
                "source=${if (combined != null) "model" else "estimate"} bins=${measured?.get(2)?.toInt() ?: 0} " +
                "snr=${stats[1]} grain=${stats[2]} luma=${bounded.luma} chroma=${bounded.chroma} gpu=${"%.0f".format(stats[0])}ms " +
                "wall=${"%.0f".format((System.nanoTime() - start) / 1e6)}ms " +
                "[prep ${ms(start, estimateAt)} estimate ${ms(estimateAt, modelAt)} alloc ${ms(allocAt, nativeAt)} native ${"%.0f".format(stats[5])} " +
                "(copyIn ${"%.0f".format(stats[3])} copyOut ${if (output != 0L) "none" else "%.0f".format(stats[4])})]")
            val plane = out
            val lumaPlane = luma
            val owned = output
            val result = DenoisedRaw(
                plane, RawPlaneLayout(w, h, rowSamples * 2, 2, geometry.sensorOriginX, geometry.sensorOriginY),
                RawNormalization(normalization.sensorPattern, List(4) { PEDESTAL }, OUTPUT_WHITE),
                RawLumaResidual(lumaPlane, rowSamples, geometry.processingCrop.left, geometry.processingCrop.top, wb,
                    geometry.sensorOriginX + geometry.processingCrop.left,
                    geometry.sensorOriginY + geometry.processingCrop.top, lensShading),
                source, sourceLayout
            ) {
                if (owned != 0L) nativeReleaseOutput(owned) else {
                    HdrPlusVulkan.freeBuffer(plane)
                    HdrPlusVulkan.freeBuffer(lumaPlane)
                }
            }
            out = null
            luma = null
            output = 0L
            return result
        } catch (failure: RuntimeException) {
            Log.w(TAG, "RAW denoise failed, developing without it", failure)
            return null
        } catch (failure: OutOfMemoryError) {
            Log.w(TAG, "RAW denoise out of memory, developing without it", failure)
            return null
        } finally {
            if (output != 0L) nativeReleaseOutput(output) else {
                out?.let(HdrPlusVulkan::freeBuffer)
                luma?.let(HdrPlusVulkan::freeBuffer)
            }
            corrected?.let(HdrPlusVulkan::freeBuffer)
        }
    }

    private fun ms(from: Long, to: Long) = "%.0f".format((to - from) / 1e6)

    private external fun nativeInit(assets: AssetManager): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeEstimate(
        input: ByteBuffer, strideBytes: Int, w: Int, h: Int, cfa: Int, black: FloatArray, white: Float
    ): FloatArray?
    private external fun nativeProcess(
        handle: Long, input: ByteBuffer, strideBytes: Int, out: ByteBuffer?, luma: ByteBuffer?, w: Int, h: Int, cfa: Int,
        black: FloatArray, white: Float, a: Float, b: Float, wb: FloatArray, texture: Float,
        lumaStrength: Float, chromaStrength: Float, v1Grain: Boolean, mapped: LongArray?
    ): FloatArray

    companion object {
        private const val TAG = "RawDenoiser"

        @JvmStatic private external fun nativeOutputBuffer(output: Long, which: Int, stride: Int, h: Int): ByteBuffer?
        /** Any thread; also after [close]: the GPU device stays until its last output is released. */
        @JvmStatic private external fun nativeReleaseOutput(output: Long)
        /** Native output: linear = (v / 65535 - 1/16) * 16/15. */
        internal const val OUTPUT_WHITE = 65535f
        internal const val PEDESTAL = 65535f / 16f

        /** Median of the in-frame same-colour neighbours, as the CFA defect corrector. */
        internal fun copyWithoutHotPixels(
            source: ByteBuffer, layout: RawPlaneLayout, target: ByteBuffer, hot: List<IntPointSnapshot>
        ) {
            val src = source.duplicate().order(ByteOrder.nativeOrder())
            val dst = target.duplicate().order(ByteOrder.nativeOrder())
            val rowBytes = layout.width * 2
            for (y in 0 until layout.height) {
                src.limit(y * layout.rowStride + rowBytes).position(y * layout.rowStride)
                dst.limit((y + 1) * rowBytes).position(y * rowBytes)
                dst.put(src)
            }
            src.clear()
            dst.clear()
            fun at(x: Int, y: Int) = src.getShort(y * layout.rowStride + x * 2).toInt() and 0xffff
            for (p in hot) {
                val x = p.x - layout.sensorOriginX
                val y = p.y - layout.sensorOriginY
                if (x !in 0 until layout.width || y !in 0 until layout.height) continue
                val near = ArrayList<Int>(8)
                for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
                    if ((dx != 0 || dy != 0) && x + dx in 0 until layout.width && y + dy in 0 until layout.height)
                        near.add(at(x + dx, y + dy))
                }
                near.sort()
                val m = near.size / 2
                val median = if (near.size % 2 == 1) near[m] else (near[m - 1] + near[m]) / 2
                dst.putShort(y * rowBytes + x * 2, median.toShort())
            }
        }
    }
}
