// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.os.SystemClock
import android.util.Log

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class LensShadingModel(
    val rows: Int,
    val columns: Int,
    /** Camera2 order: map point, then R, G-even, G-odd, B. */
    val gains: FloatArray,
    val activeArray: IntRectSnapshot,
    val alreadyApplied: Boolean = false
) {
    init {
        require(rows > 0 && columns > 0) { "Lens-shading map dimensions must be positive" }
        require(gains.size == rows * columns * CHANNEL_COUNT) {
            "Lens-shading map size does not match its dimensions"
        }
        require(gains.all { it.isFinite() && it >= 1f }) {
            "Camera2 lens-shading gains must be finite and at least 1.0"
        }
        require(activeArray.width > 0 && activeArray.height > 0) {
            "Lens-shading active array must be non-empty"
        }
    }

    fun gainAt(sensorX: Int, sensorY: Int, color: CfaColor): Float {
        if (alreadyApplied) return 1f
        val channel = when (color) {
            CfaColor.RED -> 0
            CfaColor.GREEN -> if (sensorY and 1 == 0) 1 else 2
            CfaColor.BLUE -> 3
        }
        val nx = if (activeArray.width == 1) 0f else
            (sensorX - activeArray.left).toFloat() / (activeArray.width - 1).toFloat()
        val ny = if (activeArray.height == 1) 0f else
            (sensorY - activeArray.top).toFloat() / (activeArray.height - 1).toFloat()
        val mapX = nx.coerceIn(0f, 1f) * (columns - 1)
        val mapY = ny.coerceIn(0f, 1f) * (rows - 1)
        val x0 = floor(mapX).toInt()
        val y0 = floor(mapY).toInt()
        val x1 = min(x0 + 1, columns - 1)
        val y1 = min(y0 + 1, rows - 1)
        val tx = mapX - x0
        val ty = mapY - y0
        val top = gain(y0, x0, channel) * (1f - tx) + gain(y0, x1, channel) * tx
        val bottom = gain(y1, x0, channel) * (1f - tx) + gain(y1, x1, channel) * tx
        return top * (1f - ty) + bottom * ty
    }

    private fun gain(row: Int, column: Int, channel: Int): Float =
        gains[((row * columns + column) * CHANNEL_COUNT) + channel]

    private companion object {
        const val CHANNEL_COUNT = 4
    }
}

fun interface RawSaturationMap {
    /** Normalized, lens-shading-corrected saturation level at this local CFA coordinate. */
    fun levelAt(x: Int, y: Int): Float
}

data class LensShadingResult(
    val cfa: UnpackedRawCfa,
    val saturationMap: RawSaturationMap
)

object LensShadingCorrector {
    fun apply(input: UnpackedRawCfa, model: LensShadingModel?): LensShadingResult {
        return applyInternal(input, model, copyInput = true)
    }

    /** The production coordinator owns the unpacked CFA and can reuse it to avoid a 50 MB copy. */
    internal fun applyOwnedInPlace(
        input: UnpackedRawCfa,
        model: LensShadingModel?
    ): LensShadingResult = applyInternal(input, model, copyInput = false)

    private fun applyInternal(
        input: UnpackedRawCfa,
        model: LensShadingModel?,
        copyInput: Boolean
    ): LensShadingResult {
        require(input.values.size == input.width * input.height) { "CFA buffer size mismatch" }
        val output = if (copyInput) input.values.copyOf() else input.values
        val saturationMap = if (model == null || model.alreadyApplied) {
            RawSaturationMap { _, _ -> 1f }
        } else {
            RawSaturationMap { x, y ->
                val sensorX = input.sensorCropLeft + x
                val sensorY = input.sensorCropTop + y
                model.gainAt(sensorX, sensorY, input.pattern.colorAt(x, y))
            }
        }
        if (model != null && !model.alreadyApplied) {
            for (y in 0 until input.height) {
                for (x in 0 until input.width) {
                    val index = y * input.width + x
                    output[index] *= saturationMap.levelAt(x, y)
                }
            }
        }
        return LensShadingResult(input.copy(values = output), saturationMap)
    }
}

data class DefectCorrectionSettings(
    val autoDetect: Boolean = false,
    val madMultiplier: Float = 10f,
    val minimumAbsoluteDeviation: Float = 0.02f
) {
    init {
        require(madMultiplier > 0f && minimumAbsoluteDeviation >= 0f)
    }
}

data class DefectCorrectionStats(
    val metadataDefectsCorrected: Int,
    val automaticallyDetectedDefects: Int
)

object RawDefectCorrector {
    /** Mutates an owned preprocessing buffer; neighbor decisions always read an immutable copy. */
    fun correctInPlace(
        cfa: UnpackedRawCfa,
        metadataDefects: List<IntPointSnapshot>,
        settings: DefectCorrectionSettings = DefectCorrectionSettings()
    ): DefectCorrectionStats {
        require(cfa.values.size == cfa.width * cfa.height) { "CFA buffer size mismatch" }
        if (metadataDefects.isEmpty() && !settings.autoDetect) {
            return DefectCorrectionStats(0, 0)
        }
        val known = HashSet<Long>(metadataDefects.size)
        metadataDefects.forEach { point ->
            val x = point.x - cfa.sensorCropLeft
            val y = point.y - cfa.sensorCropTop
            if (x in 0 until cfa.width && y in 0 until cfa.height) known += coordinateKey(x, y)
        }
        if (known.isEmpty() && !settings.autoDetect) return DefectCorrectionStats(0, 0)
        val source = cfa.values.copyOf()

        if (!settings.autoDetect) {
            var corrected = 0
            known.forEach { key ->
                val x = key.toInt()
                val y = (key shr 32).toInt()
                val neighbors = sameColorNeighbors(source, cfa.width, cfa.height, x, y)
                if (neighbors.size >= MIN_NEIGHBORS) {
                    cfa.values[y * cfa.width + x] = median(neighbors)
                    corrected++
                }
            }
            return DefectCorrectionStats(corrected, 0)
        }

        var knownCount = 0
        var automaticCount = 0
        for (y in 0 until cfa.height) {
            for (x in 0 until cfa.width) {
                val neighbors = sameColorNeighbors(source, cfa.width, cfa.height, x, y)
                if (neighbors.size < MIN_NEIGHBORS) continue
                val median = median(neighbors)
                val key = coordinateKey(x, y)
                val isKnown = key in known
                val isAutomatic = settings.autoDetect && !isKnown && run {
                    val deviations = FloatArray(neighbors.size) { abs(neighbors[it] - median) }
                    val mad = median(deviations)
                    abs(source[y * cfa.width + x] - median) >
                        max(settings.minimumAbsoluteDeviation, settings.madMultiplier * mad)
                }
                if (isKnown || isAutomatic) {
                    cfa.values[y * cfa.width + x] = median
                    if (isKnown) knownCount++ else automaticCount++
                }
            }
        }
        return DefectCorrectionStats(knownCount, automaticCount)
    }

    private fun sameColorNeighbors(
        values: FloatArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int
    ): FloatArray {
        val collected = FloatArray(8)
        var count = 0
        for (dy in -2..2 step 2) {
            for (dx in -2..2 step 2) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until width && ny in 0 until height) {
                    collected[count++] = values[ny * width + nx]
                }
            }
        }
        return collected.copyOf(count)
    }

    private fun median(values: FloatArray): Float {
        values.sort()
        val middle = values.size / 2
        return if (values.size and 1 == 1) values[middle]
        else 0.5f * (values[middle - 1] + values[middle])
    }

    private fun coordinateKey(x: Int, y: Int): Long =
        (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)

    private const val MIN_NEIGHBORS = 3
}

data class PreDemosaicSettings(
    val defectCorrection: DefectCorrectionSettings = DefectCorrectionSettings()
)

data class PreDemosaicResult(
    /** Present on the compatibility CPU path; direct GPU preprocessing keeps this off the heap. */
    val cfa: UnpackedRawCfa?,
    val lensShadingStatus: LensShadingStatus,
    val defectStats: DefectCorrectionStats
)

enum class LensShadingStatus { APPLIED, ALREADY_APPLIED, MISSING_MAP_IDENTITY_FALLBACK }

object RawPreDemosaicPipeline {
    internal fun lensShadingModel(metadata: RawFrameMetadata): LensShadingModel? =
        metadata.lensShadingMap?.let { map ->
            val active = metadata.activeArray ?: metadata.preCorrectionActiveArray
                ?: throw UnsupportedOperationException("Lens-shading map has no active-array geometry")
            LensShadingModel(
                map.rows,
                map.columns,
                map.gains.toFloatArray(),
                active,
                metadata.lensShadingAlreadyApplied
            )
        }

    internal fun lensShadingStatus(metadata: RawFrameMetadata): LensShadingStatus = when {
        metadata.lensShadingAlreadyApplied -> LensShadingStatus.ALREADY_APPLIED
        metadata.lensShadingMap == null -> LensShadingStatus.MISSING_MAP_IDENTITY_FALLBACK
        else -> LensShadingStatus.APPLIED
    }

    fun process(
        input: UnpackedRawCfa,
        metadata: RawFrameMetadata,
        settings: PreDemosaicSettings = PreDemosaicSettings()
    ): PreDemosaicResult {
        val startedAt = SystemClock.elapsedRealtime()
        metadata.rawDevelopmentUnsupportedReason?.let { throw UnsupportedOperationException(it) }
        val shadingStatus = lensShadingStatus(metadata)
        val shading = lensShadingModel(metadata)
        val corrected = LensShadingCorrector.applyOwnedInPlace(input, shading)
        val shadingAt = SystemClock.elapsedRealtime()
        val defectStats = RawDefectCorrector.correctInPlace(
            corrected.cfa,
            metadata.hotPixels,
            settings.defectCorrection
        )
        val defectsAt = SystemClock.elapsedRealtime()
        Log.i(
            "RawLensDevelop",
            "Pre-demosaic ${input.width}x${input.height}: " +
                "lensShading=${shadingAt - startedAt}ms " +
                "defects=${defectsAt - shadingAt}ms total=${defectsAt - startedAt}ms"
        )
        return PreDemosaicResult(corrected.cfa, shadingStatus, defectStats)
    }
}
