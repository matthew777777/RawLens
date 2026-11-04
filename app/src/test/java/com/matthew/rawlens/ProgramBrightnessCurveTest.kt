// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class ProgramBrightnessCurveTest {
    @Test fun customTargetsAreStoredAndUsedByAdaptiveCurves() {
        val profile = ProgramAeProfile(darkTarget = .03, dimTarget = .08, normalTarget = .20, brightTarget = .25)
        assertEquals(profile, ProgramAeProfile.fromJson(profile.toJson()))
        for (mode in listOf(ProgramBrightnessTuning.NATURAL, ProgramBrightnessTuning.HIGHLIGHTS)) {
            val dark = ProgramBrightnessCurve.evaluate(mode, .001, 100, 10_000_000, profile)
            assertEquals(.03, dark.target, 1e-12)
            assertEquals(.03 / .16, dark.constraintScale, 1e-12)
            assertEquals(.25, ProgramBrightnessCurve.evaluate(mode, 1.0, 100, 1_000_000, profile).target, 1e-12)
        }
        assertEquals(.16, ProgramBrightnessCurve.evaluate(ProgramBrightnessTuning.FIXED, .001, 100, 10_000_000, profile).target, 0.0)
    }
    @Test fun unchangedSceneKeepsTargetWhenExposureChanges() {
        val a = ProgramBrightnessCurve.evaluate(ProgramBrightnessTuning.NATURAL, .01, 100, 10_000_000)
        val b = ProgramBrightnessCurve.evaluate(ProgramBrightnessTuning.NATURAL, .08, 400, 20_000_000)
        assertEquals(a.target, b.target, 1e-12)
        assertEquals(a.constraintScale, b.constraintScale, 1e-12)
    }
    @Test fun darkScenesHaveLowerTargetsAndConstraints() {
        val dark = ProgramBrightnessCurve.evaluate(ProgramBrightnessTuning.NATURAL, .001, 100, 10_000_000)
        val bright = ProgramBrightnessCurve.evaluate(ProgramBrightnessTuning.NATURAL, .16, 100, 10_000_000)
        assertTrue(dark.target < bright.target)
        assertTrue(dark.constraintScale < bright.constraintScale)
    }
    @Test fun fixedAndInvalidExposureUseLegacyTarget() {
        assertEquals(.16, ProgramBrightnessCurve.evaluate(ProgramBrightnessTuning.FIXED, .001, 3200, 100_000_000).target, 0.0)
        assertEquals(.16, ProgramBrightnessCurve.evaluate(ProgramBrightnessTuning.NATURAL, Double.NaN, 0, 0).target, 0.0)
    }
    @Test fun tuningSurvivesProfileStorageAndMissingTuningUsesNatural() {
        for (mode in ProgramBrightnessTuning.values()) {
            val p = ProgramAeProfile(brightnessTuning = mode)
            assertEquals(p, ProgramAeProfile.fromJson(p.toJson()))
        }
        assertEquals(ProgramBrightnessTuning.NATURAL, ProgramAeProfile.fromJson("{}").brightnessTuning)
    }
}
