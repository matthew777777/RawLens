package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Durable parity for the from-scratch Vulkan HDR merge ([HdrVulkanMerge]).
 *
 * The GPU dispatches cannot run on the JVM, so these tests pin the CPU
 * reference ([HdrVulkanMerge.mergeCpu]) and every pure stage twin it shares
 * with the shaders: warp and Darktable accumulate must reproduce the legacy
 * CPU merge ([HdrRawMerge]) on ghost-free brackets, and the spatial robust
 * stage must collapse ghosts to the reference.
 */
class HdrVulkanMergeTest {
    private fun textured(size: Int, seed: Int) = UnpackedRawCfa(
        size, size, BayerPattern.RGGB,
        FloatArray(size * size) { i -> (((i * 7 + seed) % 19) / 19f).coerceIn(0.05f, 0.9f) },
        RawCrop(0, 0, size, size)
    )

    private fun flat(value: Float, size: Int = 16) = UnpackedRawCfa(
        size, size, BayerPattern.RGGB, FloatArray(size * size) { value },
        RawCrop(0, 0, size, size)
    )

    private fun frame(cfa: UnpackedRawCfa, nanos: Long, flow: HdrFlowField? = null) =
        HdrMergeFrame(cfa, nanos, 100, flow = flow)

    @Test fun warpMatchesSmoothSamplerOnFiniteFlow() {
        val input = textured(24, 3)
        val flows = listOf(
            TranslationFlow(2.5f, -1.25f),
            HdrFlowField { x, y -> (0.1f * x) to (-0.05f * y) }
        )
        for (flow in flows) {
            for (y in 0 until 24) for (x in 0 until 24) {
                assertEquals(
                    HdrRawMerge.sampleSmooth(input, flow, x, y),
                    HdrVulkanMerge.warpPixel(input, flow, x, y), 0f
                )
            }
        }
    }

    @Test fun nonFiniteFlowFallsBackToIdentity() {
        val input = textured(10, 1)
        val bad = HdrFlowField { _, _ -> Float.NaN to 0f }
        for (y in 0 until 10) for (x in 0 until 10) {
            assertEquals(
                input.values[y * 10 + x],
                HdrVulkanMerge.warpPixel(input, bad, x, y), 0f
            )
        }
    }

    @Test fun blockGridMatchesDarktableBorders() {
        val w = 6
        val h = 6
        val values = FloatArray(w * h) { i -> i.toFloat() / (w * h) }
        val (mx, mn) = HdrVulkanMerge.blockGrid(values, w, h)
        // Border cells (origin past width/height-2) keep fresh-buffer defaults.
        assertEquals(0f, mx[2 + 0 * 3], 0f)
        assertEquals(Float.MAX_VALUE, mn[2 + 0 * 3], 0f)
        // Interior cell (0,0): 3x3 over values 0..2 rows.
        var emx = 0f
        var emn = Float.MAX_VALUE
        for (dy in 0..2) for (dx in 0..2) {
            val v = values[dy * w + dx]
            emx = maxOf(emx, v)
            emn = minOf(emn, v)
        }
        assertEquals(emx, mx[0], 0f)
        assertEquals(emn, mn[0], 0f)
    }

    @Test fun collapseWeightProperties() {
        // Perfect match keeps the alternate entirely.
        assertEquals(0f, HdrVulkanMerge.collapseWeight(0f, 1e-6f, 8f, 6f, 1f), 0f)
        // Huge residual collapses to the reference.
        assertEquals(1f, HdrVulkanMerge.collapseWeight(1f, 1e-9f, 8f, 6f, 1f), 1e-6f)
        // Monotonic in the residual.
        val a = HdrVulkanMerge.collapseWeight(1e-6f, 1e-6f, 8f, 1f, 1f)
        val b = HdrVulkanMerge.collapseWeight(1e-4f, 1e-6f, 8f, 1f, 1f)
        assertTrue(a < b)
    }

    @Test fun robustPixelKeepsMatchedAlternate() {
        val s = FloatArray(4) { CfaNoiseModel.FALLBACK_SCALE }
        val o = FloatArray(4) { CfaNoiseModel.FALLBACK_OFFSET }
        val (kept, w) = HdrVulkanMerge.robustPixel(
            0.3f, 0.3f, 1, s, o, s, o,
            gain = 1f, refScale = 1f, dampedStrength = 8f, maxMotion = 6f, factor = 1f
        )
        assertEquals(0f, w, 1e-6f)
        assertEquals(0.3f, kept, 1e-6f)
    }

    @Test fun robustPixelCollapsesGhostToReference() {
        val s = FloatArray(4) { CfaNoiseModel.FALLBACK_SCALE }
        val o = FloatArray(4) { CfaNoiseModel.FALLBACK_OFFSET }
        val (kept, w) = HdrVulkanMerge.robustPixel(
            0.3f, 0.95f, 0, s, o, s, o,
            gain = 1f, refScale = 1f, dampedStrength = 8f, maxMotion = 6f, factor = 1f
        )
        assertTrue("ghost must collapse, w=$w", w > 0.99f)
        assertEquals(0.3f, kept, 1e-3f)
    }

    @Test fun cpuMergeMatchesLegacyMergeWithoutDeghost() {
        val a = textured(24, 5)
        val b = textured(24, 9)
        val frames = listOf(frame(a, 5_000_000L), frame(b, 20_000_000L))
        val legacy = HdrRawMerge.merge(
            frames, 1, HdrRawMerge.Options(smoothMask = true, deghost = false)
        ).values
        val rebuilt = HdrVulkanMerge.mergeCpu(
            frames, 1, HdrVulkanMerge.Options(smoothMask = true, deghost = false)
        ).values
        require(legacy.size == rebuilt.size)
        var maxDiff = 0f
        for (i in legacy.indices) maxDiff = maxOf(maxDiff, abs(legacy[i] - rebuilt[i]))
        assertTrue("darktable core drifted: maxDiff=$maxDiff", maxDiff < 1e-5f)
    }

    @Test fun cpuMergeMatchesMergeExactWithoutSmoothing() {
        val a = textured(16, 5)
        val b = textured(16, 9)
        val frames = listOf(frame(a, 5_000_000L), frame(b, 20_000_000L))
        val exact = HdrRawMerge.mergeExact(frames, 0).values
        val rebuilt = HdrVulkanMerge.mergeCpu(
            frames, 0, HdrVulkanMerge.Options(smoothMask = false, deghost = false)
        ).values
        var maxDiff = 0f
        for (i in exact.indices) maxDiff = maxOf(maxDiff, abs(exact[i] - rebuilt[i]))
        assertTrue("exact-path drifted: maxDiff=$maxDiff", maxDiff < 1e-5f)
    }

    @Test fun cpuMergeDeghostsOutlier() {
        val ref = flat(0.3f)
        val movValues = FloatArray(16 * 16) { 0.3f }.also {
            for (y in 5..10) for (x in 5..10) it[y * 16 + x] = 0.95f
        }
        val mov = UnpackedRawCfa(16, 16, BayerPattern.RGGB, movValues, RawCrop(0, 0, 16, 16))
        val merged = HdrVulkanMerge.mergeCpu(
            listOf(frame(ref, 10_000_000L), frame(mov, 10_000_000L)), 0
        ).values
        assertTrue(merged.all { it.isFinite() })
        var ghostMean = 0.0
        var n = 0
        for (y in 6..9) for (x in 6..9) {
            ghostMean += merged[y * 16 + x]
            n++
        }
        ghostMean /= n
        assertTrue("ghost survived: mean=$ghostMean", ghostMean < 0.5)
    }

    @Test fun alternateDropFiresOnFullyGhostedFrame() {
        val ref = flat(0.3f, 16)
        // Inverted twin: every pixel mismatches, so the census rejects all.
        val movValues = FloatArray(16 * 16) { i -> 1f - (i % 19) / 19f }
        val mov = UnpackedRawCfa(16, 16, BayerPattern.RGGB, movValues, RawCrop(0, 0, 16, 16))
        var dropped = -1
        val merged = HdrVulkanMerge.mergeCpu(
            listOf(frame(ref, 10_000_000L), frame(mov, 10_000_000L)), 0,
            HdrVulkanMerge.Options(alternateDropRejectFrac = 0.5),
            onAlternateDropped = { index, _ -> dropped = index }
        ).values
        assertEquals(1, dropped)
        assertTrue(merged.all { it.isFinite() })
    }

    @Test fun entryRoutesToCpuWithoutGpu() {
        val frames = listOf(frame(textured(12, 2), 5_000_000L), frame(textured(12, 4), 20_000_000L))
        val a = HdrVulkanMerge.merge(frames, 1)
        val b = HdrVulkanMerge.mergeCpu(frames, 1)
        assertTrue(a.values.zip(b.values.toList()).all { (x, y) -> x == y })
    }

    @Test fun denseFlowPayloadCarriesFiniteDisplacements() {
        val flow = TranslationFlow(1.5f, -2f)
        val payload = HdrVulkanMerge.denseFlow(flow, 4, 3)
        assertEquals(4 * 3 * 4, payload.size)
        for (i in 0 until 4 * 3) {
            assertEquals(1.5f, payload[i * 4], 0f)
            assertEquals(-2f, payload[i * 4 + 1], 0f)
        }
        // A throwing displacement poisons the whole pair: both fall back to 0.
        val nan = HdrVulkanMerge.denseFlow(HdrFlowField { _, _ -> Float.NaN to 1f }, 2, 2)
        assertEquals(0f, nan[0], 0f)
        assertEquals(0f, nan[1], 0f)
    }

    @Test fun cpuSessionMatchesMergeCpu() {
        val a = textured(20, 5)
        val b = textured(20, 11)
        val frames = listOf(frame(a, 5_000_000L), frame(b, 20_000_000L))
        val options = HdrVulkanMerge.Options()
        val direct = HdrVulkanMerge.mergeCpu(frames, 1, options).values
        val session = HdrVulkanMerge.beginCpuSession(
            frames[1],
            frames.map {
                HdrRawMerge.calibration(it.exposureTimeNanos, it.sensitivityIso, it.aperture, it.focalLength)
            },
            frames.map {
                HdrRawMerge.photonCount(it.exposureTimeNanos, it.sensitivityIso, it.aperture, it.focalLength)
            },
            1, frames.size, options
        )
        frames.forEachIndexed { k, f -> session.accumulate(k, f) }
        val streamed = session.finish().values
        assertTrue(direct.zip(streamed.toList()).all { (x, y) -> x == y })
    }

    @Test(expected = IllegalArgumentException::class)
    fun cpuSessionRejectsOutOfOrderAccumulate() {
        val frames = listOf(frame(textured(8, 1), 5_000_000L), frame(textured(8, 2), 20_000_000L))
        val session = HdrVulkanMerge.beginCpuSession(
            frames[0],
            frames.map {
                HdrRawMerge.calibration(it.exposureTimeNanos, it.sensitivityIso, it.aperture, it.focalLength)
            },
            frames.map {
                HdrRawMerge.photonCount(it.exposureTimeNanos, it.sensitivityIso, it.aperture, it.focalLength)
            },
            0, frames.size
        )
        session.accumulate(1, frames[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun badStrengthRejected() {
        HdrVulkanMerge.Options(wienerStrength = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun badDropFracRejected() {
        HdrVulkanMerge.Options(alternateDropRejectFrac = 2.0)
    }
}
