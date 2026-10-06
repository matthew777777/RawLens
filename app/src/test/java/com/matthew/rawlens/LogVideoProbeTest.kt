// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogVideoProbeTest {
    @Test
    fun targetIsHevc10_4k30_75m() {
        val t = LogVideoProbe.target()
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, t.mime)
        assertEquals(3840, t.width)
        assertEquals(2160, t.height)
        assertEquals(30, t.fps)
        assertEquals(75_000_000, t.bitrate)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, t.profile)
    }

    @Test
    fun targetSignalsBt709Sdr() {
        val t = LogVideoProbe.target()
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, t.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, t.colorTransfer)
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, t.colorRange)
    }

    @Test
    fun formatKeysCarryHevc10Bt709() {
        val keys = LogVideoProbe.formatKeys()
        assertEquals(3840, keys[MediaFormat.KEY_WIDTH])
        assertEquals(2160, keys[MediaFormat.KEY_HEIGHT])
        assertEquals(75_000_000, keys[MediaFormat.KEY_BIT_RATE])
        assertEquals(30, keys[MediaFormat.KEY_FRAME_RATE])
        assertEquals(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            keys[MediaFormat.KEY_BITRATE_MODE]
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            keys[MediaFormat.KEY_PROFILE]
        )
        assertEquals(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            keys[MediaFormat.KEY_COLOR_FORMAT]
        )
        assertEquals(
            MediaFormat.COLOR_STANDARD_BT709,
            keys[MediaFormat.KEY_COLOR_STANDARD]
        )
        assertEquals(
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            keys[MediaFormat.KEY_COLOR_TRANSFER]
        )
        assertEquals(
            MediaFormat.COLOR_RANGE_LIMITED,
            keys[MediaFormat.KEY_COLOR_RANGE]
        )
    }

    @Test
    fun bitrateFitsUhdMain10Level5Budget() {
        // Sanity: 75 Mbps @ 3840x2160p30 is inside HEVC Main10 Level 5.0
        // max bitrate (160 Mbps Main tier). Catches accidental 10x typos.
        val t = LogVideoProbe.target()
        assertTrue(t.bitrate in 1_000_000..160_000_000)
        assertTrue(t.width * t.height == 3840 * 2160)
    }

    @Test
    fun profileLabelsCoverForceModes() {
        assertEquals("Main", LogVideoProbe.profileLabel(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain))
        assertEquals("Main10", LogVideoProbe.profileLabel(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10))
        assertEquals(
            "Main10HDR10",
            LogVideoProbe.profileLabel(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
        )
    }

    @Test
    fun formatKeysHonorOverriddenProfile() {
        val t = LogVideoProbe.target()
            .copy(profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            LogVideoProbe.formatKeys(t)[MediaFormat.KEY_PROFILE]
        )
    }

    @Test
    fun framePtsIsMonotonic33ms() {
        assertEquals(0L, LogVideoProbe.framePtsNs(0))
        assertEquals(33_333_333L, LogVideoProbe.framePtsNs(1))
        assertEquals(66_666_666L, LogVideoProbe.framePtsNs(2))
        assertTrue(LogVideoProbe.framePtsNs(30) == 30 * (1_000_000_000L / 30))
    }
}
