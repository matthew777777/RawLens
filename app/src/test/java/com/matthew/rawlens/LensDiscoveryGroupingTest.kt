// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensDiscoveryGroupingTest {
    private fun lens(id: String, kind: LensRouteKind, metric: Float = 1f) = DiscoveredLens(
        id = id,
        label = "Camera $id",
        opticalMetric = metric,
        kind = kind,
        role = LensRole.WIDE,
        title = "Wide",
        details = "details",
        route = kind.name
    )

    @Test
    fun groupsAreOrderedDirectThenLogicalThenVendorThenUnavailable() {
        val lenses = listOf(
            lens("3/2", LensRouteKind.VENDOR_COMPOSITE, 1f),
            lens("0/1", LensRouteKind.LOGICAL_PHYSICAL, 2f).copy(logicalId = "0", physicalId = "1"),
            lens("0", LensRouteKind.STANDALONE, 3f),
            unavailableLens("9")
        )
        val groups = groupLenses(lenses)
        assertEquals(4, groups.size)
        assertEquals(LensRouteKind.STANDALONE, groups[0].kind)
        assertEquals(LensRouteKind.LOGICAL_PHYSICAL, groups[1].kind)
        assertEquals(LensRouteKind.VENDOR_COMPOSITE, groups[2].kind)
        assertEquals(LensRouteKind.UNAVAILABLE, groups[3].kind)
    }

    @Test
    fun logicalRoutesAreSplitPerParent() {
        val lenses = listOf(
            lens("0/1", LensRouteKind.LOGICAL_PHYSICAL, 1f).copy(logicalId = "0", physicalId = "1"),
            lens("1/2", LensRouteKind.LOGICAL_PHYSICAL, 2f).copy(logicalId = "1", physicalId = "2")
        )
        val groups = groupLenses(lenses)
        assertEquals(2, groups.size)
        assertTrue(groups[0].title.contains("Logical 0"))
        assertTrue(groups[1].title.contains("Logical 1"))
    }

    @Test
    fun roleThresholds() {
        assertEquals(LensRole.ULTRA_WIDE, LensRole.forEquivalentMm(14f))
        assertEquals(LensRole.WIDE, LensRole.forEquivalentMm(24f))
        assertEquals(LensRole.NORMAL, LensRole.forEquivalentMm(50f))
        assertEquals(LensRole.TELE, LensRole.forEquivalentMm(70f))
        assertEquals(LensRole.SUPER_TELE, LensRole.forEquivalentMm(120f))
        assertEquals(LensRole.UNKNOWN, LensRole.forEquivalentMm(null))
    }
}
