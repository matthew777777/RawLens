// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Copies one aligned Bayer quad per display texel into canonical R/Gr/Gb/B order.
 *
 * Hot path for the 30 Hz RAW viewfinder: the inner loop multiplies by a
 * precomputed reciprocal instead of dividing, and packed 16-bit rows
 * (`pixelStride == 2`, even `rowStride`) are bulk-fetched once per visited
 * row so per-sample access is a plain array load. Generic strides keep the
 * checked `ByteBuffer` fallback for correctness.
 */
internal object RawPreviewSampler {
    fun copy(source: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int,
             width: Int, height: Int, step: Int, channels: IntArray, black: FloatArray,
             white: Float, destination: ByteBuffer) {
        require(left % 2 == 0 && top % 2 == 0 && step >= 2 && step % 2 == 0)
        require(width > 0 && height > 0 && width <= 960 && height <= 960)
        // Reciprocal normalization: one multiply per sample, no per-pixel division.
        val invRange = FloatArray(4) { i -> 1f / (white - black[i]).coerceAtLeast(1f) }
        val blackCh = floatArrayOf(black[0], black[1], black[2], black[3])
        destination.clear()
        if (pixelStride == 2 && rowStride % 2 == 0 && tryBulkCopy(
                source, rowStride, left, top, width, height, step,
                channels, blackCh, invRange, destination
            )
        ) {
            destination.flip()
            return
        }
        val input = source.duplicate().order(ByteOrder.nativeOrder())
        val base = input.position()
        for (y in 0 until height) for (x in 0 until width) for (c in 0..3) {
            val channel = channels[c]
            val sx = left + x * step + channel % 2
            val sy = top + y * step + channel / 2
            val value = input.getShort(base + sy * rowStride + sx * pixelStride).toInt() and 65535
            val normalized = ((value - blackCh[channel]) * invRange[channel]).coerceIn(0f, 1f)
            destination.put((normalized * 255f + 0.5f).toInt().toByte())
        }
        destination.flip()
    }

    /**
     * Bulk-row fast path for packed RAW_SENSOR (`pixelStride == 2`).
     * Returns false when the buffer is too short so the caller falls back.
     */
    private fun tryBulkCopy(
        source: ByteBuffer, rowStride: Int, left: Int, top: Int,
        width: Int, height: Int, step: Int, channels: IntArray,
        black: FloatArray, invRange: FloatArray, destination: ByteBuffer
    ): Boolean {
        val input = source.duplicate().order(ByteOrder.nativeOrder())
        val shortView = input.asShortBuffer()
        val shortCapacity = shortView.capacity()
        val shortsPerRow = rowStride / 2
        // Bounds check the last visited sample up front; per-sample checks stay out
        // of the hot loop afterwards.
        val lastX = left + (width - 1) * step + 1
        val lastY = top + (height - 1) * step + 1
        if (lastX < 0 || lastY < 0) return false
        if ((lastY.toLong() * shortsPerRow + lastX + 1) > shortCapacity) return false
        if ((top.toLong() * shortsPerRow + left) < 0) return false
        // Cache channel geometry: dx/dy per output position.
        val dx = IntArray(4) { channels[it] % 2 }
        val dy = IntArray(4) { channels[it] / 2 }
        val baseY = top
        for (y in 0 until height) {
            val quadTop = baseY + y * step
            for (x in 0 until width) {
                val quadLeft = left + x * step
                // Unrolled 4-channel superpixel: canonical R/Gr/Gb/B order.
                for (c in 0..3) {
                    val code = shortView.get((quadTop + dy[c]) * shortsPerRow + quadLeft + dx[c]).toInt() and 0xffff
                    val ch = channels[c]
                    val n = ((code - black[ch]) * invRange[ch]).coerceIn(0f, 1f)
                    destination.put((n * 255f + 0.5f).toInt().toByte())
                }
            }
        }
        return true
    }
}
