// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * RAW Video crop selector. Crops are applied inside the CRAW
 * encoder ([cropTop]/[cropHeight]) — the camera session always streams the
 * full sensor frame, so switching crops never reconfigures the session.
 * Even-row crops preserve Bayer CFA phase, as the encoder requires.
 */
enum class VideoCrop(val label: String, val ratio: Double?) {
    OPEN_GATE("Open Gate", null),
    WIDE_16_9("16:9", 16.0 / 9.0),
    SCOPE_2_39("2.39:1", 2.39),
    UNIVISIUM_2_00("2.00:1", 2.00);

    data class Resolved(val top: Int, val height: Int, val width: Int)

    companion object {
        /**
         * 2x binning needs full width % 4 (the binned width stays even for
         * the tile codec). The height side is always satisfiable: [resolve]
         * trims the crop window to % 8 when [binned].
         */
        fun supportsBinning(sensorW: Int): Boolean = sensorW % 4 == 0
    }

    /**
     * Centered crop for a [sensorW]x[sensorH] frame. Guarantees the encoder
     * contract: even [Resolved.top], [Resolved.height] % 4 == 0,
     * top + height <= sensorH. With [binned], the window additionally
     * satisfies [Resolved.height] % 8 == 0 so the binned height stays
     * % 4 (Open Gate trims up to 7 centered rows when the sensor height is
     * % 4 but not % 8); [supportsBinning] must hold.
     */
    fun resolve(sensorW: Int, sensorH: Int, binned: Boolean = false): Resolved {
        require(sensorW > 0 && sensorW % 2 == 0) { "Bad sensor width $sensorW" }
        require(sensorH > 0 && sensorH % 4 == 0) { "Bad sensor height $sensorH" }
        if (binned) require(supportsBinning(sensorW)) {
            "Binning requires width % 4 == 0, got $sensorW"
        }
        val align = if (binned) 8 else 4
        val full = sensorH / align * align
        require(!binned || full > 0) { "Sensor height $sensorH too small to bin" }
        val r = ratio ?: run {
            var top = (sensorH - full) / 2
            top -= top % 2
            return Resolved(top, full, sensorW)
        }
        var h = (sensorW / r).toInt() / align * align
        if (h <= 0 || h > sensorH) h = full
        var top = (sensorH - h) / 2
        top -= top % 2
        return Resolved(top, h, sensorW)
    }
}
