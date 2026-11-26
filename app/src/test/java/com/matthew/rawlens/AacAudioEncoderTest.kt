// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AacAudioEncoderTest {

    @Test fun stereo48kIsAacLcWithIndex3AndTwoChannels() {
        // 00010 (AAC-LC) 0011 (48 kHz) 0010 (stereo) 000 => 0x11 0x90
        assertArrayEquals(
            byteArrayOf(0x11, 0x90.toByte()),
            AacAudioEncoder.audioSpecificConfig(48000, 2),
        )
    }

    @Test fun monoConfigUsesChannelConfigOne() {
        // 00010 0011 0001 000 => 0x11 0x88
        assertArrayEquals(
            byteArrayOf(0x11, 0x88.toByte()),
            AacAudioEncoder.audioSpecificConfig(48000, 1),
        )
    }

    @Test fun unsupportedRateOrChannelsYieldNull() {
        assertNull(AacAudioEncoder.audioSpecificConfig(47999, 2))
        assertNull(AacAudioEncoder.audioSpecificConfig(48000, 0))
        assertNull(AacAudioEncoder.audioSpecificConfig(48000, 8))
    }
}
