package com.matthew.rawlens

import android.media.MediaMuxer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class VideoLocationMetadataTest {
    @Test fun parsesMp4AndQuickTimeIso6709Coordinates() {
        assertEquals(48.8582f to 2.2945f, VideoLocationMetadata.parse("+48.8582+002.2945/"))
        assertEquals(-33.86f to 151.21f, VideoLocationMetadata.parse("-33.86+151.21+12.5/"))
        assertEquals(0f to 0f, VideoLocationMetadata.parse("+00.0000+000.0000/"))
        for (value in listOf(null, "", "NaN", "+91.0+002.0/", "+45.0-181.0/", "+48.0+002.0/junk")) {
            assertNull(value, VideoLocationMetadata.parse(value))
        }
    }

    @Test fun noFixWritesNothingAndValidFixReachesMuxer() {
        val muxer = mock(MediaMuxer::class.java)
        VideoLocationMetadata.writeTo(muxer, null)
        verifyNoInteractions(muxer)
        VideoLocationMetadata.writeTo(muxer, GpsLocation(-33.86, 151.21))
        verify(muxer).setLocation(-33.86f, 151.21f)
    }
}
