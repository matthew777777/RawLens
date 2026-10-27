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

    @Test fun floatUpmixDuplicatesEachSample() {
        val out = AudioPcmRecorder.upmixMonoToStereo(floatArrayOf(0.5f, -1.25f), 2)
        org.junit.Assert.assertArrayEquals(floatArrayOf(0.5f, 0.5f, -1.25f, -1.25f), out, 0f)
    }

    @Test fun peakDbfsMapsFullScaleAndClampsRange() {
        org.junit.Assert.assertEquals(0f, AudioPcmRecorder.peakDbfs(1f), 1e-4f)
        org.junit.Assert.assertEquals(-6.0206f, AudioPcmRecorder.peakDbfs(0.5f), 1e-3f)
        // Float input above full scale reads positive, up to the meter ceiling.
        org.junit.Assert.assertEquals(6.0206f, AudioPcmRecorder.peakDbfs(2f), 1e-3f)
        org.junit.Assert.assertEquals(AudioPcmRecorder.METER_MAX_DB, AudioPcmRecorder.peakDbfs(100f), 0f)
        org.junit.Assert.assertEquals(AudioPcmRecorder.METER_MIN_DB, AudioPcmRecorder.peakDbfs(0f), 0f)
        org.junit.Assert.assertEquals(AudioPcmRecorder.METER_MIN_DB, AudioPcmRecorder.peakDbfs(Float.NaN), 0f)
    }
}
