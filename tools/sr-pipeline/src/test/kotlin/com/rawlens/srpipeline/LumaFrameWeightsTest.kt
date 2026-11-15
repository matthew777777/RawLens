package com.rawlens.srpipeline

import com.rawlens.srpipeline.stages.LumaFrameWeights
import org.junit.Assert.*
import org.junit.Test

class LumaFrameWeightsTest {
    @Test fun `grain retention preserves total mass and spans the burst including reference`() {
        for(frames in 1..59) {
            val weights=LumaFrameWeights.forBurst(frames,1f)
            assertEquals(frames.toDouble(),weights.sum().toDouble(),1e-4)
            assertEquals(minOf(frames,6),weights.count { it>0 })
            assertTrue(weights.first()>0)
            assertTrue(weights.last()>0)
            assertTrue(weights.all { it.isFinite() && it>=0 })
            assertArrayEquals(FloatArray(frames) { 1f },LumaFrameWeights.forBurst(frames,0f),0f)
            val blended=LumaFrameWeights.forBurst(frames,.5f)
            assertEquals(frames.toDouble(),blended.sum().toDouble(),1e-4)
            assertTrue(blended.all { it>0 })
        }
    }
}
