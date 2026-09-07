package com.matthew.rawlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FloatCfaDngWriterTest {
    @Test fun writesFloatCfaDngTagsAndUnclampedSamples() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.exifOrientation).thenReturn(1)
        `when`(metadata.referenceIlluminant1).thenReturn(21)
        val values = FloatArray(16) { if (it == 15) 3.5f else it / 16f }
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB, values, RawCrop(0, 0, 4, 4))
        val bytes = ByteArrayOutputStream().also { FloatCfaDngWriter.write(it, cfa, metadata) }.toByteArray()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x4949, buffer.getShort(0).toInt() and 0xffff)
        assertEquals(42, buffer.getShort(2).toInt())
        val count = buffer.getShort(8).toInt() and 0xffff
        val tags = HashMap<Int, Pair<Int, Int>>()
        var stripOffset = 0
        val inlineValues = HashMap<Int, Int>()
        for (i in 0 until count) {
            val p = 10 + i * 12
            val tag = buffer.getShort(p).toInt() and 0xffff
            tags[tag] = (buffer.getShort(p + 2).toInt() and 0xffff) to buffer.getInt(p + 4)
            inlineValues[tag] = buffer.getInt(p + 8)
            if (tag == 273) stripOffset = buffer.getInt(p + 8)
        }
        assertEquals(3, tags.getValue(339).first) // SHORT
        assertEquals(3, inlineValues.getValue(339)) // IEEE floating point, not just tag type
        assertEquals(32, inlineValues.getValue(258))
        assertEquals(32803, inlineValues.getValue(262))
        assertEquals(0x00000401, inlineValues.getValue(50707))
        assertEquals(1, inlineValues.getValue(50717))
        assertEquals(64, inlineValues.getValue(279))
        assertEquals(stripOffset + 64, bytes.size)
        assertEquals(1, tags.getValue(339).second)
        assertEquals(1, tags.getValue(50717).second)
        assertTrue(tags.containsKey(33422))
        assertEquals(0f, buffer.getFloat(stripOffset), 0f)
        assertEquals(3.5f, buffer.getFloat(stripOffset + 15 * 4), 0f)
    }
}
