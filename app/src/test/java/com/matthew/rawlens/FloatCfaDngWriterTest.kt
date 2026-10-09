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
    @Test fun capturedMatricesAreWrittenInRawSensorRowMajorOrder() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.exifOrientation).thenReturn(1)
        `when`(metadata.referenceIlluminant2).thenReturn(21)
        val captured = ImmutableDoubleValues(doubleArrayOf(1.0, 4.0, 7.0, -2.0, 5.0, 8.0, 3.0, 6.0, 9.0))
        `when`(metadata.colorMatrix1).thenReturn(captured)
        `when`(metadata.colorMatrix2).thenReturn(captured)
        `when`(metadata.forwardMatrix1).thenReturn(captured)
        `when`(metadata.forwardMatrix2).thenReturn(captured)
        `when`(metadata.cameraCalibration1).thenReturn(captured)
        `when`(metadata.cameraCalibration2).thenReturn(captured)
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB, FloatArray(16), RawCrop(0, 0, 4, 4))
        val bytes = ByteArrayOutputStream().also { FloatCfaDngWriter.write(it, cfa, metadata) }.toByteArray()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val expected = doubleArrayOf(1.0, -2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)
        val remaining = mutableSetOf(50721, 50722, 50723, 50724, 50964, 50965)
        for (i in 0 until buffer.getShort(8).toInt()) {
            val p = 10 + i * 12
            val tag = buffer.getShort(p).toInt() and 0xffff
            if (!remaining.remove(tag)) continue
            assertEquals(10, buffer.getShort(p + 2).toInt())
            assertEquals(9, buffer.getInt(p + 4))
            val offset = buffer.getInt(p + 8)
            for (j in expected.indices) assertEquals(expected[j],
                buffer.getInt(offset + j * 8).toDouble() / buffer.getInt(offset + j * 8 + 4), 1e-6)
        }
        assertTrue(remaining.isEmpty())
    }

    @Test fun writesBaselineExposureAndReferenceExposureTags() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.exifOrientation).thenReturn(1)
        `when`(metadata.referenceIlluminant1).thenReturn(21)
        `when`(metadata.exposureTimeNanos).thenReturn(10_000_000L)
        `when`(metadata.sensitivityIso).thenReturn(153)
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB, FloatArray(16), RawCrop(0, 0, 4, 4))
        val bytes = ByteArrayOutputStream().also {
            FloatCfaDngWriter.write(it, cfa, metadata, baselineExposureEv = 2.0)
        }.toByteArray()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.getShort(8).toInt() and 0xffff
        var baseline: Double? = null
        var exposure: Double? = null
        var iso: Int? = null
        for (i in 0 until count) {
            val p = 10 + i * 12
            when (buffer.getShort(p).toInt() and 0xffff) {
                50730 -> {
                    assertEquals(10, buffer.getShort(p + 2).toInt())
                    val offset = buffer.getInt(p + 8)
                    baseline = buffer.getInt(offset).toDouble() / buffer.getInt(offset + 4)
                }
                33434 -> {
                    assertEquals(5, buffer.getShort(p + 2).toInt())
                    val offset = buffer.getInt(p + 8)
                    exposure = (buffer.getInt(offset).toLong() and 0xffffffffL).toDouble() /
                        (buffer.getInt(offset + 4).toLong() and 0xffffffffL)
                }
                34855 -> {
                    assertEquals(3, buffer.getShort(p + 2).toInt())
                    iso = buffer.getShort(p + 8).toInt() and 0xffff
                }
            }
        }
        assertEquals(2.0, baseline ?: Double.NaN, 1e-6)
        assertEquals(0.01, exposure ?: Double.NaN, 1e-9)
        assertEquals(153, iso)
    }

    @Test fun omitsExposureTagsForUniformMergesByDefault() {
        val metadata = mock(RawFrameMetadata::class.java)
        `when`(metadata.cameraId).thenReturn("0")
        `when`(metadata.exifOrientation).thenReturn(1)
        `when`(metadata.referenceIlluminant1).thenReturn(21)
        `when`(metadata.exposureTimeNanos).thenReturn(10_000_000L)
        `when`(metadata.sensitivityIso).thenReturn(153)
        val cfa = UnpackedRawCfa(4, 4, BayerPattern.RGGB, FloatArray(16), RawCrop(0, 0, 4, 4))
        val bytes = ByteArrayOutputStream().also {
            FloatCfaDngWriter.write(it, cfa, metadata)
        }.toByteArray()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.getShort(8).toInt() and 0xffff
        val tags = HashSet<Int>()
        for (i in 0 until count) tags.add(buffer.getShort(10 + i * 12).toInt() and 0xffff)
        assertTrue(tags.intersect(setOf(50730, 33434, 34855)).isEmpty())
    }

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
        // Rawspeed wants Make/Model for camera identification.
        assertTrue(tags.containsKey(271))
        assertTrue(tags.containsKey(272))
        assertEquals(0f, buffer.getFloat(stripOffset), 0f)
        assertEquals(3.5f, buffer.getFloat(stripOffset + 15 * 4), 0f)
    }
}
