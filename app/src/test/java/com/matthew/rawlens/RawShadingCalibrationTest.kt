package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class RawShadingCalibrationTest {
    @Test fun camera2RemainingMapSurvivesSensorAlreadyAppliedFlag() {
        val map=LensShadingSnapshot(1,1,ImmutableFloatValues(floatArrayOf(1f,1.1f,1.2f,1.3f)))
        assertFalse(RawShadingCalibration.alreadyApplied(map,true))
        assertFalse(RawShadingCalibration.alreadyApplied(map,false))
        assertTrue(RawShadingCalibration.alreadyApplied(null,true))
    }

    @Test fun nokiaSizedResidualMapIsPreservedWithSensorCorrection() {
        // Supplied Nokia X30 DNG: 17 columns, 13 rows, substantial residual
        // corner gains. A sensor-side correction flag must not discard it.
        val gains = FloatArray(17 * 13 * 4) { 1.005859375f }
        gains[0] = 4.69140625f
        gains[1] = 4.69921875f
        gains[2] = 4.69921875f
        gains[3] = 5.498046875f
        val map = LensShadingSnapshot(13, 17, ImmutableFloatValues(gains))
        assertSame(map, RawShadingCalibration.readoutMap(map))
        assertFalse(RawShadingCalibration.alreadyApplied(map, true))
        assertFalse(RawShadingCalibration.alreadyApplied(map, false))
        assertFalse(RawShadingCalibration.alreadyApplied(null, false))
    }

    @Test fun preservesDistinctFullDcgAndCropMapsWithoutRecropping() {
        for(corner in listOf(4.1f,1.98f,1.54f,1.12f)) {
            val values=FloatArray(17*17*4) { 1f }.also { it[0]=corner;it[1]=corner-0.01f }
            val map=LensShadingSnapshot(17,17,ImmutableFloatValues(values))
            assertSame(map,RawShadingCalibration.readoutMap(map))
            assertEquals(corner,RawShadingCalibration.readoutMap(map)!!.gains[0],0f)
        }
    }

    @Test fun refusesCorruptOrInvalidMaps() {
        assertNull(RawShadingCalibration.readoutMap(LensShadingSnapshot(17,17,ImmutableFloatValues(floatArrayOf(1f)))))
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,0f,-1f))
            assertNull(RawShadingCalibration.readoutMap(LensShadingSnapshot(1,1,ImmutableFloatValues(floatArrayOf(value,1f,1f,1f)))))
    }
}
