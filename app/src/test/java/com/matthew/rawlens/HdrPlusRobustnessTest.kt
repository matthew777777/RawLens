package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class HdrPlusRobustnessTest {
    @Test fun exposureFactorIsCompOverRef() {
        val ref = frame(0.2f, 32, 10_000_000L, 100)
        val long = frame(0.2f, 32, 40_000_000L, 100)
        val short = frame(0.2f, 32, 2_500_000L, 100)
        assertEquals(4f, HdrPlusRobustness.exposureFactor(ref, long), 1e-5f)
        assertEquals(0.25f, HdrPlusRobustness.exposureFactor(ref, short), 1e-5f)
        assertEquals(1f, HdrPlusRobustness.exposureFactor(ref, ref.copy()), 1e-5f)
    }

    @Test fun exposureCorrRatioMatchesUpstreamMeans() {
        // Uniform burst: corr1 = corr2 = 1.
        assertEquals(1f, HdrPlusRobustness.exposureCorrRatio(listOf(1f, 1f)), 1e-6f)
        // Pair [ref 1x, long 4x]: corr1 = (1 + 0.625)/2, corr2 = (1+4)/2.
        assertEquals(0.8125f / 2.5f, HdrPlusRobustness.exposureCorrRatio(listOf(1f, 4f)), 1e-6f)
        assertEquals(0.8125f / 2.5f, HdrPlusRobustness.pairCorrRatio(4f), 1e-6f)
        // 3-frame bracket [0.25x, 1x, 4x].
        val corr1 = (2.5 + 1.0 + 0.625) / 3.0
        val corr2 = (0.25 + 1.0 + 4.0) / 3.0
        assertEquals((corr1 / corr2).toFloat(),
            HdrPlusRobustness.exposureCorrRatio(listOf(0.25f, 1f, 4f)), 1e-6f)
    }

    @Test fun strengthDampingIsConservativeOnly() {
        // Uniform: exactly 1 (legacy behavior preserved).
        assertEquals(1f, HdrPlusRobustness.strengthDamping(listOf(1f, 1f)), 0f)
        // Long companion: damps (calmer cross-exposure averaging).
        assertEquals(0.325f, HdrPlusRobustness.pairStrengthDamping(4f), 1e-6f)
        // Short companion's raw corr exceeds 1 (2.8) but damping clamps:
        // brackets never average harder than uniform (the DNG noise model
        // already captures the companion's own noise).
        assertEquals(1f, HdrPlusRobustness.pairStrengthDamping(0.25f), 0f)
    }

    @Test fun maxMotionAdaptsToCompanionExposure() {
        // Uniform path: base ceiling unchanged.
        assertEquals(6f, HdrPlusRobustness.maxMotionForFactor(6f, 1f, true), 0f)
        assertEquals(6f, HdrPlusRobustness.maxMotionForFactor(6f, 4f, true), 0f)
        // Bracket path: min(4,f)*sqrt(base) — longer averages harder.
        assertEquals(4f * sqrt(6f), HdrPlusRobustness.maxMotionForFactor(6f, 4f, false), 1e-5f)
        // Shorter stays at the floor (never below 1).
        assertEquals(1f, HdrPlusRobustness.maxMotionForFactor(6f, 0.25f, false), 0f)
        assertEquals(1f, HdrPlusRobustness.maxMotionForFactor(6f, 0.01f, false), 0f)
    }

    @Test fun motionNormHonorsPerCompanionCeiling() {
        // Static tiles hit the companion ceiling; motion tiles cut to 1.
        assertEquals(9.798f, HdrTileDeghost.motionNorm(0f, 4f * sqrt(6f)), 1e-3f)
        assertEquals(1f, HdrTileDeghost.motionNorm(0.17f, 4f * sqrt(6f)), 1e-5f)
        assertEquals(1f, HdrTileDeghost.motionNorm(0f, 1f), 1e-5f)
        // Legacy default unchanged.
        assertEquals(6f, HdrTileDeghost.motionNorm(0f), 1e-5f)
    }

    @Test fun highlightsNormGatesOnLongerCompanion() {
        // Same/shorter companions: no-op (upstream factor > 1.001 gate).
        assertEquals(1f, HdrPlusRobustness.highlightsNorm(1f, 1f), 0f)
        assertEquals(1f, HdrPlusRobustness.highlightsNorm(0.25f, 1f), 0f)
        // Longer companion: (1-frac)^2 with upstream floor.
        assertEquals(1f, HdrPlusRobustness.highlightsNorm(4f, 0f), 1e-6f)
        assertEquals(0.25f, HdrPlusRobustness.highlightsNorm(4f, 0.5f), 1e-6f)
        assertEquals(0.01f, HdrPlusRobustness.highlightsNorm(4f, 1f), 1e-6f)
        assertEquals(0.04f / 2f, HdrPlusRobustness.highlightsNorm(2f, 1f), 1e-6f)
    }

    @Test fun uniformDetectionToleratesMeteringNoise() {
        assertTrue(HdrPlusRobustness.isUniform(listOf(1f, 1f)))
        assertTrue(HdrPlusRobustness.isUniform(listOf(1f, 1.02f)))
        assertFalse(HdrPlusRobustness.isUniform(listOf(1f, 1.1f)))
        assertFalse(HdrPlusRobustness.isUniform(listOf(0.25f, 1f, 4f)))
        assertTrue(HdrPlusRobustness.isUniformPair(1f))
        assertFalse(HdrPlusRobustness.isUniformPair(4f))
    }

    @Test fun explicitBurstFactorsMatchPairwiseFallback() {
        // Deghost derives [1, factor] from EXIF when burstFactors is null;
        // passing the same pair explicitly must be bit-identical.
        val ref = frame(0.2f, 48, 10_000_000L, 100)
        val mov = frame(0.2f, 48, 40_000_000L, 100)
        val a = HdrTileDeghost.deghost(ref, mov, mov.cfa.copy()).values
        val factor = HdrPlusRobustness.exposureFactor(ref, mov)
        val b = HdrTileDeghost.deghost(ref, mov, mov.cfa.copy(), burstFactors = listOf(1f, factor)).values
        assertTrue(a.zip(b.toList()).all { (x, y) -> x == y })
    }

    @Test fun uniformBurstFactorsAreLegacyIdentical() {
        // Uniform pairs damp to exactly 1 with the base ceiling and no
        // highlight discount, so explicit [1, 1] equals the null fallback.
        val ref = textured(48, 3)
        val mov = textured(48, 3)
        val a = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100),
            HdrMergeFrame(mov, 10_000_000L, 100), mov.copy()).values
        val b = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100),
            HdrMergeFrame(mov, 10_000_000L, 100), mov.copy(),
            burstFactors = listOf(1f, 1f)).values
        assertTrue(a.zip(b.toList()).all { (x, y) -> x == y })
    }

    @Test fun bracketDampingRejectsBlurredLongExposureHarder() {
        // Pairwise corr damping (0.325 for a 1x+4x pair) must reject a
        // blurred long exposure harder than the undamped strength: the
        // damped output stays closer to the sharp reference.
        val size = 48
        val ref = checker(size)
        val movValues = FloatArray(size * size) { i ->
            val x = i % size
            val y = i / size
            var sum = 0f
            for (dy in -1..1) for (dx in -1..1) {
                sum += ref.values[y.coerceIn(0, size - 1) * size + x.coerceIn(0, size - 1)]
            }
            sum / 9f * 4f
        }
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        val noise = CfaNoiseModel(FloatArray(4) { 2.5e-4f }, FloatArray(4) { 2.5e-6f })
        // Undamped: uniform burst factors force damping 1 + base ceiling.
        val lax = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 40_000_000L, 100, noiseModel = noise),
            mov.copy(), burstFactors = listOf(1f, 1f))
        // Damped: pairwise fallback applies the 1x+4x corr ratio.
        val strict = HdrTileDeghost.deghost(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noise),
            HdrMergeFrame(mov, 40_000_000L, 100, noiseModel = noise),
            mov.copy())
        fun edgeEnergy(v: FloatArray): Float {
            var sum = 0f
            for (y in 1 until size - 1) for (x in 1 until size - 1) {
                sum += kotlin.math.abs(v[y * size + x] - v[y * size + x - 1])
            }
            return sum / 4f
        }
        val refEnergy = edgeEnergy(ref.values.map { it * 4f }.toFloatArray())
        val laxRatio = edgeEnergy(lax.values) / refEnergy
        val strictRatio = edgeEnergy(strict.values) / refEnergy
        assertTrue("damped=$strictRatio lax=$laxRatio", strictRatio >= laxRatio)
    }

    private fun frame(value: Float, size: Int, expNanos: Long, iso: Int) = HdrMergeFrame(
        UnpackedRawCfa(size, size, BayerPattern.RGGB,
            FloatArray(size * size) { value }, RawCrop(0, 0, size, size)),
        expNanos, iso)

    private fun textured(size: Int, seed: Int) = UnpackedRawCfa(size, size, BayerPattern.RGGB,
        FloatArray(size * size) { i -> (((i * 7 + seed) % 19) / 19f).coerceIn(0.05f, 0.9f) },
        RawCrop(0, 0, size, size))

    private fun checker(size: Int) = UnpackedRawCfa(size, size, BayerPattern.RGGB,
        FloatArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if ((x / 2 + y / 2) % 2 == 0) 0.2f else 0.4f
        }, RawCrop(0, 0, size, size))
}
