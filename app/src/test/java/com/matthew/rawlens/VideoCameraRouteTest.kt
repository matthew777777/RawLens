// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.OutputConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class VideoCameraRouteTest {
    private val rawCameras = mutableSetOf<CameraCharacteristics>()
    private fun camera(raw: Boolean = true): CameraCharacteristics =
        mock(CameraCharacteristics::class.java).also { if (raw) rawCameras += it }

    // Framework Key constants are null in local Android stubs; inject capabilities
    // while exercising the real route selection and output binding.
    private fun resolve(identity: String, lookup: (String) -> CameraCharacteristics): VideoCameraRoute? =
        resolveVideoCameraRoute(identity, isFront = { false }, hasRaw = { it in rawCameras }, lookup = lookup)

    @Test fun logicalRouteOpensParentAndBindsPhysicalOutput() {
        for (identity in listOf("6/5", "6-5")) {
            val parent = camera(false)
            val sensor = camera()
            val queried = mutableListOf<String>()
            val route = requireNotNull(resolve(identity) { id ->
                queried += id
                when (id) {
                    "6" -> parent
                    "5" -> sensor
                    else -> throw IllegalArgumentException("unknown device $id")
                }
            })
            assertEquals("6", route.openCameraId)
            assertEquals("5", route.physicalCameraId)
            assertSame(sensor, route.characteristics)
            assertTrue(queried.contains("5"))
            val output = mock(OutputConfiguration::class.java)
            assertSame(output, route.bind(output))
            verify(output).setPhysicalCameraId("5")
        }
    }

    @Test fun directRouteKeepsDeviceAndDoesNotBindPhysicalOutput() {
        val sensor = camera()
        val route = requireNotNull(resolve("5") { sensor })
        assertEquals("5", route.openCameraId)
        assertNull(route.physicalCameraId)
        assertSame(sensor, route.characteristics)
        val output = mock(OutputConfiguration::class.java)
        route.bind(output)
        verifyNoInteractions(output)
    }

    @Test fun vendorCompositeCharacteristicsTakePrecedenceOverSuffix() {
        val parent = camera(false)
        val composite = camera()
        val route = requireNotNull(resolve("6/5") { id ->
            when (id) { "6" -> parent; "6/5" -> composite; else -> error("unexpected $id") }
        })
        assertSame(composite, route.characteristics)
        assertEquals("6", route.openCameraId)
        assertEquals("5", route.physicalCameraId)
    }

    @Test fun unavailableRouteReturnsNullInsteadOfThrowingOnUiThread() {
        assertNull(resolve("6/5") { throw IllegalArgumentException("unknown device") })
    }

    @Test fun logicalCharacteristicsCannotSubstituteForMissingPhysicalSensor() {
        val parent = camera()
        assertNull(resolve("6/5") { id ->
            if (id == "6") parent else throw IllegalArgumentException("unknown device")
        })
    }

    @Test fun nativeHyphenatedDeviceIdStillWorksWhenNoParentResolves() {
        val sensor = camera()
        val route = requireNotNull(resolve("rear-tele") { id ->
            if (id == "rear-tele") sensor else throw IllegalArgumentException("unknown device")
        })
        assertEquals("rear-tele", route.openCameraId)
        assertNull(route.physicalCameraId)
    }
}
