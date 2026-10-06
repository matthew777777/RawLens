// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectLogProfileTest {
    @Test
    fun wireIdsStableAndUnique() {
        // Wire ids ride the grade params to native; BT709 must stay 0
        // (packGrade default + native fallback for short arrays).
        assertEquals(0, DirectLogProfile.BT709.wireId)
        assertEquals(1, DirectLogProfile.SLOG3.wireId)
        assertEquals(2, DirectLogProfile.HLG.wireId)
        val ids = DirectLogProfile.entries.map { it.wireId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun fromNameRoundTripsAndHeals() {
        for (p in DirectLogProfile.entries) {
            assertEquals(p, DirectLogProfile.fromName(p.name))
        }
        assertEquals(DirectLogProfile.BT709, DirectLogProfile.fromName(null))
        assertEquals(DirectLogProfile.BT709, DirectLogProfile.fromName(""))
        assertEquals(DirectLogProfile.BT709, DirectLogProfile.fromName("PUNCHY"))
    }

    @Test
    fun fromWireIdRoundTripsAndHeals() {
        for (p in DirectLogProfile.entries) {
            assertEquals(p, DirectLogProfile.fromWireId(p.wireId))
        }
        assertEquals(DirectLogProfile.BT709, DirectLogProfile.fromWireId(-1))
        assertEquals(DirectLogProfile.BT709, DirectLogProfile.fromWireId(99))
    }

    @Test
    fun fileSuffixesDistinctAndBt709KeepsLegacyName() {
        // The UI test publishes `%_LOG.mp4`: the default profile keeps it.
        assertEquals("_LOG", DirectLogProfile.BT709.fileSuffix)
        val suffixes = DirectLogProfile.entries.map { it.fileSuffix }
        assertEquals(suffixes.size, suffixes.toSet().size)
    }

    @Test
    fun sdrProfilesSignalBt709() {
        for (p in listOf(DirectLogProfile.BT709, DirectLogProfile.SLOG3)) {
            val t = p.targetColors()
            assertEquals(MediaFormat.COLOR_STANDARD_BT709, t.colorStandard)
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, t.colorTransfer)
            assertEquals(MediaFormat.COLOR_RANGE_LIMITED, t.colorRange)
        }
    }

    @Test
    fun hlgSignalsBt2020Hlg() {
        val t = DirectLogProfile.HLG.targetColors()
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, t.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_HLG, t.colorTransfer)
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, t.colorRange)
    }

    @Test
    fun nextCyclesInHudOrder() {
        assertEquals(DirectLogProfile.SLOG3, DirectLogProfile.BT709.next())
        assertEquals(DirectLogProfile.HLG, DirectLogProfile.SLOG3.next())
        assertEquals(DirectLogProfile.BT709, DirectLogProfile.HLG.next())
    }

    @Test
    fun hudLabelsShortAndDistinct() {
        val labels = DirectLogProfile.entries.map { it.hudLabel }
        assertEquals(labels.size, labels.toSet().size)
        for (l in labels) assertTrue("HUD label too long: $l", l.length <= 5)
    }
}
