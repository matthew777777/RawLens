// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StillAwbRepairTest {
    private val converged = StillAwbRepair.Converged(
        "4", null, doubleArrayOf(0.4893, 1.0, 0.7090), floatArrayOf(2.04f, 1f, 1f, 1.41f), 1_000_000_000L)

    private fun replace(state: Int?, ts: Long? = 1_500_000_000L, camera: String = "4", mode: Int? = 1) =
        StillAwbRepair.replacementFor(camera, null, mode, state, ts, converged)

    @Test fun searchingStillUsesConvergedPreview() = assertNotNull(replace(state = 1))
    @Test fun convergedStillKeepsItsOwn() = assertNull(replace(state = 2))
    @Test fun lockedStillKeepsItsOwn() = assertNull(replace(state = 3))
    @Test fun manualWbKeepsItsOwn() = assertNull(replace(state = 1, mode = 0))
    @Test fun otherCameraIgnored() = assertNull(replace(state = 1, camera = "0"))
    @Test fun stalePreviewIgnored() = assertNull(replace(state = 1, ts = 5_000_000_000L))
    @Test fun missingTimestampIgnored() = assertNull(replace(state = 1, ts = null))
}
