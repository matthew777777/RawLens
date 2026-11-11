// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class HdrPlusSceneNoiseTest {
    private val w = 512
    private val white = 4095f

    /** Horizontal brightness ramp with Poisson-Gaussian noise var = a*x + b (normalized). */
    private fun frame(a: Double, b: Double, seed: Long, scale: Int = 1,
                      black: FloatArray = floatArrayOf(64f, 80f, 96f, 112f)): Pair<ByteBuffer, FloatArray> {
        val random = Random(seed)
        val blacks = black.map { it * scale }.toFloatArray()
        // Nonzero buffer position exercises the packed-buffer view contract.
        val bytes = ByteBuffer.allocate(w * w * 2 + 16).order(ByteOrder.nativeOrder())
        bytes.position(16)
        for (y in 0 until w) for (x in 0 until w) {
            val phase = (y % 2) * 2 + x % 2
            val range = white * scale - blacks[phase]
            val level = 0.02 + 0.6 * (x / 8) * 8 / w
            val v = blacks[phase] + range * (level + sqrt(a * level + b) * random.nextGaussian())
            bytes.putShort(v.toInt().coerceIn(0, 65535).toShort())
        }
        bytes.position(16)
        return bytes to blacks
    }

    private fun fit(a: Double, b: Double, frames: Int = 4, scale: Int = 1,
                    prior: HdrPlusNoisePrior? = null): HdrPlusNoiseModel? =
        HdrPlusSceneNoise.fit((0 until frames).map {
            val (bytes, black) = frame(a, b, 42L + it, scale)
            HdrPlusSceneNoise.sample(bytes, w, w, black, white * scale)
        }, prior)

    @Test fun recoversShotAndReadNoise() {
        val shot = fit(1e-3, 0.0)!!
        assertEquals(1e-3, shot.a, 1.5e-4)
        assertTrue(shot.b < 3e-5)
        val mixed = fit(4e-4, 1e-4)!!
        assertEquals(sqrt(4e-4 * 0.3 + 1e-4), mixed.sigma(0.3), 0.1 * mixed.sigma(0.3))
        assertEquals(sqrt(4e-4 * 0.05 + 1e-4), mixed.sigma(0.05), 0.1 * mixed.sigma(0.05))
        assertTrue(mixed.bins >= 3 && mixed.levels.size == mixed.bins)
        assertTrue(mixed.levels.toList() == mixed.levels.sorted())
    }

    @Test fun bitDepthAndBlackOffsetsDoNotChangeTheNormalizedModel() {
        val base = fit(1e-3, 2e-5)!!
        val scaled = fit(1e-3, 2e-5, scale = 4)!!
        assertEquals(base.sigma(0.2), scaled.sigma(0.2), 0.05 * base.sigma(0.2))
    }

    @Test fun unusableInputIsUnknownNotNoiseless() {
        val flat = ByteBuffer.allocate(128 * 128 * 2)
        assertNull(HdrPlusSceneNoise.fit(listOf(HdrPlusSceneNoise.sample(flat, 128, 128, FloatArray(4), white))))
        assertNull(HdrPlusSceneNoise.sample(flat, 256, 256, FloatArray(4), white))
        assertNull(HdrPlusSceneNoise.sample(flat, 128, 128, FloatArray(4), Float.NaN))
        assertNull(HdrPlusSceneNoise.fit(emptyList()))
        val (bytes, black) = frame(1e-3, 0.0, 1)
        // A partial burst estimate is not evidence about the missing frames.
        assertNull(HdrPlusSceneNoise.fit(listOf(HdrPlusSceneNoise.sample(bytes, w, w, black, white), null)))
    }

    @Test fun learnedPriorRefinesTheEstimateAndBadPriorsAreIgnored() {
        val plain = fit(1e-3, 2e-5)!!
        var seen: Triple<Double, Double, Int>? = null
        val refined = fit(1e-3, 2e-5, prior = { a, b, bins -> seen = Triple(a, b, bins); 2e-3 to 5e-5 })!!
        assertEquals(Triple(plain.a, plain.b, plain.bins), seen)
        assertEquals(2e-3, refined.a, 0.0)
        assertEquals(5e-5, refined.b, 0.0)
        assertArrayEquals(plain.levels, refined.levels, 0.0)
        for (bad in listOf<Pair<Double, Double>?>(null, Double.NaN to 0.0, -1.0 to 0.0, 0.0 to 0.0)) {
            val kept = fit(1e-3, 2e-5, prior = { _, _, _ -> bad })!!
            assertEquals(plain.a, kept.a, 0.0)
            assertEquals(plain.b, kept.b, 0.0)
        }
    }

    @Test fun expectedNoisePredictsTheMeterOnStillFrames() {
        val a = 1e-3
        val b = 2e-5
        val model = fit(a, b)!!
        val (f0, black) = frame(a, b, 7)
        val (f1, _) = frame(a, b, 8)
        val range = (white - black.average()).toDouble()
        val observed = HdrPlusMotionMeter.meanAbsDiffNormalized(f0, f1, w * w, normalizationRange = range)
        val s0 = HdrPlusSceneNoise.frameSigma(f0, w, w, black, range, model)
        val s1 = HdrPlusSceneNoise.frameSigma(f1, w, w, black, range, model)
        val expected = (s0 + s1) / sqrt(Math.PI)
        assertEquals(observed, expected, 0.08 * observed)
        assertTrue(HdrPlusAutoSelect.motionShare(observed, expected) < 0.15)
        val cells = HdrPlusSceneNoise.cellSigma(f0, w, w, black, range, model)!!
        val (mw, mh) = HdrPlusMotionMeter.mapCells(w, w)
        assertEquals(mw * mh, cells.size)
        assertTrue(cells.all { it.isFinite() && it > 0.0 })
        // Brighter cells are noisier under a shot-noise model.
        assertTrue(cells[mw - 1] > cells[0])
    }

    @Test fun spatialReferenceOnFlatContentIsTheNoiseItself() {
        val a = 1e-3
        val b = 2e-5
        val model = fit(a, b)!!
        val (f0, black) = frame(a, b, 7)
        val range = (white - black.average()).toDouble()
        val reference = HdrPlusSceneNoise.spatialNoiseReference(f0, w, w, range)
        // |pixel - wide blur| of noise is nearly |noise|: 4 pixels x sigma x sqrt(2/pi).
        val expected = 4 * Math.sqrt(2 / Math.PI) * HdrPlusSceneNoise.frameSigma(f0, w, w, black, range, model)
        assertEquals(expected, reference, 0.1 * expected)
        assertTrue(HdrPlusSceneNoise.spatialNoiseReference(f0, w, w, Double.NaN).isNaN())
        assertTrue(HdrPlusSceneNoise.spatialNoiseReference(f0, 2 * w, w, range).isNaN())
    }
}
