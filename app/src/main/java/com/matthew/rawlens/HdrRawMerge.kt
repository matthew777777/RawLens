// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-FileCopyrightText: 2010-2026 darktable developers
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Dense displacement from reference coordinates into a moving frame, in full RAW pixels. */
fun interface HdrFlowField {
    fun displacement(x: Int, y: Int): Pair<Float, Float>
}

data class HdrMergeFrame(
    val cfa: UnpackedRawCfa,
    val exposureTimeNanos: Long,
    val sensitivityIso: Int,
    val aperture: Float = 1f,
    /** Reference-to-this-frame flow. Null means identity (the reference frame). */
    val flow: HdrFlowField? = null,
    val focalLength: Float = 1f
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
 * Flow is sampled in CFA-safe two-pixel steps so a warped sample never changes Bayer colour.
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
        var whiteLevel = 0f
        val ordered = listOf(frames[referenceIndex]) + frames.filterIndexed { i, _ -> i != referenceIndex }
        for (frame in ordered) {
            val apertureArea = Math.PI.toFloat() * sq(0.5f * frame.focalLength / frame.aperture)
            val seconds = frame.exposureTimeNanos * 1e-9f
            val calibration = 100f / (apertureArea * seconds * frame.sensitivityIso)
            val photonCount = 100f * apertureArea * seconds / frame.sensitivityIso
            whiteLevel = max(whiteLevel, calibration)
            // Warp once per frame, like darktable's aligned_buf. The 3x3 envelope then reads
            // the same registered mosaic without running flow interpolation ten times per pixel.
            val registered = if (frame.flow == null) frame else frame.copy(
                cfa = frame.cfa.copy(values = FloatArray(count) { i ->
                    sampleCfaSafe(frame.cfa, frame.flow, i % reference.width, i / reference.width)
                }), flow = null)
            accumulate(registered, calibration, photonCount, whiteLevel, pixels, weights)
        }
        // Preserve upstream's progressive clipped fallback and final nonnegative normalization.
        for (i in pixels.indices) if (weights[i] > 0f)
            pixels[i] = max(0f, pixels[i] / (weights[i] * whiteLevel))
        return reference.copy(values = pixels)
    }

    private fun accumulate(
        frame: HdrMergeFrame,
        calibration: Float,
        photonCount: Float,
        whiteLevel: Float,
        pixels: FloatArray,
        weights: FloatArray
    ) {
        val cfa = frame.cfa
        val width = cfa.width
        val height = cfa.height
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val sample = sampleCfaSafe(cfa, frame.flow, x, y)
            val qx = x and -2
            val qy = y and -2
            var maximum = 0f
            var minimum = Float.MAX_VALUE
            if (qx < width - 2 && qy < height - 2) {
                for (dy in 0..2) for (dx in 0..2) {
                    val v = sampleCfaSafe(cfa, frame.flow, qx + dx, qy + dy)
                    maximum = max(maximum, v)
                    minimum = min(minimum, v)
                }
            }
            var weight = photonCount
            if (minimum != Float.MAX_VALUE) weight *= EPS_WEIGHT + envelope(maximum + QUANTIZATION_MARGIN)
            if (maximum + QUANTIZATION_MARGIN >= 1f) {
                if (weights[i] <= 0f && (weights[i] == 0f || minimum < -weights[i])) {
                    pixels[i] = if (minimum + QUANTIZATION_MARGIN >= 1f) 1f else sample * calibration / whiteLevel
                    weights[i] = -minimum
                }
            } else {
                if (weights[i] <= 0f) { pixels[i] = 0f; weights[i] = 0f }
                pixels[i] += weight * sample * calibration
                weights[i] += weight
            }
        }
    }

    internal fun sampleCfaSafe(cfa: UnpackedRawCfa, flow: HdrFlowField?, x: Int, y: Int): Float {
        if (flow == null) return cfa.values[y * cfa.width + x]
        val (dx, dy) = flow.displacement(x, y)
        require(dx.isFinite() && dy.isFinite()) { "Invalid FlowNet displacement" }
        // Bayer parity must be preserved. Round displacement to the nearest complete CFA cell.
        val sx = (x.toFloat() + 2f * kotlin.math.round(dx / 2f))
            .coerceIn((x and 1).toFloat(), (cfa.width - 2 + (x and 1)).toFloat()).toInt()
        val sy = (y.toFloat() + 2f * kotlin.math.round(dy / 2f))
            .coerceIn((y and 1).toFloat(), (cfa.height - 2 + (y and 1)).toFloat()).toInt()
        return cfa.values[sy * cfa.width + sx]
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
}
