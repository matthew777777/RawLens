// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Full-resolution grouped CFA. This must never enter the regular Bayer AMaZE graph. */
data class QuadBayerFrame(val samples: UnpackedRawCfa, val sensorPattern: BayerPattern)

internal object QuadBayerPreparation {
    fun unpack(
        source: ByteBuffer, layout: RawPlaneLayout, normalization: RawNormalization,
        crop: RawCrop, lens: LensShadingModel?,
        byteOrder: ByteOrder = ByteOrder.nativeOrder()
    ): QuadBayerFrame {
        // Camera2 blackLevelPattern is a repeating 2x2 offset tile, independent
        // of the larger color-filter group. Preserve per-photosite offsets.
        val samples = RawSensorUnpacker.unpackNormalized(source, layout, normalization, crop, byteOrder)
        require(samples.width >= 4 && samples.height >= 4)
        if (lens != null && !lens.alreadyApplied) {
            RawSrWorkers.forEachShard(samples.height) { y0, y1 ->
                for (y in y0 until y1) for (x in 0 until samples.width) {
                    val sx = samples.sensorCropLeft + x
                    val sy = samples.sensorCropTop + y
                    val color = normalization.sensorPattern.colorAt(sx / 2, sy / 2)
                    samples.values[y * samples.width + x] *= lens.gainAt(sx, sy, color, sy / 2)
                }
            }
        }
        return QuadBayerFrame(samples, normalization.sensorPattern)
    }
}
