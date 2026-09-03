// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import java.nio.ByteOrder

data class RgbHistogram(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val fromRaw: Boolean
)

/**
 * Samples the sensor mosaic without demosaicing; green combines both green CFA sites.
 *
 * Keep the live ZSL sampler deliberately sparse. This runs on the camera callback thread because
 * the Image must remain owned by the ZSL ring, so excessive sampling directly increases callback
 * latency. ~8k Bayer blocks (up to ~32k channel samples) is ample for a 64-bin live histogram
 * while leaving significantly more headroom for preview composition and RAW pairing.
 */
object RawHistogramSampler {
    private const val BIN_COUNT = 64
    private const val TARGET_BLOCKS = 8_000

    fun sample(image: Image, characteristics: CameraCharacteristics): RgbHistogram? {
        val plane = image.planes.singleOrNull() ?: return null
        if (plane.pixelStride < 2 || image.width < 2 || image.height < 2) return null
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: return null
        if (cfa !in 0..3) return null
        val black = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val white = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?.coerceAtLeast(1) ?: return null
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val red = IntArray(BIN_COUNT)
        val green = IntArray(BIN_COUNT)
        val blue = IntArray(BIN_COUNT)
        val blocksWide = image.width / 2
        val blocksHigh = image.height / 2
        val blockStep = kotlin.math.sqrt(
            (blocksWide.toLong() * blocksHigh / TARGET_BLOCKS.toDouble()).coerceAtLeast(1.0)
        ).toInt().coerceAtLeast(1)

        var blockY = 0
        while (blockY < blocksHigh) {
            var blockX = 0
            while (blockX < blocksWide) {
                for (dy in 0..1) for (dx in 0..1) {
                    val x = blockX * 2 + dx
                    val y = blockY * 2 + dy
                    val offset = y * plane.rowStride + x * plane.pixelStride
                    if (offset + 1 >= buffer.limit()) continue
                    val value = buffer.getShort(offset).toInt() and 0xffff
                    val floor = black?.getOffsetForIndex(x, y) ?: 0
                    val bin = (((value - floor).coerceAtLeast(0).toLong() * (BIN_COUNT - 1)) /
                        (white - floor).coerceAtLeast(1)).toInt().coerceIn(0, BIN_COUNT - 1)
                    when (colorAt(cfa, x and 1, y and 1)) {
                        0 -> red[bin]++
                        1 -> green[bin]++
                        2 -> blue[bin]++
                    }
                }
                blockX += blockStep
            }
            blockY += blockStep
        }
        return RgbHistogram(red, green, blue, fromRaw = true)
    }

    private fun colorAt(cfa: Int, x: Int, y: Int): Int = when (cfa) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB ->
            if (y == 0) if (x == 0) 0 else 1 else if (x == 0) 1 else 2
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG ->
            if (y == 0) if (x == 0) 1 else 0 else if (x == 0) 2 else 1
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG ->
            if (y == 0) if (x == 0) 1 else 2 else if (x == 0) 0 else 1
        else -> if (y == 0) if (x == 0) 2 else 1 else if (x == 0) 1 else 0
    }
}
