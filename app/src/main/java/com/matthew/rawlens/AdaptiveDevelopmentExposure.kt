// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.ln
import kotlin.math.min

data class AdaptiveExposureResult(
    val correctionEv: Double,
    val logAverage: Double,
    val highlight: Double,
    val lowKey: Boolean
)

/** One instance is frozen per shutter press and shared by every frame in that logical capture. */
class SharedAdaptiveExposure {
    private var correctionEv: Double? = null

    @Synchronized
    fun resolve(analyze: () -> AdaptiveExposureResult): AdaptiveExposureResult {
        correctionEv?.let { return AdaptiveExposureResult(it, Double.NaN, Double.NaN, false) }
        return analyze().also { correctionEv = it.correctionEv }
    }
}

/** Robust global RAW exposure placement before the deterministic display transform. */
object AdaptiveDevelopmentExposure {
    class Workspace internal constructor() {
        // The historical floor-derived sampling stride can yield just under 2*MAX_SAMPLES.
        // Keep it unchanged so this allocation optimization cannot alter exposure placement.
        internal val samples = FloatArray(MAX_SAMPLES * 2)
    }

    fun workspace(): Workspace = Workspace()

    fun analyze(cfa: UnpackedRawCfa, workspace: Workspace = Workspace()): AdaptiveExposureResult {
        require(cfa.values.size == cfa.width * cfa.height)
        val stride = maxOf(1, cfa.values.size / MAX_SAMPLES)
        val samples = workspace.samples
        var count = 0
        var index = 0
        while (index < cfa.values.size) {
            val value = cfa.values[index]
            if (value.isFinite() && value > SHADOW_FLOOR) samples[count++] = value
            index += stride
        }
        return analyzeSamples(samples, count)
    }

    /** Samples packed RAW directly, applying the same normalization and lens gain as GLES. */
    fun analyzeRaw(
        source: ByteBuffer,
        layout: RawPlaneLayout,
        normalization: RawNormalization,
        crop: RawCrop,
        lensShading: LensShadingModel?,
        workspace: Workspace = Workspace()
    ): AdaptiveExposureResult {
        require(crop.left + crop.width <= layout.width && crop.top + crop.height <= layout.height)
        val input = source.duplicate().order(ByteOrder.nativeOrder())
        val origin = input.position()
        val pixelCount = crop.width * crop.height
        val stride = maxOf(1, pixelCount / MAX_SAMPLES)
        val samples = workspace.samples
        var count = 0
        var index = 0
        while (index < pixelCount) {
            val x = index % crop.width
            val y = index / crop.width
            val planeX = crop.left + x
            val planeY = crop.top + y
            val sensorX = layout.sensorOriginX + planeX
            val sensorY = layout.sensorOriginY + planeY
            val byteOffset = origin + planeY * layout.rowStride + planeX * layout.pixelStride
            val code = input.getShort(byteOffset).toInt() and 0xffff
            val black = normalization.blackAt(sensorX, sensorY)
            var value = (code - black) / (normalization.whiteLevel - black)
            if (lensShading != null && !lensShading.alreadyApplied) {
                value *= lensShading.gainAt(
                    sensorX, sensorY, normalization.sensorPattern.colorAt(sensorX, sensorY)
                )
            }
            if (value.isFinite() && value > SHADOW_FLOOR) samples[count++] = value
            index += stride
        }
        return analyzeSamples(samples, count)
    }

    private fun analyzeSamples(samples: FloatArray, count: Int): AdaptiveExposureResult {
        if (count < MIN_SAMPLES) {
            return AdaptiveExposureResult(0.0, 0.0, 0.0, false)
        }
        Arrays.sort(samples, 0, count)
        val low = percentile(samples, count, 0.05)
        val highForAverage = percentile(samples, count, 0.95)
        var logSum = 0.0
        var logCount = 0
        for (index in 0 until count) {
            val value = samples[index]
            if (value in low..highForAverage) {
                logSum += log2(value.toDouble())
                logCount++
            }
        }
        if (logCount == 0) return AdaptiveExposureResult(0.0, 0.0, 0.0, false)
        val logAverage = logSum / logCount
        val highlight = percentile(samples, count, 0.995).toDouble()
        var correction = log2(TARGET_MIDDLE / exp2(logAverage))
        // Do not move the measured upper tail past display-referred white before AgX rolls it off.
        correction = min(correction, log2(HIGHLIGHT_HEADROOM / highlight.coerceAtLeast(1e-6)))

        val median = percentile(samples, count, 0.50).toDouble()
        val upper = percentile(samples, count, 0.90).toDouble()
        val lowKey = median < LOW_KEY_MEDIAN && upper < LOW_KEY_UPPER
        if (lowKey && correction > 0.0) correction *= LOW_KEY_POSITIVE_SCALE
        correction = correction.coerceIn(-MAX_CORRECTION_EV, MAX_CORRECTION_EV)
        return AdaptiveExposureResult(correction, logAverage, highlight, lowKey)
    }

    private fun percentile(sorted: FloatArray, count: Int, fraction: Double): Float =
        sorted[(((count - 1) * fraction).toInt()).coerceIn(0, count - 1)]

    private fun log2(value: Double): Double = ln(value) / LN_2
    private fun exp2(value: Double): Double = kotlin.math.exp(value * LN_2)

    private const val MAX_SAMPLES = 65_536
    private const val MIN_SAMPLES = 64
    private const val SHADOW_FLOOR = 1e-4f
    private const val TARGET_MIDDLE = 0.18
    // Preserve room for AgX's shoulder instead of forcing the RAW upper tail below display white.
    private const val HIGHLIGHT_HEADROOM = 4.0
    private const val MAX_CORRECTION_EV = 1.5
    private const val LOW_KEY_MEDIAN = 0.012
    private const val LOW_KEY_UPPER = 0.05
    private const val LOW_KEY_POSITIVE_SCALE = 0.25
    private const val LN_2 = 0.6931471805599453
}
