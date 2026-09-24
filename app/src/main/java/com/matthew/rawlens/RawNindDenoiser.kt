// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.content.Context
import android.util.Log
import com.particlesdevs.photoncamera.processing.ml.RawNindNcnnProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Pure packed-Bayer helpers for the RawNIND-tiny denoiser.
 *
 * Canonical packed order is [R, G1, G2, B] (G1 = green on even rows), packed
 * channel-last per quad: `packed[(qy * w2 + qx) * 4 + c]`. This matches
 * python/rawnind-train/dng_loader.py (packed_to_rggb) and the CfaNoiseModel
 * CFA order (R, Gr, Gb, B). NOTE: python/burst-ref uses [R,G1,B,G2]; the two
 * must never be mixed without permuting.
 *
 * Kept free of Android APIs so unit tests cover the exact packing the
 * trainer and the NCNN inference path share.
 */
object RawNindPack {
    /** Stored quad index (TL=0, TR=1, BL=2, BR=3) -> canonical [R,G1,G2,B] slot. */
    fun canonicalPerm(pattern: BayerPattern): IntArray {
        val perm = IntArray(4)
        for (k in 0..3) {
            val px = k and 1
            val py = k shr 1
            perm[k] = when (pattern.colorAt(px, py)) {
                CfaColor.RED -> 0
                CfaColor.BLUE -> 3
                CfaColor.GREEN -> if (py == 0) 1 else 2
            }
        }
        return perm
    }

    /** (H, W) Bayer -> channel-last (H/2*W/2*4) canonical [R,G1,G2,B]. */
    fun packCanonical(values: FloatArray, width: Int, height: Int, pattern: BayerPattern): FloatArray {
        require(width % 2 == 0 && height % 2 == 0) { "Bayer crop must have even dimensions" }
        require(values.size == width * height) { "CFA buffer size mismatch" }
        val perm = canonicalPerm(pattern)
        val w2 = width / 2
        val h2 = height / 2
        val out = FloatArray(w2 * h2 * 4)
        for (qy in 0 until h2) for (qx in 0 until w2) {
            val qi = qy * w2 + qx
            val base = (qy * 2) * width + qx * 2
            out[qi * 4 + perm[0]] = values[base]
            out[qi * 4 + perm[1]] = values[base + 1]
            out[qi * 4 + perm[2]] = values[base + width]
            out[qi * 4 + perm[3]] = values[base + width + 1]
        }
        return out
    }

    /** Inverse of [packCanonical] for tests and non-RGGB restoration checks. */
    fun unpackCanonical(packed: FloatArray, width: Int, height: Int, pattern: BayerPattern): FloatArray {
        require(width % 2 == 0 && height % 2 == 0) { "Bayer crop must have even dimensions" }
        val w2 = width / 2
        val h2 = height / 2
        require(packed.size == w2 * h2 * 4) { "Packed buffer size mismatch" }
        val perm = canonicalPerm(pattern)
        val out = FloatArray(width * height)
        for (qy in 0 until h2) for (qx in 0 until w2) {
            val qi = qy * w2 + qx
            val base = (qy * 2) * width + qx * 2
            // Stored quad k holds canonical slot perm[k]; recover each position.
            out[base] = packed[qi * 4 + perm[0]]
            out[base + 1] = packed[qi * 4 + perm[1]]
            out[base + width] = packed[qi * 4 + perm[2]]
            out[base + width + 1] = packed[qi * 4 + perm[3]]
        }
        return out
    }

    /** RawNIND Bayer was trained with match_gain=output. Its raw prediction
     * is not normalized sensor data (the shipped graph can output ~200,000
     * for a 0.2 input). Use one scalar for the whole image, never per-channel
     * or per-tile gains which would change colour or create seams.
     */
    fun bayerOutputGain(rgb: java.nio.FloatBuffer, packedInput: FloatArray): Double {
        require(packedInput.isNotEmpty() && rgb.limit() > 0)
        var inputSum = 0.0
        for (v in packedInput) {
            require(v.isFinite()) { "Non-finite RawNIND input" }
            inputSum += v.coerceIn(0f, 1f)
        }
        var outputSum = 0.0
        for (i in 0 until rgb.limit()) {
            val v = rgb.get(i)
            require(v.isFinite()) { "Non-finite RawNIND output" }
            outputSum += v
        }
        val mean = outputSum / rgb.limit()
        require(kotlin.math.abs(mean) >= 1e-12) { "Degenerate RawNIND output gain" }
        return (inputSum / packedInput.size) / mean
    }

    /** Project canonical RGGB model RGB back onto the source CFA sites.
     * Absolute buffer reads avoid a second full-resolution RGB heap copy.
     * Strength is blended in CFA space so zero strength preserves the source,
     * and other settings do not introduce block-constant RGB into AMaZE.
     */
    fun bayerRgbToCfa(rgb: java.nio.FloatBuffer, source: UnpackedRawCfa, strength: Float, gain: Double = 1.0): UnpackedRawCfa {
        source.requireAmazeCompatible()
        require(rgb.limit().toLong() >= source.width.toLong() * source.height * 3)
        require(strength.isFinite() && strength in 0f..1f && gain.isFinite())
        if (strength == 0f) return source
        val perm = canonicalPerm(source.pattern)
        val values = FloatArray(source.values.size)
        for (qy in 0 until source.height / 2) for (qx in 0 until source.width / 2) {
            val base = qy * 2 * source.width + qx * 2
            for (k in 0..3) {
                val canonical = perm[k]
                val target = base + (k / 2) * source.width + k % 2
                val modelPixel = base + (canonical / 2) * source.width + canonical % 2
                val color = if (canonical == 0) 0 else if (canonical == 3) 2 else 1
                val denoised = (rgb.get(modelPixel * 3 + color) * gain).toFloat()
                val original = source.values[target]
                values[target] = if (denoised.isFinite())
                    original + strength * (denoised.coerceAtLeast(0f) - original)
                else original
            }
        }
        return source.copy(values = values)
    }

    /** Per-patch noise sigma from the DNG profile averages (normalized domain). */
    fun sigmaFor(mean: Float, avgScale: Float, avgOffset: Float): Float {
        val m = mean.coerceAtLeast(0f)
        return sqrt(avgScale * m + avgOffset)
    }
}

/**
 * Full-resolution denoised linear camRGB from the bayer (UtNet2) model.
 *
 * Unlike [RawNindDenoiser.denoise] (tiny model, packed Bayer out), the bayer
 * model jointly denoises and demosaics: output is channel-last
 * [width]*[height]*3 at the full Bayer resolution (2x the packed input),
 * ready for the color pipeline instead of AMaZE.
 */
data class BayerDenoisedRgb(
    val width: Int,
    val height: Int,
    val rgb: FloatArray
) {
    init {
        require(width > 0 && height > 0)
        require(rgb.size == width * height * 3) { "RGB buffer size mismatch" }
    }
}

/**
 * darktable neural-restore raw-strength port
 * (src/common/ai/restore_raw_bayer.h): uniform per-sample
 * `out = s*denoised + (1-s)*source`, applied after inference so the
 * strength slider never re-runs the model. Pure math, fully unit-tested;
 * both denoise paths share it.
 */
object RawNindBlend {
    /** Per-sample linear blend of equal-length buffers. */
    fun mix(source: FloatArray, denoised: FloatArray, strength: Float): FloatArray {
        require(source.size == denoised.size) { "Blend buffer size mismatch" }
        val s = strength.coerceIn(0f, 1f)
        if (s >= 1f) return denoised
        if (s <= 0f) return source
        return FloatArray(source.size) { i -> s * denoised[i] + (1f - s) * source[i] }
    }

    /**
     * Naive full-res RGB expansion of canonical packed [R,G1,G2,B] (the
     * "source" endpoint for the bayer blend): each quad's 2x2 pixels share
     * (R, avg(G1,G2), B). Block-constant by design — darktable blends
     * against the true source CFA at 0%; this is its RGB-domain equivalent,
     * since our bayer-model output is already demosaiced.
     */
    fun expandPackedToRgb(packed4: FloatArray, width: Int, height: Int): FloatArray {
        require(width % 2 == 0 && height % 2 == 0) { "Bayer crop must have even dimensions" }
        val w2 = width / 2
        val h2 = height / 2
        require(packed4.size == w2 * h2 * 4) { "Packed buffer size mismatch" }
        val rgb = FloatArray(width * height * 3)
        for (qy in 0 until h2) for (qx in 0 until w2) {
            val qi = qy * w2 + qx
            val r = packed4[qi * 4].coerceAtLeast(0f)
            val g = ((packed4[qi * 4 + 1] + packed4[qi * 4 + 2]) * 0.5f).coerceAtLeast(0f)
            val b = packed4[qi * 4 + 3].coerceAtLeast(0f)
            for (dy in 0..1) for (dx in 0..1) {
                val o = (((qy * 2 + dy) * width) + qx * 2 + dx) * 3
                rgb[o] = r
                rgb[o + 1] = g
                rgb[o + 2] = b
            }
        }
        return rgb
    }
}

/**
 * Single-frame AI Bayer denoiser (RawNIND via NCNN, GPU-first).
 *
 * Two model variants (see RawNindNcnnProcessor):
 * - tiny: consumes a normalized post-lens-shading CFA (the exact training
 *   domain), reorders any Bayer pattern to RGGB-canonical, runs tiled
 *   inference, and returns a denoised CFA restored to its original phase
 *   (safe for AMaZE and FloatCfaDngWriter).
 * - bayer (UtNet2): same normalized CFA in, denoised+demosaiced camRGB at
 *   full Bayer resolution out ([denoiseBayerRgb]); arbitrary sensor gain, no
 *   sigma plane; output bypasses AMaZE.
 *
 * The capture CFA path uses tiny when available, otherwise projects the
 * bundled Bayer model back onto the original CFA sites for the existing
 * denoised-DNG/JPEG path. Learned gain is matched before strength blending.
 * Both return null when their model is unavailable or inference
 * fails/OOMs — callers fall back to the plain (non-denoised) path.
 */
class RawNindDenoiser internal constructor(private val processor: RawNindNcnnProcessor) {
    constructor(context: Context) : this(RawNindNcnnProcessor.start(context.applicationContext))

    /** Per-stage cost of the last [denoise] call (any thread). */
    data class Timings(val packMs: Long, val inferMs: Long, val unpackMs: Long)
    @Volatile var lastTimings = Timings(0, 0, 0)
        private set

    /** Non-blocking: true only after the tiny background model load succeeded. */
    fun isReady(): Boolean = processor.isReady

    /** Non-blocking: true only after the bayer background model load succeeded. */
    fun isBayerReady(): Boolean = processor.isBayerReady

    /**
     * @param strength darktable raw-strength blend in [0,1] (0 = source CFA,
     * 1 = full model output), applied per sample after inference.
     */
    fun denoise(
        cfa: UnpackedRawCfa,
        noiseModel: CfaNoiseModel,
        strength: Float = 1f
    ): UnpackedRawCfa? {
        try {
            cfa.requireAmazeCompatible()
            if (strength == 0f) return cfa
            if (!processor.waitReady(30_000)) return denoiseBayerCfa(cfa, strength)
            var t = System.nanoTime()
            val packed4 = RawNindPack.packCanonical(cfa.values, cfa.width, cfa.height, cfa.pattern)
            val w2 = cfa.width / 2
            val h2 = cfa.height / 2
            var mean = 0.0
            for (v in cfa.values) mean += v.coerceAtLeast(0f).toDouble()
            mean /= cfa.values.size.toDouble()
            val sigma = RawNindPack.sigmaFor(
                mean.toFloat(), noiseModel.averageScale, noiseModel.averageOffset)
            val packed5 = ByteBuffer.allocateDirect(w2 * h2 * 5 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until w2 * h2) {
                packed5.put(packed4[i * 4])
                packed5.put(packed4[i * 4 + 1])
                packed5.put(packed4[i * 4 + 2])
                packed5.put(packed4[i * 4 + 3])
                packed5.put(sigma)
            }
            packed5.rewind()
            val packMs = (System.nanoTime() - t) / 1_000_000

            t = System.nanoTime()
            val outBuf = processor.runInference(packed5, w2, h2) ?: run {
                lastTimings = Timings(packMs, (System.nanoTime() - t) / 1_000_000, 0)
                return null
            }
            val inferMs = (System.nanoTime() - t) / 1_000_000

            t = System.nanoTime()
            val fb = outBuf.asFloatBuffer()
            val out4 = FloatArray(w2 * h2 * 4)
            fb.get(out4)
            // darktable raw-strength: uniform blend against the packed source.
            val blended4 = RawNindBlend.mix(packed4, out4, strength)
            val values = RawNindPack.unpackCanonical(blended4, cfa.width, cfa.height, cfa.pattern)
            for (i in values.indices) values[i] = sanitize(values[i], cfa.values[i])
            lastTimings = Timings(packMs, inferMs, (System.nanoTime() - t) / 1_000_000)
            Log.i(LOG_TAG, "AI denoise ${cfa.width}x${cfa.height} " +
                "pack=${lastTimings.packMs}ms infer=${lastTimings.inferMs}ms " +
                "unpack=${lastTimings.unpackMs}ms sigma=$sigma strength=$strength")
            return cfa.copy(values = values)
        } catch (oom: OutOfMemoryError) {
            // Full-frame packed buffers peak near ~17B/px; low-RAM devices fall
            // back to the plain path instead of dying on the writer thread.
            Log.w(LOG_TAG, "AI denoise OOM, falling back", oom)
            return null
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "AI denoise failed, falling back", failure)
            return null
        }
    }

    /** Bundled Bayer model feeds the existing denoise-once CFA save path. */
    private fun denoiseBayerCfa(cfa: UnpackedRawCfa, strength: Float): UnpackedRawCfa? {
        if (!processor.waitBayerReady(30_000)) return null
        val started = System.nanoTime()
        val packed = RawNindPack.packCanonical(cfa.values, cfa.width, cfa.height, cfa.pattern)
        for (i in packed.indices) packed[i] = packed[i].coerceIn(0f, 1f)
        val direct = ByteBuffer.allocateDirect(packed.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        direct.put(packed).rewind()
        val packedAt = System.nanoTime()
        val output = processor.runInferenceBayer(direct, cfa.width / 2, cfa.height / 2) ?: return null
        val inferredAt = System.nanoTime()
        val outputFloats = output.asFloatBuffer()
        val gain = RawNindPack.bayerOutputGain(outputFloats, packed)
        val result = RawNindPack.bayerRgbToCfa(outputFloats, cfa, strength, gain)
        lastTimings = Timings((packedAt - started) / 1_000_000,
            (inferredAt - packedAt) / 1_000_000, (System.nanoTime() - inferredAt) / 1_000_000)
        Log.i(LOG_TAG, "AI Bayer CFA denoise ${cfa.width}x${cfa.height} " +
            "infer=${lastTimings.inferMs}ms strength=$strength")
        return result
    }

    private fun sanitize(v: Float, fallback: Float): Float =
        if (v.isFinite()) v.coerceAtLeast(0f) else fallback.coerceAtLeast(0f)

    /**
     * Bayer-model denoise+demosaic: normalized pre-demosaic CFA (any Bayer
     * pattern, reordered to RGGB-canonical like [denoise]) to full-resolution
     * denoised camRGB. Returns null when the bayer model is unavailable or
     * inference fails/OOMs — callers fall back to the plain path.
     *
     * @param strength darktable raw-strength blend in [0,1] (0 = naive
     * source RGB, 1 = full model output), applied per sample after inference.
     */
    fun denoiseBayerRgb(cfa: UnpackedRawCfa, strength: Float = 1f): BayerDenoisedRgb? {
        try {
            cfa.requireAmazeCompatible()
            var t = System.nanoTime()
            val packed4 = RawNindPack.packCanonical(cfa.values, cfa.width, cfa.height, cfa.pattern)
            for (i in packed4.indices) packed4[i] = packed4[i].coerceIn(0f, 1f)
            val w2 = cfa.width / 2
            val h2 = cfa.height / 2
            val direct = ByteBuffer.allocateDirect(w2 * h2 * 4 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            direct.put(packed4)
            direct.rewind()
            val packMs = (System.nanoTime() - t) / 1_000_000

            t = System.nanoTime()
            val outBuf = processor.runInferenceBayer(direct, w2, h2) ?: run {
                lastTimings = Timings(packMs, (System.nanoTime() - t) / 1_000_000, 0)
                return null
            }
            val inferMs = (System.nanoTime() - t) / 1_000_000

            t = System.nanoTime()
            val ow = cfa.width
            val oh = cfa.height
            val fb = outBuf.asFloatBuffer()
            val gain = RawNindPack.bayerOutputGain(fb, packed4)
            val rgb = FloatArray(ow * oh * 3)
            fb.get(rgb)
            for (i in rgb.indices) {
                val v = (rgb[i] * gain).toFloat()
                rgb[i] = if (v.isFinite()) v.coerceAtLeast(0f) else 0f
            }
            // darktable raw-strength: uniform blend against the naive source
            // RGB (block-constant quad expansion); no re-inference.
            val s = strength.coerceIn(0f, 1f)
            val final = if (s >= 1f) {
                rgb
            } else {
                RawNindBlend.mix(RawNindBlend.expandPackedToRgb(packed4, ow, oh), rgb, s)
            }
            lastTimings = Timings(packMs, inferMs, (System.nanoTime() - t) / 1_000_000)
            Log.i(LOG_TAG, "AI bayer denoise ${cfa.width}x${cfa.height} " +
                "pack=${lastTimings.packMs}ms infer=${lastTimings.inferMs}ms " +
                "unpack=${lastTimings.unpackMs}ms strength=$s")
            return BayerDenoisedRgb(ow, oh, final)
        } catch (oom: OutOfMemoryError) {
            // Full-res 3ch float output peaks near ~12B/px plus the packed
            // input; low-RAM devices fall back instead of dying.
            Log.w(LOG_TAG, "AI bayer denoise OOM, falling back", oom)
            return null
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "AI bayer denoise failed, falling back", failure)
            return null
        }
    }

    companion object {
        private const val LOG_TAG = "RawLensAiDenoise"
    }
}
