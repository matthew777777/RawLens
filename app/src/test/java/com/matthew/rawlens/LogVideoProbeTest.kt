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

    @Test
    fun encodeCandidatesTryRequestedThenLadder() {
        val got = LogVideoProbe.encodeCandidates(3840, 2160)
        assertEquals(
            listOf(
                LogVideoProbe.EncodeSize(3840, 2160),
                LogVideoProbe.EncodeSize(2560, 1440),
                LogVideoProbe.EncodeSize(1920, 1080),
            ),
            got
        )
    }

    @Test
    fun encodeCandidatesOffer1440pBelow4K() {
        // A 1440p request offers 1440p then 1080p, never 4K.
        assertEquals(
            listOf(
                LogVideoProbe.EncodeSize(2560, 1440),
                LogVideoProbe.EncodeSize(1920, 1080),
            ),
            LogVideoProbe.encodeCandidates(2560, 1440)
        )
    }

    @Test
    fun encodeCandidatesNeverUpscalePastRequest() {
        // A 1080p request must not offer the 4K rung.
        assertEquals(
            listOf(LogVideoProbe.EncodeSize(1920, 1080)),
            LogVideoProbe.encodeCandidates(1920, 1080)
        )
    }

    @Test
    fun selectEncodeSizeFallsBackTo1080p() {
        val got = LogVideoProbe.selectEncodeSize(3840, 2160, 30) { w, h, _ ->
            w <= 1920 && h <= 1080
        }
        assertEquals(LogVideoProbe.EncodeSize(1920, 1080), got)
    }

    @Test
    fun selectEncodeSizeStopsAt1440pWhenSupported() {
        val got = LogVideoProbe.selectEncodeSize(3840, 2160, 30) { w, h, _ ->
            w <= 2560 && h <= 1440
        }
        assertEquals(LogVideoProbe.EncodeSize(2560, 1440), got)
    }

    @Test
    fun selectEncodeSizeKeepsRequestedWhenSupported() {
        val got = LogVideoProbe.selectEncodeSize(3840, 2160, 30) { _, _, _ -> true }
        assertEquals(LogVideoProbe.EncodeSize(3840, 2160), got)
    }

    @Test
    fun selectEncodeSizeNullWhenNothingSupported() {
        assertEquals(null, LogVideoProbe.selectEncodeSize(3840, 2160, 30) { _, _, _ -> false })
    }

    @Test
    fun clampedBitrateCapsAtAdvertisedMax() {
        assertEquals(40_000_000, LogVideoProbe.clampedBitrate(75_000_000, 40))
        assertEquals(75_000_000, LogVideoProbe.clampedBitrate(75_000_000, 100))
        assertEquals(LogVideoProbe.MIN_BITRATE, LogVideoProbe.clampedBitrate(0, 100))
    }

    @Test
    fun writerPathNeedsApi33() {
        assertEquals(false, LogVideoProbe.writerPathAvailable(32))
        assertEquals(true, LogVideoProbe.writerPathAvailable(33))
        assertEquals(true, LogVideoProbe.writerPathAvailable(34))
    }

    @Test
    fun defaultBitrateHighQualityPerRung() {
        assertEquals(75_000_000, LogVideoProbe.defaultBitrateFor(3840, 2160))
        assertEquals(45_000_000, LogVideoProbe.defaultBitrateFor(2560, 1440))
        assertEquals(30_000_000, LogVideoProbe.defaultBitrateFor(1920, 1080))
        assertTrue(LogVideoProbe.defaultBitrateFor(640, 480) >= 8_000_000)
    }

    @Test
    fun encodeSizeForResHealsTo1080p() {
        assertEquals(
            LogVideoProbe.EncodeSize(1920, 1080),
            LogVideoProbe.encodeSizeForRes(null)
        )
        assertEquals(
            LogVideoProbe.EncodeSize(1920, 1080),
            LogVideoProbe.encodeSizeForRes("HD1080")
        )
        assertEquals(
            LogVideoProbe.EncodeSize(3840, 2160),
            LogVideoProbe.encodeSizeForRes("UHD4K")
        )
        assertEquals(
            LogVideoProbe.EncodeSize(2560, 1440),
            LogVideoProbe.encodeSizeForRes("QHD1440")
        )
        assertEquals(
            LogVideoProbe.EncodeSize(1920, 1080),
            LogVideoProbe.encodeSizeForRes("bogus")
        )
    }

    @Test
    fun fixedFpsRangePrefersExact() {
        val avail = listOf(15 to 30, 24 to 24, 30 to 30)
        assertEquals(24 to 24, LogVideoProbe.fixedFpsRange(24, avail))
        assertEquals(30 to 30, LogVideoProbe.fixedFpsRange(30, avail))
    }

    @Test
    fun fixedFpsRangeFallsBackToNarrowestContainer() {
        val avail = listOf(15 to 30, 10 to 60)
        assertEquals(15 to 30, LogVideoProbe.fixedFpsRange(30, avail))
        assertEquals(null, LogVideoProbe.fixedFpsRange(24, listOf(25 to 30)))
        assertEquals(null, LogVideoProbe.fixedFpsRange(30, emptyList()))
    }

    @Test
    fun selectSdrPlanNeedsBt709() {
        assertEquals(
            null,
            LogVideoProbe.selectSdrPlan(3840, 2160, 30, sdrAllowed = false) { _, _, _ -> "m8" }
        )
    }

    @Test
    fun selectSdrPlanWalksLadder() {
        val got = LogVideoProbe.selectSdrPlan(3840, 2160, 30, sdrAllowed = true) { w, _, _ ->
            if (w <= 1920) "m8" else null
        }
        assertEquals(
            LogVideoProbe.EncodePlan(
                1920, 1080, "m8", LogVideoProbe.EncoderInputMode.SDR_EGL
            ),
            got
        )
        assertEquals(
            null,
            LogVideoProbe.selectSdrPlan(3840, 2160, 30, sdrAllowed = true) { _, _, _ -> null }
        )
    }

    @Test
    fun rungModePrefersDirectThenWriter() {
        assertEquals(
            LogVideoProbe.EncoderInputMode.DIRECT_P010,
            LogVideoProbe.rungMode("a", "b")
        )
        assertEquals(
            LogVideoProbe.EncoderInputMode.IMAGE_WRITER,
            LogVideoProbe.rungMode(null, "b")
        )
        assertEquals(null, LogVideoProbe.rungMode(null, null))
    }

    @Test
    fun selectEncodePlanPrefersDirect() {
        val got = LogVideoProbe.selectEncodePlan(
            3840, 2160, 30,
            directCodec = { _, _, _ -> "direct" },
            surfaceCodec = { _, _, _ -> "surface" },
        )
        assertEquals(
            LogVideoProbe.EncodePlan(
                3840, 2160, "direct", LogVideoProbe.EncoderInputMode.DIRECT_P010
            ),
            got
        )
    }

    @Test
    fun selectEncodePlanFallsBackToWriter() {
        val got = LogVideoProbe.selectEncodePlan(
            3840, 2160, 30,
            directCodec = { _, _, _ -> null },
            surfaceCodec = { _, _, _ -> "surface" },
        )
        assertEquals(
            LogVideoProbe.EncodePlan(
                3840, 2160, "surface", LogVideoProbe.EncoderInputMode.IMAGE_WRITER
            ),
            got
        )
    }

    @Test
    fun selectEncodePlanStepsDownRungBeforeWriter() {
        // Direct 4K missing, Surface 4K present: writer at 4K wins over
        // direct 1080p (same rung prefers direct only within the rung).
        val got = LogVideoProbe.selectEncodePlan(
            3840, 2160, 30,
            directCodec = { w, _, _ -> if (w <= 1920) "direct1080" else null },
            surfaceCodec = { _, _, _ -> "surface" },
        )
        assertEquals(
            LogVideoProbe.EncodePlan(
                3840, 2160, "surface", LogVideoProbe.EncoderInputMode.IMAGE_WRITER
            ),
            got
        )
    }

    @Test
    fun selectEncodePlanDirectAt1440p() {
        // 10-bit direct missing at 4K but present at 1440p: same-rung
        // direct wins before stepping further down.
        val got = LogVideoProbe.selectEncodePlan(
            3840, 2160, 30,
            directCodec = { w, _, _ -> if (w <= 2560) "direct1440" else null },
            surfaceCodec = { _, _, _ -> null },
        )
        assertEquals(
            LogVideoProbe.EncodePlan(
                2560, 1440, "direct1440", LogVideoProbe.EncoderInputMode.DIRECT_P010
            ),
            got
        )
    }

    @Test
    fun selectEncodePlanWriterAt1440p() {
        val got = LogVideoProbe.selectEncodePlan(
            3840, 2160, 30,
            directCodec = { _, _, _ -> null },
            surfaceCodec = { w, _, _ -> if (w <= 2560) "surface1440" else null },
        )
        assertEquals(
            LogVideoProbe.EncodePlan(
                2560, 1440, "surface1440", LogVideoProbe.EncoderInputMode.IMAGE_WRITER
            ),
            got
        )
    }

    @Test
    fun selectSdrPlanCovers1440p() {
        // 8-bit SDR fallback (BT709) also stops at 1440p when covered.
        val got = LogVideoProbe.selectSdrPlan(3840, 2160, 30, sdrAllowed = true) { w, _, _ ->
            if (w <= 2560) "m8" else null
        }
        assertEquals(
            LogVideoProbe.EncodePlan(
                2560, 1440, "m8", LogVideoProbe.EncoderInputMode.SDR_EGL
            ),
            got
        )
    }

    @Test
    fun selectEncodePlanNullWhenNothingSupported() {
        assertEquals(
            null,
            LogVideoProbe.selectEncodePlan(
                3840, 2160, 30,
                directCodec = { _, _, _ -> null },
                surfaceCodec = { _, _, _ -> null },
            )
        )
    }
}
