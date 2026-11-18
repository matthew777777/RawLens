package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class AfScanProbeVerdictTest {
    private fun e(scanned: Boolean, change: Double, jitter: Double = 0.01, moving: Boolean = false, span: Float = 0f) =
        AfProbeEvidence(10f, scanned, change, jitter, span, moving)

    @Test fun fixedFocus() = assertEquals(AfVerdict.FIXED_FOCUS, afVerdictOf(AfProbeEvidence(0f, true, 1.0, 0.0, 1f, true)))
    @Test fun working() = assertEquals(AfVerdict.WORKING, afVerdictOf(e(true, 0.3)))
    @Test fun logicalOnlyWhenPictureStill() = assertEquals(AfVerdict.LOGICAL_ONLY, afVerdictOf(e(true, 0.01, moving = true, span = 10f)))
    @Test fun physicalOnly() = assertEquals(AfVerdict.PHYSICAL_ONLY, afVerdictOf(e(false, 0.3)))
    @Test fun dead() = assertEquals(AfVerdict.DEAD, afVerdictOf(e(false, 0.01)))
    @Test fun noisySceneFallsBackToMetadata() =
        assertEquals(AfVerdict.WORKING, afVerdictOf(e(true, 0.3, jitter = 0.2, moving = true, span = 5f)))
    @Test fun noisySceneWithoutMetadataIsUnknown() =
        assertEquals(AfVerdict.UNKNOWN, afVerdictOf(e(false, 0.3, jitter = 0.2)))
}
