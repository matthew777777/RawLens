package com.matthew.rawlens

import android.hardware.HardwareBuffer
import java.nio.ByteBuffer

/** Separate device/context from the live viewfinder. Caller serializes the entire job. */
internal object DualRawVulkan {
    val available = runCatching { System.loadLibrary("rawLensDualRaw") }.isSuccess
    external fun init(bootstrap: ByteArray, shader: ByteArray): Int
    external fun merge(low: HardwareBuffer, high: HardwareBuffer, geometry: IntArray,
                       levels: FloatArray, output: ByteBuffer, timings: DoubleArray): Int
    external fun close()
}
