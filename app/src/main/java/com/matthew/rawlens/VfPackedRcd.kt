// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.hardware.HardwareBuffer

/** Separate packed-ratio variant from third_party/rcd-packed-upstream. */
internal object VfPackedRcd {
    fun init(context: Context, localX: Int = 16, localY: Int = 16, fused: Boolean = false): Int {
        if (!VfVulkan.available || !VfVulkan.supportsF16MathNative()) return VfVulkan.BAD_ARGUMENT
        val stages = arrayOf("guide", "green", "median", "output").map {
            context.assets.open("shaders/rcd_packed/rcd_${if (it == "output" && fused) "output_yuv" else it}.spv").use { stream -> stream.readBytes() }
        }.toTypedArray()
        return initNative(stages, localX, localY, fused)
    }

    external fun initNative(stages: Array<ByteArray>, localX: Int, localY: Int, fused: Boolean = false): Int
    /** Runs all four stages as one GPU submission, waiting before RAW may be recycled. */
    external fun processNative(raw: HardwareBuffer, rgb: HardwareBuffer,
                               iparams: IntArray, fparams: FloatArray): Int
    external fun processYuvNative(raw: HardwareBuffer, p010: HardwareBuffer,
                                  iparams: IntArray, fparams: FloatArray,
                                  grade: FloatArray, strides: IntArray): Int
    external fun timingsNative(): FloatArray?
}
