// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.matthew.rawlens.SessionStreamCombo
import com.matthew.rawlens.SessionStreamSize

class SessionStreamComboTest {
    private fun size(width: Int, height: Int) = SessionStreamSize(width, height)

    // Representative Xiaomi 14 Ultra tele-route plausibility: 12 MP binned RAW
    // plus a 4:3 preview shortlist.
    private val rawSizes = listOf(size(4096, 3072), size(2048, 1536))
    private val previewSizes = listOf(
        size(1920, 1080), size(1440, 1080), size(1280, 720), size(640, 480)
    )

    private fun combos(
        raw: List<SessionStreamSize> = rawSizes,
        previews: List<SessionStreamSize> = previewSizes,
        fullMaxImages: Int = 20,
        reducedMaxImages: Int = 8
    ): List<SessionStreamCombo> = SessionStreamSelection.selectSessionStreamCombos(
        raw, previews, fullMaxImages, reducedMaxImages
    )

    @Test
    fun defaultStepIsRawOnlyFullBuffers() {
        // Raw-only default: full RAW with a ZSL-capable reader and no
        // preview target at all (taps/metering already run from the RAW
        // viewfinder). Most HALs sustain full rate this way.
        val first = combos().first()
        assertEquals(size(4096, 3072), first.raw)
        assertNull(first.preview)
        assertEquals(20, first.readerMaxImages)
        assertEquals("RAW ONLY FULL", first.label)
    }

    @Test
    fun fullComboRequestsSmallestPreviewOnly() {
        // Invisible-stream rule: the smallest preview available, requested
        // only when the ISP probe proves the HAL stalls raw-only (~20 fps
        // without any preview request). No aspect, coverage, or viewfinder
        // requirement applies.
        val full = combos()[1]
        assertEquals(size(4096, 3072), full.raw)
        assertEquals(size(640, 480), full.preview)
        assertEquals(20, full.readerMaxImages)
        assertEquals("FULL", full.label)
    }

    @Test
    fun rawOnlyFullSkippedWhenBuffersEqual() {
        // Degraded readers with the same depth would retry an identical
        // session: no RAW ONLY FULL step, the ladder starts at FULL.
        val ladder = combos(fullMaxImages = 8, reducedMaxImages = 8)
        assertEquals("FULL", ladder.first().label)
    }

    @Test
    fun fpsProbeVerdict() {
        // 45 sensor-timestamped frames: 30 fps streams pass, ~20 fps MTK
        // stalls trip the preview remedy; the 25 fps gate itself passes.
        val fullRate = SessionStreamSelection.rawFpsForProbeWindow(44, 44_000_000_000L / 30)
        assertEquals(30.0, fullRate, 0.01)
        assertEquals(false, SessionStreamSelection.previewRequestNeededForFps(fullRate))
        val stalled = SessionStreamSelection.rawFpsForProbeWindow(44, 44_000_000_000L / 20)
        assertEquals(20.0, stalled, 0.01)
        assertEquals(true, SessionStreamSelection.previewRequestNeededForFps(stalled))
        assertEquals(false, SessionStreamSelection.previewRequestNeededForFps(25.0))
        assertEquals(false, SessionStreamSelection.previewRequestNeededForFps(
            SessionStreamSelection.rawFpsForProbeWindow(44, 0L)
        ))
    }

    @Test
    fun firstPreviewComboIsFullStep() {
        // The 20 fps jump lands on FULL (smallest preview, full buffers),
        // never on a degraded tail step.
        val ladder = combos()
        val full = ladder.indexOfFirst { it.label == "FULL" }
        assertEquals(full, SessionStreamSelection.firstPreviewComboIndex(ladder))
        assertEquals(
            -1,
            SessionStreamSelection.firstPreviewComboIndex(combos(previews = emptyList()))
        )
    }

    @Test
    fun fullPreviewIgnoresAspectAndViewSize() {
        // 176x144 wins purely on area even though it matches neither the
        // sensor aspect nor any viewfinder size.
        assertEquals(
            size(176, 144),
            SessionStreamSelection.selectFullPreviewSize(
                listOf(size(1920, 1080), size(640, 480), size(176, 144))
            )
        )
    }

    @Test
    fun tinyPreviewEscalatesToLargerRetryWhenRejected() {
        // FULL carries the postage stamp; if the HAL rejects it, MIN PREVIEW
        // retries with the larger usable stream instead of giving up the
        // preview target HALs need for 30 fps RAW.
        val ladder = combos(
            previews = listOf(size(1440, 1080), size(640, 480), size(176, 144))
        )
        assertEquals(
            listOf(
                SessionStreamCombo(size(4096, 3072), null, 20, "RAW ONLY FULL"),
                SessionStreamCombo(size(4096, 3072), size(176, 144), 20, "FULL"),
                SessionStreamCombo(size(4096, 3072), size(640, 480), 20, "MIN PREVIEW"),
                SessionStreamCombo(size(4096, 3072), size(640, 480), 8, "MIN BUFFERS"),
                SessionStreamCombo(size(2048, 1536), size(640, 480), 8, "SMALL RAW"),
                SessionStreamCombo(size(4096, 3072), null, 8, "RAW ONLY"),
                SessionStreamCombo(size(2048, 1536), null, 8, "RAW ONLY MIN")
            ),
            ladder
        )
    }

    @Test
    fun ladderDegradesPreviewThenBuffersThenRawSizeThenRawOnly() {
        // 640x480 is both the smallest preview and the fallback floor, so
        // FULL and MIN PREVIEW are identical sessions and the retry dedups.
        val ladder = combos()
        assertEquals(
            listOf(
                SessionStreamCombo(size(4096, 3072), null, 20, "RAW ONLY FULL"),
                SessionStreamCombo(size(4096, 3072), size(640, 480), 20, "FULL"),
                SessionStreamCombo(size(4096, 3072), size(640, 480), 8, "MIN BUFFERS"),
                SessionStreamCombo(size(2048, 1536), size(640, 480), 8, "SMALL RAW"),
                SessionStreamCombo(size(4096, 3072), null, 8, "RAW ONLY"),
                SessionStreamCombo(size(2048, 1536), null, 8, "RAW ONLY MIN")
            ),
            ladder
        )
    }

    @Test
    fun singleRawSizeSkipsSmallRawStep() {
        val ladder = combos(raw = listOf(size(4096, 3072)))
        assertEquals(
            listOf("RAW ONLY FULL", "FULL", "MIN BUFFERS", "RAW ONLY"),
            ladder.map { it.label }
        )
    }

    @Test
    fun emptyPreviewListYieldsOnlyRawOnlySteps() {
        // HALs that expose no preview sizes at all skip straight to the
        // RAW-only tail instead of failing the session outright.
        val ladder = combos(previews = emptyList())
        assertEquals(
            listOf(
                SessionStreamCombo(size(4096, 3072), null, 20, "RAW ONLY FULL"),
                SessionStreamCombo(size(4096, 3072), null, 8, "RAW ONLY"),
                SessionStreamCombo(size(2048, 1536), null, 8, "RAW ONLY MIN")
            ),
            ladder
        )
    }

    @Test
    fun emptyRawListYieldsNoCombos() {
        assertEquals(emptyList<SessionStreamCombo>(), combos(raw = emptyList()))
    }

    @Test
    fun identicalFallbackPreviewSkipsMinPreviewStep() {
        // Only one preview size exists, so the full and fallback previews are
        // the same stream: the MIN PREVIEW step would retry an identical
        // session and must be deduped away.
        val ladder = combos(previews = listOf(size(640, 480)))
        assertEquals(
            listOf(
                SessionStreamCombo(size(4096, 3072), null, 20, "RAW ONLY FULL"),
                SessionStreamCombo(size(4096, 3072), size(640, 480), 20, "FULL"),
                SessionStreamCombo(size(4096, 3072), size(640, 480), 8, "MIN BUFFERS"),
                SessionStreamCombo(size(2048, 1536), size(640, 480), 8, "SMALL RAW"),
                SessionStreamCombo(size(4096, 3072), null, 8, "RAW ONLY"),
                SessionStreamCombo(size(2048, 1536), null, 8, "RAW ONLY MIN")
            ),
            ladder
        )
    }

    @Test
    fun fallbackPreviewKeepsUsableFloor() {
        // The fallback preview minimizes bandwidth but must stay usable as a
        // viewfinder: postage-stamp streams below 640x480 are skipped when a
        // larger matching-aspect stream exists.
        val fallback = SessionStreamSelection.selectFallbackPreviewSize(
            listOf(
                size(1920, 1080), size(1440, 1080), size(640, 480),
                size(320, 240), size(176, 144)
            ),
            size(4096, 3072)
        )
        assertEquals(size(640, 480), fallback)
    }

    @Test
    fun fallbackPreviewFallsBackToSmallestOverallWithoutMatchingAspect() {
        val fallback = SessionStreamSelection.selectFallbackPreviewSize(
            listOf(size(1920, 1080), size(1280, 720)),
            size(4096, 3072)
        )
        assertEquals(size(1280, 720), fallback)
    }

    @Test
    fun smallerRawSizeHalvesSensorArea() {
        assertEquals(
            size(4096, 3072),
            SessionStreamSelection.selectSmallerRawSize(
                listOf(size(8192, 6144), size(4096, 3072), size(1024, 768))
            )
        )
    }

    @Test
    fun smallerRawSizeAbsentWhenNoHalfStepExists() {
        assertNull(
            SessionStreamSelection.selectSmallerRawSize(
                listOf(size(4000, 3000), size(3900, 2900))
            )
        )
        assertNull(SessionStreamSelection.selectSmallerRawSize(listOf(size(4096, 3072))))
    }

    @Test
    fun clampKeepsFullCapacity() {
        // Inverse of the reader sizing: a reader built for the capacity must
        // clamp back to exactly that capacity.
        assertEquals(30, SessionStreamSelection.clampZslCapacityForReader(30, 44))
        assertEquals(6, SessionStreamSelection.clampZslCapacityForReader(6, 20))
        assertEquals(1, SessionStreamSelection.clampZslCapacityForReader(1, 20))
    }

    @Test
    fun clampDisablesRingForReducedReader() {
        // MIN_ACQUIRED_RAW_IMAGES (burst 6 + 2) cannot host a ZSL ring plus
        // pairing headroom: the reduced session is viewfinder-only.
        assertEquals(0, SessionStreamSelection.clampZslCapacityForReader(30, 8))
        assertEquals(0, SessionStreamSelection.clampZslCapacityForReader(6, 8))
        // Between the pairing headroom and the JPEG floor no ring fits either:
        // max(capacity, 6) + 14 must stay within the reader.
        assertEquals(0, SessionStreamSelection.clampZslCapacityForReader(30, 18))
    }

    @Test
    fun readerDepthKeepsHeadroomBelowCap() {
        // Ring plus pairing (6) + transition (2) + spare (2) + rearm (4).
        assertEquals(44, SessionStreamSelection.fullReaderMaxImagesFor(30))
        assertEquals(22, SessionStreamSelection.fullReaderMaxImagesFor(8))
        // JPEG floor: tiny rings still size for six in-flight JPEG saves.
        assertEquals(20, SessionStreamSelection.fullReaderMaxImagesFor(1))
    }

    @Test
    fun readerDepthClampsToHardCap() {
        // 59 + 14 headroom would request 73 buffers; the HAL queue hard cap
        // (61: session configure limit, abort past 62) wins instead.
        assertEquals(61, SessionStreamSelection.fullReaderMaxImagesFor(59))
        assertEquals(61, SessionStreamSelection.fullReaderMaxImagesFor(100))
    }

    @Test
    fun clampShrinksDeepRingIntoCappedReader() {
        // A 59-frame request on the capped 61-buffer reader keeps a 47-frame
        // ring (61 - 14 headroom): deep but provably inside the queue.
        assertEquals(47, SessionStreamSelection.clampZslCapacityForReader(59, 61))
    }

    @Test
    fun preferredSizeMovesItsCombosFirstStably() {
        val ladder = combos()
        val preferred = size(2048, 1536)
        val ordered = SessionStreamSelection.orderCombosForPreferredSize(ladder, preferred)
        // Every preferred-raw combo leads, in ladder order; the rest follow
        // untouched, so HAL rejection still degrades exactly as before.
        assertEquals(
            ladder.filter { it.raw == preferred },
            ordered.take(ladder.count { it.raw == preferred })
        )
        assertEquals(ladder.filter { it.raw != preferred }, ordered.drop(ladder.count { it.raw == preferred }))
        assertEquals(preferred, ordered.first().raw)
    }

    @Test
    fun preferredSizeFallsBackToAuto() {
        val ladder = combos()
        assertEquals(ladder, SessionStreamSelection.orderCombosForPreferredSize(ladder, null))
        assertEquals(
            ladder,
            SessionStreamSelection.orderCombosForPreferredSize(ladder, size(1, 1))
        )
    }

    @Test
    fun sizePreferenceParsing() {
        assertEquals(size(4080, 3072), SessionStreamSelection.parseRawSizePreference("4080x3072"))
        assertNull(SessionStreamSelection.parseRawSizePreference(null))
        assertNull(SessionStreamSelection.parseRawSizePreference("huge"))
        assertNull(SessionStreamSelection.parseRawSizePreference("0x3072"))
    }

    @Test
    fun formatPreferenceResolvesPackedOnlyWhenAvailable() {
        val sensor = android.graphics.ImageFormat.RAW_SENSOR
        val raw12 = android.graphics.ImageFormat.RAW12
        val both = setOf(sensor, raw12)
        // Auto and unknown names stay unpacked: packed is strictly opt-in.
        assertEquals(sensor, SessionStreamSelection.resolveRawFormatPreference(null, both))
        assertEquals(sensor, SessionStreamSelection.resolveRawFormatPreference("YUV", both))
        assertEquals(sensor, SessionStreamSelection.resolveRawFormatPreference("RAW14", both))
        // An opted-in format wins only where the map lists it.
        assertEquals(raw12, SessionStreamSelection.resolveRawFormatPreference("RAW12", both))
        assertEquals(sensor, SessionStreamSelection.resolveRawFormatPreference("RAW12", setOf(sensor)))
        assertEquals(sensor, SessionStreamSelection.resolveRawFormatPreference("RAW10", both))
    }
}
