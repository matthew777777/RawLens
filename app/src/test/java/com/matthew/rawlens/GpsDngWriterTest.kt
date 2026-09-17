package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GPS sub-IFD coverage for the hand-rolled Kotlin DNG writer. The platform
 * DngCreator path is owned by Android's writer (setLocation) and the JPEG
 * path by ExifInterface; both are pinned in [GpsLocationTest].
 */
class GpsDngWriterTest {
    private val gps = GpsLocation(48.858222, 2.2945, 35.5, timeMillis = 1780276800000L)

    @Test fun floatCfaCarriesGpsSubIfd() {
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB, FloatArray(16) { 0.5f }, RawCrop(0, 0, 4, 4))
        val bytes = ByteArrayOutputStream().also {
            FloatCfaDngWriter.write(it, cfa, metadata(), gps)
        }.toByteArray()
        val tiff = Tiff(bytes)
        assertTrue(tiff.has(GpsTiffDirectory.TAG_GPS_IFD_POINTER))
        assertGpsContents(tiff.subIfd(GpsTiffDirectory.TAG_GPS_IFD_POINTER), gps)
        assertEquals(tiff.entry(273).value + tiff.entry(279).value, bytes.size)
    }

    @Test fun writersOmitGpsPointerWithoutAFix() {
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB, FloatArray(16), RawCrop(0, 0, 4, 4))
        val float = ByteArrayOutputStream().also {
            FloatCfaDngWriter.write(it, cfa, metadata())
        }.toByteArray()
        assertFalse(Tiff(float).has(GpsTiffDirectory.TAG_GPS_IFD_POINTER))
    }

    @Test fun gpsPointerEntryIsSortedAndInline() {
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB, FloatArray(16) { 0.5f }, RawCrop(0, 0, 4, 4))
        val bytes = ByteArrayOutputStream().also {
            FloatCfaDngWriter.write(it, cfa, metadata(), gps)
        }.toByteArray()
        val tiff = Tiff(bytes)
        val tags = tiff.tags
        assertEquals(tags.sorted(), tags)
        val pointer = tiff.entry(GpsTiffDirectory.TAG_GPS_IFD_POINTER)
        assertEquals(4, pointer.type)
        assertEquals(1, pointer.count)
        // The sub-IFD sits between the main-IFD data and the pixel strip.
        assertTrue(pointer.value > 8)
        assertTrue(pointer.value < tiff.entry(273).value)
    }

    private fun assertGpsContents(gpsIfd: Tiff.Ifd, expected: GpsLocation) {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 27, 29),
            gpsIfd.entries.keys.sorted()
        )
        assertArrayEquals(byteArrayOf(2, 3, 0, 0), gpsIfd.bytes(0))
        assertEquals("N", gpsIfd.ascii(1))
        assertEquals("E", gpsIfd.ascii(3))
        assertEquals(expected.latitude, gpsIfd.coordinate(2), 1e-6)
        assertEquals(expected.longitude, gpsIfd.coordinate(4), 1e-6)
        assertArrayEquals(byteArrayOf(0), gpsIfd.bytes(5))
        assertEquals(35.5, gpsIfd.rational(6), 1e-6)
        assertEquals("2026:06:01", gpsIfd.ascii(29))
        assertEquals("GPS", gpsIfd.ascii(27))
    }

    private fun metadata(): RawFrameMetadata {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.exifOrientation).thenReturn(1)
        return metadata
    }

    /** Minimal little-endian TIFF reader for the writer round trips. */
    private class Tiff(val bytes: ByteArray) {
        private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val tags: List<Int>
        private val map: Map<Int, Entry>
        init {
            require(u16(0) == 0x4949 && u16(2) == 42)
            val ifd = Ifd(u32(4).toInt())
            map = ifd.entries
            tags = map.keys.sorted()
        }
        fun has(tag: Int) = map.containsKey(tag)
        fun entry(tag: Int) = requireNotNull(map[tag]) { "missing tag $tag" }
        fun subIfd(pointerTag: Int): Ifd = Ifd(entry(pointerTag).value)
        private fun u16(off: Int) = buf.getShort(off).toInt() and 0xFFFF
        private fun u32(off: Int) = (buf.getInt(off).toLong() and 0xFFFFFFFFL)

        inner class Ifd(base: Int) {
            val entries: Map<Int, Entry>
            init {
                val count = u16(base)
                val parsed = HashMap<Int, Entry>()
                repeat(count) { i ->
                    val off = base + 2 + i * 12
                    val tag = u16(off)
                    val type = u16(off + 2)
                    val entryCount = u32(off + 4).toInt()
                    val valueOrOffset = u32(off + 8).toInt()
                    parsed[tag] = Entry(tag, type, entryCount, valueOrOffset)
                }
                entries = parsed
            }
            fun bytes(tag: Int): ByteArray {
                val entry = requireNotNull(entries[tag]) { "missing GPS tag $tag" }
                val size = entry.count * when (entry.type) {
                    3 -> 2; 4 -> 4; 5 -> 8; else -> 1
                }
                return if (size <= 4) {
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
                        putInt(entry.value)
                    }.array().copyOf(size)
                } else {
                    bytes.copyOfRange(entry.value, entry.value + size)
                }
            }
            fun ascii(tag: Int): String {
                val raw = bytes(tag)
                val end = raw.indexOf(0).takeIf { it >= 0 } ?: raw.size
                return String(raw, 0, end, Charsets.US_ASCII)
            }
            fun rational(tag: Int): Double {
                val raw = bytes(tag)
                val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                return b.getInt(0).toDouble() / b.getInt(4).toDouble()
            }
            fun coordinate(tag: Int): Double {
                val raw = bytes(tag)
                val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                val deg = b.getInt(0).toDouble() / b.getInt(4).toDouble()
                val min = b.getInt(8).toDouble() / b.getInt(12).toDouble()
                val sec = b.getInt(16).toDouble() / b.getInt(20).toDouble()
                return deg + min / 60.0 + sec / 3600.0
            }
        }

        data class Entry(val tag: Int, val type: Int, val count: Int, val value: Int)
    }
}
