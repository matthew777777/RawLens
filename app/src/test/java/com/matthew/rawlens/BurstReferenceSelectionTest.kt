// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstReferenceSelectionTest {
    @Test fun `formula weights match the plan`() {
        val total = BurstReferenceSelection.scoreTotal(
            unclippedFraction = 1f,
            sharpnessPercentile = 1f,
            inverseNoise = 1f,
            poseCentrality = 1f,
            exposureUtility = 1f,
            gyroBlur = 0f,
            focusAePenalty = 0f
        )

        assertEquals(1.8f + 1.5f + 1.2f + 0.8f + 0.4f, total, 1e-6f)
    }

    @Test fun `first frame is not privileged`() {
        val candidates = listOf(
            candidate(index = 0, timestamp = 1_000L, sharpness = 0.2f),
            candidate(index = 1, timestamp = 2_000L, sharpness = 0.9f),
            candidate(index = 2, timestamp = 3_000L, sharpness = 0.3f)
        )

        val selection = BurstReferenceSelection.select(candidates)

        assertEquals(1, selection.selectedIndex)
        assertEquals(1, selection.records.count { it.selected })
    }

    @Test fun `blurred clipped and noisy candidates rank as expected`() {
        val sharp = candidate(index = 0, timestamp = 1_000L, sharpness = 0.9f, unclipped = 0.95f, iso = 100)
        val blurred = candidate(index = 1, timestamp = 2_000L, sharpness = 0.1f, unclipped = 0.95f, iso = 100,
            gyro = listOf(GyroSample(2_000L, 0.5f, 0f, 0f), GyroSample(2_010_000L, 0.5f, 0f, 0f)))
        val clipped = candidate(index = 2, timestamp = 3_000L, sharpness = 0.9f, unclipped = 0.1f, iso = 100)
        val noisy = candidate(index = 3, timestamp = 4_000L, sharpness = 0.9f, unclipped = 0.95f, iso = 3200)

        val selection = BurstReferenceSelection.select(listOf(sharp, blurred, clipped, noisy))
        val totals = selection.records.associate { it.index to it.total }

        assertEquals(0, selection.selectedIndex)
        assertTrue(totals[0]!! > totals[1]!!)
        assertTrue(totals[0]!! > totals[2]!!)
        assertTrue(totals[0]!! > totals[3]!!)
    }

    @Test fun `tie band chooses the temporally central candidate`() {
        // Near-identical frames: all totals inside the tie band.
        val candidates = (0..4).map { i ->
            candidate(index = i, timestamp = 1_000L * (i + 1), sharpness = 0.5f)
        }

        val selection = BurstReferenceSelection.select(candidates)

        assertEquals(2, selection.selectedIndex)
    }

    @Test fun `missing optional metadata degrades conservatively`() {
        val candidates = listOf(
            ReferenceCandidateInput(
                index = 0, timestampNanos = 1_000L, thumbnail = null,
                exposureNanos = null, sensitivityIso = null, noiseProfile = null,
                blackLevel = 64f, whiteLevel = 1023f, gyroSamples = emptyList(),
                rollingShutterSkewNanos = 0L, afState = null, aeState = null, lensState = null
            ),
            candidate(index = 1, timestamp = 2_000L, sharpness = 0.5f)
        )

        val selection = BurstReferenceSelection.select(candidates)

        assertEquals(2, selection.records.size)
        selection.records.forEach {
            assertTrue(it.total.isFinite())
        }
        val degraded = selection.records.first { it.index == 0 }
        assertTrue(degraded.notes.contains("thumbnail=missing"))
        assertTrue(degraded.notes.contains("noise=iso-fallback"))
        assertTrue(degraded.notes.contains("gyro=missing"))
        assertTrue(degraded.notes.contains("focus=unknown"))
    }

    @Test fun `single candidate is selected with a debug record`() {
        val selection = BurstReferenceSelection.select(listOf(candidate(index = 0, timestamp = 1_000L)))

        assertEquals(0, selection.selectedIndex)
        assertEquals(1, selection.records.size)
        assertTrue(selection.records.single().selected)
    }

    @Test fun `pose centrality prefers the temporal middle`() {
        val times = listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L)

        assertTrue(
            BurstReferenceSelection.poseCentrality(3_000L, times) >
                BurstReferenceSelection.poseCentrality(1_000L, times)
        )
        assertEquals(1f, BurstReferenceSelection.poseCentrality(9_999L, listOf(9_999L)), 1e-6f)
    }

    private fun candidate(
        index: Int,
        timestamp: Long,
        sharpness: Float = 0.5f,
        unclipped: Float = 0.9f,
        iso: Int = 100,
        gyro: List<GyroSample> = emptyList()
    ): ReferenceCandidateInput {
        // Gradient thumbnail (8x8 quads) whose 90th percentile tracks `sharpness`.
        val quads = 8
        val luminance = FloatArray(quads * quads) { i ->
            val x = i % quads
            val y = i / quads
            (0.4f + sharpness * 0.05f * ((x + y) % 8)).coerceIn(0f, 0.99f)
        }
        if (unclipped < 0.5f) luminance.fill(1.0f)
        return ReferenceCandidateInput(
            index = index,
            timestampNanos = timestamp,
            thumbnail = GreenThumbnail(quads, quads, luminance, 1, "test-$index"),
            exposureNanos = 10_000_000L,
            sensitivityIso = iso,
            noiseProfile = null,
            blackLevel = 64f,
            whiteLevel = 1023f,
            gyroSamples = gyro,
            rollingShutterSkewNanos = 5_000_000L,
            afState = 4,
            aeState = 2,
            lensState = 0
        )
    }
}
