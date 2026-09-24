package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test

class DualRawExposureTest {
    @Test fun meteredLongAndTwoStopShortHeadroom() {
        val p=DualRawExposure.plan(100,33_333_333L,50,12800,100_000L,400_000_000L)
        assertTrue(p.high.iso==p.low.iso)
        assertTrue(p.high.nanos<p.low.nanos)
        assertEquals(100.0*33_333_333,p.low.iso.toDouble()*p.low.nanos,100.0)
        assertEquals(100.0*33_333_333*.25,p.high.iso.toDouble()*p.high.nanos,100.0)
    }
    @Test fun darkSceneRespectsShutterAndAnalogLimits() {
        val p=DualRawExposure.plan(12800,400_000_000L,50,12800,100_000L,400_000_000L)
        for(f in listOf(p.high,p.low)) {
            assertTrue(f.iso in 50..12800)
            assertTrue(f.nanos in 100_000L..33_333_333L)
        }
        assertTrue(p.high.iso==p.low.iso)
    }
    @Test fun brightSceneReducesHighIsoToRespectMinimumShutter() {
        val p=DualRawExposure.plan(100,400_000L,50,12800,100_000L,400_000_000L)
        assertEquals(50,p.high.iso)
        assertEquals(200_000L,p.high.nanos)
    }
    @Test(expected=IllegalArgumentException::class)
    fun impossibleGainSeparationIsRejected() {
        DualRawExposure.plan(50,100_000L,50,12800,100_000L,400_000_000L)
    }
    @Test fun restrictedSensorRangesStillApply() {
        val p=DualRawExposure.plan(200,10_000_000L,200,1600,1_000_000L,10_000_000L)
        for(f in listOf(p.high,p.low)) {
            assertTrue(f.iso in 200..1600)
            assertTrue(f.nanos in 1_000_000L..10_000_000L)
        }
    }
}
