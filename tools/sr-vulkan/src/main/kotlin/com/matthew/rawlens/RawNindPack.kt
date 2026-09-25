// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// VERBATIM LIFT from app/.../RawNindDenoiser.kt:13-137 (the object documents
// itself as Android-free). Needed by RawSrKernelNetAniso.sigmaFor.
// tools/parity_sr_vulkan.py diffs the LIFT region on every check.
package com.matthew.rawlens

import kotlin.math.sqrt

// LIFT-BEGIN (verbatim, parity-checked)
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
// LIFT-END
