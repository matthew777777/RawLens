package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HdrTileDeghostTest {
    private val noise = CfaNoiseModel(FloatArray(4) { 2.5e-4f }, FloatArray(4) { 2.5e-6f })

    @Test fun fftRoundtripIsIdentity() {
        val n = HdrTileDeghost.TILE * HdrTileDeghost.TILE
        val re = FloatArray(n) { i -> ((i * 13) % 17) / 17f - 0.5f }
        // Nonzero imaginary input must roundtrip too (the Wiener blend feeds
        // complex spectra through forward+inverse every tile).
        val im = FloatArray(n) { i -> ((i * 7) % 11) / 11f - 0.5f }
        val origRe = re.copyOf()
        val origIm = im.copyOf()
        HdrTileDeghost.fft2D(re, im, false)
        HdrTileDeghost.fft2D(re, im, true)
        for (i in 0 until n) assertEquals(origRe[i], re[i], 1e-3f)
        for (i in 0 until n) assertEquals(origIm[i], im[i], 1e-3f)
    }

    @Test fun identicalFramesPassThrough() {
        val cfa = textured(32, 7)
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(cfa, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(cfa.copy(), 10_000_000L, 100, noiseModel = noise),
            cfa.copy()
        )
        var maxDiff = 0f
        for (i in cfa.values.indices) {
            maxDiff = maxOf(maxDiff, abs(out.values[i] - cfa.values[i]))
        }
        assertTrue("maxDiff=$maxDiff", maxDiff < 0.01f)
    }

    @Test fun ghostSquareCollapsesToReference() {
        val size = 32
        val ref = cfa(0.3f, size)
        val movValues = FloatArray(size * size) { 0.3f }.also {
            for (y in 12..19) for (x in 12..19) it[y * size + x] = 0.9f
        }
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
            mov
        )
        val center = out.values[15 * size + 15]
        assertTrue("ghost leaked: $center", center < 0.55f && center > 0.15f)
        val corner = out.values[2 * size + 2]
        assertEquals(0.3f, corner, 0.02f)
    }

    @Test fun blurredAlternateKeepsReferenceSharpness() {
        // Motion-blurred long exposure (the F02 failure): high frequencies
        // must come from the sharp reference, not the blurred alternate.
        val size = 32
        val ref = UnpackedRawCfa(size, size, BayerPattern.RGGB,
            FloatArray(size * size) { i ->
                val x = i % size
                val y = i / size
                if ((x / 2 + y / 2) % 2 == 0) 0.2f else 0.4f
            }, RawCrop(0, 0, size, size))
        val movValues = FloatArray(size * size) { i ->
            val x = i % size
            val y = i / size
            var sum = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, size - 1)
                val yy = (y + dy).coerceIn(0, size - 1)
                sum += ref.values[yy * size + xx]
            }
            sum / 9f
        }
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        assertTrue("fixture must blur", edgeEnergy(movValues, size) < 0.5f * edgeEnergy(ref.values, size))
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
            mov
        )
        val ratio = edgeEnergy(out.values, size) / edgeEnergy(ref.values, size)
        assertTrue("sharpness lost: ratio=$ratio", ratio > 0.7f)
    }

    @Test fun matchedContentIsKeptForDownstreamAveraging() {
        // Sub-noise offset must survive (darktable averages it later),
        // not snap to the reference.
        val size = 32
        val ref = cfa(0.2f, size)
        val mov = cfa(0.203f, size)
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
            mov
        )
        assertEquals(0.203, out.values.average(), 0.005)
    }

    @Test fun exposureGainRoundtripsToMovingDomain() {
        val size = 32
        val ref = cfa(0.4f, size)
        val mov = cfa(0.1f, size)
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 2_500_000L, 100, noiseModel = noise),
            mov
        )
        // Matched content returns to the moving frame's own exposure domain.
        assertEquals(0.1, out.values.average(), 0.01)
    }

    @Test fun clippedReferenceBypassesBlend() {
        // Short-exposure highlight detail must survive for darktable rescue.
        val size = 32
        val ref = cfa(1f, size)
        val mov = cfa(0.25f, size)
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 2_500_000L, 100, noiseModel = noise),
            mov
        )
        assertEquals(0.25, out.values.average(), 0.01)
    }

    @Test fun manyTilesPreserveBrightnessAndWriteEveryPixel() {
        // Scratch FFT planes are reused across tiles: stale imaginary input
        // used to feed back spectrum-scale garbage, growing to NaN within a
        // dozen tiles and silently voiding every later tile (exact 0.0).
        // 128px keeps >20 tiles per thread even with 32px Bayer tiles.
        val size = 128
        val ref = textured(size, 5)
        val mov = textured(size, 5)
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
            mov
        )
        assertEquals(mov.values.average(), out.values.average(), 0.02)
        assertTrue("unwritten pixels", out.values.all { it > 0f })
    }

    @Test fun tileGainCorrectsSmallBias() {
        // Matched means 10% high -> gain refined 10% down.
        val ref = FloatArray(4) { 0.05f }
        val mov = FloatArray(4) { 0.055f }
        assertEquals(3.636f, HdrTileDeghost.refineTileGain(ref, mov, 4f, 0), 1e-3f)
    }

    @Test fun tileGainClampsGhostShifts() {
        // 3x mean shift (ghost) must not become the gain.
        val ref = FloatArray(4) { 0.15f }
        val mov = FloatArray(4) { 0.05f }
        assertEquals(4f * 4f / 3f, HdrTileDeghost.refineTileGain(ref, mov, 4f, 0), 1e-5f)
    }

    @Test fun tileGainKeepsGlobalWhenUntrustworthy() {
        // Near-black tiles: ratios are noise.
        assertEquals(4f, HdrTileDeghost.refineTileGain(
            FloatArray(4) { 0.001f }, FloatArray(4) { 0.001f }, 4f, 0), 0f)
        // Heavily clipped moving tile: means understate the signal, but a
        // few clipped sites are tolerated.
        assertEquals(4f, HdrTileDeghost.refineTileGain(
            FloatArray(4) { 0.5f }, FloatArray(4) { 0.5f }, 4f, 1024), 0f)
        assertEquals(4f, HdrTileDeghost.refineTileGain(
            FloatArray(4) { 0.5f }, FloatArray(4) { 0.5f }, 4f, 3), 0f)
        // Fewer than two valid channels.
        assertEquals(4f, HdrTileDeghost.refineTileGain(
            floatArrayOf(0.05f, 0f, 0f, 0f), floatArrayOf(0.05f, 0f, 0f, 0f), 4f, 0), 0f)
    }

    @Test fun localExposureBiasStillMerges() {
        // One region sits 25% off the global gain (local light change): the
        // per-tile refinement must recover it instead of rejecting the tile
        // to the reference (which would cost the long exposure's shadows).
        val size = 96
        val ref = cfa(0.05f, size)
        val movValues = FloatArray(size * size) { i ->
            if (i % size < size / 2) 0.0125f else 0.01f
        }
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 2_500_000L, 100, noiseModel = noise),
            mov
        )
        // Tile-interior samples, away from the region boundary at x=48.
        assertEquals(0.0125f, out.values[48 * size + 16], 0.0125f * 0.04f)
        assertEquals(0.01f, out.values[48 * size + 80], 0.01f * 0.04f)
    }

    @Test fun dcFollowsLowFrequencyConsensus() {
        // Dark static content with a pure brightness offset (blur leakage
        // across tile borders moves only DC): gain refinement skips
        // near-black tiles, so without LF-consensus DC the tile would
        // collapse to the reference. With it, the tile averages and the
        // alternate's clean low frequencies survive.
        val size = 64
        val refValues = FloatArray(size * size) { i ->
            (((i * 7 + 5) % 19) / 19f) * 0.014f + 0.008f
        }
        val ref = UnpackedRawCfa(size, size, BayerPattern.RGGB, refValues, RawCrop(0, 0, size, size))
        val movValues = FloatArray(size * size) { refValues[it] + 0.004f }
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
            mov
        )
        // Offset preserved (averaged), not removed (reference is 0.004 lower).
        assertEquals(movValues.average(), out.values.average(), 0.001)
    }

    @Test fun darkFramesAverageLongExposureCleanShadows() {
        // Dark scene: texture sits below the noise floor, so even a blurred
        // long exposure matches and must average (its 4x photons clean the
        // shadows). Gain refinement skips near-black tiles; the global gain
        // plus LF-consensus DC still average.
        val size = 64
        var seed = 1234567L
        fun nextNoise(amp: Float): Float {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            return ((seed % 1000) / 1000f - 0.5f) * 2f * amp
        }
        val refValues = FloatArray(size * size) { 0.01f + nextNoise(0.002f) }
        val ref = UnpackedRawCfa(size, size, BayerPattern.RGGB, refValues, RawCrop(0, 0, size, size))
        val movValues = FloatArray(size * size) { 0.04f + nextNoise(0.001f) }
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 40_000_000L, 100, noiseModel = noise),
            mov
        )
        assertEquals(0.04, out.values.average(), 0.004)
        // Compare in the reference domain: averaged output must be clearly
        // cleaner than the reference alone.
        fun variance(v: FloatArray, scale: Float): Double {
            val mean = v.average() * scale
            return v.map { (it * scale - mean) * (it * scale - mean) }.average()
        }
        assertTrue(
            "no averaging: ${variance(out.values, 0.25f)} vs ${variance(refValues, 1f)}",
            variance(out.values, 0.25f) < variance(refValues, 1f) * 0.5
        )
    }

    @Test fun deghostIsDeterministic() {
        val ref = textured(48, 3)
        val mov = textured(48, 11)
        val a = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100), HdrMergeFrame(mov, 5_000_000L, 100), mov
        ).values
        val b = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100), HdrMergeFrame(mov, 5_000_000L, 100), mov
        ).values
        assertTrue(a.zip(b.toList()).all { (x, y) -> x == y })
    }

    private fun edgeEnergy(v: FloatArray, size: Int): Float {
        var sum = 0f
        for (y in 1 until size - 1) for (x in 1 until size - 1) {
            sum += abs(v[y * size + x] - v[y * size + x - 1])
            sum += abs(v[y * size + x] - v[(y - 1) * size + x])
        }
        return sum
    }

    private fun textured(size: Int, seed: Int) = UnpackedRawCfa(size, size, BayerPattern.RGGB,
        FloatArray(size * size) { i -> (((i * 7 + seed) % 19) / 19f).coerceIn(0.05f, 0.9f) },
        RawCrop(0, 0, size, size))

    private fun cfa(value: Float, size: Int) = UnpackedRawCfa(
        size, size, BayerPattern.RGGB, FloatArray(size * size) { value },
        RawCrop(0, 0, size, size)
    )
}
