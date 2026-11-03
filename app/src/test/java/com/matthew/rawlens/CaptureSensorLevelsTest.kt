package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CaptureSensorLevelsTest {
    @Test fun vendorModeLevelsPreserveHighlightHeadroomThroughUnpacking() {
        val overrides = DngMetadataOverrides(blackLevels = List(4) { 63.0 }, whiteLevel = 16383.0)
        val (black, white) = overrides.captureSensorLevels(FloatArray(4) { 64f }, 1023f)
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        listOf(63, 8223, 15408, 16383).forEach { bytes.putShort(it.toShort()) }
        bytes.rewind()
        val before = bytes.array().copyOf()
        val decoded = RawSensorUnpacker.unpackNormalized(bytes, RawPlaneLayout(2, 2, 4, 2),
            RawNormalization(BayerPattern.RGGB, black!!.toList(), white!!))
        assertArrayEquals(floatArrayOf(0f, .5f, (15408f - 63f) / 16320f, 1f), decoded.values, 1e-6f)
        assertArrayEquals(before, bytes.array())
        assertEquals(1023f, DngMetadataOverrides().captureSensorLevels(black, 1023f).second!!, 0f)
    }

    @Test fun partialOverridesPreserveTheOtherHalLevelAndPhaseOrder() {
        val hal = floatArrayOf(64f, 65f, 66f, 67f)
        val whiteOnly = DngMetadataOverrides(whiteLevel = 16383.0).captureSensorLevels(hal, 1023f)
        assertArrayEquals(hal, whiteOnly.first, 0f)
        val blackOnly = DngMetadataOverrides(blackLevels = listOf(60.0, 61.0, 62.0, 63.0))
            .captureSensorLevels(hal, 1023f)
        assertArrayEquals(floatArrayOf(60f, 61f, 62f, 63f), blackOnly.first, 0f)
        assertEquals(1023f, blackOnly.second!!, 0f)
    }

    @Test fun noOverridePreservesMissingLevelsForExistingRepair() {
        val selected = DngMetadataOverrides().captureSensorLevels(null, null)
        assertNull(selected.first)
        assertNull(selected.second)
    }
}
