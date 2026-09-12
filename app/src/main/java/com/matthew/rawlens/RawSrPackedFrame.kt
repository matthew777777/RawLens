// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer

/** Borrowed Camera2 RAW_SENSOR plane. The capture owns the Image until processing returns.
 * Position/limit and metadata are frozen; pixel storage is deliberately not copied.
 * RAW10/RAW12 and non-two-byte pixel strides are not supported by this RAW16 executor.
 */
class RawSrPackedFrame(
    plane: ByteBuffer,
    val layout: RawPlaneLayout,
    val crop: RawCrop,
    normalization: RawNormalization,
    lensShading: LensShadingModel?,
    noiseProfile: ImmutableDoubleValues? = null
) {
    private val planeView = plane.asReadOnlyBuffer()
    private val black = normalization.blackLevels.toFloatArray()
    private val sensorPattern = normalization.sensorPattern
    private val white = normalization.whiteLevel
    private val shading = lensShading?.copy(gains = lensShading.gains.copyOf())
    val noiseProfile = noiseProfile?.let { ImmutableDoubleValues(it.toDoubleArray()) }
    val width get() = crop.width
    val height get() = crop.height
    val pattern get() = sensorPattern.shifted(layout.sensorOriginX + crop.left, layout.sensorOriginY + crop.top)

    init {
        require(layout.pixelStride == 2 && layout.rowStride % 2 == 0)
        require(crop.left.toLong() + width <= layout.width && crop.top.toLong() + height <= layout.height)
        val end = planeView.position().toLong() + (crop.top.toLong() + height - 1) * layout.rowStride +
            (crop.left.toLong() + width - 1) * 2 + 2
        require(end <= planeView.limit()) { "Truncated RAW16 plane" }
    }

    internal fun uploadInput() = GpuRawAmazeInput(planeView.duplicate(), layout, crop,
        RawNormalization(sensorPattern, black.toList(), white), shading?.copy(gains = shading.gains.copyOf()))

    companion object {
        fun fromMetadata(plane: ByteBuffer, metadata: RawFrameMetadata): RawSrPackedFrame {
            val geometry = metadata.bufferGeometry as? RawBufferGeometry.Supported
                ?: throw IllegalArgumentException("Unsupported RAW sensor geometry")
            return RawSrPackedFrame(plane,
                RawPlaneLayout(metadata.imageWidth, metadata.imageHeight,
                    requireNotNull(metadata.rawPlaneRowStride), requireNotNull(metadata.rawPlanePixelStride),
                    geometry.sensorOriginX, geometry.sensorOriginY),
                geometry.processingCrop, requireNotNull(metadata.normalizationOrNull()),
                RawPreDemosaicPipeline.lensShadingModel(metadata), metadata.noiseProfile)
        }
    }
}

/** Actual live texture-storage accounting, excluding driver overhead and borrowed Camera2 planes. */
class RawSrTextureMemory {
    var liveBytes = 0L; private set
    var peakBytes = 0L; private set
    fun allocate(bytes: Long) {
        require(bytes >= 0)
        liveBytes = Math.addExact(liveBytes, bytes)
        peakBytes = maxOf(peakBytes, liveBytes)
    }
    fun release(bytes: Long) {
        require(bytes in 0..liveBytes)
        liveBytes -= bytes
    }
}
