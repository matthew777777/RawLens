// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.matthew.rawlens.RawCameraController.Companion.SessionStreamCombo
import com.matthew.rawlens.RawCameraController.Companion.SessionStreamSize

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
        requiredWidth: Int = 1920,
        requiredHeight: Int = 1080,
        fullMaxImages: Int = 20,
        reducedMaxImages: Int = 8
    ): List<SessionStreamCombo> = RawCameraController.selectSessionStreamCombos(
        raw, previews, requiredWidth, requiredHeight, fullMaxImages, reducedMaxImages
    )

    @Test
    fun fullComboMatchesLegacyPreviewChoice() {
        // Legacy rule: least-expensive 4:3 stream covering the view, else the
        // largest bounded 4:3 stream. Neither 1440x1080 nor 640x480 covers
        // 1920x1080, so the full combo keeps the largest: 1440x1080.
        val full = combos().first()
        assertEquals(size(4096, 3072), full.raw)
        assertEquals(size(1440, 1080), full.preview)
        assertEquals(20, full.readerMaxImages)
        assertEquals("FULL", full.label)
    }

    @Test
    fun ladderDegradesPreviewThenBuffersThenRawSizeThenRawOnly() {
        val ladder = combos()
        assertEquals(
            listOf(
                SessionStreamCombo(size(4096, 3072), size(1440, 1080), 20, "FULL"),
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
    fun singleRawSizeSkipsSmallRawStep() {
        val ladder = combos(raw = listOf(size(4096, 3072)))
        assertEquals(
            listOf("FULL", "MIN PREVIEW", "MIN BUFFERS", "RAW ONLY"),
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
        val ladder = combos(
            previews = listOf(size(640, 480)),
            requiredWidth = 320,
            requiredHeight = 240
        )
        assertEquals(
            listOf(
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
        val fallback = RawCameraController.selectFallbackPreviewSize(
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
        val fallback = RawCameraController.selectFallbackPreviewSize(
            listOf(size(1920, 1080), size(1280, 720)),
            size(4096, 3072)
        )
        assertEquals(size(1280, 720), fallback)
    }

    @Test
    fun smallerRawSizeHalvesSensorArea() {
        assertEquals(
            size(4096, 3072),
            RawCameraController.selectSmallerRawSize(
                listOf(size(8192, 6144), size(4096, 3072), size(1024, 768))
            )
        )
    }

    @Test
    fun smallerRawSizeAbsentWhenNoHalfStepExists() {
        assertNull(
            RawCameraController.selectSmallerRawSize(
                listOf(size(4000, 3000), size(3900, 2900))
            )
        )
        assertNull(RawCameraController.selectSmallerRawSize(listOf(size(4096, 3072))))
    }

    @Test
    fun clampKeepsFullCapacity() {
        // Inverse of the reader sizing: a reader built for the capacity must
        // clamp back to exactly that capacity.
        assertEquals(30, RawCameraController.clampZslCapacityForReader(30, 44))
        assertEquals(6, RawCameraController.clampZslCapacityForReader(6, 20))
        assertEquals(1, RawCameraController.clampZslCapacityForReader(1, 20))
    }

    @Test
    fun clampDisablesRingForReducedReader() {
        // MIN_ACQUIRED_RAW_IMAGES (burst 6 + 2) cannot host a ZSL ring plus
        // pairing headroom: the reduced session is viewfinder-only.
        assertEquals(0, RawCameraController.clampZslCapacityForReader(30, 8))
        assertEquals(0, RawCameraController.clampZslCapacityForReader(6, 8))
        // Between the pairing headroom and the JPEG floor no ring fits either:
        // max(capacity, 6) + 14 must stay within the reader.
        assertEquals(0, RawCameraController.clampZslCapacityForReader(30, 18))
    }
}
