package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercises the real JNI encoder when host libjxl is available. */
class NativeJxlDngWriterTest {
    companion object {
        @BeforeClass @JvmStatic fun hostLibrary() = HostDngLib.assumeAvailable()
    }

    @Test fun rawPixelsBecomeJxlWithAutomaticVersionAndGps() {
        val output = ByteArrayOutputStream()
        val pixels = ByteBuffer.allocate(16 * 12 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(16 * 12) { putShort((it * 313).toShort()) }
        }.array()
        val gps = TiffFields().apply { ascii(29, "2026:10:27") }
        try {
            TinyDngImageWriter.writeCompressed(output, 16, 12, pixels, TiffFields(), null, gps)
        } catch (e: IOException) {
            if (e.message?.contains("requires a libjxl-enabled build") == true) {
                assumeNoException("host libjxl not installed", e)
            }
            throw e
        }
        val bytes = output.toByteArray()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = b.getInt(4)
        fun entry(directory: Int, tag: Int): Int {
            repeat(b.getShort(directory).toInt() and 65535) { i ->
                val offset = directory + 2 + i * 12
                if ((b.getShort(offset).toInt() and 65535) == tag) return offset
            }
            error("Missing tag $tag")
        }
        assertEquals(52546, b.getShort(entry(ifd, 259) + 8).toInt() and 65535)
        for (tag in listOf(50706, 50707)) {
            val offset = entry(ifd, tag) + 8
            assertArrayEquals(byteArrayOf(1, 7, 0, 0), bytes.copyOfRange(offset, offset + 4))
        }
        val strip = b.getInt(entry(ifd, 273) + 8)
        // libjxl may wrap 16-bit level-10 streams in a JPEG XL container.
        val bare = bytes[strip] == 255.toByte() && bytes[strip + 1] == 10.toByte()
        val container = byteArrayOf(0, 0, 0, 12, 74, 88, 76, 32, 13, 10, -121, 10)
        assertTrue(bare || bytes.copyOfRange(strip, strip + 12).contentEquals(container))
        val gpsIfd = b.getInt(entry(ifd, 34853) + 8)
        val dateOffset = b.getInt(entry(gpsIfd, 29) + 8)
        assertEquals("2026:10:27", String(bytes, dateOffset, 10, Charsets.US_ASCII))
    }
}
