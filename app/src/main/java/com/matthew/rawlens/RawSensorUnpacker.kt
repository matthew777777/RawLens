// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class CfaColor { RED, GREEN, BLUE }

/** Standard Camera2 2x2 Bayer arrangements, expressed at the full-sensor origin. */
enum class BayerPattern(private val cells: List<CfaColor>) {
    RGGB(listOf(CfaColor.RED, CfaColor.GREEN, CfaColor.GREEN, CfaColor.BLUE)),
    GRBG(listOf(CfaColor.GREEN, CfaColor.RED, CfaColor.BLUE, CfaColor.GREEN)),
    GBRG(listOf(CfaColor.GREEN, CfaColor.BLUE, CfaColor.RED, CfaColor.GREEN)),
    BGGR(listOf(CfaColor.BLUE, CfaColor.GREEN, CfaColor.GREEN, CfaColor.RED));

    fun colorAt(sensorX: Int, sensorY: Int): CfaColor =
        cells[((sensorY and 1) shl 1) or (sensorX and 1)]

    fun shifted(sensorX: Int, sensorY: Int): BayerPattern {
        val shiftedCells = List(4) { index ->
            colorAt(sensorX + (index and 1), sensorY + (index shr 1))
        }
        return entries.first { it.cells == shiftedCells }
    }

    companion object {
        fun fromCamera2(value: Int): BayerPattern = when (value) {
            0 -> RGGB
            1 -> GRBG
            2 -> GBRG
            3 -> BGGR
            4 -> throw UnsupportedOperationException("Camera2 CFA RGB is not a 2x2 Bayer mosaic")
            5 -> throw UnsupportedOperationException("Camera2 CFA MONO is not supported by AMaZE")
            6 -> throw UnsupportedOperationException("Camera2 CFA NIR is not supported by AMaZE")
            else -> throw UnsupportedOperationException("Unknown Camera2 CFA arrangement $value")
        }
    }
}

data class IntRectSnapshot(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class RawCrop(val left: Int, val top: Int, val width: Int, val height: Int) {
    init {
        require(left >= 0 && top >= 0) { "RAW crop origin must be non-negative" }
        require(width > 0 && height > 0) { "RAW crop must have positive dimensions" }
    }
}

data class RawPlaneLayout(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val sensorOriginX: Int = 0,
    val sensorOriginY: Int = 0
) {
    init {
        require(width > 0 && height > 0) { "RAW plane must have positive dimensions" }
        require(pixelStride >= U16_BYTES) { "RAW_SENSOR pixel stride must hold 16 bits" }
        require(rowStride >= (width - 1) * pixelStride + U16_BYTES) {
            "RAW_SENSOR row stride is too short for the declared width"
        }
    }

    private companion object {
        const val U16_BYTES = 2
    }
}

data class RawNormalization(
    val sensorPattern: BayerPattern,
    /** Camera2 reading order: top-left, top-right, bottom-left, bottom-right. */
    val blackLevels: List<Float>,
    val whiteLevel: Float
) {
    init {
        require(blackLevels.size == 4) { "Exactly four CFA black levels are required" }
        require(blackLevels.all(Float::isFinite)) { "Black levels must be finite" }
        require(whiteLevel.isFinite()) { "White level must be finite" }
        require(blackLevels.all { whiteLevel > it }) {
            "White level must be greater than every black level"
        }
    }

    fun blackAt(sensorX: Int, sensorY: Int): Float =
        blackLevels[((sensorY and 1) shl 1) or (sensorX and 1)]
}

data class UnpackedRawCfa(
    val width: Int,
    val height: Int,
    val pattern: BayerPattern,
    val values: FloatArray,
    val sourceCrop: RawCrop,
    /** Full-sensor coordinates of values[0], required for CFA and calibration-map parity. */
    val sensorCropLeft: Int = 0,
    val sensorCropTop: Int = 0
) {
    fun requireAmazeCompatible() {
        require(width >= 4 && height >= 4 && width % 2 == 0 && height % 2 == 0) {
            "AMaZE requires an even Bayer crop of at least 4x4 pixels"
        }
    }
}

/**
 * Unpacks a 16-bit RAW_SENSOR plane without clipping normalized values. The input buffer's
 * current position is its data origin; padding after the final row is never assumed mapped.
 */
object RawSensorUnpacker {
    fun unpackNormalized(
        source: ByteBuffer,
        layout: RawPlaneLayout,
        normalization: RawNormalization,
        crop: RawCrop = RawCrop(0, 0, layout.width, layout.height),
        byteOrder: ByteOrder = ByteOrder.nativeOrder()
    ): UnpackedRawCfa {
        require(crop.left + crop.width <= layout.width && crop.top + crop.height <= layout.height) {
            "RAW crop exceeds the plane bounds"
        }

        val input = source.duplicate().order(byteOrder)
        val dataOrigin = input.position()
        val lastIndex = dataOrigin +
            (crop.top + crop.height - 1).toLong() * layout.rowStride +
            (crop.left + crop.width - 1).toLong() * layout.pixelStride
        require(lastIndex + U16_BYTES <= input.limit().toLong()) {
            "RAW_SENSOR buffer is truncated for its stride and crop"
        }

        val output = FloatArray(crop.width * crop.height)
        var outputIndex = 0
        for (y in 0 until crop.height) {
            val planeY = crop.top + y
            val sensorY = layout.sensorOriginY + planeY
            val rowStart = dataOrigin + planeY * layout.rowStride + crop.left * layout.pixelStride
            for (x in 0 until crop.width) {
                val planeX = crop.left + x
                val sensorX = layout.sensorOriginX + planeX
                val code = input.getShort(rowStart + x * layout.pixelStride).toInt() and 0xffff
                val black = normalization.blackAt(sensorX, sensorY)
                output[outputIndex++] = (code - black) / (normalization.whiteLevel - black)
            }
        }
        val sensorCropX = layout.sensorOriginX + crop.left
        val sensorCropY = layout.sensorOriginY + crop.top
        return UnpackedRawCfa(
            crop.width,
            crop.height,
            normalization.sensorPattern.shifted(sensorCropX, sensorCropY),
            output,
            crop,
            sensorCropX,
            sensorCropY
        )
    }

    private const val U16_BYTES = 2L
}

sealed interface RawBufferGeometry {
    data class Supported(
        val sensorOriginX: Int,
        val sensorOriginY: Int,
        val processingCrop: RawCrop,
        val provenance: String
    ) : RawBufferGeometry

    data class Unsupported(val reason: String) : RawBufferGeometry

    companion object {
        fun resolve(
            imageWidth: Int,
            imageHeight: Int,
            rawCropRegion: IntRectSnapshot?,
            preCorrectionActiveArray: IntRectSnapshot?,
            pixelArrayWidth: Int?,
            pixelArrayHeight: Int?,
            imageCrop: IntRectSnapshot = IntRectSnapshot(0, 0, imageWidth, imageHeight)
        ): RawBufferGeometry {
            if (imageCrop.left < 0 || imageCrop.top < 0 || imageCrop.right > imageWidth ||
                imageCrop.bottom > imageHeight || imageCrop.width <= 0 || imageCrop.height <= 0
            ) {
                return Unsupported("RAW Image crop is outside the plane bounds")
            }
            fun Supported.withImageCrop(): RawBufferGeometry {
                val left = maxOf(processingCrop.left, imageCrop.left)
                val top = maxOf(processingCrop.top, imageCrop.top)
                val right = minOf(processingCrop.left + processingCrop.width, imageCrop.right)
                val bottom = minOf(processingCrop.top + processingCrop.height, imageCrop.bottom)
                return if (right > left && bottom > top) {
                    copy(
                        processingCrop = RawCrop(left, top, right - left, bottom - top),
                        provenance = "$provenance + Image.cropRect"
                    )
                } else {
                    Unsupported("RAW Image crop does not intersect the sensor active area")
                }
            }
            if (rawCropRegion != null &&
                rawCropRegion.width == imageWidth && rawCropRegion.height == imageHeight
            ) {
                return Supported(
                    rawCropRegion.left,
                    rawCropRegion.top,
                    RawCrop(0, 0, imageWidth, imageHeight),
                    "SCALER_RAW_CROP_REGION"
                ).withImageCrop()
            }
            if (preCorrectionActiveArray != null &&
                preCorrectionActiveArray.width == imageWidth &&
                preCorrectionActiveArray.height == imageHeight
            ) {
                return Supported(
                    preCorrectionActiveArray.left,
                    preCorrectionActiveArray.top,
                    RawCrop(0, 0, imageWidth, imageHeight),
                    "SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE"
                ).withImageCrop()
            }
            if (preCorrectionActiveArray != null &&
                pixelArrayWidth == imageWidth && pixelArrayHeight == imageHeight &&
                preCorrectionActiveArray.left >= 0 && preCorrectionActiveArray.top >= 0 &&
                preCorrectionActiveArray.right <= imageWidth &&
                preCorrectionActiveArray.bottom <= imageHeight
            ) {
                return Supported(
                    0,
                    0,
                    RawCrop(
                        preCorrectionActiveArray.left,
                        preCorrectionActiveArray.top,
                        preCorrectionActiveArray.width,
                        preCorrectionActiveArray.height
                    ),
                    "SENSOR_INFO_PIXEL_ARRAY_SIZE"
                ).withImageCrop()
            }
            return Unsupported(
                "Cannot prove RAW buffer coordinates from image ${imageWidth}x$imageHeight and sensor metadata"
            )
        }
    }
}
