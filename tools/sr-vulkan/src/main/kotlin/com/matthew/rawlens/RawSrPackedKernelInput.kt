// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/** KernelNet's existing quad-mean luma domain, directly from frozen packed RAW16. */
internal object RawSrPackedKernelInput {
    fun luma(frame: RawSrPackedFrame, scratch: FloatBuffer): FloatBuffer {
        require(frame.width % 2 == 0 && frame.height % 2 == 0)
        val w = frame.width / 2
        val h = frame.height / 2
        require(scratch.isDirect && scratch.capacity() >= w * h)
        scratch.clear()
        val raw = frame.uploadInput()
        val bytes = raw.buffer.duplicate().order(ByteOrder.nativeOrder())
        val origin = bytes.position() + raw.crop.top * raw.layout.rowStride + raw.crop.left * 2
        val black = FloatArray(4) { raw.normalization.blackAt(
            raw.sensorCropLeft + (it and 1), raw.sensorCropTop + (it shr 1)) }
        val denominator = FloatArray(4) { raw.normalization.whiteLevel - black[it] }
        RawSrWorkers.forEachShard(h) { y0, y1 ->
            for (y in y0 until y1) for (x in 0 until w) {
                val start = origin + y * 2 * raw.layout.rowStride + x * 4
                var mean = 0.0
                for (k in 0..3) {
                    val code = bytes.getShort(start + (k shr 1) * raw.layout.rowStride + (k and 1) * 2).toInt() and 65535
                    val value = ((code - black[k]) / denominator[k]).coerceIn(0f, 1f)
                    if (value.isFinite()) mean += value
                }
                scratch.put(y * w + x, sqrt((mean * .25).toFloat()))
            }
        }
        scratch.rewind()
        return scratch
    }
}
