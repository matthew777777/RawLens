package com.matthew.rawlens

import org.junit.Test

/** TEMPORARY diagnostic: swept deghost behavior on the blur fixture. DELETE BEFORE MERGE. */
class HdrGhostDiagTest {
    private val noiseHi = CfaNoiseModel(FloatArray(4) { 0.0017f }, FloatArray(4) { 4e-7f })

    private fun fixture(size: Int, exposureRatio: Float, period: Double = 4.0): Triple<HdrMergeFrame, HdrMergeFrame, UnpackedRawCfa> {
        val rng = kotlin.random.Random(7)
        fun sigma(level: Float) = kotlin.math.sqrt(0.0017f * level + 4e-7f)
        fun gauss(): Float {
            var u = 0.0
            while (u == 0.0) u = rng.nextDouble()
            val v = rng.nextDouble()
            return (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) *
                kotlin.math.cos(2.0 * kotlin.math.PI * v)).toFloat()
        }
        val grating = FloatArray(size * size) { i ->
            val x = i % size
            0.2f + 0.03f * kotlin.math.sin(2.0 * kotlin.math.PI * x / period).toFloat()
        }
        val refValues = FloatArray(size * size) { i -> grating[i] + sigma(grating[i]) * gauss() }
        val movValues = FloatArray(size * size) { i ->
            val x = i % size
            val y = i / size
            var sum = 0f
            for (dx in -1..1) sum += grating[y * size + (x + dx).coerceIn(0, size - 1)]
            val level = sum / 3f * exposureRatio
            level + sigma(level.coerceAtMost(1.2f)) * gauss()
        }
        val ref = UnpackedRawCfa(size, size, BayerPattern.RGGB, refValues, RawCrop(0, 0, size, size))
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movValues, RawCrop(0, 0, size, size))
        val refExp = 10_000_000L
        return Triple(
            HdrMergeFrame(ref, refExp, 100, noiseModel = noiseHi),
            HdrMergeFrame(mov, (refExp * exposureRatio).toLong(), 100, noiseModel = noiseHi),
            mov
        )
    }

    private fun edgeEnergy(v: FloatArray, size: Int): Float {
        var sum = 0f
        for (y in 1 until size - 1) for (x in 1 until size - 1) {
            sum += kotlin.math.abs(v[y * size + x] - v[y * size + x - 1])
            sum += kotlin.math.abs(v[y * size + x] - v[(y - 1) * size + x])
        }
        return sum
    }

    @Test fun sweepBlurResponse() {
        for (ratio in floatArrayOf(1f, 4f)) {
            val (ref, mov, movCfa) = fixture(64, ratio)
            val refE = edgeEnergy(ref.cfa.values, 64)
            for (strength in floatArrayOf(1f, 8f, 25f)) {
                val (out, stats) = HdrTileDeghost.deghostWithStats(ref, mov, movCfa, strength)
                val outE = edgeEnergy(FloatArray(64 * 64) { out.values[it] / ratio }, 64)
                println("DIAG ratio=$ratio strength=$strength sharpRatio=${outE / refE} " +
                    "dcReject=${stats.dcRejectFrac} meanMismatch=${stats.meanMismatch} tiles=${stats.tiles}")
            }
        }
        // Same fixture but SHARP alternate (control: must keep averaging, ratio ~1, low reject)
        val rng = kotlin.random.Random(11)
        val size = 64
        fun sigma(level: Float) = kotlin.math.sqrt(0.0017f * level + 4e-7f)
        val grating = FloatArray(size * size) { i ->
            0.2f + 0.03f * kotlin.math.sin(2.0 * kotlin.math.PI * (i % size) / 4.0).toFloat()
        }
        val refV = FloatArray(size * size) { grating[it] + sigma(grating[it]) * rng.nextFloat() }
        val movV = FloatArray(size * size) { grating[it] * 4f + sigma(grating[it] * 4f) * rng.nextFloat() }
        val ref = UnpackedRawCfa(size, size, BayerPattern.RGGB, refV, RawCrop(0, 0, size, size))
        val mov = UnpackedRawCfa(size, size, BayerPattern.RGGB, movV, RawCrop(0, 0, size, size))
        val (out, stats) = HdrTileDeghost.deghostWithStats(
            HdrMergeFrame(ref, 10_000_000L, 100, noiseModel = noiseHi),
            HdrMergeFrame(mov, 40_000_000L, 100, noiseModel = noiseHi), mov, 8f)
        val outE = edgeEnergy(FloatArray(size * size) { out.values[it] / 4f }, size)
        println("DIAG sharp-control sharpRatio=${outE / edgeEnergy(refV, size)} " +
            "dcReject=${stats.dcRejectFrac} meanMismatch=${stats.meanMismatch}")
    }
}
