
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.matthew.rawlens.SessionStreamCombo
import com.matthew.rawlens.SessionStreamSize

class SessionStreamComboTest {
    private fun size(width: Int, height: Int) = SessionStreamSize(width, height)

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
    fun dcg16CropRemainsSelectableBetweenLargestAndSmallestRaw() {
        val dcg16 = size(4096, 2304)
        val ladder = combos(raw = listOf(size(4096, 3072), dcg16, size(2048, 1536)))
        val ordered = SessionStreamSelection.orderCombosForPreferredSize(ladder, dcg16)
        assertEquals(dcg16, ordered.first().raw)
        assertNull(ordered.first().preview)
        assertEquals(20, ordered.first().readerMaxImages)
        assertEquals(listOf(20, 8), ordered.filter { it.raw == dcg16 }.map { it.readerMaxImages })
        assertEquals(ladder.filter { it.raw != dcg16 }, ordered.drop(2))
    }

    @Test
    fun defaultStepIsRawOnlyFullBuffers() {
        val first = combos().first()
        assertEquals(size(4096, 3072), first.raw)
        assertNull(first.preview)
        assertEquals(20, first.readerMaxImages)
        assertEquals("RAW ONLY FULL", first.label)
    }

    @Test
    fun fullComboRequestsNormalPreview() {
        val full = combos()[1]
        assertEquals(size(4096, 3072), full.raw)
        assertEquals(size(1440, 1080), full.preview)
        assertEquals(20, full.readerMaxImages)
        assertEquals("FULL", full.label)
    }

    @Test
    fun rawOnlyFullSkippedWhenBuffersEqual() {
        val ladder = combos(fullMaxImages = 8, reducedMaxImages = 8)
        assertEquals("FULL", ladder.first().label)
    }

    @Test
    fun fpsProbeVerdict() {
        val fullRate = SessionStreamSelection.rawFpsForProbeWindow(44, 44_000_000_000L / 30)
        assertEquals(30.0, fullRate, 0.01)
        assertEquals(false, SessionStreamSelection.previewRequestNeededForFps(fullRate))
        val stalled = SessionStreamSelection.rawFpsForProbeWindow(44, 44_000_000_000L / 20)
        assertEquals(20.0, stalled, 0.01)
        assertEquals(true, SessionStreamSelection.previewRequestNeededForFps(stalled))
        assertEquals(false, SessionStreamSelection.previewRequestNeededForFps(25.0))
        assertEquals(false, SessionStreamSelection.previewRequestNeededForFps(24.3))
        assertEquals(false, SessionStreamSelection.previewRequestNeededForFps(24.0))
        assertEquals(false, SessionStreamSelection.previewRequestNeededForFps(22.5))
        assertEquals(true, SessionStreamSelection.previewRequestNeededForFps(22.0))
        assertEquals(false, SessionStreamSelection.previewRequestNeededForFps(
            SessionStreamSelection.rawFpsForProbeWindow(44, 0L)
        ))
    }

    @Test
    fun firstPreviewComboIsFullStep() {
        val ladder = combos()
        val full = ladder.indexOfFirst { it.label == "FULL" }
        assertEquals(full, SessionStreamSelection.firstPreviewComboIndex(ladder))
        assertEquals(
            -1,
            SessionStreamSelection.firstPreviewComboIndex(combos(previews = emptyList()))
        )
    }

    @Test
    fun fullPreviewRejectsTinyPlaceholder() {
        assertEquals(
            size(1920, 1080),
            SessionStreamSelection.selectFullPreviewSize(
                listOf(size(1920, 1080), size(640, 480), size(176, 144))
            )
        )
    }

    @Test
    fun tinyPreviewIsNeverUsedWhenNormalPreviewExists() {
        val ladder = combos(
            previews = listOf(size(1440, 1080), size(640, 480), size(176, 144))
        )
        assertEquals(
            listOf(
                SessionStreamCombo(size(4096, 3072), null, 20, "RAW ONLY FULL"),
                SessionStreamCombo(size(4096, 3072), size(1440, 1080), 20, "FULL"),
                SessionStreamCombo(size(4096, 3072), size(1440, 1080), 8, "MIN BUFFERS"),
                SessionStreamCombo(size(2048, 1536), size(1440, 1080), 8, "SMALL RAW"),
                SessionStreamCombo(size(4096, 3072), null, 8, "RAW ONLY"),
                SessionStreamCombo(size(2048, 1536), null, 8, "RAW ONLY MIN")
            ),
            ladder
        )
    }

    @Test
    fun ladderDegradesPreviewThenBuffersThenRawSizeThenRawOnly() {
        val ladder = combos()
        assertEquals(
            listOf(
                SessionStreamCombo(size(4096, 3072), null, 20, "RAW ONLY FULL"),
                SessionStreamCombo(size(4096, 3072), size(1440, 1080), 20, "FULL"),
                SessionStreamCombo(size(4096, 3072), size(1440, 1080), 8, "MIN BUFFERS"),
                SessionStreamCombo(size(2048, 1536), size(1440, 1080), 8, "SMALL RAW"),
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
    fun bufferRetriesRetainNormalPreview() {
        val fallback = SessionStreamSelection.selectFallbackPreviewSize(
            listOf(
                size(1920, 1080), size(1440, 1080), size(640, 480),
                size(320, 240), size(176, 144)
            ),
            size(4096, 3072)
        )
        assertEquals(size(1440, 1080), fallback)
    }

    @Test
    fun normalPreviewUsesLargestBoundedSizeWithoutMatchingAspect() {
        val fallback = SessionStreamSelection.selectFallbackPreviewSize(
            listOf(size(1920, 1080), size(1280, 720)),
            size(4096, 3072)
        )
        assertEquals(size(1920, 1080), fallback)
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
        assertEquals(30, SessionStreamSelection.clampZslCapacityForReader(30, 44))
        assertEquals(6, SessionStreamSelection.clampZslCapacityForReader(6, 20))
        assertEquals(1, SessionStreamSelection.clampZslCapacityForReader(1, 20))
    }

    @Test
    fun clampDisablesRingForReducedReader() {
        assertEquals(0, SessionStreamSelection.clampZslCapacityForReader(30, 8))
        assertEquals(0, SessionStreamSelection.clampZslCapacityForReader(6, 8))
        assertEquals(0, SessionStreamSelection.clampZslCapacityForReader(30, 18))
    }

    @Test
    fun readerDepthKeepsHeadroomBelowCap() {
        assertEquals(44, SessionStreamSelection.fullReaderMaxImagesFor(30))
        assertEquals(22, SessionStreamSelection.fullReaderMaxImagesFor(8))
        assertEquals(20, SessionStreamSelection.fullReaderMaxImagesFor(1))
    }

    @Test
    fun readerDepthClampsToHardCap() {
        assertEquals(61, SessionStreamSelection.fullReaderMaxImagesFor(59))
        assertEquals(61, SessionStreamSelection.fullReaderMaxImagesFor(100))
    }

    @Test
    fun clampShrinksDeepRingIntoCappedReader() {
        assertEquals(47, SessionStreamSelection.clampZslCapacityForReader(59, 61))
    }

    @Test
    fun preferredSizeMovesItsCombosFirstStably() {
        val ladder = combos()
        val preferred = size(2048, 1536)
        val ordered = SessionStreamSelection.orderCombosForPreferredSize(ladder, preferred)
        assertEquals(
            ladder.filter { it.raw == preferred },
            ordered.drop(1).take(ladder.count { it.raw == preferred })
        )
        assertEquals(ladder.filter { it.raw != preferred }, ordered.drop(1 + ladder.count { it.raw == preferred }))
        assertEquals(preferred, ordered.first().raw)
        assertNull(ordered.first().preview)
        assertEquals(20, ordered.first().readerMaxImages)
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
        assertEquals(size(4096, 2304), SessionStreamSelection.parseRawSizePreference("4096×2304"))
        assertNull(SessionStreamSelection.parseRawSizePreference(null))
        assertNull(SessionStreamSelection.parseRawSizePreference("huge"))
        assertNull(SessionStreamSelection.parseRawSizePreference("0x3072"))
    }

    @Test
    fun twoSizeXiaomiMapHonorsSizeChosenFromDiscovery() {
        val crop = SessionStreamSelection.parseRawSizePreference("4096×2304")
        val ladder = combos(raw = listOf(size(4096, 3072), size(4096, 2304)))
        assertEquals(size(4096, 2304),
            SessionStreamSelection.orderCombosForPreferredSize(ladder, crop).first().raw)
        val selected = SessionStreamSelection.orderCombosForPreferredSize(ladder, crop).first()
        assertEquals(20, selected.readerMaxImages)
        org.junit.Assert.assertTrue(CaptureQueueCapacity.accepts(0, 1, 6,
            selected.readerMaxImages, SessionStreamSelection.RAW_PREVIEW_RESERVED_SLOTS))
    }

    @Test
    fun formatPreferenceResolvesPackedOnlyWhenAvailable() {
        val sensor = android.graphics.ImageFormat.RAW_SENSOR
        val raw12 = android.graphics.ImageFormat.RAW12
        val both = setOf(sensor, raw12)
        assertEquals(sensor, SessionStreamSelection.resolveRawFormatPreference(null, both))
        assertEquals(sensor, SessionStreamSelection.resolveRawFormatPreference("YUV", both))
        assertEquals(sensor, SessionStreamSelection.resolveRawFormatPreference("RAW14", both))
        assertEquals(raw12, SessionStreamSelection.resolveRawFormatPreference("RAW12", both))
        assertEquals(sensor, SessionStreamSelection.resolveRawFormatPreference("RAW12", setOf(sensor)))
        assertEquals(sensor, SessionStreamSelection.resolveRawFormatPreference("RAW10", both))
    }
}
