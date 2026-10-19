package com.matthew.rawlens

import android.hardware.HardwareBuffer
import java.nio.ByteBuffer

/**
 * Stage-1 Vulkan averaging for the 0, -2, 0 trio: same-exposure Bayer
 * pair merged under CPU tile shifts ([HdrSeqMerge.estimateTileShifts]).
 *
 * Shares the `rawLensDualRaw` device/context (zero-copy AHB imports, no
 * second device) with [DualRawVulkan]; the caller serializes the entire
 * job. Falls back to [HdrSeqMerge.mergeZeroPair] where unavailable — the
 * CPU path is the parity reference.
 */
internal object HdrSeqVulkan {
    val available = runCatching { System.loadLibrary("rawLensDualRaw") }.isSuccess
    external fun init(bootstrap: ByteArray, shader: ByteArray): Int
    external fun mergeAvg(
        ref: HardwareBuffer,
        alt: HardwareBuffer,
        /** width, height, ref pitch in uint16, alt pitch in uint16. */
        geometry: IntArray,
        /** Tile grid width, height, tile edge in green px. */
        grid: IntArray,
        /** Full-res (dx, dy) float pairs in tile order. */
        shifts: ByteBuffer,
        /** blackRef[4] + blackAlt[4] + white, noiseScale, noiseOffset, rejectK. */
        levels: FloatArray,
        output: ByteBuffer,
        timings: DoubleArray
    ): Int
    external fun close()
}
