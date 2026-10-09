// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.MediaFormat

/**
 * Direct-Log record output profile: the final encode applied to developed
 * linear (WB/CCM/exposure-folded) sensor RGB before the YUV pack.
 *
 * All profiles share the front end (MHC demosaic, per-frame WB/CCM, LOG EV);
 * they differ only in the output transfer, gamut matrix, and container
 * signalling. The enum is the extension point for later work (full
 * S-Gamut3/S-Log3, further log curves, technical/creative LUTs): a new
 * profile adds a wire id, an encode branch in the two record shaders
 * (`vf_gradeyuv.comp`, `vf_mhcyuv.comp`), a CPU golden in [VfLogGrade],
 * and container colors here.
 *
 * The record path currently applies NO contrast curve and NO saturation in
 * any profile (direct encode); the developed-look warp is dormant until the
 * look/LUT work lands.
 */
enum class DirectLogProfile(
    /** Stable wire id carried in the grade params ([VfLogGrade.packGrade] slot 17). */
    val wireId: Int,
    /** Short HUD label (right chip in LOG mode). */
    val hudLabel: String,
    /** MP4 filename suffix (`_LOG` + HUD label). */
    val fileSuffix: String,
) {
    /** Ready-to-watch SDR: linear -> BT.709 OETF -> BT.709 YUV. Default. */
    BT709(0, "709", "_LOG709"),

    /** Flat log for later grading: linear -> Sony S-Log3 -> BT.709 YUV. */
    SLOG3(1, "SLOG3", "_LOGSLOG3"),

    /** Ready-to-watch HDR: linear sRGB -> BT.2020 -> HLG OETF -> BT.2020 YUV. */
    HLG(2, "HLG", "_LOGHLG");

    /** Container color signalling for the encoder format ([LogVideoProbe.Target]). */
    fun targetColors(): LogVideoProbe.Target =
        when (this) {
            BT709, SLOG3 -> LogVideoProbe.Target(
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            )
            HLG -> LogVideoProbe.Target(
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            )
        }

    /** Next profile in HUD cycle order (BT709 -> SLOG3 -> HLG -> BT709). */
    fun next(): DirectLogProfile {
        val entries = entries
        return entries[(entries.indexOf(this) + 1) % entries.size]
    }

    companion object {
        /** Parses a persisted profile name; unknown/blank heals to [BT709]. */
        fun fromName(name: String?): DirectLogProfile =
            try {
                if (name.isNullOrBlank()) BT709 else valueOf(name)
            } catch (_: IllegalArgumentException) {
                BT709
            }

        /** Parses a wire id; unknown heals to [BT709]. */
        fun fromWireId(id: Int): DirectLogProfile =
            entries.firstOrNull { it.wireId == id } ?: BT709
    }
}
