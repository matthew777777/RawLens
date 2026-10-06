// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer

/**
 * RCD demosaic (RawTherapee v2.3 port, see `vf_rcd.comp`): full-res float
 * RGB for the Direct-Log RECORD path. One pipeline, four mode dispatches
 * (0 directions + low-pass to scratch, 1 green, 2 R/B at opposite, 3 R/B
 * at green); every mode stages a shared-memory CFA tile first. The
 * viewfinder never initializes this.
 *
 * Init order (shared device): [VfVulkan.initNative] then [VfLogGrade]
 * (twin command buffers live with the grade init, as for grade-YUV).
 * Submits export a completion fd each (grade convention); the recorder
 * closes the intermediate fds, probes poll them for per-mode GPU timings.
 */
internal object VfRcd {
    val available: Boolean get() = VfVulkan.available

    fun describe(code: Int): String = VfVulkan.describe(code)

    const val MODE_DIRS = 0
    const val MODE_GREEN = 1
    const val MODE_OPPOSITE = 2
    const val MODE_AT_GREEN = 3

    /**
     * Pack RCD params. C++ reads iparams[10] + fparams[8] into the 72-byte
     * push block: dims, crop origin (CFA phase), quad-offset map
     * (channels[] convention), sensor pitch, mode; per-quad
     * black levels + inv-ranges.
     */
    /**
     * Quad-offset map for [packRcd] from a crop-local [BayerPattern]:
     * channels[color] is the quad holding it, Gr/Gb ordered (green in
     * the R row first). Pure and JVM-testable.
     */
    fun channelsFromPattern(pattern: BayerPattern): IntArray {
        val quadColor = IntArray(4) { q -> pattern.colorOrdinalAt(q and 1, q shr 1) }
        val rQuad = quadColor.indexOf(CfaColor.RED.ordinal)
        val bQuad = quadColor.indexOf(CfaColor.BLUE.ordinal)
        require(rQuad >= 0 && bQuad >= 0) { "pattern $pattern has no R/B quad" }
        val rRow = rQuad shr 1
        var gr = -1
        var gb = -1
        for (q in 0..3) {
            if (quadColor[q] != CfaColor.GREEN.ordinal) continue
            if ((q shr 1) == rRow) gr = q else gb = q
        }
        require(gr >= 0 && gb >= 0) { "pattern $pattern has no Gr/Gb pair" }
        return intArrayOf(rQuad, gr, gb, bQuad)
    }

    fun packRcd(
        width: Int, height: Int,
        left: Int, top: Int,
        channels: IntArray,
        pitch: Int,
        mode: Int,
        levels: FloatArray,
        white: Float,
    ): Pair<IntArray, FloatArray> {
        require(channels.size == 4 && levels.size == 4)
        require(mode in 0..3)
        val iparams = intArrayOf(
            width, height, left, top,
            channels[0], channels[1], channels[2], channels[3],
            pitch, mode
        )
        val fparams = FloatArray(8) { i ->
            if (i < 4) levels[i] else 1f / (white - levels[i - 4]).coerceAtLeast(1f)
        }
        return iparams to fparams
    }

    /** Create the RCD pipeline from SPIR-V bytes. Idempotent. */
    external fun initRcdNative(spv: ByteArray): Int

    /**
     * Run one RCD mode: CFA storage buffer [cfaBuffer] + full-res rgb
     * image [rgbBuffer] (written mode 1, refined 2-3) + direction
     * scratch [scratchBuffer] (written mode 0, read 1-3). Returns the
     * completion sync fd (>= 0; poll with [VfEglImport.pollSyncFd],
     * close with [VfEglImport.closeSyncFd]) or a negative code.
     *
     * @param iparams 10 ints from [packRcd]; @param fparams 8 floats.
     * @param slot 0..2 ping-pong selector.
     */
    external fun rcdSubmitNative(
        cfaBuffer: HardwareBuffer,
        rgbBuffer: HardwareBuffer,
        scratchBuffer: HardwareBuffer,
        iparams: IntArray,
        fparams: FloatArray,
        slot: Int,
    ): Int
}
