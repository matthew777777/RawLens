// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.content.Context
import android.hardware.HardwareBuffer

/** Separate packed-ratio variant from third_party/rcd-packed-upstream. */
internal object VfPackedRcd {
    fun init(context: Context, localX: Int = 32, localY: Int = 32, fused: Boolean = false, tuned: Boolean = true): Int {
        if (!VfVulkan.available || !VfVulkan.supportsF16MathNative()) return VfVulkan.BAD_ARGUMENT
        val stages = arrayOf("guide", "green", "median", "output").map {
            context.assets.open("shaders/rcd_packed/rcd_${if (it == "output" && fused) "output_yuv" else it}.spv").use { stream -> stream.readBytes() }
        }.toTypedArray()
        val rc = initNative(stages, localX, localY, fused, tuned)
        return if (rc == VfVulkan.BAD_ARGUMENT && (localX > 16 || localY > 16))
            initNative(stages, 16, 16, fused, tuned) else rc
    }

    external fun initNative(stages: Array<ByteArray>, localX: Int, localY: Int, fused: Boolean = false, tuned: Boolean = false): Int
    /** Runs all four stages as one GPU submission, waiting before RAW may be recycled. */
    external fun processNative(raw: HardwareBuffer, rgb: HardwareBuffer,
                               iparams: IntArray, fparams: FloatArray,
                               shadeDims: IntArray? = null, shadeGains: ShortArray? = null): Int
    external fun processYuvNative(raw: HardwareBuffer, p010: HardwareBuffer,
                                  iparams: IntArray, fparams: FloatArray,
                                  grade: FloatArray, strides: IntArray,
                                  shadeDims: IntArray? = null, shadeGains: ShortArray? = null): Int
    /** Returns a completion fd, or -1 when GPU completion was already proved. */
    external fun submitYuvNative(raw: HardwareBuffer, p010: HardwareBuffer,
                                 iparams: IntArray, fparams: FloatArray,
                                 grade: FloatArray, strides: IntArray, slot: Int,
                                 shadeDims: IntArray? = null, shadeGains: ShortArray? = null): Int
    external fun validateP010Native(buffer: HardwareBuffer, width: Int, height: Int): Int
    external fun timingsNative(): FloatArray?
}
