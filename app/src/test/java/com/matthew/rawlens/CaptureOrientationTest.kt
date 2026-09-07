package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureOrientationTest {
    @Test
    fun backCamera_sensor90_mapsOrientationEventListenerAnglesCorrectly() {
        assertEquals(6, CaptureOrientation.exif(90, 0, frontFacing = false))
        // OrientationEventListener reports 270 when a portrait-natural phone is physically
        // rotated 90 degrees counter-clockwise. The RAW sensor is then already upright landscape.
        assertEquals(1, CaptureOrientation.exif(90, 270, frontFacing = false))
        // Clockwise landscape needs the sensor-native frame turned 180 degrees.
        assertEquals(3, CaptureOrientation.exif(90, 90, frontFacing = false))
        assertEquals(8, CaptureOrientation.exif(90, 180, frontFacing = false))
    }

    @Test
    fun frontCamera_sensor270_usesMirroredCamera2Convention() {
        assertEquals(8, CaptureOrientation.exif(270, 0, frontFacing = true))
        assertEquals(1, CaptureOrientation.exif(270, 270, frontFacing = true))
        assertEquals(3, CaptureOrientation.exif(270, 90, frontFacing = true))
    }

    @Test
    fun listenerAngles_areNormalizedToQuarterTurns() {
        assertEquals(3, CaptureOrientation.exif(90, 89, frontFacing = false))
        assertEquals(1, CaptureOrientation.exif(90, -90, frontFacing = false))
        assertEquals(6, CaptureOrientation.exif(450, 360, frontFacing = false))
    }
}
