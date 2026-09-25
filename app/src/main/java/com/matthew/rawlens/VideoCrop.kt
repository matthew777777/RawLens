// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

/**
 * RAW Video crop selector. Crops are applied inside the MediaCinemaRAW
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

    /**
     * Centered crop for a [sensorW]x[sensorH] frame. Guarantees the encoder
     * contract: even [Resolved.top], [Resolved.height] % 4 == 0,
     * top + height <= sensorH.
     */
    fun resolve(sensorW: Int, sensorH: Int): Resolved {
        require(sensorW > 0 && sensorW % 2 == 0) { "Bad sensor width $sensorW" }
        require(sensorH > 0 && sensorH % 4 == 0) { "Bad sensor height $sensorH" }
        val r = ratio ?: return Resolved(0, sensorH / 4 * 4, sensorW)
        var h = (sensorW / r).toInt() / 4 * 4
        if (h <= 0 || h > sensorH) h = sensorH / 4 * 4
        var top = (sensorH - h) / 2
        top -= top % 2
        return Resolved(top, h, sensorW)
    }
}
