package com.matthew.rawlens

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class BasicRgbExposureGuardTest {
    @Test fun calibratedGainReducesExposureBudgetWithoutChangingInput() {
        val rgb=ByteBuffer.allocateDirect(1200).order(ByteOrder.nativeOrder())
        repeat(300) { rgb.putFloat(.2f) }; rgb.flip()
        val identity=doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        val normal=BasicRgbExposureGuard.limitEv(rgb,10,10,identity,.85)
        val amplified=BasicRgbExposureGuard.limitEv(rgb,10,10,identity.map { it*4 }.toDoubleArray(),.85)
        assertEquals(2.0,normal-amplified,1e-5)
        assertEquals(0,rgb.position())
        repeat(300) { assertEquals(.2f,rgb.getFloat(it*4),0f) }
    }
    @Test fun isolatedHotPixelDoesNotDimTheWholeFrame() {
        val rgb=ByteBuffer.allocateDirect(1200).order(ByteOrder.nativeOrder())
        repeat(300) { rgb.putFloat(if(it<3) 100f else .2f) }; rgb.flip()
        val identity=doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        assertTrue(BasicRgbExposureGuard.limitEv(rgb,10,10,identity,.85)>1.5)
    }
}
