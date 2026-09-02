// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.Image
import android.os.Build
import android.util.Rational

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
    val activePhysicalCameraId: String?
) {
    fun normalizationOrNull(): RawNormalization? {
        val pattern = cfaPattern ?: return null
        val black = blackLevels?.toList() ?: return null
        val white = whiteLevel ?: return null
        return runCatching { RawNormalization(pattern, black, white) }.getOrNull()
    }
}

object RawFrameMetadataFactory {
    fun capture(
        cameraId: String,
        image: Image,
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        exifOrientation: Int
    ): RawFrameMetadata {
        val cfaValue = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val cfaResult = runCatching { cfaValue?.let(BayerPattern::fromCamera2) }
        val cfa = cfaResult.getOrNull()

        val dynamicBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?.takeIf { it.size == 4 && it.all(Float::isFinite) }
        val staticBlack = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?.let { pattern -> FloatArray(4) { index ->
                pattern.getOffsetForIndex(index and 1, index shr 1).toFloat()
            } }
        val black = dynamicBlack ?: staticBlack
        val blackSource = when {
            dynamicBlack != null -> BlackLevelSource.DYNAMIC
            staticBlack != null -> BlackLevelSource.STATIC
            else -> BlackLevelSource.MISSING
        }

        val dynamicWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?.takeIf { it > 0 }?.toFloat()
        val staticWhite = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?.takeIf { it > 0 }?.toFloat()
        val white = dynamicWhite ?: staticWhite
        val whiteSource = when {
            dynamicWhite != null -> WhiteLevelSource.DYNAMIC
            staticWhite != null -> WhiteLevelSource.STATIC
            else -> WhiteLevelSource.MISSING
        }

        val pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val imageCrop = image.cropRect.snapshot()!!
        val plane = image.planes.singleOrNull()
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE).snapshot()
        val preCorrection = characteristics
            .get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE).snapshot()
        val rawCrop = if (Build.VERSION.SDK_INT >= 34) {
            result.get(CaptureResult.SCALER_RAW_CROP_REGION).snapshot()
        } else null
        val geometry = RawBufferGeometry.resolve(
            image.width,
            image.height,
            rawCrop,
            preCorrection,
            pixelSize?.width,
            pixelSize?.height,
            imageCrop
        )

        val lensMap = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            ?.let { map ->
                val factors = FloatArray(map.gainFactorCount)
                map.copyGainFactors(factors, 0)
                LensShadingSnapshot(map.rowCount, map.columnCount, ImmutableFloatValues(factors))
            }
        val hotPixels = result.get(CaptureResult.STATISTICS_HOT_PIXEL_MAP)
            ?.map { it.snapshot() }.orEmpty()
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
            ImmutableFloatValues(floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue))
        }
        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            ?.toDoubleValues()
        val noise = result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { pairs ->
            ImmutableDoubleValues(DoubleArray(pairs.size * 2) { index ->
                if (index and 1 == 0) pairs[index / 2].first else pairs[index / 2].second
            })
        }
        val rawBinning = if (Build.VERSION.SDK_INT >= 35) {
            result.get(CaptureResult.SENSOR_RAW_BINNING_FACTOR_USED)
        } else null

        val unsupported = when {
            plane == null -> "RAW_SENSOR must expose exactly one image plane"
            plane.pixelStride < 2 -> "RAW_SENSOR pixel stride cannot hold a 16-bit sample"
            plane.rowStride < (image.width - 1) * plane.pixelStride + 2 ->
                "RAW_SENSOR row stride is shorter than the image width"
            cfaValue == null -> "Camera2 did not report a CFA arrangement"
            cfaResult.isFailure -> cfaResult.exceptionOrNull()?.message
            rawBinning == true -> "RAW binning/Quad-Bayer remosaic is not supported"
            geometry is RawBufferGeometry.Unsupported -> geometry.reason
            black == null -> "Camera2 did not report black levels"
            white == null -> "Camera2 did not report a white level"
            else -> null
        }

        return RawFrameMetadata(
            cameraId = cameraId,
            timestampNanos = image.timestamp,
            frameNumber = result.frameNumber,
            imageWidth = image.width,
            imageHeight = image.height,
            imageCrop = imageCrop,
            rawPlaneCount = image.planes.size,
            rawPlaneRowStride = plane?.rowStride,
            rawPlanePixelStride = plane?.pixelStride,
            exifOrientation = exifOrientation,
            sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY),
            exposureTimeNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            frameDurationNanos = result.get(CaptureResult.SENSOR_FRAME_DURATION),
            rollingShutterSkewNanos = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
            cfaPattern = cfa,
            rawDevelopmentUnsupportedReason = unsupported,
            blackLevels = black?.let(::ImmutableFloatValues),
            blackLevelSource = blackSource,
            whiteLevel = white,
            whiteLevelSource = whiteSource,
            pixelArraySize = pixelSize?.let { it.width to it.height },
            activeArray = activeArray,
            preCorrectionActiveArray = preCorrection,
            rawCropRegion = rawCrop,
            bufferGeometry = geometry,
            lensShadingAlreadyApplied = characteristics
                .get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) == true,
            lensShadingMap = lensMap,
            hotPixels = hotPixels,
            wbGains = gains,
            neutralColorPoint = neutral,
            colorCorrectionTransform = result
                .get(CaptureResult.COLOR_CORRECTION_TRANSFORM).toDoubleValues(),
            colorMatrix1 = characteristics
                .get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1).toDoubleValues(),
            colorMatrix2 = characteristics
                .get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2).toDoubleValues(),
            cameraCalibration1 = characteristics
                .get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1).toDoubleValues(),
            cameraCalibration2 = characteristics
                .get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2).toDoubleValues(),
            forwardMatrix1 = characteristics
                .get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1).toDoubleValues(),
            forwardMatrix2 = characteristics
                .get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2).toDoubleValues(),
            referenceIlluminant1 = characteristics
                .get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
            referenceIlluminant2 = characteristics
                .get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
            noiseProfile = noise,
            sensorPixelMode = if (Build.VERSION.SDK_INT >= 31) {
                result.get(CaptureResult.SENSOR_PIXEL_MODE)
            } else null,
            rawBinningFactorUsed = rawBinning,
            activePhysicalCameraId = result
                .get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        )
    }

    private fun Rect?.snapshot(): IntRectSnapshot? = this?.let {
        IntRectSnapshot(it.left, it.top, it.right, it.bottom)
    }

    private fun Point.snapshot() = IntPointSnapshot(x, y)

    private fun ColorSpaceTransform?.toDoubleValues(): ImmutableDoubleValues? = this?.let {
        ImmutableDoubleValues(DoubleArray(9) { index ->
            it.getElement(index / 3, index % 3).toDouble()
        })
    }

    private fun Array<Rational>?.toDoubleValues(): ImmutableDoubleValues? = this?.let {
        ImmutableDoubleValues(DoubleArray(size) { index -> get(index).toDouble() })
    }
}
