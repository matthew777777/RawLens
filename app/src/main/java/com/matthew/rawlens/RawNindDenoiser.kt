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
        val quads = Array(4) { FloatArray(w2 * h2) }
        for (qy in 0 until h2) for (qx in 0 until w2) {
            val qi = qy * w2 + qx
            val base = (qy * 2) * width + qx * 2
            quads[0][qi] = values[base]
            quads[1][qi] = values[base + 1]
            quads[2][qi] = values[base + width]
            quads[3][qi] = values[base + width + 1]
        }
        val out = FloatArray(w2 * h2 * 4)
        for (qi in 0 until w2 * h2) for (k in 0..3) {
            out[qi * 4 + perm[k]] = quads[k][qi]
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

    /** Per-patch noise sigma from the DNG profile averages (normalized domain). */
    fun sigmaFor(mean: Float, avgScale: Float, avgOffset: Float): Float {
        val m = mean.coerceAtLeast(0f)
        return sqrt(avgScale * m + avgOffset)
    }
}

/**
 * Single-frame AI Bayer denoiser (RawNIND-tiny via NCNN, GPU-first).
 *
 * Consumes a normalized post-lens-shading CFA (the exact training domain),
 * reorders any Bayer pattern to RGGB-canonical, runs tiled inference, and
 * returns a denoised CFA that is always RGGB in the local frame (safe for
 * AMaZE and FloatCfaDngWriter, which both read the pattern in local
 * coordinates). Returns null when the model is unavailable or inference
 * fails/OOMs — callers fall back to the plain (non-denoised) path.
 */
class RawNindDenoiser(context: Context) {
    private val processor = RawNindNcnnProcessor.start(context.applicationContext)

    /** Per-stage cost of the last [denoise] call (any thread). */
    data class Timings(val packMs: Long, val inferMs: Long, val unpackMs: Long)
    @Volatile var lastTimings = Timings(0, 0, 0)
        private set

    /** Non-blocking: true only after the background model load succeeded. */
    fun isReady(): Boolean = processor.isReady

    fun denoise(cfa: UnpackedRawCfa, noiseModel: CfaNoiseModel): UnpackedRawCfa? {
        try {
            cfa.requireAmazeCompatible()
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
            val values = FloatArray(cfa.width * cfa.height)
            for (qy in 0 until h2) for (qx in 0 until w2) {
                val qi = qy * w2 + qx
                val base = (qy * 2) * cfa.width + qx * 2
                // Canonical [R,G1,G2,B] -> RGGB local mosaic.
                values[base] = sanitize(out4[qi * 4], cfa.values[base])
                values[base + 1] = sanitize(out4[qi * 4 + 1], cfa.values[base + 1])
                values[base + cfa.width] = sanitize(out4[qi * 4 + 2], cfa.values[base + cfa.width])
                values[base + cfa.width + 1] =
                    sanitize(out4[qi * 4 + 3], cfa.values[base + cfa.width + 1])
            }
            lastTimings = Timings(packMs, inferMs, (System.nanoTime() - t) / 1_000_000)
            Log.i(LOG_TAG, "AI denoise ${cfa.width}x${cfa.height} " +
                "pack=${lastTimings.packMs}ms infer=${lastTimings.inferMs}ms " +
                "unpack=${lastTimings.unpackMs}ms sigma=$sigma")
            return UnpackedRawCfa(
                cfa.width, cfa.height, BayerPattern.RGGB, values,
                cfa.sourceCrop, cfa.sensorCropLeft, cfa.sensorCropTop
            )
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

    private fun sanitize(v: Float, fallback: Float): Float =
        if (v.isFinite()) v.coerceAtLeast(0f) else fallback.coerceAtLeast(0f)

    companion object {
        private const val LOG_TAG = "RawLensAiDenoise"
    }
}
