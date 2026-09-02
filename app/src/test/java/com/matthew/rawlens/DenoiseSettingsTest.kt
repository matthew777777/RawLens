// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DenoiseSettingsTest {
    @Test fun defaultsAreOptInAndFilmGrainBiased() {
        val settings = DenoiseSettings()
        assertEquals(false, settings.enabled)
        assertEquals(1f, settings.chromaStrength)
        assertEquals(0.55f, settings.lumaCleanup)
        assertEquals(0.85f, settings.grainRetention)
        assertEquals(true, settings.filmGrainEnabled)
        assertEquals(0.22f, settings.filmGrainAmount)
    }

    @Test fun cameraRgbNoiseExpandsToBothGreenCfaPlanes() {
        val model = CfaNoiseModel.from(
            ImmutableDoubleValues(doubleArrayOf(1.0, 0.1, 2.0, 0.2, 3.0, 0.3))
        )
        assertArrayEquals(floatArrayOf(1f, 2f, 2f, 3f), model.scale, 0f)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.2f, 0.3f), model.offset, 0f)
    }
}
