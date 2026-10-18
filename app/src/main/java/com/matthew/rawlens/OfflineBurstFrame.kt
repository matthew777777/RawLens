// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import org.json.JSONObject

/**
 * One decoded `_BURST.crs` frame ready for offline merge: row-major
 * uint16 Bayer pixels plus metadata synthesized from the container and
 * frame JSON (no Camera2 result exists offline, so [RawFrameMetadata] is
 * built directly; unknown HAL tags stay null exactly like an unreported
 * key on a live capture).
 */
internal data class OfflineBurstFrame(
    val pixels: ByteBuffer,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    /** Recording-relative timestamp (ns); consistent within one take. */
    val timestampNanos: Long,
    val exposureNanos: Long,
    val sensitivityIso: Int,
    val metadata: RawFrameMetadata,
    val normalization: RawNormalization
)

internal object OfflineBurstMetadata {
    /**
     * Builds the merge-time metadata for one decoded frame. Throws
     * IllegalArgumentException when the JSON lacks required keys.
     */
    fun synthesize(
        containerJson: String,
        frameJson: String,
        frameIndex: Int,
        cameraId: String = "unknown",
        exifOrientation: Int = 1
    ): RawFrameMetadata {
        val container = JSONObject(containerJson)
        val frame = JSONObject(frameJson)
        val width = frame.getInt("width")
        val height = frame.getInt("height")
        val pattern = cfaFrom(container.getString("sensorArrangment"))
        val blackArray = container.getJSONArray("blackLevel")
        require(blackArray.length() == 4) { "Container blackLevel needs 4 entries" }
        val blacks = List(4) { blackArray.getDouble(it).toFloat() }
        val white = container.getInt("whiteLevel").toFloat()
        require(blacks.all { white > it }) { "White level must exceed black levels" }
        val neutral = frame.optJSONArray("asShotNeutral")?.let { array ->
            DoubleArray(array.length()) { array.getDouble(it) }
        }
        val timestamp = frame.getString("timestamp").toLong()
        return RawFrameMetadata(
            cameraId = cameraId,
            timestampNanos = timestamp,
            frameNumber = frameIndex.toLong(),
            imageWidth = width,
            imageHeight = height,
            imageCrop = IntRectSnapshot(0, 0, width, height),
            rawPlaneCount = 1,
            rawPlaneRowStride = width * 2,
            rawPlanePixelStride = 2,
            exifOrientation = exifOrientation,
            sensorOrientationDegrees = container.optInt("sensorOrientation", 0),
            sensitivityIso = frame.optInt("iso", 0).takeIf { it > 0 },
            exposureTimeNanos = frame.optLong("exposureTime", 0L).takeIf { it > 0 },
            frameDurationNanos = null,
            rollingShutterSkewNanos = null,
            cfaPattern = pattern,
            rawDevelopmentUnsupportedReason = null,
            blackLevels = ImmutableFloatValues(blacks.toFloatArray()),
            blackLevelSource = BlackLevelSource.STATIC,
            whiteLevel = white,
            whiteLevelSource = WhiteLevelSource.STATIC,
            pixelArraySize = width to height,
            activeArray = IntRectSnapshot(0, 0, width, height),
            preCorrectionActiveArray = null,
            rawCropRegion = null,
            bufferGeometry = RawBufferGeometry.Supported(
                sensorOriginX = 0,
                sensorOriginY = 0,
                processingCrop = RawCrop(0, 0, width, height),
                provenance = "crs-offline"
            ),
            lensShadingAlreadyApplied = false,
            lensShadingMap = null,
            hotPixels = emptyList(),
            wbGains = null,
            neutralColorPoint = neutral?.let(::ImmutableDoubleValues),
            colorCorrectionTransform = null,
            colorMatrix1 = doubleArray("colorMatrix1", container),
            colorMatrix2 = doubleArray("colorMatrix2", container),
            cameraCalibration1 = doubleArray("calibrationMatrix1", container),
            cameraCalibration2 = doubleArray("calibrationMatrix2", container),
            forwardMatrix1 = doubleArray("forwardMatrix1", container),
            forwardMatrix2 = doubleArray("forwardMatrix2", container),
            referenceIlluminant1 = container.optInt("referenceIlluminant1", 0).takeIf { it > 0 },
            referenceIlluminant2 = container.optInt("referenceIlluminant2", 0).takeIf { it > 0 },
            noiseProfile = null,
            sensorPixelMode = null,
            rawBinningFactorUsed = false,
            activePhysicalCameraId = null
        )
    }

    fun cfaFrom(sensorArrangement: String): BayerPattern = when (sensorArrangement.lowercase()) {
        "rggb" -> BayerPattern.RGGB
        "grbg" -> BayerPattern.GRBG
        "gbrg" -> BayerPattern.GBRG
        "bggr" -> BayerPattern.BGGR
        else -> throw IllegalArgumentException("Unknown sensor arrangement $sensorArrangement")
    }

    fun normalizationFor(metadata: RawFrameMetadata): RawNormalization =
        metadata.normalizationOrNull()
            ?: throw IllegalStateException("Offline frame has no black/white levels")

    private fun doubleArray(name: String, container: JSONObject): ImmutableDoubleValues? =
        if (!container.has(name)) null else container.getJSONArray(name).let { array ->
            ImmutableDoubleValues(DoubleArray(array.length()) { array.getDouble(it) })
        }
}
