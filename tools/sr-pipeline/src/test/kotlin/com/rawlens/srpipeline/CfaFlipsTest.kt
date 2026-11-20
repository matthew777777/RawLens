package com.rawlens.srpipeline

import com.rawlens.srpipeline.stages.CfaFlips
import org.junit.Assert.assertEquals
import org.junit.Test

class CfaFlipsTest {
    @Test
    fun rggbFlipsMatchDngReadFlipToRggb() {
        assertEquals(false to false, CfaFlips.rggbFlips(intArrayOf(0, 1, 1, 2)))
        assertEquals(true to true, CfaFlips.rggbFlips(intArrayOf(2, 1, 1, 0)))
        assertEquals(true to false, CfaFlips.rggbFlips(intArrayOf(1, 0, 2, 1)))
        assertEquals(false to true, CfaFlips.rggbFlips(intArrayOf(1, 2, 0, 1)))
    }
}
