// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GcsvLogTest {
    @Test fun emptyGyroGivesNoLog() {
        assertNull(GcsvLog().render("m", "v"))
    }

    @Test fun matchesDecoderLayout() {
        val log = GcsvLog()
        log.rollingShutterSkewNs = 16_500_000
        log.addGyro(longArrayOf(-1000, 0, 2_000_000), floatArrayOf(9f, 9f, 9f, 0.1f, -0.2f, 0.3f, 1f, 2f, 3f), 3)
        log.addAccel(longArrayOf(0, 4_000_000), floatArrayOf(0f, 0f, 9.80665f, 0f, 0f, 0f), 2)
        val lines = log.render("Model", "Vendor")!!.trimEnd().lines()
        assertEquals("GYROFLOW IMU LOG", lines[0])
        assertEquals("frame_readout_time,16.500", lines[6])
        assertEquals("t,gx,gy,gz,ax,ay,az", lines.first { it.startsWith("t,") })
        assertEquals("0,0.100000,-0.200000,0.300000,0.00000,0.00000,1.00000", lines[lines.size - 2])
        assertEquals("2000,1.000000,2.000000,3.000000,0.00000,0.00000,0.50000", lines.last())
    }
}
