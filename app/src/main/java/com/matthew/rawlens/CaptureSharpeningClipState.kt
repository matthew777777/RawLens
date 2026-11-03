// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RAWR sensor_clip_pack: R/G1/G2/B bits per 2x2 cell, before LSC or reconstruction. */
class CaptureSharpeningClipState private constructor(
    val width: Int, val height: Int, val pattern: BayerPattern,
    private val cells: ShortArray
) {
    internal fun bitsAt(cellX: Int, cellY: Int): Int = cells[cellY * (width / 2) + cellX].toInt()

    internal fun directBuffer(): ByteBuffer = ByteBuffer.allocateDirect(cells.size * 2)
        .order(ByteOrder.nativeOrder()).apply { asShortBuffer().put(cells) }

    companion object {
        const val CLIP_THRESHOLD = 0.995f // RAWR RawFrameParameters default

        fun fromSensorCfa(cfa: UnpackedRawCfa): CaptureSharpeningClipState {
            require(cfa.width % 2 == 0 && cfa.height % 2 == 0)
            return pack(cfa.width, cfa.height, cfa.pattern) { x, y ->
                val index = y * cfa.width + x
                cfa.highlightClipping?.isClipped(index) ?: (cfa.values[index] >= CLIP_THRESHOLD)
            }
        }

        /** Retains the GPU demosaic path: read only clip bits, without a full float CFA. */
        fun fromRawSensor(raw: ByteBuffer, layout: RawPlaneLayout, normalization: RawNormalization,
                          crop: RawCrop): CaptureSharpeningClipState {
            require(crop.left >= 0 && crop.top >= 0 && crop.left + crop.width <= layout.width &&
                crop.top + crop.height <= layout.height)
            val input = raw.duplicate().order(ByteOrder.nativeOrder())
            val base = input.position()
            val last = base.toLong() + (crop.top + crop.height - 1).toLong() * layout.rowStride +
                (crop.left + crop.width - 1).toLong() * layout.pixelStride + 2
            require(last <= input.limit()) { "Truncated RAW sensor clip input" }
            val sx = layout.sensorOriginX + crop.left
            val sy = layout.sensorOriginY + crop.top
            return pack(crop.width, crop.height, normalization.sensorPattern.shifted(sx, sy)) { x, y ->
                val code = input.getShort(base + (crop.top + y) * layout.rowStride +
                    (crop.left + x) * layout.pixelStride).toInt() and 0xffff
                val black = normalization.blackAt(sx + x, sy + y)
                // Same subtraction and inverse-range multiply as RAWR sensor_clip_pack.
                (code - black) * (1f / (normalization.whiteLevel - black)) >= CLIP_THRESHOLD
            }
        }

        private inline fun pack(width: Int, height: Int, pattern: BayerPattern,
                                clipped: (Int, Int) -> Boolean): CaptureSharpeningClipState {
            require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
            val labels = when (pattern) {
                BayerPattern.RGGB -> intArrayOf(0, 1, 2, 3)
                BayerPattern.GRBG -> intArrayOf(1, 0, 3, 2)
                BayerPattern.GBRG -> intArrayOf(2, 3, 0, 1)
                BayerPattern.BGGR -> intArrayOf(3, 2, 1, 0)
            }
            val cells = ShortArray(width / 2 * (height / 2))
            for (y in 0 until height step 2) for (x in 0 until width step 2) {
                var bits = 0
                for (phase in 0..3) {
                    if (clipped(x + (phase and 1), y + (phase shr 1))) bits = bits or (1 shl labels[phase])
                }
                cells[y / 2 * (width / 2) + x / 2] = bits.toShort()
            }
            return CaptureSharpeningClipState(width, height, pattern, cells)
        }
    }
}
