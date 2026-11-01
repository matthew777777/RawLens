// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedVideoProbeTest {
    @Test
    fun targetIsHevc10_4k30_75m() {
        val t = EncodedVideoProbe.target()
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, t.mime)
        assertEquals(3840, t.width)
        assertEquals(2160, t.height)
        assertEquals(30, t.fps)
        assertEquals(75_000_000, t.bitrate)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, t.profile)
    }

    @Test
    fun targetSignalsBt709Sdr() {
        val t = EncodedVideoProbe.target()
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, t.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, t.colorTransfer)
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, t.colorRange)
    }

    @Test
    fun formatKeysCarryHevc10Bt709() {
        val keys = EncodedVideoProbe.formatKeys()
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
        val t = EncodedVideoProbe.target()
        assertTrue(t.bitrate in 1_000_000..160_000_000)
        assertTrue(t.width * t.height == 3840 * 2160)
    }

    @Test
    fun profileLabelsCoverForceModes() {
        assertEquals("Main", EncodedVideoProbe.profileLabel(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain))
        assertEquals("Main10", EncodedVideoProbe.profileLabel(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10))
        assertEquals(
            "Main10HDR10",
            EncodedVideoProbe.profileLabel(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
        )
    }

    @Test
    fun formatKeysHonorOverriddenProfile() {
        val t = EncodedVideoProbe.target()
            .copy(profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            EncodedVideoProbe.formatKeys(t)[MediaFormat.KEY_PROFILE]
        )
    }

    @Test
    fun framePtsIsMonotonic33ms() {
        assertEquals(0L, EncodedVideoProbe.framePtsNs(0))
        assertEquals(33_333_333L, EncodedVideoProbe.framePtsNs(1))
        assertEquals(66_666_666L, EncodedVideoProbe.framePtsNs(2))
        assertTrue(EncodedVideoProbe.framePtsNs(30) == 30 * (1_000_000_000L / 30))
    }

    @Test
    fun encodeCandidatesTryRequestedThenLadder() {
        val got = EncodedVideoProbe.encodeCandidates(3840, 2160)
        assertEquals(
            listOf(
                EncodedVideoProbe.EncodeSize(3840, 2160),
                EncodedVideoProbe.EncodeSize(2560, 1440),
                EncodedVideoProbe.EncodeSize(1920, 1080),
            ),
            got
        )
    }

    @Test
    fun encodeCandidatesOffer1440pBelow4K() {
        // A 1440p request offers 1440p then 1080p, never 4K.
        assertEquals(
            listOf(
                EncodedVideoProbe.EncodeSize(2560, 1440),
                EncodedVideoProbe.EncodeSize(1920, 1080),
            ),
            EncodedVideoProbe.encodeCandidates(2560, 1440)
        )
    }

    @Test
    fun encodeCandidatesNeverUpscalePastRequest() {
        // A 1080p request must not offer the 4K rung.
        assertEquals(
            listOf(EncodedVideoProbe.EncodeSize(1920, 1080)),
            EncodedVideoProbe.encodeCandidates(1920, 1080)
        )
    }

    @Test
    fun selectEncodeSizeFallsBackTo1080p() {
        val got = EncodedVideoProbe.selectEncodeSize(3840, 2160, 30) { w, h, _ ->
            w <= 1920 && h <= 1080
        }
        assertEquals(EncodedVideoProbe.EncodeSize(1920, 1080), got)
    }

    @Test
    fun selectEncodeSizeStopsAt1440pWhenSupported() {
        val got = EncodedVideoProbe.selectEncodeSize(3840, 2160, 30) { w, h, _ ->
            w <= 2560 && h <= 1440
        }
        assertEquals(EncodedVideoProbe.EncodeSize(2560, 1440), got)
    }

    @Test
    fun selectEncodeSizeKeepsRequestedWhenSupported() {
        val got = EncodedVideoProbe.selectEncodeSize(3840, 2160, 30) { _, _, _ -> true }
        assertEquals(EncodedVideoProbe.EncodeSize(3840, 2160), got)
    }

    @Test
    fun selectEncodeSizeNullWhenNothingSupported() {
        assertEquals(null, EncodedVideoProbe.selectEncodeSize(3840, 2160, 30) { _, _, _ -> false })
    }

    @Test
    fun clampedBitrateCapsAtAdvertisedMax() {
        assertEquals(40_000_000, EncodedVideoProbe.clampedBitrate(75_000_000, 40))
        assertEquals(75_000_000, EncodedVideoProbe.clampedBitrate(75_000_000, 100))
        assertEquals(EncodedVideoProbe.MIN_BITRATE, EncodedVideoProbe.clampedBitrate(0, 100))
    }

    @Test
    fun writerPathNeedsApi33() {
        assertEquals(false, EncodedVideoProbe.writerPathAvailable(32))
        assertEquals(true, EncodedVideoProbe.writerPathAvailable(33))
        assertEquals(true, EncodedVideoProbe.writerPathAvailable(34))
    }

    @Test
    fun qualcommNativeP010UsesPlaneCopy() {
        for (name in listOf("OMX.qcom.video.encoder.hevc", "c2.qti.hevc.encoder",
            "OMX.qti.video.encoder.hevc", "c2.qcom.hevc.encoder", "")) {
            assertEquals(name, false, EncodedVideoProbe.gpuWriterStorageLayoutSupported(name))
        }
        for (name in listOf("c2.mtk.hevc.encoder", "OMX.MTK.VIDEO.ENCODER.HEVC")) {
            assertEquals(name, true, EncodedVideoProbe.gpuWriterStorageLayoutSupported(name))
        }
    }

    @Test
    fun defaultBitrateHighQualityPerRung() {
        assertEquals(75_000_000, EncodedVideoProbe.defaultBitrateFor(3840, 2160))
        assertEquals(45_000_000, EncodedVideoProbe.defaultBitrateFor(2560, 1440))
        assertEquals(30_000_000, EncodedVideoProbe.defaultBitrateFor(1920, 1080))
        assertTrue(EncodedVideoProbe.defaultBitrateFor(640, 480) >= 8_000_000)
    }

    @Test
    fun encodeSizeForResHealsTo1080p() {
        assertEquals(
            EncodedVideoProbe.EncodeSize(1920, 1080),
            EncodedVideoProbe.encodeSizeForRes(null)
        )
        assertEquals(
            EncodedVideoProbe.EncodeSize(1920, 1080),
            EncodedVideoProbe.encodeSizeForRes("HD1080")
        )
        assertEquals(
            EncodedVideoProbe.EncodeSize(3840, 2160),
            EncodedVideoProbe.encodeSizeForRes("UHD4K")
        )
        assertEquals(
            EncodedVideoProbe.EncodeSize(2560, 1440),
            EncodedVideoProbe.encodeSizeForRes("QHD1440")
        )
        assertEquals(
            EncodedVideoProbe.EncodeSize(1920, 1080),
            EncodedVideoProbe.encodeSizeForRes("bogus")
        )
    }

    @Test
    fun fixedFpsRangePrefersExact() {
        val avail = listOf(15 to 30, 24 to 24, 30 to 30)
        assertEquals(24 to 24, EncodedVideoProbe.fixedFpsRange(24, avail))
        assertEquals(30 to 30, EncodedVideoProbe.fixedFpsRange(30, avail))
    }

    @Test
    fun fixedFpsRangeFallsBackToNarrowestContainer() {
        val avail = listOf(15 to 30, 10 to 60)
        assertEquals(15 to 30, EncodedVideoProbe.fixedFpsRange(30, avail))
        assertEquals(null, EncodedVideoProbe.fixedFpsRange(24, listOf(25 to 30)))
        assertEquals(null, EncodedVideoProbe.fixedFpsRange(30, emptyList()))
    }

    @Test
    fun selectSdrPlanNeedsBt709() {
        assertEquals(
            null,
            EncodedVideoProbe.selectSdrPlan(3840, 2160, 30, sdrAllowed = false) { _, _, _ -> "m8" }
        )
    }

    @Test
    fun selectSdrPlanWalksLadder() {
        val got = EncodedVideoProbe.selectSdrPlan(3840, 2160, 30, sdrAllowed = true) { w, _, _ ->
            if (w <= 1920) "m8" else null
        }
        assertEquals(
            EncodedVideoProbe.EncodePlan(
                1920, 1080, "m8", EncodedVideoProbe.EncoderInputMode.SDR_EGL
            ),
            got
        )
        assertEquals(
            null,
            EncodedVideoProbe.selectSdrPlan(3840, 2160, 30, sdrAllowed = true) { _, _, _ -> null }
        )
    }

    @Test
    fun rungModePrefersDirectThenWriter() {
        assertEquals(
            EncodedVideoProbe.EncoderInputMode.DIRECT_P010,
            EncodedVideoProbe.rungMode("a", "b")
        )
        assertEquals(
            EncodedVideoProbe.EncoderInputMode.IMAGE_WRITER,
            EncodedVideoProbe.rungMode(null, "b")
        )
        assertEquals(null, EncodedVideoProbe.rungMode(null, null))
    }

    @Test
    fun selectEncodePlanPrefersDirect() {
        val got = EncodedVideoProbe.selectEncodePlan(
            3840, 2160, 30,
            directCodec = { _, _, _ -> "direct" },
            surfaceCodec = { _, _, _ -> "surface" },
        )
        assertEquals(
            EncodedVideoProbe.EncodePlan(
                3840, 2160, "direct", EncodedVideoProbe.EncoderInputMode.DIRECT_P010
            ),
            got
        )
    }

    @Test
    fun selectEncodePlanFallsBackToWriter() {
        val got = EncodedVideoProbe.selectEncodePlan(
            3840, 2160, 30,
            directCodec = { _, _, _ -> null },
            surfaceCodec = { _, _, _ -> "surface" },
        )
        assertEquals(
            EncodedVideoProbe.EncodePlan(
                3840, 2160, "surface", EncodedVideoProbe.EncoderInputMode.IMAGE_WRITER
            ),
            got
        )
    }

    @Test
    fun selectEncodePlanStepsDownRungBeforeWriter() {
        // Direct 4K missing, Surface 4K present: writer at 4K wins over
        // direct 1080p (same rung prefers direct only within the rung).
        val got = EncodedVideoProbe.selectEncodePlan(
            3840, 2160, 30,
            directCodec = { w, _, _ -> if (w <= 1920) "direct1080" else null },
            surfaceCodec = { _, _, _ -> "surface" },
        )
        assertEquals(
            EncodedVideoProbe.EncodePlan(
                3840, 2160, "surface", EncodedVideoProbe.EncoderInputMode.IMAGE_WRITER
            ),
            got
        )
    }

    @Test
    fun selectEncodePlanDirectAt1440p() {
        // 10-bit direct missing at 4K but present at 1440p: same-rung
        // direct wins before stepping further down.
        val got = EncodedVideoProbe.selectEncodePlan(
            3840, 2160, 30,
            directCodec = { w, _, _ -> if (w <= 2560) "direct1440" else null },
            surfaceCodec = { _, _, _ -> null },
        )
        assertEquals(
            EncodedVideoProbe.EncodePlan(
                2560, 1440, "direct1440", EncodedVideoProbe.EncoderInputMode.DIRECT_P010
            ),
            got
        )
    }

    @Test
    fun selectEncodePlanWriterAt1440p() {
        val got = EncodedVideoProbe.selectEncodePlan(
            3840, 2160, 30,
            directCodec = { _, _, _ -> null },
            surfaceCodec = { w, _, _ -> if (w <= 2560) "surface1440" else null },
        )
        assertEquals(
            EncodedVideoProbe.EncodePlan(
                2560, 1440, "surface1440", EncodedVideoProbe.EncoderInputMode.IMAGE_WRITER
            ),
            got
        )
    }

    @Test
    fun selectSdrPlanCovers1440p() {
        // 8-bit SDR fallback (BT709) also stops at 1440p when covered.
        val got = EncodedVideoProbe.selectSdrPlan(3840, 2160, 30, sdrAllowed = true) { w, _, _ ->
            if (w <= 2560) "m8" else null
        }
        assertEquals(
            EncodedVideoProbe.EncodePlan(
                2560, 1440, "m8", EncodedVideoProbe.EncoderInputMode.SDR_EGL
            ),
            got
        )
    }

    @Test
    fun selectEncodePlanNullWhenNothingSupported() {
        assertEquals(
            null,
            EncodedVideoProbe.selectEncodePlan(
                3840, 2160, 30,
                directCodec = { _, _, _ -> null },
                surfaceCodec = { _, _, _ -> null },
            )
        )
    }
}
