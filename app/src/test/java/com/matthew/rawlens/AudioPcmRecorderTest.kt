// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AudioPcmRecorderTest {
    @Test
    fun upmixDuplicatesEachSampleToStereo() {
        val out = AudioPcmRecorder.upmixMonoToStereo(shortArrayOf(1, -2, 30000, -30000), 4)
        assertArrayEquals(shortArrayOf(1, 1, -2, -2, 30000, 30000, -30000, -30000), out)
    }

    @Test
    fun upmixHonorsFrameCount() {
        val out = AudioPcmRecorder.upmixMonoToStereo(shortArrayOf(5, 6, 7), 2)
        assertArrayEquals(shortArrayOf(5, 5, 6, 6), out)
    }

    @Test
    fun upmixEmptyIsEmpty() {
        assertArrayEquals(shortArrayOf(), AudioPcmRecorder.upmixMonoToStereo(shortArrayOf(), 0))
    }
}
