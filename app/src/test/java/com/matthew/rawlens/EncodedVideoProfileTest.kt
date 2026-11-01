// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedVideoProfileTest {
    @Test
    fun wireIdsStableAndUnique() {
        // Wire ids ride the grade params to native; BT709 must stay 0
        // (packGrade default + native fallback for short arrays).
        assertEquals(0, EncodedVideoProfile.BT709.wireId)
        assertEquals(1, EncodedVideoProfile.SLOG3.wireId)
        assertEquals(2, EncodedVideoProfile.HLG.wireId)
        val ids = EncodedVideoProfile.entries.map { it.wireId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun fromNameRoundTripsAndHeals() {
        for (p in EncodedVideoProfile.entries) {
            assertEquals(p, EncodedVideoProfile.fromName(p.name))
        }
        assertEquals(EncodedVideoProfile.BT709, EncodedVideoProfile.fromName(null))
        assertEquals(EncodedVideoProfile.BT709, EncodedVideoProfile.fromName(""))
        assertEquals(EncodedVideoProfile.BT709, EncodedVideoProfile.fromName("PUNCHY"))
    }

    @Test
    fun fromWireIdRoundTripsAndHeals() {
        for (p in EncodedVideoProfile.entries) {
            assertEquals(p, EncodedVideoProfile.fromWireId(p.wireId))
        }
        assertEquals(EncodedVideoProfile.BT709, EncodedVideoProfile.fromWireId(-1))
        assertEquals(EncodedVideoProfile.BT709, EncodedVideoProfile.fromWireId(99))
    }

    @Test
    fun fileSuffixesAreLogPlusHudLabelAndDistinct() {
        // Every profile (BT709 included) names takes `_LOG<HUD>.mp4`;
        // the device tests publish `%_LOG709.mp4` for the default profile.
        for (p in EncodedVideoProfile.entries) {
            assertEquals("_LOG" + p.hudLabel, p.fileSuffix)
        }
        assertEquals("_LOG709", EncodedVideoProfile.BT709.fileSuffix)
        assertEquals("_LOGSLOG3", EncodedVideoProfile.SLOG3.fileSuffix)
        assertEquals("_LOGHLG", EncodedVideoProfile.HLG.fileSuffix)
        val suffixes = EncodedVideoProfile.entries.map { it.fileSuffix }
        assertEquals(suffixes.size, suffixes.toSet().size)
    }

    @Test
    fun sdrProfilesSignalBt709() {
        for (p in listOf(EncodedVideoProfile.BT709, EncodedVideoProfile.SLOG3, EncodedVideoProfile.ACES)) {
            val t = p.targetColors()
            assertEquals(MediaFormat.COLOR_STANDARD_BT709, t.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, t.colorTransfer)
            assertEquals(MediaFormat.COLOR_RANGE_LIMITED, t.colorRange)
        }
    }

    @Test
    fun hlgSignalsBt2020Hlg() {
        val t = EncodedVideoProfile.HLG.targetColors()
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, t.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_HLG, t.colorTransfer)
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, t.colorRange)
    }

    @Test
    fun nextCyclesInHudOrder() {
        assertEquals(EncodedVideoProfile.SLOG3, EncodedVideoProfile.BT709.next())
        assertEquals(EncodedVideoProfile.HLG, EncodedVideoProfile.SLOG3.next())
        assertEquals(EncodedVideoProfile.ACES, EncodedVideoProfile.HLG.next())
        assertEquals(EncodedVideoProfile.BT709, EncodedVideoProfile.ACES.next())
    }

    @Test
    fun hudLabelsShortAndDistinct() {
        val labels = EncodedVideoProfile.entries.map { it.hudLabel }
        assertEquals(labels.size, labels.toSet().size)
        for (l in labels) assertTrue("HUD label too long: $l", l.length <= 5)
    }
}
