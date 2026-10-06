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
 * Pure packed-Bayer helpers for the RawNIND denoisers.
 *
 * Canonical packed order is [R, G1, G2, B] (G1 = green on even rows), packed
 * channel-last per quad: `packed[(qy * w2 + qx) * 4 + c]`. This matches
 * python/rawnind-train/dng_loader.py (packed_to_rggb) and the CfaNoiseModel
 * CFA order (R, Gr, Gb, B). NOTE: python/burst-ref uses [R,G1,B,G2]; the two
 * must never be mixed without permuting.
 *
 * Two RGGB-unification policies share that order, and they must not be
 * confused: [packCanonical] permutes packed channels on the native grid
 * (correct only for same-shape packed-to-packed nets — the tiny model),
 * while [bayerPackShifted] shifts the packing grid to the R site
 * ([bayerOrigin]) and packs without permutation (required by the
 * upsampling Bayer model, whose PixelShuffle tail assigns learned RGGB
 * subpixel geometry; feeding it permuted quads re-mosaics into
 * maze/zipper). Mirrors darktable's FORCE_RGGB packing in
 * src/common/ai/restore_raw_bayer.c.
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

    /** R-site origin (row, col) of the local 2x2 pattern: shifting the Bayer
     * packing grid there makes every packed quad natively RGGB. Mirrors
     * darktable's `_bayer_origin` (restore_raw_bayer.c).
     */
    fun bayerOrigin(pattern: BayerPattern): Pair<Int, Int> = when (pattern) {
        BayerPattern.RGGB -> 0 to 0
        BayerPattern.GRBG -> 0 to 1
        BayerPattern.GBRG -> 1 to 0
        BayerPattern.BGGR -> 1 to 1
    }

    /** RGGB-phase packed input for the upsampling Bayer model ([bayerOrigin]
     * grid, no channel permutation) with its working geometry. Pixels
     * outside `[y0, y0 + 2*h2) x [x0, x0 + 2*w2)` are margins the model
     * never sees; [bayerRgbToCfa] leaves them at source values.
     */
    data class BayerShiftedPack(
        val y0: Int,
        val x0: Int,
        val w2: Int,
        val h2: Int,
        val packed: FloatArray
    )

    /** (H, W) Bayer -> channel-last (h2*w2*4) RGGB-phase [R,G1,G2,B], where
     * the packing grid starts at the R site so packed channel k always
     * holds the RGGB slot-k color. For RGGB this equals [packCanonical].
     */
    fun bayerPackShifted(
        values: FloatArray,
        width: Int,
        height: Int,
        pattern: BayerPattern
    ): BayerShiftedPack {
        require(width % 2 == 0 && height % 2 == 0) { "Bayer crop must have even dimensions" }
        require(values.size == width * height) { "CFA buffer size mismatch" }
        val (y0, x0) = bayerOrigin(pattern)
        val w2 = (width - x0) / 2
        val h2 = (height - y0) / 2
        require(w2 > 0 && h2 > 0) { "Bayer crop is too small for its R-site origin" }
        val out = FloatArray(w2 * h2 * 4)
        for (qy in 0 until h2) for (qx in 0 until w2) {
            val qi = qy * w2 + qx
            val base = (y0 + qy * 2) * width + x0 + qx * 2
            // Unpermuted: on the R-shifted grid block position k IS RGGB slot k.
            out[qi * 4] = values[base]
            out[qi * 4 + 1] = values[base + 1]
            out[qi * 4 + 2] = values[base + width]
            out[qi * 4 + 3] = values[base + width + 1]
        }
        return BayerShiftedPack(y0, x0, w2, h2, out)
    }

    /** Project Bayer model RGB back onto the source CFA sites at their own
     * sensor positions (channel of the site's color, sampled at the site).
     * The model output aligns to the [bayerOrigin]-shifted working grid,
     * so working pixel (y, x) reads model pixel (y - y0, x - x0); margin
     * pixels keep source values. Absolute buffer reads avoid a second
     * full-resolution RGB heap copy. Strength is blended in CFA space so
     * zero strength preserves the source, and other settings do not
     * introduce block-constant RGB into AMaZE.
     */
    fun bayerRgbToCfa(rgb: java.nio.FloatBuffer, source: UnpackedRawCfa, strength: Float, gain: Double = 1.0): UnpackedRawCfa {
        source.requireAmazeCompatible()
        require(strength.isFinite() && strength in 0f..1f && gain.isFinite())
        if (strength == 0f) return source
        val (y0, x0) = bayerOrigin(source.pattern)
        val w2 = (source.width - x0) / 2
        val h2 = (source.height - y0) / 2
        require(w2 > 0 && h2 > 0) { "Bayer crop is too small for its R-site origin" }
        require(rgb.limit().toLong() >= 2L * w2 * 2 * h2 * 3) { "Model RGB is too small for the working grid" }
        val values = source.values.copyOf()
        val mw = w2 * 2
        for (wy in 0 until h2 * 2) {
            val y = y0 + wy
            for (wx in 0 until w2 * 2) {
                val x = x0 + wx
                // Working grid is RGGB by construction: TL=R, TR/BL=G, BR=B.
                val color = when ((wy and 1) * 2 + (wx and 1)) {
                    0 -> 0
                    3 -> 2
                    else -> 1
                }
                val denoised = (rgb.get((wy * mw + wx) * 3 + color) * gain).toFloat()
                val target = y * source.width + x
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
 * - bayer (UtNet2): same normalized CFA in, RGGB-phase packed on the
 *   R-shifted grid ([RawNindPack.bayerPackShifted], never channel-permuted:
 *   the PixelShuffle tail needs RGGB subpixel geometry), denoised+
 *   demosaiced camRGB at working resolution out ([denoiseBayerRgb]);
 *   arbitrary sensor gain, no sigma plane; output bypasses AMaZE.
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
        val pack = RawNindPack.bayerPackShifted(cfa.values, cfa.width, cfa.height, cfa.pattern)
        val packed = pack.packed
        for (i in packed.indices) packed[i] = packed[i].coerceIn(0f, 1f)
        val direct = ByteBuffer.allocateDirect(packed.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        direct.put(packed).rewind()
        val packedAt = System.nanoTime()
        val output = processor.runInferenceBayer(direct, pack.w2, pack.h2) ?: return null
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
     * pattern, RGGB-phase packed like [denoiseBayerCfa]) to full-resolution
     * denoised camRGB aligned to the sensor grid. Returns null when the
     * bayer model is unavailable or inference fails/OOMs — callers fall
     * back to the plain path.
     *
     * @param strength darktable raw-strength blend in [0,1] (0 = naive
     * source RGB, 1 = full model output), applied per sample after inference.
     */
    fun denoiseBayerRgb(cfa: UnpackedRawCfa, strength: Float = 1f): BayerDenoisedRgb? {
        try {
            cfa.requireAmazeCompatible()
            var t = System.nanoTime()
            val pack = RawNindPack.bayerPackShifted(cfa.values, cfa.width, cfa.height, cfa.pattern)
            val packed4 = pack.packed
            for (i in packed4.indices) packed4[i] = packed4[i].coerceIn(0f, 1f)
            val direct = ByteBuffer.allocateDirect(packed4.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            direct.put(packed4)
            direct.rewind()
            val packMs = (System.nanoTime() - t) / 1_000_000

            t = System.nanoTime()
            val outBuf = processor.runInferenceBayer(direct, pack.w2, pack.h2) ?: run {
                lastTimings = Timings(packMs, (System.nanoTime() - t) / 1_000_000, 0)
                return null
            }
            val inferMs = (System.nanoTime() - t) / 1_000_000

            t = System.nanoTime()
            val ow = cfa.width
            val oh = cfa.height
            val fb = outBuf.asFloatBuffer()
            val gain = RawNindPack.bayerOutputGain(fb, packed4)
            // Sensor-aligned model RGB: the naive source expansion is the
            // base (margin pixels keep it; it is also the strength-0
            // endpoint), working pixels come from the model output placed
            // at its sensor positions.
            val native4 = RawNindPack.packCanonical(cfa.values, cfa.width, cfa.height, cfa.pattern)
            for (i in native4.indices) native4[i] = native4[i].coerceIn(0f, 1f)
            val base = RawNindBlend.expandPackedToRgb(native4, ow, oh)
            val rgb = base.copyOf()
            val mw = pack.w2 * 2
            for (wy in 0 until pack.h2 * 2) for (wx in 0 until pack.w2 * 2) {
                val o = ((pack.y0 + wy) * ow + (pack.x0 + wx)) * 3
                for (c in 0..2) {
                    val v = (fb.get((wy * mw + wx) * 3 + c) * gain).toFloat()
                    rgb[o + c] = if (v.isFinite()) v.coerceAtLeast(0f) else base[o + c]
                }
            }
            // darktable raw-strength: uniform blend against the naive source
            // RGB (block-constant quad expansion); no re-inference.
            val s = strength.coerceIn(0f, 1f)
            val final = if (s >= 1f) {
                rgb
            } else {
                RawNindBlend.mix(base, rgb, s)
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
