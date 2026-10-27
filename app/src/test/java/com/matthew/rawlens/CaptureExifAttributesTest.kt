package com.matthew.rawlens

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import android.util.Pair
import java.io.File

class CaptureExifAttributesTest {
    @Test fun actualJpegExifRoundTrip() {
        // android.jar's Pair constructor does not assign its fields on host JVMs.
        // Restore just that framework value type so the real AndroidX codec runs.
        mockConstruction(Pair::class.java) { pair, context ->
            Pair::class.java.getField("first").set(pair, context.arguments()[0])
            Pair::class.java.getField("second").set(pair, context.arguments()[1])
        }.use {
            val file = File.createTempFile("capture-exif", ".jpg")
            try {
                file.writeBytes(byteArrayOf(-1, -40, -1, -64, 0, 11, 8, 0, 1, 0, 1, 1, 1, 17, 0, -1, -39))
                val metadata = mock(RawFrameMetadata::class.java)
                `when`(metadata.aeMode).thenReturn(0)
                `when`(metadata.awbMode).thenReturn(1)
                `when`(metadata.exposureTimeNanos).thenReturn(2_000_000_000L)
                `when`(metadata.exposureCompensationEv).thenReturn(-1.5)
                ExifInterface(file).apply {
                    CaptureExifAttributes.writeTo(this, metadata, 1_700_000_000_123L)
                    GpsLocation(51.0, -1.0, -4.5, 1780276800000L, accuracyMeters = 3f).writeTo(this)
                    saveAttributes()
                }
                val read = ExifInterface(file)
                assertEquals(1, read.getAttributeInt("ExposureMode", -1))
                assertEquals(0, read.getAttributeInt("WhiteBalance", -1))
                assertEquals(-1.0, read.getAttributeDouble("ShutterSpeedValue", 0.0), 1e-6)
                assertEquals(-1.5, read.getAttributeDouble("ExposureBiasValue", 0.0), 1e-6)
                assertEquals("123", read.getAttribute("SubSecTimeOriginal"))
                assertArrayEquals(byteArrayOf(2, 3, 0, 0), read.getAttributeBytes("GPSVersionID"))
                assertEquals("01:20:00", read.getAttribute("GPSTimeStamp"))
                assertEquals(-4.5, read.getAltitude(0.0), 1e-6)
                assertEquals(3.0, read.getAttributeDouble("GPSHPositioningError", -1.0), 1e-6)
                val method = requireNotNull(read.getAttributeBytes("GPSProcessingMethod"))
                assertArrayEquals(byteArrayOf(65, 83, 67, 73, 73, 0, 0, 0), method.copyOfRange(0, 8))
            } finally { file.delete() }
        }
    }

    @Test fun jpegUsesSignedApexValuesAndFrozenModes() {
        val exif = mock(ExifInterface::class.java)
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.exposureTimeNanos).thenReturn(2_000_000_000L)
        `when`(metadata.aperture).thenReturn(2f)
        `when`(metadata.aeMode).thenReturn(0)
        `when`(metadata.awbMode).thenReturn(1)
        `when`(metadata.exposureCompensationEv).thenReturn(-1.5)
        `when`(metadata.compositeSourceCount).thenReturn(8)
        `when`(metadata.compositeUsedCount).thenReturn(6)
        CaptureExifAttributes.writeTo(exif, metadata, 1_700_000_000_123L)
        verify(exif).setAttribute("ShutterSpeedValue", "-1/1")
        verify(exif).setAttribute("ExposureBiasValue", "-3/2")
        verify(exif).setAttribute("ExposureMode", "1")
        verify(exif).setAttribute("WhiteBalance", "0")
        verify(exif).setAttribute("SubSecTimeOriginal", "123")
        verify(exif).setAttribute("OffsetTimeOriginal", DngExifDirectory.offsetString(1_700_000_000_123L))
    }
}
