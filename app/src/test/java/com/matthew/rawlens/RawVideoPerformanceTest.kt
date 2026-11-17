package com.matthew.rawlens
import org.junit.Assert.*
import org.junit.Test
class RawVideoPerformanceTest {
    @Test fun concurrencyStaysBoundedAndPreservesThirtyFpsDefaults() {
        assertEquals(3,RawVideoPerformance.workers(30,8))
        assertEquals(4,RawVideoPerformance.workers(60,8))
        assertEquals(3,RawVideoPerformance.workers(60,4))
        assertEquals(1,RawVideoPerformance.workers(60,1))
    }
    @Test fun monitoringKeepsEveryOtherSixtyFpsImageDespiteSensorRounding() {
        var last=Long.MIN_VALUE
        val selected=(0..5).filter { i ->
            val stamp=i*16_665_000L
            RawVideoPerformance.offerPreview(last,stamp,60).also { if(it)last=stamp }
        }
        assertEquals(listOf(0,2,4),selected)
        assertTrue(RawVideoPerformance.offerPreview(0,16_665_000L,30))
    }
}
