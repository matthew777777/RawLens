package com.matthew.rawlens

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GpsLocationTest {
    @Test fun rejectsOutOfRangeCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            GpsLocation(91.0, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpsLocation(0.0, -181.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpsLocation(Double.NaN, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpsLocation(0.0, 0.0, processingMethod = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpsLocation(0.0, 0.0, altitudeMeters = Double.POSITIVE_INFINITY)
        }
    }

    @Test fun hemisphereRefsFollowSign() {
        assertEquals("N", GpsLocation(48.85, 2.35).latitudeRef)
        assertEquals("E", GpsLocation(48.85, 2.35).longitudeRef)
        assertEquals("S", GpsLocation(-33.86, 151.20).latitudeRef)
        assertEquals("W", GpsLocation(40.71, -74.0).longitudeRef)
        // Zero is neither south nor west.
        assertEquals("N", GpsLocation(0.0, 0.0).latitudeRef)
        assertEquals("E", GpsLocation(0.0, 0.0).longitudeRef)
    }

    @Test fun dmsFormatsExactHalfDegree() {
        assertEquals("1/1,30/1,0/1000", Dms.from(1.5).toExifString())
        assertEquals("0/1,0/1,0/1000", Dms.from(0.0).toExifString())
    }

    @Test fun dmsCarriesRoundingOverflowIntoMinutesAndDegrees() {
        // 3599.99999999 arc-seconds rounds the seconds field to 60.000 and
        // must carry into minutes, then into degrees: never "0/1,59/1,60000/1000".
        assertEquals(Dms(1, 0, 0), Dms.from(3599.99999999 / 3600.0))
    }

    @Test fun dmsRoundTripsWithinSubMeterTolerance() {
        for (decimal in listOf(48.858222, -33.865143, 51.4775, -0.000001, 89.999999)) {
            val dms = Dms.from(kotlin.math.abs(decimal))
            val back = dms.degrees + dms.minutes / 60.0 + (dms.secondsNum / 1000.0) / 3600.0
            assertEquals(kotlin.math.abs(decimal), back, 1e-6)
        }
    }

    @Test fun dateStampIsUtcCalendarDate() {
        assertEquals("1970:01:01", GpsLocation.dateStamp(0L))
        // 2026-06-01T12:00:00Z stays 06-01 in UTC even where local time differs.
        assertEquals("2026:06:01", GpsLocation.dateStamp(1780276800000L))
    }

    // ---- TIFF GPS sub-IFD fields (layout owned by the TinyDNG writer) ----

    @Test fun gpsDirectoryCarriesEveryRequiredTag() {
        val location = GpsLocation(48.858222, 2.2945, 35.5, timeMillis = 1780276800000L)
        val parsed = GpsFields(GpsTiffDirectory.fields(location))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 27, 29), parsed.tags)
        assertArrayEquals(byteArrayOf(2, 3, 0, 0), parsed.bytes(0))
        assertEquals("N", parsed.ascii(1))
        assertEquals("E", parsed.ascii(3))
        assertEquals(listOf(48L, 51L), parsed.rationals(2).take(2).map { it.first / it.second })
        assertEquals(listOf(2L, 17L), parsed.rationals(4).take(2).map { it.first / it.second })
        assertArrayEquals(byteArrayOf(0), parsed.bytes(5))
        assertEquals(35500L / 1000L, parsed.rationals(6).single().let { it.first / it.second })
        assertEquals("GPS", parsed.ascii(27))
        assertEquals("2026:06:01", parsed.ascii(29))
    }

    @Test fun gpsDirectoryOmitsAltitudeWithoutAFix() {
        val location = GpsLocation(-33.86, 151.2, altitudeMeters = null)
        val parsed = GpsFields(GpsTiffDirectory.fields(location))
        assertFalse(parsed.tags.contains(5))
        assertFalse(parsed.tags.contains(6))
        assertEquals("S", parsed.ascii(1))
        assertEquals("E", parsed.ascii(3))
    }

    @Test fun gpsDirectoryMarksBelowSeaLevel() {
        val location = GpsLocation(31.5, 35.5, altitudeMeters = -430.0)
        val parsed = GpsFields(GpsTiffDirectory.fields(location))
        assertArrayEquals(byteArrayOf(1), parsed.bytes(5))
        assertEquals(430L, parsed.rationals(6).single().let { it.first / it.second })
    }

    @Test fun gpsFieldsCarryValuesWithoutLayout() {
        // The retired blob builder embedded absolute file offsets; fields
        // carry (tag, type, count, payload) only, so the same field list
        // serializes identically wherever the writer places the sub-IFD.
        // Offset correctness now lives in the writer (GpsDngWriterTest
        // parses full TinyDNG output) instead of per-base blob tests.
        val location = GpsLocation(48.85, 2.35, 100.0, timeMillis = 1780276800000L)
        val a = GpsTiffDirectory.fields(location)
        val b = GpsTiffDirectory.fields(location)
        assertEquals(a.descriptors, b.descriptors)
        assertEquals(a.payloads.size, b.payloads.size)
        a.payloads.forEachIndexed { i, payload -> assertArrayEquals(payload, b.payloads[i]) }
        val parsed = GpsFields(a)
        assertEquals("N", parsed.ascii(1))
        assertEquals("E", parsed.ascii(3))
        assertEquals("2026:06:01", parsed.ascii(29))
        assertEquals(100L, parsed.rationals(6).single().let { it.first / it.second })
    }

    // ---- ExifInterface contract (JPEG) ----
    //
    // A real ExifInterface read/write round trip cannot run on the JVM unit-test
    // classpath (android.util.Pair is a stub there), so these tests pin the exact
    // attribute strings handed to the library. The library's tag/format table was
    // verified against its 1.4.2 sources: DMS triples encode as URATIONAL, refs
    // as STRING, altitude ref as a single BYTE, and the version as BYTE.

    @Test fun exifWriteSetsEveryGpsTag() {
        val exif = mock(ExifInterface::class.java)
        val location = GpsLocation(48.858222, -2.2945, -5.25, timeMillis = 1780276800000L)
        location.writeTo(exif)
        verify(exif).setAttribute(ExifInterface.TAG_GPS_VERSION_ID, "2.3.0.0")
        verify(exif).setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        verify(exif).setAttribute(
            ExifInterface.TAG_GPS_LATITUDE, location.latitudeDms().toExifString()
        )
        verify(exif).setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
        verify(exif).setAttribute(
            ExifInterface.TAG_GPS_LONGITUDE, location.longitudeDms().toExifString()
        )
        verify(exif).setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, "1")
        verify(exif).setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "5250/1000")
        verify(exif).setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2026:06:01")
        verify(exif).setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "GPS")
        assertNotNull(location.latitudeDms().toExifString())
    }

    @Test fun exifTimestampIsUtcBrokenDownTime() {
        val exif = mock(ExifInterface::class.java)
        // 2026-06-01T01:20:00Z.
        GpsLocation(0.0, 0.0, timeMillis = 1780276800000L).writeTo(exif)
        verify(exif).setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "1/1,20/1,0/1")
        verify(exif).setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2026:06:01")
    }

    @Test fun exifWriteOmitsAltitudeWithoutAFix() {
        val exif = mock(ExifInterface::class.java)
        GpsLocation(48.85, 2.35, altitudeMeters = null).writeTo(exif)
        verify(exif, never()).setAttribute(eq(ExifInterface.TAG_GPS_ALTITUDE), any())
        verify(exif, never()).setAttribute(eq(ExifInterface.TAG_GPS_ALTITUDE_REF), any())
        verify(exif).setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
    }

    /** Minimal field-list reader mirroring the retired blob parser. */
    private class GpsFields(fields: TiffFields) {
        private val payloads: Map<Int, ByteArray>
        val tags: List<Int>
        init {
            val map = HashMap<Int, ByteArray>()
            val d = fields.descriptors
            require(d.size == fields.payloads.size * 3)
            fields.payloads.forEachIndexed { i, payload -> map[d[i * 3]] = payload }
            payloads = map
            tags = map.keys.sorted()
        }
        fun bytes(tag: Int): ByteArray = requireNotNull(payloads[tag]) { "missing GPS tag $tag" }
        fun ascii(tag: Int): String {
            val raw = bytes(tag)
            return String(raw, 0, raw.indexOf(0).takeIf { it >= 0 } ?: raw.size, Charsets.US_ASCII)
        }
        fun rationals(tag: Int): List<Pair<Long, Long>> {
            val raw = bytes(tag)
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            return (0 until raw.size / 8).map {
                val num = b.getInt(it * 8).toLong() and 0xFFFFFFFFL
                val den = b.getInt(it * 8 + 4).toLong() and 0xFFFFFFFFL
                num to den
            }
        }
    }
}
