// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class GyroCsvWriterTest {
    @Test fun `header plus rows match desktop contract exactly`() {
        val text = GyroCsvWriter.format(
            listOf(
                GyroSample(1_000L, 0.2f, 0.1f, -0.3f),
                GyroSample(1_050L, 0.4f, 0.5f, 0.6f)
            )
        )
        assertEquals(
            "timestamp_ns,x_rad_s,y_rad_s,z_rad_s\n" +
                "1000,0.2,0.1,-0.3\n" +
                "1050,0.4,0.5,0.6\n",
            text
        )
    }

    @Test fun `empty window is header only (caller skips the file)`() {
        assertEquals(
            "timestamp_ns,x_rad_s,y_rad_s,z_rad_s\n",
            GyroCsvWriter.format(emptyList())
        )
    }
}
