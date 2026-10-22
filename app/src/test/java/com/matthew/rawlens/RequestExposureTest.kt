// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestExposureTest {
    private fun inputs(
        selectedIso: Int? = null, selectedExposure: Long? = null, still: Boolean = false,
        programActive: Boolean = false, dynamic: Boolean = false, zsl: Boolean = false,
        pIso: Int? = null, pShutter: Long? = null, tIso: Int? = null, tShutter: Long? = null,
        ettr: Boolean = false, eIso: Int? = null, eShutter: Long? = null, manualSensor: Boolean = true,
    ) = RequestExposure.Inputs(selectedIso, selectedExposure, still, programActive, dynamic, zsl,
        pIso, pShutter, tIso, tShutter, ettr, eIso, eShutter, 100, 10_000_000L,
        50..6400, 100_000L..1_000_000_000L, manualSensor)

    @Test fun hardwareAeByDefault() = assertNull(RequestExposure.resolve(inputs()))

    @Test fun manualIsoWinsAndIsClamped() {
        assertEquals(SensorExposure(6400, 10_000_000L),
            RequestExposure.resolve(inputs(selectedIso = 99_999)))
        // ISO-only manual: the shutter is the last metered one, never a
        // left-over PROGRAM pair (R-A3-1).
        assertEquals(SensorExposure(6400, 10_000_000L),
            RequestExposure.resolve(inputs(selectedIso = 99_999, pIso = 800, pShutter = 1L)))
    }

    @Test fun programLivePairOnRepeating() {
        assertEquals(SensorExposure(800, 20_000_000L), RequestExposure.resolve(inputs(
            programActive = true, dynamic = true, pIso = 800, pShutter = 20_000_000L)))
    }

    @Test fun zslKeepsHardwareAe() = assertNull(RequestExposure.resolve(inputs(
        programActive = true, dynamic = true, zsl = true, pIso = 800, pShutter = 20_000_000L)))

    @Test fun ettrPairOnStillUnlessGliding() {
        val still = inputs(still = true, ettr = true, eIso = 200, eShutter = 30_000_000L)
        assertEquals(SensorExposure(200, 30_000_000L), RequestExposure.resolve(still))
        val gliding = inputs(still = true, ettr = true, eIso = 200, eShutter = 30_000_000L,
            dynamic = true, pIso = 400, pShutter = 10_000_000L, tIso = 800, tShutter = 10_000_000L)
        assertEquals(SensorExposure(400, 10_000_000L), RequestExposure.resolve(gliding))
    }

    @Test fun noManualSensorMeansHardwareAe() =
        assertNull(RequestExposure.resolve(inputs(selectedIso = 400, manualSensor = false)))
}
