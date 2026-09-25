// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Shared RAW upload descriptor (unified phone/desktop bytes): extracted from
// the retired GLES host file; same-package users resolve it unchanged.
package com.matthew.rawlens

import java.nio.ByteBuffer

/** RAW plane description retained only until the GPU has copied its integer sensor codes. */
data class GpuRawAmazeInput(
    val buffer: ByteBuffer,
    val layout: RawPlaneLayout,
    val crop: RawCrop,
    val normalization: RawNormalization,
    val lensShading: LensShadingModel?
) {
    val width: Int get() = crop.width
    val height: Int get() = crop.height
    val sensorCropLeft: Int get() = layout.sensorOriginX + crop.left
    val sensorCropTop: Int get() = layout.sensorOriginY + crop.top
    val pattern: BayerPattern get() = normalization.sensorPattern.shifted(sensorCropLeft, sensorCropTop)

    init {
        require(layout.pixelStride == Short.SIZE_BYTES) {
            "Direct GPU RAW upload requires a packed 16-bit pixel stride"
        }
        require(layout.rowStride % Short.SIZE_BYTES == 0) {
            "Direct GPU RAW upload requires an even byte row stride"
        }
        require(crop.left + crop.width <= layout.width && crop.top + crop.height <= layout.height)
    }
}
