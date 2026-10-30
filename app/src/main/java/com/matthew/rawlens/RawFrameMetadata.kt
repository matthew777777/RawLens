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

enum class BlackLevelSource { DYNAMIC, STATIC, MISSING, SAMPLED }
enum class WhiteLevelSource { DYNAMIC, STATIC, MISSING, SAMPLED }

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
    val quadBayer: Boolean = false,
    /** f-number at capture (LENS_APERTURE); feeds EXIF FNumber/ApertureValue. Null when unreported. */
    val aperture: Float? = null,
    /** Focal length in mm (LENS_FOCAL_LENGTH); feeds EXIF FocalLength. Null when unreported. */
    val focalLengthMm: Float? = null,
    /** Focus distance in meters (1/LENS_FOCUS_DISTANCE); feeds EXIF SubjectDistance. Null at infinity/unknown. */
    val focusDistanceM: Float? = null,
    /** True when the flash fired for this frame (FLASH_STATE); feeds EXIF Flash. Null when unknown. */
    val flashFired: Boolean? = null,
    /**
     * Camera2 stream format of the frame (`RAW_SENSOR` unless a packed
     * session was chosen). Defaults to `RAW_SENSOR` so every existing
     * construction keeps meaning "unpacked 16-bit".
     */
    val rawImageFormat: Int = android.graphics.ImageFormat.RAW_SENSOR,
    /** Capture controls, retained for delayed/offline writers; states are not modes. */
    val aeMode: Int? = null,
    val awbMode: Int? = null,
    val exposureCompensationEv: Double? = null,
    val digitalZoomRatio: Float? = null,
    /** App-generated lens identifier, built from reported camera ID/focal length/aperture. */
    val lensModel: String? = null,
    /** EXIF min/max focal length and maximum apertures, only for a declared fixed lens. */
    val lensSpecification: ImmutableDoubleValues? = null,
    val compositeSourceCount: Int? = null,
    val compositeUsedCount: Int? = null,
    /**
     * True when [neutralColorPoint]/[wbGains] are a gray-world estimate from
     * the frame because the HAL ran no AWB ([RawWhiteBalanceFallback]). The
     * platform DngCreator would write the HAL's NaN neutral, so DNG saves
     * route to TinyDNG, which writes these values.
     */
    val whiteBalanceFallback: Boolean = false
) {
    fun normalizationOrNull(): RawNormalization? {
        val pattern = cfaPattern ?: return null
        val black = blackLevels?.toList() ?: return null
        val white = whiteLevel ?: return null
        return runCatching { RawNormalization(pattern, black, white) }.getOrNull()
    }
}

object RawFrameMetadataFactory {
    /**
     * Physical-result routing contract (the `captureValue` routing pattern).
     * Returns the physical camera id to read sensor keys from, or null when the
     * frame has no physical source or the id is stale/unknown. Pure strings in
     * and out so it stays JVM-testable without the Android framework.
     */
    internal fun selectPhysicalResultId(
        activePhysicalId: String?,
        availableIds: Set<String>
    ): String? = activePhysicalId?.takeIf { it in availableIds }

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

        // Physical-first routing: when the HAL reports which physical camera
        // produced this frame, sensor keys below read from that physical result
        // and fall back to the logical result per key. Pipeline states
        // (AF/AE/lens/flash) and frame identity stay logical. With no physical
        // source this degrades to a plain logical read, identical to before.
        // Physical results need Android 12 (the runCatching below also
        // covers HALs that throw, but only an SDK guard satisfies the
        // minSdk-30 NewApi gate for the call itself).
        val physicalResults = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { result.physicalCameraTotalResults }.getOrNull()
        } else {
            null
        }
        val physicalId = selectPhysicalResultId(
            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
            physicalResults?.keys ?: emptySet()
        )
        val physical: TotalCaptureResult? = physicalId?.let { physicalResults?.get(it) }
        fun <T> sensorKey(key: CaptureResult.Key<T>): T? = physical?.get(key) ?: result.get(key)

        val dynamicBlack = sensorKey(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
            ?.takeIf { it.size == 4 && it.all(Float::isFinite) }
        val staticBlack = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            ?.let { pattern -> FloatArray(4) { index ->
                pattern.getOffsetForIndex(index and 1, index shr 1).toFloat()
            } }
        val reportedBlack = dynamicBlack ?: staticBlack
        val reportedBlackSource = when {
            dynamicBlack != null -> BlackLevelSource.DYNAMIC
            staticBlack != null -> BlackLevelSource.STATIC
            else -> BlackLevelSource.MISSING
        }

        val dynamicWhite = sensorKey(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?.takeIf { it > 0 }?.toFloat()
        val staticWhite = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?.takeIf { it > 0 }?.toFloat()
        val reportedWhite = dynamicWhite ?: staticWhite
        val reportedWhiteSource = when {
            dynamicWhite != null -> WhiteLevelSource.DYNAMIC
            staticWhite != null -> WhiteLevelSource.STATIC
            else -> WhiteLevelSource.MISSING
        }

        // Missing/insane pairs (Vivo X300 Ultra default mode: white=0
        // placeholders or white <= black) would fail the save or write a
        // black DNG. Sample the frame's own bytes instead; the tags stay
        // self-consistent with the payload. Sane HALs never sample.
        val sampled = if (DngLevelRepair.needsRepair(reportedBlack, reportedWhite)) {
            image.planes.singleOrNull()?.let { plane ->
                DngLevelRepair.sample(plane.buffer, plane.rowStride, plane.pixelStride,
                    image.width, image.height)
            }
        } else null
        val repaired = sampled?.let(DngLevelRepair::repair)
        val black = repaired?.first ?: reportedBlack
        val white = repaired?.second ?: reportedWhite
        val blackSource = if (repaired != null) BlackLevelSource.SAMPLED else reportedBlackSource
        val whiteSource = if (repaired != null) WhiteLevelSource.SAMPLED else reportedWhiteSource

        val pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val imageCrop = image.cropRect.snapshot()!!
        val plane = image.planes.singleOrNull()
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE).snapshot()
        val preCorrection = characteristics
            .get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE).snapshot()
        val rawCrop = if (Build.VERSION.SDK_INT >= 34) {
            sensorKey(CaptureResult.SCALER_RAW_CROP_REGION).snapshot()
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

        val lensMap = sensorKey(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            ?.let { map ->
                val factors = FloatArray(map.gainFactorCount)
                map.copyGainFactors(factors, 0)
                LensShadingSnapshot(map.rowCount, map.columnCount, ImmutableFloatValues(factors))
            }
        val hotPixels = sensorKey(CaptureResult.STATISTICS_HOT_PIXEL_MAP)
            ?.map { it.snapshot() }.orEmpty()
        val halGains = sensorKey(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
            ImmutableFloatValues(floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue))
        }
        val halNeutral = sensorKey(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            ?.toDoubleValues()
        // Direct-opened Xiaomi aux lenses run no AWB (unity gains, NaN
        // neutral): estimate from the frame so JPEG and DNG are not green.
        val fallbackNeutral = if (RawWhiteBalanceFallback.halAwbMissing(
                halNeutral?.toDoubleArray(), halGains?.toFloatArray()) &&
            cfaValue != null && black != null && white != null && plane != null
        ) {
            runCatching {
                RawWhiteBalanceFallback.estimate(plane.buffer, plane.rowStride, plane.pixelStride,
                    image.width, image.height, cfaValue, black, white,
                    RawWhiteBalanceFallback.locusFor(characteristics))
            }.getOrNull()
        } else null
        val gains = fallbackNeutral?.let { ImmutableFloatValues(RawWhiteBalanceFallback.gainsFor(it)) } ?: halGains
        val neutral = fallbackNeutral?.let(::ImmutableDoubleValues) ?: halNeutral
        val noise = sensorKey(CaptureResult.SENSOR_NOISE_PROFILE)?.let { pairs ->
            ImmutableDoubleValues(DoubleArray(pairs.size * 2) { index ->
                if (index and 1 == 0) pairs[index / 2].first else pairs[index / 2].second
            })
        }
        val rawBinning = if (Build.VERSION.SDK_INT >= 31) {
            sensorKey(CaptureResult.SENSOR_RAW_BINNING_FACTOR_USED)
        } else null
        val pixelMode = if (Build.VERSION.SDK_INT >= 31) {
            sensorKey(CaptureResult.SENSOR_PIXEL_MODE)
        } else null

        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val groupSize = if (Build.VERSION.SDK_INT >= 31) {
            characteristics.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)
        } else null
        val groupedBayer = RawBayerLayout.requiresRemosaic(
            rawBinning,
            capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) == true,
            pixelMode,
            groupSize?.width, groupSize?.height
        )
        val quadBayer = groupedBayer && groupSize?.width == 2 && groupSize.height == 2
        // Known packed formats carry stride 0 by design; geometry is
        // validated again at decode time, so the gate only refuses packed
        // frames it cannot name. Anything else keeps the 16-bit rules.
        val packedFormat = PackedRawFormat.ofInt(image.format)
        val unsupported = when {
            plane == null -> "RAW_SENSOR must expose exactly one image plane"
            plane.pixelStride < 2 && packedFormat == null ->
                "RAW_SENSOR pixel stride cannot hold a 16-bit sample"
            plane.rowStride < (image.width - 1) * plane.pixelStride + 2 ->
                "RAW_SENSOR row stride is shorter than the image width"
            cfaValue == null -> "Camera2 did not report a CFA arrangement"
            cfaResult.isFailure -> cfaResult.exceptionOrNull()?.message
            geometry is RawBufferGeometry.Unsupported -> geometry.reason
            black == null -> "Camera2 did not report black levels"
            white == null -> "Camera2 did not report a white level"
            quadBayer -> RawBayerLayout.QUAD_REQUIRES_DEMOSAIC
            groupedBayer && (groupSize?.width != 1 || groupSize.height != 1) ->
                "Unsupported or missing RAW CFA group size: $groupSize"
            else -> null
        }

        val metadata = RawFrameMetadata(
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
            sensitivityIso = sensorKey(CaptureResult.SENSOR_SENSITIVITY),
            exposureTimeNanos = sensorKey(CaptureResult.SENSOR_EXPOSURE_TIME),
            frameDurationNanos = sensorKey(CaptureResult.SENSOR_FRAME_DURATION),
            rollingShutterSkewNanos = sensorKey(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
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
            colorCorrectionTransform = sensorKey(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                .toDoubleValues(),
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
            sensorPixelMode = pixelMode,
            rawBinningFactorUsed = rawBinning,
            activePhysicalCameraId = result
                .get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
            afState = result.get(CaptureResult.CONTROL_AF_STATE),
            aeState = result.get(CaptureResult.CONTROL_AE_STATE),
            lensState = result.get(CaptureResult.LENS_STATE),
            quadBayer = quadBayer,
            aperture = sensorKey(CaptureResult.LENS_APERTURE)
                ?.takeIf { it.isFinite() && it > 0f },
            focalLengthMm = sensorKey(CaptureResult.LENS_FOCAL_LENGTH)
                ?.takeIf { it.isFinite() && it > 0f },
            focusDistanceM = sensorKey(CaptureResult.LENS_FOCUS_DISTANCE)
                ?.takeIf { it.isFinite() && it > 0f }?.let { 1f / it },
            flashFired = result.get(CaptureResult.FLASH_STATE)?.let {
                it == CaptureResult.FLASH_STATE_FIRED || it == CaptureResult.FLASH_STATE_PARTIAL
            },
            rawImageFormat = image.format,
            aeMode = if (result.get(CaptureResult.CONTROL_MODE) == CaptureResult.CONTROL_MODE_OFF) 0
                else result.get(CaptureResult.CONTROL_AE_MODE),
            awbMode = if (result.get(CaptureResult.CONTROL_MODE) == CaptureResult.CONTROL_MODE_OFF) 0
                else result.get(CaptureResult.CONTROL_AWB_MODE),
            exposureCompensationEv = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)?.let { steps ->
                characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    ?.toDouble()?.let { steps * it }?.takeIf(Double::isFinite)
            },
            digitalZoomRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                ?.takeIf { it.isFinite() && it >= 1f &&
                    capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) != true },
            lensModel = sensorKey(CaptureResult.LENS_FOCAL_LENGTH)
                ?.takeIf { it.isFinite() && it > 0f }?.let { focal ->
                    val aperture = sensorKey(CaptureResult.LENS_APERTURE)?.takeIf { it.isFinite() && it > 0f }
                    "${Build.MODEL} camera ${physicalId ?: cameraId} ${focal}mm" +
                        (aperture?.let { " f/$it" } ?: "")
                },
            lensSpecification = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.singleOrNull()?.takeIf { it.isFinite() && it > 0f }?.let { focal ->
                    characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                        ?.filter { it.isFinite() && it > 0f }?.minOrNull()?.let { aperture ->
                            ImmutableDoubleValues(doubleArrayOf(focal.toDouble(), focal.toDouble(),
                                aperture.toDouble(), aperture.toDouble()))
                        }
                },
            whiteBalanceFallback = fallbackNeutral != null
        )
        logColorDiag(cameraId, metadata, physicalId, result)
        return metadata
    }

    private var lastColorDiagCamera: String? = null
    private var lastColorDiagNanos = 0L

    /**
     * One colour line per capture: bursts build metadata for every frame
     * within a few hundred ms, so repeats on the same camera inside
     * [COLOR_DIAG_INTERVAL_NANOS] are dropped. Never fails the save.
     */
    private fun logColorDiag(
        cameraId: String,
        metadata: RawFrameMetadata,
        physicalId: String?,
        result: TotalCaptureResult
    ) {
        val now = System.nanoTime()
        synchronized(this) {
            if (cameraId == lastColorDiagCamera && now - lastColorDiagNanos < COLOR_DIAG_INTERVAL_NANOS) return
            lastColorDiagCamera = cameraId
            lastColorDiagNanos = now
        }
        runCatching {
            val statics = SceneLinearColorMetadata.from(metadata)
            val used = ColorDiagnostics.ResultColor(
                source = (physicalId?.let { "used(phys:$it,per-key logical fallback)" } ?: "used(logical)") +
                    if (metadata.whiteBalanceFallback) "+grayWorldWb" else "",
                gains = metadata.wbGains?.toFloatArray(),
                neutral = metadata.neutralColorPoint?.toDoubleArray(),
                ccm = metadata.colorCorrectionTransform?.toDoubleArray(),
                awbMode = metadata.awbMode,
                awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
            )
            val (physicalHeader, physicalColors) = result.physicalColorDiags()
            Log.i("RawLensColor", ColorDiagnostics.format(
                "CAPTURE color", "$cameraId frame=${metadata.frameNumber} $physicalHeader",
                used, listOf(result.colorDiag("logical")) + physicalColors, statics))
        }.onFailure { Log.w("RawLensColor", "CAPTURE color diag failed: ${it.message}") }
    }

    private const val COLOR_DIAG_INTERVAL_NANOS = 1_500_000_000L

    private fun Rect?.snapshot(): IntRectSnapshot? = this?.let {
        IntRectSnapshot(it.left, it.top, it.right, it.bottom)
    }

    private fun Point.snapshot() = IntPointSnapshot(x, y)

    private fun ColorSpaceTransform?.toDoubleValues(): ImmutableDoubleValues? =
        this.toImmutableDoubles()

    private fun Array<Rational>?.toDoubleValues(): ImmutableDoubleValues? =
        this.toImmutableDoubles()
}

/**
 * Camera2 storage order shared by saves and the WYSIWYG viewfinder: ColorSpaceTransform
 * elements in API order (frozen[column * 3 + row] = getElement(column, row)), converted
 * to mathematical row-major exactly once by [SceneLinearColorProcessor].
 */
internal fun ColorSpaceTransform?.toImmutableDoubles(): ImmutableDoubleValues? = this?.let {
    ImmutableDoubleValues(DoubleArray(9) { index ->
        it.getElement(index / 3, index % 3).toDouble()
    })
}

internal fun Array<Rational>?.toImmutableDoubles(): ImmutableDoubleValues? = this?.let {
    ImmutableDoubleValues(DoubleArray(size) { index -> get(index).toDouble() })
}
