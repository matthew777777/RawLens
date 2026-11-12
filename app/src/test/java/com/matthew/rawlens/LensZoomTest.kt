// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class LensZoomTest {
    private fun metric(equivalentMm: Float) = equivalentMm / 36f

    @Test fun mainLensIsOneTimesAndOthersAreRelativeToIt() {
        val rear = listOf(metric(14f), metric(23f), metric(70f))
        assertEquals("0.6×", LensZoom.label(metric(14f), rear))
        assertEquals("1×", LensZoom.label(metric(23f), rear))
        assertEquals("3×", LensZoom.label(metric(70f), rear))
    }

    @Test fun ultraWideAloneIsNotPromotedToOneTimes() {
        val rear = listOf(metric(14f), metric(14f), metric(14f))
        assertEquals("0.6×", LensZoom.label(metric(14f), rear))
    }

    @Test fun ultraWideAndTeleWithoutMainUseNominalMain() {
        val rear = listOf(metric(14f), metric(120f))
        assertEquals("0.6×", LensZoom.label(metric(14f), rear))
        assertEquals("5×", LensZoom.label(metric(120f), rear))
    }

    @Test fun unknownOpticsAreMarkedUnlessTheOnlyLens() {
        assertEquals("?", LensZoom.label(Float.MAX_VALUE, listOf(Float.MAX_VALUE, metric(24f))))
        assertEquals("1×", LensZoom.label(Float.MAX_VALUE, listOf(Float.MAX_VALUE)))
    }
}
