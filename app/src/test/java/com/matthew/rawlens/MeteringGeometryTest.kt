// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class MeteringGeometryTest {
    private val active = SensorActiveArray(100, 200, 4100, 3200)
    private val pre = SensorActiveArray(20, 40, 4220, 3240)

    @Test fun distortionModeSelectsTheAdvertisedCoordinateSpace() {
        assertEquals(pre, MeteringGeometry.activeArray(active, pre, true, true))
        assertEquals(active, MeteringGeometry.activeArray(active, pre, true, false))
        assertEquals(active, MeteringGeometry.activeArray(active, pre, false, true))
        assertEquals(active, MeteringGeometry.activeArray(active, null, true, true))
    }

    @Test fun processedPreviewCropsWithinOffsetSensorBounds() {
        assertEquals(SensorActiveArray(100, 575, 4100, 2825), MeteringGeometry.streamCrop(active, 1920, 1080))
        assertEquals(SensorActiveArray(600, 200, 3600, 3200), MeteringGeometry.streamCrop(active, 1000, 1000))
        assertEquals(active, MeteringGeometry.streamCrop(active, 4000, 3000))
    }

    @Test fun tapsAndFaceBoxesAreInverseInsideTheDisplayedCrop() {
        val crop = SensorActiveArray(1000, 750, 3000, 2250)
        for (rotation in listOf(0, 90, 180, 270)) for (mirrored in listOf(false, true)) {
            val region = MeteringGeometry.tap(.25f, .75f, crop, rotation, mirrored)
            val box = FacePriority.mapToView(HalFace(region.left, region.top, region.right, region.bottom, 100),
                crop, rotation, mirrored)
            assertEquals(.25f, (box.left + box.right) / 2, .001f)
            assertEquals(.75f, (box.top + box.bottom) / 2, .001f)
        }
    }

    @Test fun edgeTapsRemainNonemptyAndInsideTheVisibleCrop() {
        for (crop in listOf(active, SensorActiveArray(10, 20, 11, 21))) {
            for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
                val region = MeteringGeometry.tap(x, y, crop, 90, true)
                assertTrue(region.left >= crop.left && region.top >= crop.top)
                assertTrue(region.right <= crop.right && region.bottom <= crop.bottom)
                assertTrue(region.right > region.left && region.bottom > region.top)
            }
        }
    }
}
