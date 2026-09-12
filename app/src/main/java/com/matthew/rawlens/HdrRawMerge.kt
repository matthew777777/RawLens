// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-FileCopyrightText: 2010-2026 darktable developers
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Dense displacement from reference coordinates into a moving frame, in full RAW pixels. */
fun interface HdrFlowField {
    /** X/Y float bits packed into a Long to keep full-resolution sampling allocation-free. */
    fun displacement(x: Int, y: Int): Long
}

internal fun packHdrDisplacement(x: Float, y: Float): Long =
    (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xffffffffL)

internal fun hdrDisplacementX(value: Long): Float = Float.fromBits((value ushr 32).toInt())
internal fun hdrDisplacementY(value: Long): Float = Float.fromBits(value.toInt())

data class HdrMergeFrame(
    val cfa: UnpackedRawCfa,
    val exposureTimeNanos: Long,
    val sensitivityIso: Int,
    val aperture: Float = 1f,
    /** Reference-to-this-frame flow. Null means identity (the reference frame). */
    val flow: HdrFlowField? = null,
    val focalLength: Float = 1f,
    /**
     * MFSR-style hard reject on the quad grid (see [HdrMfsrAssist.validateFlow]).
     * Quads with weight 0 contribute nothing, so misaligned edges stay
     * reference-sharp instead of averaging into a fringe. Null accepts everything
     * (legacy behavior).
     */
    val robustness: RawSrRobustness.FrameRobustness? = null
) {
    init {
        require(exposureTimeNanos > 0L)
        require(sensitivityIso > 0)
        require(aperture.isFinite() && aperture > 0f)
        require(focalLength.isFinite() && focalLength > 0f)
        require(cfa.values.size == Math.multiplyExact(cfa.width, cfa.height))
        require(cfa.values.all(Float::isFinite))
    }
}

/**
 * Darktable's RAW HDR radiance merge, adapted to an already-normalized Bayer mosaic.
 *
 * Matches control_jobs.c at 52435b9a0c6bcf470f683e0c4455ecd321b9aec5.
 * Output is normalized to the shortest exposure's saturation, as in darktable.
 * Flow uses bilinear interpolation on the same-colour CFA lattice.
 */
object HdrRawMerge {
    private const val EPS_WEIGHT = 1e-8f
    private const val QUANTIZATION_MARGIN = 3000f / 65535f

    fun merge(frames: List<HdrMergeFrame>, referenceIndex: Int = frames.size / 2): UnpackedRawCfa {
        require(frames.size >= 2) { "HDR merge requires at least two exposures" }
        require(referenceIndex in frames.indices)
        val reference = frames[referenceIndex].cfa
        reference.requireAmazeCompatible()
        require(frames.all {
            it.cfa.width == reference.width && it.cfa.height == reference.height &&
                it.cfa.pattern == reference.pattern &&
                it.cfa.sensorCropLeft == reference.sensorCropLeft &&
                it.cfa.sensorCropTop == reference.sensorCropTop
        }) { "HDR frames must have identical dimensions, crop, and CFA phase" }

        val count = reference.width * reference.height
        val pixels = FloatArray(count)
        val weights = FloatArray(count)
        // Darktable establishes the common output white before accumulating any image. This is
        // especially important when the neutral exposure (rather than the shortest) is first.
        val whiteLevel = frames.maxOf(::calibration)
        frames.forEach {
            it.robustness?.let { robust ->
                require(robust.width == reference.width / 2 && robust.height == reference.height / 2) {
                    "HDR robustness grid must match the quad grid"
                }
            }
        }
        val ordered = listOf(frames[referenceIndex]) + frames.filterIndexed { i, _ -> i != referenceIndex }
        for ((orderIndex, frame) in ordered.withIndex()) {
            val apertureArea = Math.PI.toFloat() * sq(0.5f * frame.focalLength / frame.aperture)
            val seconds = frame.exposureTimeNanos * 1e-9f
            val calibration = calibration(frame)
            val photonCount = 100f * apertureArea * seconds / frame.sensitivityIso
            // Sample the flow in-place. A full-size aligned copy costs another width*height*4
            // bytes (about 50 MB on current 12 MP sensors) while all three source mosaics and
            // the two merge accumulators are live. That exceeds Android's 256 MB heap on real
            // devices. The rolling registered rows below retain the same result at low peak RAM.
            // The reference (orderIndex 0) always accumulates; moving frames honor the hard
            // MFSR reject so misaligned edge quads cannot fringe or blur the reference.
            accumulate(frame, calibration, photonCount, pixels, weights, skipRejected = orderIndex != 0)
        }
        // A clipped measurement is only a lower bound on radiance. Selecting the frame
        // with the smallest neighborhood minimum (upstream's static-scene fallback) is
        // unsafe after warping: a dark adjacent tap can select a longer, clipped exposure
        // and create a 2/4-stop dark notch. Keep one fallback exposure for every CFA site.
        // The shortest exposure shares our output white; retain its aligned mosaic rather
        // than synthesizing white on some Bayer cells and exposure-scaled values on others.
        val fallback = frames.maxBy(::calibration)
        for (i in pixels.indices) {
            pixels[i] = if (weights[i] > 0f) max(0f, pixels[i] / (weights[i] * whiteLevel))
            else max(0f, sampleCfaSafe(fallback.cfa, fallback.flow,
                i % reference.width, i / reference.width))
        }
        suppressMotionDisagreement(reference, pixels, calibration(frames[referenceIndex]) / whiteLevel, weights)
        return reference.copy(values = pixels)
    }

    /**
     * Flow cannot describe disappearing foam, occlusion, or exposure-dependent motion blur.
     * Compare radiance at the same exposure scale and retain the reference in those regions.
     * One confidence for all four CFA sites avoids independent colour-channel switching.
     * This is a RawLens deghosting extension to the static-scene Darktable merge.
     */
    internal fun suppressMotionDisagreement(reference: UnpackedRawCfa, merged: FloatArray, scale: Float,
                                           mergeWeights: FloatArray? = null) {
        val width = reference.width
        for (y in 0 until reference.height step 2) for (x in 0 until width step 2) {
            var disagreement = 0f
            var usable = true
            for (dy in 0..1) for (dx in 0..1) {
                val i = (y + dy) * width + x + dx
                val raw = reference.values[i]
                // No radiometric comparison is possible when every merge envelope clipped.
                // Otherwise this pass can undo the safe shortest-exposure fallback at the
                // edge of a lamp and reintroduce a dark 2x2 reference patch.
                if (mergeWeights != null && mergeWeights[i] <= 0f) usable = false
                // Neither clipping nor deep black permits a reliable photometric comparison.
                if (raw >= 1f - QUANTIZATION_MARGIN || raw < 0.02f) usable = false
                val expected = max(0f, raw) * scale
                val tolerance = 0.02f * scale + 0.15f * expected
                disagreement = max(disagreement, abs(merged[i] - expected) / tolerance)
            }
            if (!usable || disagreement <= 1f) continue
            val t = (disagreement - 1f).coerceIn(0f, 1f)
            val referenceWeight = t * t * (3f - 2f * t)
            for (dy in 0..1) for (dx in 0..1) {
                val i = (y + dy) * width + x + dx
                val expected = max(0f, reference.values[i]) * scale
                merged[i] += referenceWeight * (expected - merged[i])
            }
        }
    }

    private fun rejected(frame: HdrMergeFrame, x: Int, y: Int, quadsWidth: Int): Boolean {
        val robust = frame.robustness ?: return false
        return robust.r[(y / 2) * quadsWidth + (x / 2)] <= 0f
    }

    private fun accumulate(
        frame: HdrMergeFrame,
        calibration: Float,
        photonCount: Float,
        pixels: FloatArray,
        weights: FloatArray,
        skipRejected: Boolean
    ) {
        val cfa = frame.cfa
        val width = cfa.width
        val height = cfa.height
        if (frame.flow != null) {
            accumulateWarped(frame, calibration, photonCount, pixels, weights, skipRejected)
            return
        }
        val quadsWidth = width / 2
        for (y in 0 until height) for (x in 0 until width) {
            if (skipRejected && rejected(frame, x, y, quadsWidth)) continue
            val i = y * width + x
            val sample = sampleCfaSafe(cfa, frame.flow, x, y)
            val qx = x and -2
            val qy = y and -2
            var maximum = 0f
            for (dy in 0..min(2, height - 1 - qy)) {
                for (dx in 0..min(2, width - 1 - qx)) {
                    val v = sampleCfaSafe(cfa, frame.flow, qx + dx, qy + dy)
                    maximum = max(maximum, v)
                }
            }
            accumulateSample(i, sample, maximum, calibration, photonCount, pixels, weights)
        }
    }

    /**
     * Materialize only the three registered rows needed by darktable's 3x3 envelope.
     * Adjacent two-row CFA blocks overlap by one row, but this still reduces flow lookups from
     * roughly ten per output pixel to 1.5 without allocating a full-resolution aligned mosaic.
     */
    private fun accumulateWarped(
        frame: HdrMergeFrame,
        calibration: Float,
        photonCount: Float,
        pixels: FloatArray,
        weights: FloatArray,
        skipRejected: Boolean
    ) {
        val cfa = frame.cfa
        val flow = requireNotNull(frame.flow)
        val width = cfa.width
        val height = cfa.height
        val quadsWidth = width / 2
        val rows = Array(3) { FloatArray(width) }
        val saturationRows = Array(3) { FloatArray(width) }
        for (qy in 0 until height step 2) {
            for (rowOffset in 0..2) {
                val sy = (qy + rowOffset).coerceAtMost(height - 1)
                val row = rows[rowOffset]
                for (x in 0 until width) {
                    row[x] = sampleCfaSafe(cfa, flow, x, sy)
                    saturationRows[rowOffset][x] = sampleCfaSafe(cfa, flow, x, sy, true)
                }
            }
            val lastY = min(qy + 1, height - 1)
            for (y in qy..lastY) for (x in 0 until width) {
                if (skipRejected && rejected(frame, x, y, quadsWidth)) continue
                val i = y * width + x
                val sample = rows[y - qy][x]
                val qx = x and -2
                var maximum = 0f
                for (dy in 0..min(2, height - 1 - qy)) {
                    for (dx in 0..min(2, width - 1 - qx)) {
                        maximum = max(maximum, saturationRows[dy][qx + dx])
                    }
                }
                accumulateSample(i, sample, maximum, calibration, photonCount, pixels, weights)
            }
        }
    }

    private fun accumulateSample(
        index: Int,
        sample: Float,
        maximum: Float,
        calibration: Float,
        photonCount: Float,
        pixels: FloatArray,
        weights: FloatArray
    ) {
        if (maximum + QUANTIZATION_MARGIN >= 1f) return
        val weight = photonCount * (EPS_WEIGHT + envelope(maximum + QUANTIZATION_MARGIN))
        pixels[index] += weight * sample * calibration
        weights[index] += weight
    }

    internal fun sampleCfaSafe(
        cfa: UnpackedRawCfa, flow: HdrFlowField?, x: Int, y: Int,
        saturationFootprint: Boolean = false
    ): Float {
        if (flow == null) return cfa.values[y * cfa.width + x]
        val displacement = flow.displacement(x, y)
        val dx = hdrDisplacementX(displacement)
        val dy = hdrDisplacementY(displacement)
        require(dx.isFinite() && dy.isFinite()) { "Invalid FlowNet displacement" }
        // Interpolate only between sites of identical Bayer parity. This preserves colour while
        // avoiding the two-pixel snapping contours visible around high-contrast edges and lamps.
        val parityX = x and 1
        val parityY = y and 1
        val maxGridX = (cfa.width - 1 - parityX) / 2
        val maxGridY = (cfa.height - 1 - parityY) / 2
        val gridX = ((x + dx - parityX) * 0.5f).coerceIn(0f, maxGridX.toFloat())
        val gridY = ((y + dy - parityY) * 0.5f).coerceIn(0f, maxGridY.toFloat())
        val x0g = floor(gridX).toInt(); val y0g = floor(gridY).toInt()
        val x1g = min(x0g + 1, maxGridX); val y1g = min(y0g + 1, maxGridY)
        val fx = gridX - x0g; val fy = gridY - y0g
        val x0 = parityX + x0g * 2; val x1 = parityX + x1g * 2
        val y0 = parityY + y0g * 2; val y1 = parityY + y1g * 2
        val a = cfa.values[y0 * cfa.width + x0]
        val b = cfa.values[y0 * cfa.width + x1]
        val c = cfa.values[y1 * cfa.width + x0]
        val d = cfa.values[y1 * cfa.width + x1]
        // Clipping is a property of the source measurements, not their interpolated average.
        // Only include taps with nonzero support; identity motion must remain identical.
        if (saturationFootprint) {
            var peak = a
            if (fx > 0f) peak = max(peak, b)
            if (fy > 0f) peak = max(peak, c)
            if (fx > 0f && fy > 0f) peak = max(peak, d)
            return peak
        }
        return (a * (1f - fx) + b * fx) * (1f - fy) +
            (c * (1f - fx) + d * fx) * fy
    }

    internal fun envelope(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            1f - sq(abs(x / 0.5f - 1f))
        } else {
            val t = (1f - x) / 0.5f
            3f * t * t - 2f * t * t * t
        }
    }

    private fun sq(x: Float) = x * x

    private fun calibration(frame: HdrMergeFrame): Float {
        val apertureArea = Math.PI.toFloat() * sq(0.5f * frame.focalLength / frame.aperture)
        return 100f / (apertureArea * frame.exposureTimeNanos * 1e-9f * frame.sensitivityIso)
    }
}
