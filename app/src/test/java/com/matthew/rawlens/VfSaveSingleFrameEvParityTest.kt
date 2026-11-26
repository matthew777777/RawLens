// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * WYSIWYG: the RAW viewfinder's preview EV ([VfGpuImport.estimatePreviewCorrectionEv])
 * must follow the still develop's single-frame rules ([AdaptiveDevelopmentExposure.analyzeRaw]
 * with singleFrame = true): clipped photosites leave the highlight guards, and the frame's
 * own noise fit gates night scenes. Before the fix the VF ignored both, so it metered
 * darker than the photo whenever lamps/sky clipped and differently at night.
 */
class VfSaveSingleFrameEvParityTest {
    private val size = 512
    private val black = 64f
    private val white = 1023f

    /** Dim mids + [clipFraction] of fully clipped photosites, Poisson-Gaussian noise of [sigmaRead]. */
    private fun frame(mid: Float, clipFraction: Float, gain: Float, sigmaRead: Float, seed: Long = 7): ByteBuffer {
        val rng = Random(seed)
        val buf = ByteBuffer.allocate(size * size * 2).order(ByteOrder.nativeOrder())
        for (y in 0 until size) for (x in 0 until size) {
            val clipped = ((x / 16) * 31 + (y / 16) * 17) % 1000 < clipFraction * 1000
            val base = if (clipped) 1.2f else mid * (0.6f + 0.8f * x / size)
            val sigma = sqrt(gain * base.coerceAtMost(1f) + sigmaRead * sigmaRead)
            val v = (base + sigma * rng.nextGaussian().toFloat()).coerceIn(0f, 1f)
            buf.putShort((y * size + x) * 2, (black + v * (white - black)).toInt().toShort())
        }
        return buf
    }

    private fun save(buf: ByteBuffer, tuning: AdaptiveExposureTuning) = AdaptiveDevelopmentExposure.analyzeRaw(
        buf, RawPlaneLayout(size, size, size * 2, 2),
        RawNormalization(BayerPattern.RGGB, List(4) { black }, white),
        RawCrop(0, 0, size, size), null, AdaptiveDevelopmentExposure.workspace(), tuning,
        singleFrame = true
    ).correctionEv

    private fun vf(buf: ByteBuffer, tuning: AdaptiveExposureTuning, singleFrame: Boolean = true) =
        VfGpuImport.estimatePreviewCorrectionEv(
            buf, size * 2, 2, 0, 0, size / 2, size / 2, 2,
            FloatArray(4) { black }, white, FloatArray(VfGpuImport.MAX_PREVIEW_SAMPLES),
            tuning = tuning, singleFrame = singleFrame
        )

    private val tuning = AdaptiveExposureTuning().bounded()

    @Test fun clippedLampsDoNotHoldTheViewfinderDarkerThanThePhoto() {
        val buf = frame(mid = 0.05f, clipFraction = 0.03f, gain = 1e-4f, sigmaRead = 1e-3f)
        val photo = save(buf, tuning)
        val fixed = vf(buf, tuning)
        val legacy = vf(buf, tuning, singleFrame = false)
        assertTrue("legacy VF should show the bug: legacy=$legacy photo=$photo", photo - legacy > 0.3)
        assertTrue("VF $fixed vs photo $photo", abs(fixed - photo) < 0.15)
    }

    @Test fun noisyNightFollowsThePhotosLowKeyGate() {
        val buf = frame(mid = 0.012f, clipFraction = 0.02f, gain = 2e-3f, sigmaRead = 6e-3f, seed = 11)
        val photo = save(buf, tuning)
        val fixed = vf(buf, tuning)
        assertTrue("VF $fixed vs photo $photo", abs(fixed - photo) < 0.15)
    }

    @Test fun cleanDaySceneUnchanged() {
        val buf = frame(mid = 0.12f, clipFraction = 0f, gain = 1e-5f, sigmaRead = 5e-4f, seed = 3)
        val photo = save(buf, tuning)
        assertTrue(abs(vf(buf, tuning) - photo) < 0.15)
        assertTrue(abs(vf(buf, tuning) - vf(buf, tuning, singleFrame = false)) < 0.05)
    }
}
