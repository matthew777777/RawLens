// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Step-5 EV parity: the BGU viewfinder meters with the save's own dense
 * [AdaptiveDevelopmentExposure.analyzeRaw] (same population, lens model,
 * rule, tuning), so VF EV == save EV by construction. This pins the wiring
 * contract on synthetic lensed Bayer: the VF-shaped dense call must agree
 * with the legacy coarse estimator (same rule + tuning, sparser population)
 * within the 0.15 EV gate, and the VF preview tuning must equal the save
 * tuning derived from the same [JpegOutputSettings].
 */
class BguVfEvParityTest {
    private fun bayerFrame(size: Int, black: Float, white: Float): ByteBuffer {
        // Smooth scene: mid-gray gradient + dark corner + one hot spike
        // (exercises the log-average, the shadow floor, and both guards).
        val buf = ByteBuffer.allocate(size * size * 2).order(ByteOrder.nativeOrder())
        for (y in 0 until size) {
            for (x in 0 until size) {
                var v = 0.10f + 0.25f * (x.toFloat() / size)
                if (x < size / 8 && y < size / 8) v = 0.004f
                if (x == size - 1 && y == 0) v = 0.95f
                val code = (black + v * (white - black)).toInt().coerceIn(0, 65535)
                buf.putShort((y * size + x) * 2, code.toShort())
            }
        }
        return buf
    }

    private fun lensModel(size: Int): LensShadingModel {
        // 2x2 interleaved map, unity center-ish, 1.3x far corner.
        val gains = FloatArray(2 * 2 * 4) { 1f }
        for (ch in 0..3) gains[3 * 4 + ch] = 1.3f
        return LensShadingModel(2, 2, gains, IntRectSnapshot(0, 0, size, size), false)
    }

    @Test fun denseVfMeterAgreesWithLegacyEstimator() {
        val size = 256
        val black = 64f
        val white = 1023f
        val buf = bayerFrame(size, black, white)
        val tuning = AdaptiveExposureTuning(
            highlightHeadroom = 1.0,
            highlightSoftHeadroom = 0.85
        ).bounded()
        val model = lensModel(size)
        // VF-shaped call: full-frame dense analyzeRaw with the lens model.
        val vf = AdaptiveDevelopmentExposure.analyzeRaw(
            buf,
            RawPlaneLayout(size, size, size * 2, 2),
            RawNormalization(BayerPattern.RGGB, List(4) { black }, white),
            RawCrop(0, 0, size, size),
            model, AdaptiveDevelopmentExposure.workspace(), tuning
        ).correctionEv
        // Legacy-shaped call: coarse quad grid, same rule + tuning + lens.
        val legacy = VfGpuImport.estimatePreviewCorrectionEv(
            buf, size * 2, 2, 0, 0, size / 2, size / 2, 2,
            FloatArray(4) { black }, white,
            FloatArray(VfGpuImport.MAX_PREVIEW_SAMPLES),
            lens = model, pattern = BayerPattern.RGGB, sensorOriginX = 0, sensorOriginY = 0,
            tuning = tuning
        )
        assertTrue("vf=$vf legacy=$legacy", vf.isFinite() && legacy.isFinite())
        assertTrue("VF/save meter gap ${kotlin.math.abs(vf - legacy)} over gate", kotlin.math.abs(vf - legacy) < 0.15)
    }

    @Test fun previewTuningEqualsSaveTuning() {
        val settings = JpegOutputSettings(
            highlightHeadroom = 1.2f,
            highlightSoftHeadroom = 0.9f
        )
        val r = settings.resolvedForPlatform()
        // What BguViewfinder.setAgx stores for the VF meter (spike direct,
        // broad guard re-solved under these AgX settings, shoulder carried
        // over — mirroring the current setAgx body)...
        val preview = AdaptiveExposureTuning(
            highlightHeadroom = r.highlightHeadroom.toDouble(),
            highlightSoftHeadroom = AdaptiveExposureTuning.fromOutputSettings(settings).highlightSoftHeadroom,
            highlightShoulder = r.highlightShoulder
        ).bounded()
        // ...must equal what the save path derives for its meter, and the
        // non-default soft slider must survive the round trip at defaults.
        assertEquals(AdaptiveExposureTuning.fromOutputSettings(settings), preview)
        assertEquals(1.2, preview.highlightHeadroom, 1e-6)
        assertEquals(0.9, preview.highlightSoftHeadroom, 1e-4)
        assertEquals(1f, preview.highlightShoulder, 0f)
    }
}
