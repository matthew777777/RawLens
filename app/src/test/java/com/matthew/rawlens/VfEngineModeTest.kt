// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class VfEngineModeTest {
    @Test fun `overlay tap cycles AUTO GPU CPU`() {
        assertEquals(VfEngineMode.GPU, VfEngineMode.AUTO.next())
        assertEquals(VfEngineMode.CPU, VfEngineMode.GPU.next())
        assertEquals(VfEngineMode.AUTO, VfEngineMode.CPU.next())
    }

    @Test fun `short labels keep path and override distinguishable`() {
        assertEquals("AUTO", VfEngineMode.AUTO.shortLabel())
        assertEquals("GPU!", VfEngineMode.GPU.shortLabel())
        assertEquals("CPU!", VfEngineMode.CPU.shortLabel())
    }

    @Test fun `missing preference keeps AUTO`() {
        assertEquals(VfEngineMode.AUTO, VfEngineMode.fromPreference(null))
        assertEquals(VfEngineMode.AUTO, VfEngineMode.fromPreference("UNKNOWN"))
        assertEquals(VfEngineMode.GPU, VfEngineMode.fromPreference("GPU"))
        assertEquals(VfEngineMode.CPU, VfEngineMode.fromPreference("CPU"))
    }
}
