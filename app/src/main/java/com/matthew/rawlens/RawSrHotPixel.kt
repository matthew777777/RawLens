// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteOrder

/**
 * Hot-pixel pre-mask (Sabre `suppress_hot_pixels_bayer` analogue).
 * Implements [docs/raw-sr-hotpixels.md]; the document is normative.
 *
 * A stuck-bright sensor tap carries no scene signal, yet a single tap is
 * enough to corrupt everything downstream: the kernel means that smear it
 * into neighbours, the burst-nearest fallback that re-emits it, and the
 * third-party demosaic that reads it as chroma. The mask marks such taps so
 * the guide rails their quads (robustness forces exact-zero weight with an
 * explicit flag) and the frame samples are inpainted before fusion.
 *
 * Detection runs in the code domain on raw sensor codes, the same integer
 * inputs the GPU `hot_mask.glsl` pass consumes, so the CPU oracle and the
 * GPU pass agree exactly on unambiguous spikes. Bright-only by decision:
 * stuck-dark taps stay untouched (see the doc for the rationale).
 *
 * Geometry note: in a 3x3 window the center tap has zero same-colour
 * neighbours (2x2 Bayer repeat), so the comparison ring is the 5x5
 * same-phase ring — the eight taps at even offsets (±2,0), (0,±2),
 * (±2,±2) — which always share the center's CFA phase and colour.
 *
 * Scalar arithmetic over flat arrays; row-sharded over the shared worker
 * pool with disjoint rows (bitwise-identical at any worker count).
 */
object RawSrHotPixel {
    /**
     * Outlier gate in noise sigmas. A mid-tone tap at 6σ above its
     * same-colour neighbourhood is beyond any plausible photon fluctuation;
     * the absolute floor below keeps the gate from firing on read-noise
     * ripple in deep shadow.
     */
    const val HOT_SIGMA = 6.0

    /** Absolute floor in normalized units (fraction of the white level). */
    const val HOT_ABS_FLOOR = 0.02

    /**
     * Minimum distinct in-bounds ring taps. Exact-corner pixels only see
     * three ring taps and are never flagged; they sit at the crop boundary
     * where fusion has no support anyway.
     */
    const val MIN_RING_TAPS = 4

    /** Same-phase ring offsets: even displacements preserve CFA phase. */
    internal val RING = arrayOf(
        intArrayOf(-2, -2), intArrayOf(0, -2), intArrayOf(2, -2),
        intArrayOf(-2, 0), intArrayOf(2, 0),
        intArrayOf(-2, 2), intArrayOf(0, 2), intArrayOf(2, 2)
    )

    /**
     * Core detection over an integer code plane. A tap is hot only when it
     * clears the gate against BOTH its ring mean and its ring maximum: a
     * stuck-high tap is the local maximum of its colour plane, while texture
     * and step edges routinely clear a mean-only gate on one side. [sigmaAt]
     * returns the code-domain noise sigma for the tap, or NaN when no valid
     * model covers it (that tap is never flagged). Returns one flag per
     * pixel, row-major.
     */
    fun detect(
        width: Int,
        height: Int,
        codeAt: (x: Int, y: Int) -> Int,
        white: Double,
        sigmaAt: (x: Int, y: Int, code: Int) -> Double
    ): BooleanArray {
        require(width > 0 && height > 0)
        require(white.isFinite())
        val mask = BooleanArray(width * height)
        val absFloor = HOT_ABS_FLOOR * white
        RawSrWorkers.forEachShard(height) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until width) {
                val code = codeAt(x, y).toDouble()
                val sigma = sigmaAt(x, y, codeAt(x, y))
                if (!code.isFinite() || !sigma.isFinite() || sigma <= 0.0) continue
                var sum = 0.0
                var max = Double.NEGATIVE_INFINITY
                var count = 0
                for (tap in RING) {
                    val nx = x + tap[0]
                    val ny = y + tap[1]
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val v = codeAt(nx, ny).toDouble()
                    sum += v
                    if (v > max) max = v
                    count++
                }
                if (count < MIN_RING_TAPS) continue
                val gate = maxOf(HOT_SIGMA * sigma, absFloor)
                if (code - sum / count > gate && code - max > gate) {
                    mask[y * width + x] = true
                }
            }
        }
        return mask
    }

    /**
     * Detection for one burst frame, reading raw codes exactly like
     * [RawSrRobustness.linearGuide]. Null, invalid, or zero-noise profiles
     * yield an empty mask (no model, no gate — never a fabricated flag).
     */
    fun detectPacked(frame: RawSrPackedFrame): BooleanArray {
        val input = frame.uploadInput()
        val tables = RawSrCovarianceGuide.noiseTables(input, frame.noiseProfile)
        val valid = tables.modelClass == RawSrCovarianceGuide.ModelClass.VALID ||
            tables.modelClass == RawSrCovarianceGuide.ModelClass.ZERO_SHOT
        val width = input.crop.width
        val height = input.crop.height
        if (!valid) return BooleanArray(width * height)
        val source = input.buffer.duplicate().order(ByteOrder.nativeOrder())
        val base = source.position()
        val crop = input.crop
        fun codeAt(x: Int, y: Int): Int {
            val offset = base + (crop.top + y) * input.layout.rowStride + (crop.left + x) * 2
            return source.getShort(offset).toInt() and 65535
        }
        return detect(
            width, height,
            ::codeAt,
            white = input.normalization.whiteLevel.toDouble(),
            sigmaAt = { x, y, code ->
                // Crop-local phase order matches the preprocess black-level
                // order, exactly like linear_guide.glsl.
                val phase = ((y and 1) shl 1) or (x and 1)
                val variance = tables.slope[phase] * code + tables.offset[phase]
                if (variance.isFinite() && variance > 0.0) kotlin.math.sqrt(variance)
                else Double.NaN
            }
        )
    }

    /**
     * Inpaint masked taps in normalized samples ahead of fusion. Each
     * masked tap takes the mean of its unmasked finite same-colour ring
     * taps; taps whose whole ring is masked or out of range (hot clusters,
     * extreme corners) keep their original sample. Replacements are
     * computed from the original plane and applied afterwards, so clustered
     * hot taps can never feed each other. Returns the inpainted count.
     *
     * [pattern] is the phase at samples[0] (already origin-shifted, exactly
     * [UnpackedRawCfa.pattern]); the ring uses even offsets, which preserve
     * CFA phase, so lookups are crop-relative and need no origin.
     */
    fun inpaintNormalized(
        samples: FloatArray,
        mask: BooleanArray,
        width: Int,
        height: Int,
        pattern: BayerPattern
    ): Int {
        require(samples.size == width * height) { "Samples truncated" }
        require(mask.size == width * height) { "Mask must cover the frame" }
        var count = 0
        val replacement = FloatArray(width * height)
        val pending = BooleanArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val p = y * width + x
            if (!mask[p]) continue
            val want = pattern.colorAt(x, y)
            var sum = 0.0
            var taps = 0
            for (tap in RING) {
                val nx = x + tap[0]
                val ny = y + tap[1]
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                if (pattern.colorAt(nx, ny) != want) continue
                val q = ny * width + nx
                if (mask[q]) continue
                val v = samples[q].toDouble()
                if (!v.isFinite()) continue
                sum += v
                taps++
            }
            if (taps == 0) continue
            replacement[p] = (sum / taps).toFloat()
            pending[p] = true
            count++
        }
        for (p in samples.indices) if (pending[p]) samples[p] = replacement[p]
        return count
    }

    /**
     * Quad-grid projection of a full-res mask: a quad is hot when any of
     * its four taps is masked. Mirrors the GPU robustness gate, which reads
     * the same four taps from the mask texture.
     */
    fun quadHot(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        require(width % 2 == 0 && height % 2 == 0) { "Hot mask quad reduction needs complete quads" }
        require(mask.size == width * height) { "Mask must cover the frame" }
        val outW = width / 2
        val outH = height / 2
        val hot = BooleanArray(outW * outH)
        for (qy in 0 until outH) for (qx in 0 until outW) {
            val x = qx * 2
            val y = qy * 2
            hot[qy * outW + qx] = mask[y * width + x] || mask[y * width + x + 1] ||
                mask[(y + 1) * width + x] || mask[(y + 1) * width + x + 1]
        }
        return hot
    }
}
