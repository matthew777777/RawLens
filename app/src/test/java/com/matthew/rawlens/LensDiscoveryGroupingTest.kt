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

    private fun opticLens(
        id: String,
        kind: LensRouteKind,
        focal: Float? = 6.06f,
        equivalent: Float? = 24f,
        resolution: String? = "4080×3060",
        megapixels: Float? = 12.5f
    ) = DiscoveredLens(
        id = id,
        label = "Camera $id",
        opticalMetric = 1f,
        kind = kind,
        role = LensRole.WIDE,
        title = "Wide · ≈24 mm eq",
        details = "details",
        route = kind.name,
        focalMm = focal,
        equivalentMm = equivalent,
        resolutionLabel = resolution,
        megapixels = megapixels
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
    fun sameOpticAcrossRoutesCollapsesIntoOneGroup() {
        val lenses = listOf(
            opticLens("0", LensRouteKind.STANDALONE),
            opticLens("3/0", LensRouteKind.LOGICAL_PHYSICAL),
            opticLens("3-0", LensRouteKind.VENDOR_COMPOSITE)
        )
        val groups = groupByOptic(lenses)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].lenses.size)
        assertEquals("0", groups[0].recommended.id)
        assertEquals(listOf("3/0", "3-0"), groups[0].alternates.map { it.id })
    }

    @Test
    fun distinctOpticsStaySeparate() {
        val lenses = listOf(
            opticLens("0", LensRouteKind.STANDALONE),
            opticLens("2", LensRouteKind.STANDALONE, focal = 1.67f, equivalent = 16f)
        )
        assertEquals(2, groupByOptic(lenses).size)
    }

    @Test
    fun lensesWithoutOpticalDataNeverMerge() {
        val lenses = listOf(
            lens("7", LensRouteKind.STANDALONE),
            lens("8", LensRouteKind.STANDALONE)
        )
        assertEquals(2, groupByOptic(lenses).size)
    }

    @Test
    fun recommendedPrefersDirectOverLogicalOverVendor() {
        val lenses = listOf(
            opticLens("3-0", LensRouteKind.VENDOR_COMPOSITE),
            opticLens("3/0", LensRouteKind.LOGICAL_PHYSICAL),
            opticLens("0", LensRouteKind.STANDALONE)
        )
        assertEquals("0", recommendRoute(lenses).id)
        assertEquals(setOf("0"), recommendedLensIds(lenses))
    }

    @Test
    fun recommendedIdsSkipUnavailable() {
        val lenses = listOf(
            opticLens("0", LensRouteKind.STANDALONE),
            unavailableLens("9")
        )
        assertEquals(setOf("0"), recommendedLensIds(lenses))
        val groups = groupByOptic(lenses)
        assertEquals(2, groups.size)
        assertEquals("unavailable", groups.last().key)
    }

    @Test
    fun capabilityBadgesReportProbedExtras() {
        val plain = opticLens("0", LensRouteKind.STANDALONE)
        assertTrue(capabilityBadges(plain).isEmpty())
        val rich = plain.copy(
            isoRange = "50–12800",
            maxExposureMs = 30_000L,
            sensorModeSwitch = true,
            rawSizes = listOf("8160×6144", "4080×3072"),
            packedFormats = listOf("RAW12", "RAW14")
        )
        val badges = capabilityBadges(rich)
        assertTrue(badges.any { it.startsWith("ISO") })
        assertTrue(badges.any { it.startsWith("Exp") })
        assertTrue(badges.contains("Sensor-mode switch"))
        assertTrue(badges.contains("2 RAW sizes"))
        assertTrue(badges.contains("Lists RAW12+RAW14"))
    }

    @Test
    fun switcherLabelsGainIdSuffixOnlyOnTies() {
        val tied = disambiguatedLensLabels(
            listOf(
                LensOption("0", "1×", selected = true),
                LensOption("3/0", "1×", selected = false)
            )
        )
        assertEquals("1× · 0", tied[0].label)
        assertEquals("1× · 3/0", tied[1].label)
        assertTrue(tied[0].selected)
        val unique = disambiguatedLensLabels(
            listOf(
                LensOption("0", "1×", selected = true),
                LensOption("2", "0.5×", selected = false)
            )
        )
        assertEquals("1×", unique[0].label)
        assertEquals("0.5×", unique[1].label)
    }

    @Test
    fun routeVerdictMatchesExactKeysOnly() {
        val verdicts = LensVerdicts(openable = setOf("0", "3/0"), dead = setOf("5"))
        assertEquals(LensVerdict.OPENABLE, routeVerdict("0", verdicts))
        assertEquals(LensVerdict.OPENABLE, routeVerdict("3/0", verdicts))
        assertEquals(LensVerdict.DEAD, routeVerdict("5", verdicts))
        assertEquals(LensVerdict.UNKNOWN, routeVerdict("7", verdicts))
        // No prefix inference: an openable logical camera proves nothing
        // about its composites, and a dead one condemns nothing either.
        assertEquals(LensVerdict.UNKNOWN, routeVerdict("3/1", verdicts))
        assertEquals(LensVerdict.UNKNOWN, routeVerdict("5/1", verdicts))
    }

    @Test
    fun applyVerdictAnnotatesDetailsOnce() {
        val plain = opticLens("0", LensRouteKind.STANDALONE)
        val noted = applyVerdict(plain, LensVerdict.OPENABLE)
        assertEquals(LensVerdict.OPENABLE, noted.verdict)
        assertTrue("Opened OK before" in noted.details)
        assertEquals(noted.details, applyVerdict(noted, LensVerdict.OPENABLE).details)
        assertEquals(plain.details, applyVerdict(plain, LensVerdict.UNKNOWN).details)
    }

    @Test
    fun applyVerdictLeavesUnavailablePlaceholdersAlone() {
        val missing = unavailableLens("9")
        val noted = applyVerdict(missing, LensVerdict.DEAD)
        assertEquals(LensVerdict.DEAD, noted.verdict)
        assertEquals(missing.details, noted.details)
    }

    @Test
    fun recommendationPrefersProvenRoutesAndDemotesDead() {
        val lenses = listOf(
            opticLens("0", LensRouteKind.STANDALONE).copy(verdict = LensVerdict.DEAD),
            opticLens("3/0", LensRouteKind.LOGICAL_PHYSICAL).copy(verdict = LensVerdict.OPENABLE)
        )
        assertEquals("3/0", recommendRoute(lenses).id)
        assertEquals(setOf("3/0"), recommendedLensIds(lenses))
    }

    @Test
    fun streamVerifiedBeatsEveryOtherTier() {
        val verdicts = LensVerdicts(
            openable = setOf("3/2", "0"),
            dead = setOf("3/2"),
            verified = setOf("3/2")
        )
        assertEquals(LensVerdict.VERIFIED, routeVerdict("3/2", verdicts))
        assertEquals(LensVerdict.OPENABLE, routeVerdict("0", verdicts))
        val lenses = listOf(
            opticLens("0", LensRouteKind.STANDALONE).copy(verdict = LensVerdict.OPENABLE),
            opticLens("3-0", LensRouteKind.VENDOR_COMPOSITE).copy(verdict = LensVerdict.VERIFIED)
        )
        assertEquals("3-0", recommendRoute(lenses).id)
        val noted = applyVerdict(opticLens("3-0", LensRouteKind.VENDOR_COMPOSITE), LensVerdict.VERIFIED)
        assertTrue("RAW verified on this device" in noted.details)
    }

    @Test
    fun lensReportNamesEveryRoute() {
        val lenses = listOf(
            opticLens("0", LensRouteKind.STANDALONE),
            opticLens("3/0", LensRouteKind.LOGICAL_PHYSICAL)
        )
        val report = lensReport(lenses)
        assertTrue(report.contains("ID 0"))
        assertTrue(report.contains("ID 3/0"))
    }

    @Test
    fun lensReportEnumeratesAllRawSizes() {
        val lenses = listOf(
            opticLens("0", LensRouteKind.STANDALONE).copy(
                rawSizes = listOf("8160×6144", "4080×3072")
            )
        )
        val report = lensReport(lenses)
        assertTrue(report.contains("sizes: 8160×6144, 4080×3072"))
    }

    @Test
    fun lensReportMarksChosenSize() {
        val lenses = listOf(opticLens("0", LensRouteKind.STANDALONE))
        // Named argument: with two function-type parameters a trailing
        // lambda would bind to chosenFormat instead.
        val report = lensReport(lenses, chosenSize = { "4080x3072" })
        assertTrue(report.contains("chosen 4080x3072"))
        assertTrue(!lensReport(lenses).contains("chosen"))
    }

    @Test
    fun lensReportMarksChosenFormat() {
        val lenses = listOf(opticLens("0", LensRouteKind.STANDALONE))
        val report = lensReport(lenses, chosenFormat = { "RAW12" })
        assertTrue(report.contains("format RAW12"))
        assertTrue(!lensReport(lenses).contains("format"))
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
