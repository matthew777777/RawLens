// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class AfTraceTest {
    @Test fun `trace line names state, mode and lens distance`() {
        // Same shape as the old photo Idle AF line, so field logs stay greppable.
        assertEquals(
            "Idle AF ${Camera2Names.afStateName(2)} mode=${Camera2Names.afModeName(4)} fd=3.25D",
            AfTrace.line("Idle", 4, 2, 3.25f)
        )
        assertEquals(
            "Direct-Log AF ${Camera2Names.afStateName(null)} mode=${Camera2Names.afModeName(null)}",
            AfTrace.line("Direct-Log", null, null, null)
        )
    }
}
