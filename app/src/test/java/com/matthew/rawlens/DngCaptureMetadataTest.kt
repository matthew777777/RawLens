package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DngCaptureMetadataTest {
    @Test fun littleEndianPreservesPayloadAndUnknownTags() = roundTrip(ByteOrder.LITTLE_ENDIAN)
    @Test fun bigEndianPreservesPayloadAndUnknownTags() = roundTrip(ByteOrder.BIG_ENDIAN)

    private fun roundTrip(order: ByteOrder) {
        val file = File.createTempFile("capture-metadata", ".dng")
        try {
            RandomAccessFile(file, "rw").use { f ->
                val original = ByteBuffer.allocate(512).order(order)
                original.put(if (order == ByteOrder.LITTLE_ENDIAN) byteArrayOf(73, 73) else byteArrayOf(77, 77))
                original.putShort(42).putInt(8).putShort(4)
                fun entry(tag: Int, type: Int, count: Int, value: Int) {
                    original.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value)
                }
                entry(273, 4, 1, 256) // strip pointer
                entry(34665, 4, 1, 80)
                entry(50721, 10, 9, 128) // camera matrix pointer
                entry(65000, 7, 8, 248) // unknown vendor payload
                original.putInt(0)
                original.position(80)
                original.putShort(1)
                entry(37500, 7, 8, 240) // opaque MakerNote: original offset must survive
                original.putInt(0)
                for (i in 128 until 512) original.put(i, (i and 255).toByte())
                f.write(original.array())
                val metadata = mock(RawFrameMetadata::class.java)
                `when`(metadata.aeMode).thenReturn(1)
                `when`(metadata.awbMode).thenReturn(0)
                `when`(metadata.exposureTimeNanos).thenReturn(2_000_000_000L)
                `when`(metadata.exposureCompensationEv).thenReturn(-1.5)
                `when`(metadata.sensitivityIso).thenReturn(100_000)
                DngCaptureMetadata.append(f.length(), { offset, count ->
                    ByteArray(count).also { f.seek(offset); f.readFully(it) }
                }, { offset, bytes -> f.seek(offset); f.write(bytes) }, metadata,
                    1_700_000_000_123L, GpsLocation(51.0, -1.0, -5.5, accuracyMeters = 2.5f))
                val all = file.readBytes()
                assertArrayEquals(original.array().copyOfRange(8, 512), all.copyOfRange(8, 512))
                val b = ByteBuffer.wrap(all).order(order)
                fun ifd(offset: Int): Map<Int, Int> {
                    val count = b.getShort(offset).toInt() and 65535
                    val entries = (0 until count).map { offset + 2 + it * 12 }
                    val ids = entries.map { b.getShort(it).toInt() and 65535 }
                    assertEquals(ids.sorted(), ids)
                    return entries.associateBy { b.getShort(it).toInt() and 65535 }
                }
                val root = ifd(b.getInt(4))
                assertEquals(256, b.getInt(root.getValue(273) + 8))
                assertEquals(248, b.getInt(root.getValue(65000) + 8))
                val exif = ifd(b.getInt(root.getValue(34665) + 8))
                assertEquals(240, b.getInt(exif.getValue(37500) + 8))
                assertEquals(100_000, b.getInt(exif.getValue(34867) + 8))
                assertEquals(1, b.getShort(exif.getValue(41987) + 8).toInt())
                val bias = b.getInt(exif.getValue(37380) + 8)
                assertEquals(-1.5, b.getInt(bias).toDouble() / b.getInt(bias + 4), 1e-6)
                val gps = ifd(b.getInt(root.getValue(34853) + 8))
                assertEquals(7, b.getShort(gps.getValue(27) + 2).toInt())
                val method = b.getInt(gps.getValue(27) + 8)
                assertArrayEquals(byteArrayOf(65, 83, 67, 73, 73, 0, 0, 0), all.copyOfRange(method, method + 8))
                assertTrue(gps.containsKey(31))
            }
        } finally { file.delete() }
    }
}
