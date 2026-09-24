package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HdrTileDeghostTest {
    private val noise = CfaNoiseModel(FloatArray(4) { 2.5e-4f }, FloatArray(4) { 2.5e-6f })

    @Test fun movingWaterDoesNotCreateNewBrightOrDarkNeedlePoints() {
        val size = 96
        // Repeating wavelets with equal tile means but different phases. Both
        // exposures contain dark troughs and bright glints; neither has holes.
        for (pattern in BayerPattern.entries) for (ratio in listOf(0.25f, 1f, 4f)) {
            fun wave(shift: Int) = FloatArray(size * size) { i ->
                val x = (i % size) / 2
                val y = (i / size) / 2
                val phase = ((x + shift) * 13 + y * 7) and 15
                if (phase < 2) 0.019f else 0.006f + phase * 0.0005f
            }
            val ref = UnpackedRawCfa(size, size, pattern, wave(0), RawCrop(0, 0, size, size))
            val mov = ref.copy(values = wave(3).map { it * ratio }.toFloatArray())
            val out = HdrTileDeghost.deghost(
                HdrMergeFrame(ref, 4_000_000L, 100, noiseModel = noise),
                HdrMergeFrame(mov, (4_000_000L * ratio).toLong(), 100, noiseModel = noise), mov)
            // Dark reference excludes global midtone gain fitting; equal tile
            // means leave the known bracket ratio unchanged.
            for (y in 32 until 64) for (x in 32 until 64) {
                val i = y * size + x
                val a = ref.values[i] * ratio
                val b = mov.values[i]
                assertTrue("new extremum at ($x,$y), ratio=$ratio: ${out.values[i]} vs $a/$b",
                    out.values[i] >= minOf(a, b) - 1e-5f &&
                        out.values[i] <= maxOf(a, b) + 1e-5f)
            }
        }
    }

    @Test fun edgeGainRefinementCannotDarkenMatchingPixels() {
        val size = 96
        for (pattern in BayerPattern.entries) for (ratio in listOf(0.25f, 1f, 4f)) {
            val ref = UnpackedRawCfa(size, size, pattern, FloatArray(size * size) { i ->
                if (i % size < 46) 0.04f else 0.2f
            }, RawCrop(0, 0, size, size))
            // Residual edge displacement changes the tile mean, not exposure.
            val mov = ref.copy(values = FloatArray(size * size) { i ->
                (if (i % size < 50) 0.04f else 0.2f) * ratio
            })
            val out = HdrTileDeghost.deghost(
                HdrMergeFrame(ref, 4_000_000L, 100, noiseModel = noise),
                HdrMergeFrame(mov, (4_000_000L * ratio).toLong(), 100, noiseModel = noise), mov)
            for (y in 0 until size) for (x in 0 until size) {
                val i = y * size + x
                val a = ref.values[i] * ratio
                val b = mov.values[i]
                assertTrue("edge halo at $x,$y ($pattern, $ratio): ${out.values[i]} vs $a/$b",
                    out.values[i] >= minOf(a, b) - 1e-5f &&
                        out.values[i] <= maxOf(a, b) + 1e-5f)
            }
        }
    }

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

    @Test fun subpixelShiftedAlternateStillAverages() {
        // Aligner residual: alternate shifted 0.5px (same-colour bilinear,
        // no colour mixing). Fourier refinement must absorb it so the tile
        // averages instead of collapsing to the reference.
        val size = 64
        val ref = textured(size, 5)
        val flow = TranslationFlow(0.5f, 0f)
        val shifted = ref.copy(values = FloatArray(size * size) { i ->
            HdrRawMerge.sampleSmooth(ref, flow, i % size, i / size)
        })
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(shifted.copy(), 10_000_000L, 100, noiseModel = noise),
            shifted
        )
        // Output tracks the alternate (averaged), not snapped to ref.
        assertEquals(shifted.values.average(), out.values.average(), 0.01)
    }

    @Test fun singleBadChannelDoesNotVetoWholeBin() {
        // One bad channel (spike) must not veto the bin: trimmed mid-mean
        // averages the three good channels instead of collapsing to ref.
        val size = 32
        val ref = cfa(0.2f, size)
        val movValues = FloatArray(size * size) { i ->
            val x = i % size
            val y = i / size
            // Blue sites spiked, everything else matched 1% high.
            if (BayerPattern.RGGB.colorAt(x, y) == CfaColor.BLUE) 0.9f else 0.202f
        }
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
            mov
        )
        // Green/red sites keep the alternate (average), not ref.
        var n = 0
        var sum = 0.0
        for (i in movValues.indices) {
            val x = i % size
            val y = i / size
            if (BayerPattern.RGGB.colorAt(x, y) != CfaColor.BLUE) {
                sum += out.values[i]
                n++
            }
        }
        assertEquals(0.202, sum / n, 0.01)
    }

    @Test fun motionNormCurveBoostsStaticAndCutsMotion() {
        // Upstream motion_norm shape with MAX_MOTION_NORM=6: static tiles
        // (mismatch <= 0.02) average 6x harder, motion (>= 0.17) unboosted.
        assertEquals(6f, HdrTileDeghost.motionNorm(0f), 1e-5f)
        assertEquals(6f, HdrTileDeghost.motionNorm(0.02f), 1e-5f)
        assertEquals(2.6667f, HdrTileDeghost.motionNorm(0.12f), 1e-3f)
        assertEquals(1f, HdrTileDeghost.motionNorm(0.17f), 1e-5f)
        assertEquals(1f, HdrTileDeghost.motionNorm(1f), 1e-5f)
    }

    @Test fun mismatchFromStatsMapsStaticToUpstreamOperatingPoint() {
        // Static tile: mean abs near E|N(0,1)| ~0.8 sigma -> ~0.12.
        assertEquals(0.12f, HdrTileDeghost.mismatchFromStats(0.008f, 0.01f), 1e-5f)
        // Ghost: many sigma -> clamps to 1.
        assertEquals(1f, HdrTileDeghost.mismatchFromStats(0.5f, 0.01f), 0f)
        // Degenerate scale never yields NaN.
        assertEquals(1f, HdrTileDeghost.mismatchFromStats(0f, 0f), 0f)
    }

    @Test fun magnitudeNormPrefersSharperAlternateAndGates() {
        // sqRatio is (|Alt|^2/|Ref|^2): ratio^4 = sqRatio^2, capped [0.5,3].
        assertEquals(3f, HdrTileDeghost.magnitudeNorm(4f, 0.1f, false), 1e-5f)
        assertEquals(0.5f, HdrTileDeghost.magnitudeNorm(0.25f, 0.1f, false), 1e-5f)
        assertEquals(1f, HdrTileDeghost.magnitudeNorm(1f, 0.1f, false), 1e-5f)
        // DC never boosted; high mismatch gated off.
        assertEquals(1f, HdrTileDeghost.magnitudeNorm(4f, 0.1f, true), 0f)
        assertEquals(1f, HdrTileDeghost.magnitudeNorm(4f, 0.35f, false), 0f)
    }

    @Test fun softHighlightRescueHandsClippedRefToAlternate() {
        // Top half: reference clipped, alternate holds detail. Bottom half:
        // both clean and matched. No hard bypass anymore — weight handoff.
        val size = 64
        val refValues = FloatArray(size * size) { i ->
            if (i / size < size / 2) 1f else 0.3f
        }
        val ref = UnpackedRawCfa(size, size, BayerPattern.RGGB, refValues, RawCrop(0, 0, size, size))
        val movValues = FloatArray(size * size) { i ->
            if (i / size < size / 2) 0.9f else 0.3f
        }
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
            mov
        )
        // Clipped region resolves to the alternate, clean region averages.
        assertEquals(0.9f, out.values[16 * size + 16], 0.03f)
        assertEquals(0.3f, out.values[48 * size + 16], 0.02f)
    }

    @Test fun partialClipBlendsWithoutContour() {
        // Reference just inside the soft ramp (0.96): output must move off
        // the clipped value toward the alternate (no hard step), while
        // staying inside the contributing range (existing clamp invariant).
        val size = 64
        val ref = cfa(0.96f, size)
        val mov = cfa(0.5f, size)
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
            mov
        )
        val v = out.values[32 * size + 32]
        assertTrue("partial clip stuck at ref: $v", v < 0.96f - 1e-3f)
        assertTrue("partial clip escaped range: $v", v >= 0.5f - 1e-5f && v <= 0.96f + 1e-5f)
    }

    @Test fun singleClippedPixelRescuesLikeFullTile() {
        // Hot pixel / specular dot: one clipped reference site in an
        // otherwise clean tile must hand to the alternate (darktable
        // parity), not leak the clipped reference into the output.
        val size = 64
        val refValues = FloatArray(size * size) { 0.05f }
        refValues[32 * size + 32] = 1f
        val ref = UnpackedRawCfa(size, size, BayerPattern.RGGB, refValues, RawCrop(0, 0, size, size))
        val movValues = FloatArray(size * size) { 0.05f }
        movValues[32 * size + 32] = 0.04f
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
            mov
        )
        val v = out.values[32 * size + 32]
        assertTrue("hot pixel leaked: $v", v < 0.2f)
    }

    @Test fun invalidTileSizeIsRejected() {
        val cfa = cfa(0.2f, 32)
        assertThrows(IllegalArgumentException::class.java) {
            HdrTileDeghost.deghost(
                HdrMergeFrame(cfa, 10_000_000L, 100, noiseModel = noise),
                HdrMergeFrame(cfa.copy(), 10_000_000L, 100, noiseModel = noise),
                cfa.copy(), 8f, 12
            )
        }
    }

    @Test fun largeFallbackPassesThroughAndKillsGhosts() {
        // 16px fallback: identical frames survive the Hann/triangular
        // 4-phase path, and ghosts still collapse to the reference.
        val cfa = textured(64, 7)
        val out = HdrTileDeghost.deghost(
            HdrMergeFrame(cfa, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(cfa.copy(), 10_000_000L, 100, noiseModel = noise),
            cfa.copy(), 8f, HdrTileDeghost.TILE_LARGE
        )
        var maxDiff = 0f
        for (i in cfa.values.indices) {
            maxDiff = maxOf(maxDiff, abs(out.values[i] - cfa.values[i]))
        }
        assertTrue("large-tile passthrough maxDiff=$maxDiff", maxDiff < 0.02f)

        val size = 64
        val ref = cfa(0.3f, size)
        val movValues = FloatArray(size * size) { 0.3f }.also {
            for (y in 24..39) for (x in 24..39) it[y * size + x] = 0.9f
        }
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        val ghost = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
            mov, 8f, HdrTileDeghost.TILE_LARGE
        )
        val center = ghost.values[31 * size + 31]
        assertTrue("large-tile ghost leaked: $center", center < 0.55f && center > 0.15f)
    }

    @Test fun smallTilesRejectGhostAtLeastAsTightly() {
        // Same ghost fixture at both sizes: the 8px default must collapse
        // at least as tightly (smaller tiles localize the ghost better).
        val size = 64
        for (tileSize in listOf(HdrTileDeghost.TILE, HdrTileDeghost.TILE_LARGE)) {
            val ref = cfa(0.3f, size)
            val movValues = FloatArray(size * size) { 0.3f }.also {
                for (y in 24..39) for (x in 24..39) it[y * size + x] = 0.9f
            }
            val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
            val out = HdrTileDeghost.deghost(
                HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
                HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
                mov, 8f, tileSize
            )
            // Ghost energy remaining above the reference floor, center block.
            var leak = 0.0
            for (y in 26..37) for (x in 26..37) leak += out.values[y * size + x] - 0.3f
            assertTrue("tileSize=$tileSize leak=$leak", leak < 12 * 12 * 0.25)
        }
    }

    @Test fun profileBothTileSizes() {
        // Relative host timing (JVM, not device): informational, guards
        // against pathological blowups only.
        val ref = textured(256, 5)
        val mov = textured(256, 9)
        for (tileSize in listOf(HdrTileDeghost.TILE, HdrTileDeghost.TILE_LARGE)) {
            // Warmup.
            HdrTileDeghost.deghost(
                HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
                HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
                mov.copy(), 8f, tileSize
            )
            val t = System.nanoTime()
            HdrTileDeghost.deghost(
                HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
                HdrMergeFrame(mov, 10_000_000L, 100, noiseModel = noise),
                mov.copy(), 8f, tileSize
            )
            val ms = (System.nanoTime() - t) / 1_000_000
            println("deghost tileSize=$tileSize 256px: ${ms}ms")
            assertTrue("tileSize=$tileSize suspiciously slow: ${ms}ms", ms < 120_000)
        }
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
