// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import com.matthew.burstrecon.AdmittedFrame

/** A defensive immutable wrapper for metadata arrays supplied by the camera HAL. */
class ImmutableFloatValues(values: FloatArray) {
    private val data = values.copyOf()
    val size: Int get() = data.size
    operator fun get(index: Int): Float = data[index]
    fun toFloatArray(): FloatArray = data.copyOf()
    fun toList(): List<Float> = data.toList()
}

class ImmutableDoubleValues(values: DoubleArray) {
    private val data = values.copyOf()
    val size: Int get() = data.size
    operator fun get(index: Int): Double = data[index]
    fun toDoubleArray(): DoubleArray = data.copyOf()
}

data class IntPointSnapshot(val x: Int, val y: Int)

data class LensShadingSnapshot(
    val rows: Int,
    val columns: Int,
    /** R, G-even, G-odd, B gain factors in Camera2 map order. */
    val gains: ImmutableFloatValues
)

enum class BlackLevelSource { DYNAMIC, STATIC, MISSING }
enum class WhiteLevelSource { DYNAMIC, STATIC, MISSING }

/**
 * Frozen metadata from the exact TotalCaptureResult paired to one RAW_SENSOR Image timestamp.
 * No HAL-owned mutable array, Rect, Point, matrix, or map escapes this boundary.
 */
data class RawFrameMetadata(
    val cameraId: String,
    val timestampNanos: Long,
    val frameNumber: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageCrop: IntRectSnapshot,
    val rawPlaneCount: Int,
    val rawPlaneRowStride: Int?,
    val rawPlanePixelStride: Int?,
    val exifOrientation: Int,
    val sensorOrientationDegrees: Int,
    val sensitivityIso: Int?,
    val exposureTimeNanos: Long?,
    val frameDurationNanos: Long?,
    val rollingShutterSkewNanos: Long?,
    val cfaPattern: BayerPattern?,
    val rawDevelopmentUnsupportedReason: String?,
    val blackLevels: ImmutableFloatValues?,
    val blackLevelSource: BlackLevelSource,
    val whiteLevel: Float?,
    val whiteLevelSource: WhiteLevelSource,
    val pixelArraySize: Pair<Int, Int>?,
    val activeArray: IntRectSnapshot?,
    val preCorrectionActiveArray: IntRectSnapshot?,
    val rawCropRegion: IntRectSnapshot?,
    val bufferGeometry: RawBufferGeometry,
    val lensShadingAlreadyApplied: Boolean,
    val lensShadingMap: LensShadingSnapshot?,
    val hotPixels: List<IntPointSnapshot>,
    val wbGains: ImmutableFloatValues?,
    val neutralColorPoint: ImmutableDoubleValues?,
    val colorCorrectionTransform: ImmutableDoubleValues?,
    val colorMatrix1: ImmutableDoubleValues?,
    val colorMatrix2: ImmutableDoubleValues?,
    val cameraCalibration1: ImmutableDoubleValues?,
    val cameraCalibration2: ImmutableDoubleValues?,
    val forwardMatrix1: ImmutableDoubleValues?,
    val forwardMatrix2: ImmutableDoubleValues?,
    val referenceIlluminant1: Int?,
    val referenceIlluminant2: Int?,
    val noiseProfile: ImmutableDoubleValues?,
    val sensorPixelMode: Int?,
    val rawBinningFactorUsed: Boolean?,
    val activePhysicalCameraId: String?,
    val afState: Int? = null,
    val aeState: Int? = null,
    val lensState: Int? = null,
    val quadBayer: Boolean = false
) {
    fun normalizationOrNull(): RawNormalization? {
        val pattern = cfaPattern ?: return null
        val black = blackLevels?.toList() ?: return null
        val white = whiteLevel ?: return null
        return runCatching { RawNormalization(pattern, black, white) }.getOrNull()
    }
}

// DESKTOP COUNTERPART (sr-vulkan only): the Camera2 factory cannot exist on
// the JVM (android.hardware.camera2), so DNG tags build the identical
// RawFrameMetadata value instead. Field mapping mirrors capture(): the DNG
// ActiveArea crop plays the image-crop role, DNG calibration the
// characteristics role. Everything above this object is byte-identical to the
// phone file (parity-checked as a line range).
object RawFrameMetadataFactory {
    fun fromDng(
        cameraId: String,
        admitted: AdmittedFrame,
        frameNumber: Long,
        timestampNanos: Long
    ): RawFrameMetadata {
        val black = admitted.blackLevels.copyOf()
        val white = admitted.whiteLevel
        // AdmittedFrame.pattern is the crop-phase pattern (DngReader shifts by
        // the ActiveArea origin); cfaPattern must be the FULL-SENSOR pattern
        // so RawSrPackedFrame re-derives the crop phase at (origin + crop).
        // Shift is mod-2 self-inverse: shifting back by the same origin
        // restores the sensor pattern. The burstrecon and rawlens pattern
        // enums are cell-identical; valueOf converts by entry name.
        val sensorPattern = BayerPattern.valueOf(
            admitted.pattern.shifted(admitted.sensorLeft, admitted.sensorTop).name)
        val geometry = RawBufferGeometry.Supported(
            sensorOriginX = admitted.sensorLeft,
            sensorOriginY = admitted.sensorTop,
            processingCrop = RawCrop(0, 0, admitted.width, admitted.height),
            provenance = "sr-vulkan DNG import: plane holds the ActiveArea crop; origin preserves sensor phase"
        )
        val unsupported = when {
            admitted.width < 2 || admitted.height < 2 -> "DNG crop is smaller than 2x2"
            white <= (black.maxOrNull() ?: Float.NaN) -> "DNG white level does not exceed black levels"
            else -> null
        }
        return RawFrameMetadata(
            cameraId = cameraId,
            timestampNanos = timestampNanos,
            frameNumber = frameNumber,
            imageWidth = admitted.width,
            imageHeight = admitted.height,
            imageCrop = IntRectSnapshot(0, 0, admitted.width, admitted.height),
            rawPlaneCount = 1,
            rawPlaneRowStride = admitted.width * 2,
            rawPlanePixelStride = 2,
            exifOrientation = admitted.orientation,
            sensorOrientationDegrees = 0,
            sensitivityIso = admitted.iso,
            exposureTimeNanos = admitted.exposureSec?.times(1e9)?.toLong(),
            frameDurationNanos = null,
            rollingShutterSkewNanos = null,
            cfaPattern = sensorPattern,
            rawDevelopmentUnsupportedReason = unsupported,
            blackLevels = ImmutableFloatValues(black),
            blackLevelSource = BlackLevelSource.STATIC,
            whiteLevel = white,
            whiteLevelSource = WhiteLevelSource.STATIC,
            pixelArraySize = admitted.width to admitted.height,
            activeArray = IntRectSnapshot(0, 0, admitted.width, admitted.height),
            preCorrectionActiveArray = null,
            rawCropRegion = null,
            bufferGeometry = geometry,
            lensShadingAlreadyApplied = false,
            lensShadingMap = null,
            hotPixels = emptyList(),
            wbGains = null,
            neutralColorPoint = admitted.asShotNeutral?.let { ImmutableDoubleValues(it.copyOf()) },
            colorCorrectionTransform = null,
            // DNG matrices are row-major; the phone metadata convention (and
            // the writers' transpose) is Camera2 column-major, so transpose
            // once here: writer output then matches the source DNG exactly.
            colorMatrix1 = admitted.colorMatrix1?.let { ImmutableDoubleValues(transpose9(it)) },
            colorMatrix2 = admitted.colorMatrix2?.let { ImmutableDoubleValues(transpose9(it)) },
            cameraCalibration1 = null,
            cameraCalibration2 = null,
            forwardMatrix1 = admitted.forwardMatrix1?.let { ImmutableDoubleValues(transpose9(it)) },
            forwardMatrix2 = admitted.forwardMatrix2?.let { ImmutableDoubleValues(transpose9(it)) },
            referenceIlluminant1 = admitted.illuminant1,
            referenceIlluminant2 = admitted.illuminant2,
            noiseProfile = admitted.noiseProfile?.let { ImmutableDoubleValues(it.copyOf()) },
            sensorPixelMode = null,
            rawBinningFactorUsed = null,
            activePhysicalCameraId = null
        )
    }

    private fun transpose9(rowMajor: DoubleArray): DoubleArray {
        require(rowMajor.size == 9) { "DNG matrix must hold 9 values" }
        return DoubleArray(9) { rowMajor[(it % 3) * 3 + it / 3] }
    }
}
