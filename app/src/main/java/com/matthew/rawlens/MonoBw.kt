// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.os.Build
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * B&W from photosites, after rwmono (github.com/mristuccia/rwmono): no
 * demosaic, every output pixel is built from measured photosites only.
 *
 * DETAIL keeps full resolution (ratio-corrected flat mosaic, see
 * assets/spirv/mono.vk.glsl); CLEAN is rwmono `bin luma` (2x2 average,
 * half linear resolution, about 1 stop cleaner). AUTO picks per frame from the
 * noise measured on that frame ([MonoNoise]); no Camera2 noise profile is used.
 */
enum class MonoMode(val label: String, val token: String) {
    OFF("Off", "COLOR"),
    AUTO("Auto", "B&W A"),
    DETAIL("Detail", "B&W D"),
    CLEAN("Clean", "B&W C"),
    ;

    val enabled: Boolean get() = this != OFF

    fun next(): MonoMode = entries[(ordinal + 1) % entries.size]

    companion object {
        const val PREFERENCE_KEY = "mono_mode"
        const val KEY_KEEP_COLOR_DNG = "mono_keep_color_dng"
        // "DIRECT" = pre-rename name of CLEAN (2026-11-18).
        fun fromPreference(value: String?): MonoMode =
            if (value == "DIRECT") CLEAN else entries.firstOrNull { it.name == value } ?: OFF
        fun read(prefs: SharedPreferences): MonoMode = fromPreference(prefs.getString(PREFERENCE_KEY, null))
    }
}

/** Frozen-at-shutter B&W choice carried with [JpegOutputSettings]. */
data class MonoSettings(
    val mode: MonoMode = MonoMode.OFF,
    /** Also save the normal colour (Bayer) DNG next to the B&W DNG. */
    val keepColorDng: Boolean = false,
) {
    val enabled: Boolean get() = mode.enabled

    companion object {
        fun read(prefs: SharedPreferences) = MonoSettings(
            MonoMode.read(prefs), prefs.getBoolean(MonoMode.KEY_KEEP_COLOR_DNG, false)
        )
    }
}

/**
 * Per-frame noise measured on the CFA itself (rule: never trust the HAL
 * profile). Green pairs inside a quad (Gr, Gb) see the same scene, so their
 * difference is noise plus Gr/Gb offset: the per-level median removes the
 * offset, the MAD gives sigma. Variance is fitted as a*level + b (shot + read).
 *
 * Auto rule (no tuned constant): same-phase green two photosites apart
 * differs by 2*sigma^2 + fine detail. When the measured fine-detail energy
 * is below the noise energy (fine-scale SNR < 1) full resolution mostly adds
 * noise, so CLEAN binning wins; otherwise DETAIL.
 */
object MonoNoise {
    data class Estimate(val a: Float, val b: Float, val detailEnergy: Double, val noiseEnergy: Double) {
        val prefersClean: Boolean get() = detailEnergy < noiseEnergy
    }

    private const val BINS = 14 // log2 levels 2^-14 .. 1

    fun estimate(cfa: UnpackedRawCfa, balance: FloatArray): Estimate {
        val w = cfa.width; val h = cfa.height
        val greens = (0..3).filter { cfa.pattern.colorAt(it and 1, it shr 1) == CfaColor.GREEN }
        val g = balance[1]
        val v = cfa.values
        // Up to ~250k quads, evenly strided: ~10 ms on a phone core.
        val quads = (w / 2).toLong() * (h / 2)
        val step = max(1, kotlin.math.sqrt(quads / 250_000.0).toInt())
        val diffs = Array(BINS) { FloatArray(4096) }
        val counts = IntArray(BINS)
        val levelSum = DoubleArray(BINS)
        val sampleLevel = FloatArray(260_000); val sampleH2 = FloatArray(260_000)
        var samples = 0
        val ax = greens[0] and 1; val ay = greens[0] shr 1
        val bx = greens[1] and 1; val by = greens[1] shr 1
        var qy = 0
        while (qy < h / 2 - 1) {
            var qx = 0
            while (qx < w / 2 - 1) {
                val ia = (2 * qy + ay) * w + 2 * qx + ax
                val ib = (2 * qy + by) * w + 2 * qx + bx
                val ga = v[ia] * g; val gb = v[ib] * g
                val level = 0.5f * (ga + gb)
                if (v[ia] < OpposedHighlights.CLIP && v[ib] < OpposedHighlights.CLIP && level > 0f) {
                    val bin = (-kotlin.math.log2(level.toDouble())).toInt().coerceIn(0, BINS - 1)
                    val c = counts[bin]
                    if (c < diffs[bin].size) { diffs[bin][c] = ga - gb; levelSum[bin] += level }
                    counts[bin] = c + 1
                    val right = v[ia + 2]
                    if (right < OpposedHighlights.CLIP && samples < sampleLevel.size) {
                        val d = ga - right * g
                        sampleLevel[samples] = level; sampleH2[samples] = d * d; samples++
                    }
                }
                qx += step
            }
            qy += step
        }
        // Weighted least squares var = a*level + b over bins with enough samples.
        var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (bin in 0 until BINS) {
            val n = minOf(counts[bin], diffs[bin].size)
            if (n < 64) continue
            val d = diffs[bin].copyOf(n); d.sort()
            val med = d[n / 2]
            for (i in 0 until n) d[i] = abs(d[i] - med)
            d.sort()
            val sigma = 1.4826 * d[n / 2]
            val variance = sigma * sigma / 2.0
            val level = levelSum[bin] / n // measured mean level of the kept samples
            sw += n; sx += n * level; sy += n * variance
            sxx += n * level * level; sxy += n * level * variance
        }
        var a = 0.0; var b = 0.0
        if (sw > 0) {
            val den = sw * sxx - sx * sx
            if (abs(den) > 1e-30) {
                a = (sw * sxy - sx * sy) / den
                b = (sy - a * sx) / sw
            } else b = sy / sw
            if (a < 0) { a = 0.0; b = sy / sw }
            if (b < 0) { b = 0.0; a = if (sxx > 0) sxy / sxx else 0.0 }
        }
        var detail = 0.0; var noise = 0.0
        for (i in 0 until samples) {
            val n2 = 2.0 * (a * sampleLevel[i] + b)
            noise += n2; detail += sampleH2[i] - n2
        }
        return Estimate(a.toFloat(), b.toFloat(), detail, noise)
    }
}

/** B&W result: [mono] (DNG) and/or camera-linear RGBA16F [packed] (develop tail). */
class MonoFrame(
    val width: Int,
    val height: Int,
    val mode: MonoMode,
    val mono: FloatArray?,
    /** Native buffer from [HdrPlusVulkan.allocBuffer]; [close] frees it. */
    packed: ByteBuffer?,
    /** Source CFA, kept for develop metering (adaptive exposure). */
    val cfa: UnpackedRawCfa,
    val noise: MonoNoise.Estimate,
    val gpuMs: Double,
    private val free: (ByteBuffer) -> Unit = HdrPlusVulkan::freeBuffer,
) : java.io.Closeable {
    var packed: ByteBuffer? = packed
        private set

    override fun close() {
        packed?.let(free)
        packed = null
    }
}

/**
 * One Vulkan dispatch per frame (Detail: 9 textureGather taps per pixel, no
 * shared memory; Clean: one gather per quad). malioc Mali-G615: Detail LS
 * 10 cycles / 32 regs / 100 % occupancy, Clean LS 6 / 17 regs: the image stores
 * are the bound. Pipelines are retained; images are per-photo.
 */
internal class MonoVulkan(
    private val vk: VulkanHub.Handle,
    loadShader: (String) -> ByteArray,
    /** Packed-plane storage; JVM tests pass direct buffers. */
    private val alloc: (Long) -> ByteBuffer = HdrPlusVulkan::allocBuffer,
    private val free: (ByteBuffer) -> Unit = HdrPlusVulkan::freeBuffer,
) : java.io.Closeable {
    constructor(vk: VulkanHub.Handle, assets: AssetManager) :
        this(vk, { name: String -> assets.open(name).use { it.readBytes() } })

    private val modules = LongArray(2)
    private val pipelines = LongArray(2)

    init {
        try {
            listOf("spirv/mono_detail.spv", "spirv/mono_clean.spv").forEachIndexed { i, name ->
                modules[i] = vk.loadModule(loadShader(name))
                pipelines[i] = vk.createPipeline(modules[i], 3, UBO_BYTES, intArrayOf(1, 2, 4), intArrayOf(1, 1, 0))
            }
        } catch (failure: Throwable) {
            close(); throw failure
        }
    }

    override fun close() {
        for (i in 0..1) {
            if (pipelines[i] != 0L) vk.destroyPipeline(pipelines[i])
            if (modules[i] != 0L) vk.destroyModule(modules[i])
            pipelines[i] = 0L; modules[i] = 0L
        }
    }

    /**
     * @param balance WB gains R,G,B (1 / camera white).
     * @param neutral camera white (packed output = Y * neutral: neutral grey for the tail matrix).
     */
    fun run(
        cfa: UnpackedRawCfa, requested: MonoMode, balance: FloatArray, neutral: FloatArray,
        wantMono: Boolean, wantPacked: Boolean,
    ): MonoFrame {
        require(requested.enabled && (wantMono || wantPacked))
        val w = cfa.width; val h = cfa.height
        require(w >= 4 && h >= 4 && w % 2 == 0 && h % 2 == 0 && cfa.values.size == w * h)
        val noise = MonoNoise.estimate(cfa, balance)
        val mode = when (requested) {
            MonoMode.AUTO -> if (noise.prefersClean) MonoMode.CLEAN else MonoMode.DETAIL
            else -> requested
        }
        val clean = mode == MonoMode.CLEAN
        val pipeline = pipelines[if (clean) 1 else 0]
        check(pipeline != 0L) { "Mono session is closed" }
        val ow = if (clean) w / 2 else w
        val oh = if (clean) h / 2 else h
        val images = mutableListOf<Long>()
        fun image(x: Int, y: Int, format: Int) = vk.createImage(x, y, format).also { images += it }
        val started = System.nanoTime()
        try {
            val src = image(w, h, FORMAT_R32F)
            vk.writeImageFloats(src, cfa.values)
            val monoImg = if (wantMono) image(ow, oh, FORMAT_R32F) else image(1, 1, FORMAT_R32F)
            val packedImg = if (wantPacked) image(2 * ow, oh, FORMAT_R32UI) else image(1, 1, FORMAT_R32UI)
            vk.bindImage(pipeline, 1, monoImg)
            vk.bindImage(pipeline, 2, packedImg)
            vk.bindImage(pipeline, 4, src)
            val u = ByteBuffer.allocateDirect(UBO_BYTES).order(ByteOrder.nativeOrder())
            u.putInt(w).putInt(h).putInt((if (wantMono) 1 else 0) or (if (wantPacked) 2 else 0)).putInt(0)
            for (i in 0..3) u.putInt(cfa.pattern.colorAt(i and 1, i shr 1).ordinal)
            u.putFloat(balance[0]).putFloat(balance[1]).putFloat(balance[2]).putFloat(OpposedHighlights.CLIP)
            u.putFloat(neutral[0]).putFloat(neutral[1]).putFloat(neutral[2]).putFloat(0f)
            u.putFloat(noise.a).putFloat(noise.b).putFloat(0f).putFloat(0f)
            u.flip(); vk.writeUniforms(pipeline, u)
            vk.dispatch(pipeline, (ow + LOCAL_X - 1) / LOCAL_X,
                (oh + (if (clean) 8 else 16) - 1) / (if (clean) 8 else 16), 1)
            val mono = if (wantMono) FloatArray(ow * oh).also {
                vk.readImageFloatsInto(monoImg, ow, oh, it, ow, 0, 0)
            } else null
            val packed = if (wantPacked) {
                alloc(ow.toLong() * oh * 8).order(ByteOrder.nativeOrder()).also {
                    try { vk.readImage(packedImg, it); it.rewind() }
                    catch (failure: Throwable) { free(it); throw failure }
                }
            } else null
            val ms = (System.nanoTime() - started) / 1e6
            Log.i(LOG_TAG, "B&W ${mode.name} ${w}x$h -> ${ow}x$oh gpu+io=${"%.1f".format(ms)}ms " +
                "noise a=${noise.a} b=${noise.b} detail/noise=${"%.2f".format(noise.detailEnergy / max(noise.noiseEnergy, 1e-30))}")
            return MonoFrame(ow, oh, mode, mono, packed, cfa, noise, ms, free)
        } finally {
            images.asReversed().forEach { vk.destroyImage(it) }
            vk.releaseStaging()
        }
    }

    private companion object {
        const val LOG_TAG = "RawLensMono"
        const val UBO_BYTES = 80
        const val LOCAL_X = 16
        const val FORMAT_R32F = 0
        const val FORMAT_R32UI = 1
    }
}

/**
 * Monochrome LinearRaw DNG (rwmono output contract): one 16-bit sample per
 * pixel, PhotometricInterpretation 34892, no CFA and no colour matrices,
 * scene-linear (looks dark until exposure is raised, like rwmono). Values are
 * WB-balanced, green-white-relative linear luminance clamped to [0, 1].
 */
object MonoDngWriter {
    private const val STRIP_ROWS = 256

    fun write(output: OutputStream, frame: MonoFrame, metadata: RawFrameMetadata, captureTimeMillis: Long?,
              gps: GpsLocation?, provenance: String? = null) {
        val mono = requireNotNull(frame.mono) { "B&W DNG needs the mono plane" }
        val w = frame.width; val h = frame.height
        require(metadata.exifOrientation in 1..8)
        val oem = { v: String? -> v ?: "unknown" }
        val fields = TiffFields().apply {
            ascii(270, buildString {
                provenance?.let { append(it.trimEnd()).append("\n\n") }
                append("RawLens B&W ${frame.mode.label}\n")
                append("Photosite monochrome; no demosaic; WB-balanced scene-linear luminance.\n")
                append("Source: camera ${metadata.cameraId}, ${metadata.imageWidth}x${metadata.imageHeight}; ")
                append("frame ${metadata.frameNumber}, timestamp ${metadata.timestampNanos} ns.\n")
                append("Output: ${w}x${h}, 16-bit normalized LinearRaw; black 0, white 65535.\n")
                append("Lens shading applied: ${frame.cfa.lensShadingApplied || metadata.lensShadingAlreadyApplied}.")
            })
            captureTimeMillis?.takeIf { it > 0 }?.let { ascii(306, DngExifDirectory.dateTime(it)) }
            ascii(271, oem(Build.MANUFACTURER))
            ascii(272, oem(Build.MODEL))
            shorts(274, metadata.exifOrientation)
            shorts(284, 1)
            ascii(305, "RawLens")
            bytes(50706, byteArrayOf(1, 4, 0, 0))
            bytes(50707, byteArrayOf(1, 4, 0, 0))
            ascii(50708, "${oem(Build.MANUFACTURER)} ${oem(Build.MODEL)} (${metadata.cameraId}) Monochrome")
            rationals(50714, doubleArrayOf(0.0))
            longs(50717, 65535)
        }
        metadata.exposureTimeNanos?.takeIf { it > 0 }?.let { fields.rationals(33434, doubleArrayOf(it / 1e9)) }
        metadata.sensitivityIso?.takeIf { it in 1..65535 }?.let { fields.shorts(34855, it) }
        val stripCount = (h + STRIP_ROWS - 1) / STRIP_ROWS
        TinyDngImageWriter.write(output, w, h,
            samplesPerPixel = 1, bitsPerSample = 16, sampleFormat = 1,
            photometric = 34892, rowsPerStrip = STRIP_ROWS,
            fields = fields, exif = (DngExifDirectory.fields(metadata, captureTimeMillis) ?: TiffFields()).apply {
                longs(40962, w)
                longs(40963, h)
            },
            gps = gps?.let { GpsTiffDirectory.fields(it) }, stripCount = stripCount) { strip ->
            val y0 = strip * STRIP_ROWS
            val rows = minOf(STRIP_ROWS, h - y0)
            val n = w * rows
            val bytes = ByteArray(n * 2)
            val base = y0 * w
            ShardWorkers.forEachShard(n) { p0, p1 ->
                for (i in p0 until p1) {
                    val s = mono[base + i]
                    val q = if (s.isFinite()) (s.coerceIn(0f, 1f) * 65535f).roundToInt() else 0
                    bytes[2 * i] = q.toByte(); bytes[2 * i + 1] = (q shr 8).toByte()
                }
            }
            bytes
        }
    }

    /** Pending MediaStore insert, publish on success, delete on failure. */
    fun save(context: Context, frame: MonoFrame, metadata: RawFrameMetadata, captureTimeMillis: Long,
             gps: GpsLocation?, type: String? = null, provenance: String? = null): String {
        val name = CaptureFileNames.monoDng(captureTimeMillis, type)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RawLens")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create B&W DNG media entry")
        try {
            resolver.openOutputStream(uri, "w")?.use { write(it, frame, metadata, captureTimeMillis, gps, provenance) }
                ?: throw IOException("Could not open B&W DNG output stream")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not publish B&W DNG")
            return name
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }
}
