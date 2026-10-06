// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer

/**
 * MHC-family demosaic (see `vf_mhc.comp`): edge-directed green (the
 * fixed MHC green kernel zippers) plus edge-directed
 * difference-domain chroma (the fixed chroma kernels halo in cyan/red
 * around contrast edges), negatives clamped like RawTherapee,
 * full-res float RGB for the Direct-Log RECORD path at video rate
 * (single dispatch). The viewfinder never initializes this.
 *
 * Init order (shared device): [VfVulkan.initNative] then [VfLogGrade]
 * (twin command buffers live with the grade init, as for RCD/YUV).
 * Submits export a completion fd (grade convention); the recorder runs
 * MHC right before the grade (whose fd carries completion), probes
 * poll the MHC fd for GPU timings.
 */
internal object VfMhc {
    val available: Boolean get() = VfVulkan.available

    fun describe(code: Int): String = VfVulkan.describe(code)

    /**
     * Pack MHC params: same 72-byte push block as RCD (dims, crop
     * origin (CFA phase), quad-offset map (channels[] convention),
     * sensor pitch, mode 0; per-quad normalize bias + inv-ranges).
     *
     * The MHC shader normalizes taps as `fma(code, inv, bias)` (one
     * instruction vs sub+mul), so words 0..3 carry the FMA bias
     * `-black * invRange` rather than the black levels themselves
     * (same layout, reinterpreted; RCD keeps black levels).
     */
    fun packMhc(
        width: Int, height: Int,
        left: Int, top: Int,
        channels: IntArray,
        pitch: Int,
        levels: FloatArray,
        white: Float,
    ): Pair<IntArray, FloatArray> {
        require(channels.size == 4 && levels.size == 4)
        val iparams = intArrayOf(
            width, height, left, top,
            channels[0], channels[1], channels[2], channels[3],
            pitch, 0
        )
        val inv = FloatArray(4) { i -> 1f / (white - levels[i]).coerceAtLeast(1f) }
        val fparams = FloatArray(8) { i ->
            if (i < 4) -levels[i] * inv[i] else inv[i - 4]
        }
        return iparams to fparams
    }

    /** Create the MHC pipeline from SPIR-V bytes. Idempotent. */
    external fun initMhcNative(spv: ByteArray): Int

    /**
     * Run the MHC demosaic: CFA storage buffer [cfaBuffer] to full-res
     * rgb image [rgbBuffer] (fresh overwrite). Returns the completion
     * sync fd (>= 0; poll with [VfEglImport.pollSyncFd], close with
     * [VfEglImport.closeSyncFd]) or a negative code.
     *
     * @param iparams 10 ints from [packMhc]; @param fparams 8 floats.
     * @param slot 0..2 ping-pong selector.
     */
    external fun mhcSubmitNative(
        cfaBuffer: HardwareBuffer,
        rgbBuffer: HardwareBuffer,
        iparams: IntArray,
        fparams: FloatArray,
        slot: Int,
    ): Int
}
