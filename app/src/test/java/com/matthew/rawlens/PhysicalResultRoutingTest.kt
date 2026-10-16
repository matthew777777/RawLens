// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalResultRoutingTest {
    // Routing contract: read sensor keys from the HAL-reported physical
    // camera when it is a known session member, otherwise stay logical.

    @Test
    fun knownPhysicalIdRoutes() {
        assertEquals(
            "2",
            RawFrameMetadataFactory.selectPhysicalResultId("2", setOf("0", "2", "3"))
        )
    }

    @Test
    fun noPhysicalSourceStaysLogical() {
        assertNull(RawFrameMetadataFactory.selectPhysicalResultId(null, setOf("0", "2")))
    }

    @Test
    fun staleOrUnknownIdStaysLogical() {
        assertNull(RawFrameMetadataFactory.selectPhysicalResultId("5", setOf("0", "2")))
        assertNull(RawFrameMetadataFactory.selectPhysicalResultId("2", emptySet()))
    }

    @Test
    fun emptySessionHasNoRouting() {
        assertNull(RawFrameMetadataFactory.selectPhysicalResultId(null, emptySet()))
    }
}
