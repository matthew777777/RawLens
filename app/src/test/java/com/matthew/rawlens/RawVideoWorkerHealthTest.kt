package com.matthew.rawlens
import org.junit.Assert.*
import org.junit.Test
class RawVideoWorkerHealthTest {
    @Test fun recoveredWorkerSurvivesRepeatedIsolatedSchedulingSpikes() {
        val health=RawVideoWorkerHealth(4)
        repeat(10) { assertFalse(health.timedOut(1));health.completed(1,70) }
    }
    @Test fun repeatedSlowFramesCanStillRetireOnePathologicalWorker() {
        val health=RawVideoWorkerHealth(4)
        assertFalse(health.timedOut(0));health.completed(0,300)
        assertFalse(health.timedOut(0));health.completed(0,350)
        assertTrue(health.timedOut(0));assertFalse(health.timedOut(1))
    }
}
