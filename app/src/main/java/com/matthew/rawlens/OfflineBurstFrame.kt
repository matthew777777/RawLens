// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import org.json.JSONObject

/**
 * One decoded `_BURST.craw` frame ready for offline merge: row-major
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
        val shadingMap = lensShadingSnapshot(frame)
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
                provenance = "craw-offline"
            ),
            lensShadingAlreadyApplied = false,
            lensShadingMap = shadingMap,
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
            activePhysicalCameraId = null,
            aperture = frame.optDouble("aperture", Double.NaN).toFloat().takeIf { it.isFinite() && it > 0f },
            focalLengthMm = frame.optDouble("focalLengthMm", Double.NaN).toFloat().takeIf { it.isFinite() && it > 0f },
            focusDistanceM = frame.optDouble("focusDistanceDiopters", Double.NaN)
                .takeIf { it.isFinite() && it > 0.0 }?.let { (1.0 / it).toFloat() },
            flashFired = if (frame.has("flashState")) frame.getInt("flashState").let { it == 3 || it == 4 } else null,
            aeMode = if (frame.has("aeMode")) frame.getInt("aeMode") else null,
            awbMode = if (frame.has("awbMode")) frame.getInt("awbMode") else null,
            exposureCompensationEv = if (frame.has("exposureCompensationSteps")) {
                (frame.getInt("exposureCompensationSteps") *
                    container.optDouble("exposureCompensationStep", Double.NaN)).takeIf(Double::isFinite)
            } else null,
            digitalZoomRatio = frame.optDouble("digitalZoomRatio", Double.NaN).toFloat()
                .takeIf { it.isFinite() && it >= 1f && container.optBoolean("zoomRatioIsDigital", false) }
        )
    }

    /**
     * Parses the optional per-frame `lensShadingMapWidth` /
     * `lensShadingMapHeight` / `lensShadingMap` triplet (four nested
     * R, G-even, G-odd, B planes, cf. `CrawLensShading.toFrameJson`)
     * into Camera2 map order. Strictly validated (extents, plane
     * counts, finite gains >= 1.0 as `LensShadingModel` requires);
     * any defect nulls the map (no android Log here: this path runs in
     * plain JVM unit tests), leaving the frame to merge uncorrected
     * exactly as before.
     */
    fun lensShadingSnapshot(frame: JSONObject): LensShadingSnapshot? {
        if (!frame.has("lensShadingMapWidth") || !frame.has("lensShadingMapHeight")
            || !frame.has("lensShadingMap")
        ) return null
        // Any defect nulls the map, leaving the frame to merge
        // uncorrected exactly as before.
        return try {
            val columns = frame.getInt("lensShadingMapWidth")
            val rows = frame.getInt("lensShadingMapHeight")
            val planes = frame.getJSONArray("lensShadingMap")
            buildSnapshot(columns, rows, List(planes.length()) { ch ->
                val plane = planes.getJSONArray(ch)
                List(plane.length()) { i -> plane.getDouble(i) }
            })
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pure core of [lensShadingSnapshot] (plain-JVM testable; the JSON
     * shell above stays a trivial projection because android stubs
     * return defaults under unit tests). Interleaves the four nested
     * R, G-even, G-odd, B planes into Camera2 map order; throws on any
     * shape/value defect.
     */
    fun buildSnapshot(columns: Int, rows: Int, planes: List<List<Double>>): LensShadingSnapshot {
        require(rows in 1..CrawLensShading.MAX_EXTENT
            && columns in 1..CrawLensShading.MAX_EXTENT) {
            "lens-shading grid extents out of range ($columns x $rows)"
        }
        require(planes.size == 4) { "lens-shading map needs 4 planes" }
        val points = Math.multiplyExact(rows, columns)
        val gains = FloatArray(Math.multiplyExact(points, 4))
        for (ch in 0 until 4) {
            val plane = planes[ch]
            require(plane.size == points) { "lens-shading plane $ch size mismatch" }
            for (i in 0 until points) gains[i * 4 + ch] = plane[i].toFloat()
        }
        require(gains.all { it.isFinite() && it >= 1f }) {
            "lens-shading gains must be finite and at least 1.0"
        }
        return LensShadingSnapshot(rows, columns, ImmutableFloatValues(gains))
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
