package com.matthew.rawlens

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DngLensCorrectionWriterTest {
    companion object {
        @BeforeClass @JvmStatic fun nativeLibrary() = HostDngLib.assumeAvailable()
    }

    private fun metadata(): RawFrameMetadata = mock(RawFrameMetadata::class.java).also { m ->
        `when`(m.cameraId).thenReturn("test")
        `when`(m.exifOrientation).thenReturn(1)
        `when`(m.imageWidth).thenReturn(12); `when`(m.imageHeight).thenReturn(10)
        `when`(m.cfaPattern).thenReturn(BayerPattern.RGGB)
        `when`(m.preCorrectionActiveArray).thenReturn(IntRectSnapshot(2, 2, 10, 8))
        `when`(m.bufferGeometry).thenReturn(RawBufferGeometry.Supported(0, 0, RawCrop(2,2,8,6), "test"))
        `when`(m.lensIntrinsics).thenReturn(ImmutableFloatValues(floatArrayOf(8f,8f,4f,3f,0f)))
        `when`(m.lensDistortion).thenReturn(ImmutableFloatValues(floatArrayOf(-0.02f,0f,0f,0.001f,0f)))
    }

    private fun tag(bytes: ByteArray, id: Int): ByteArray? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = b.getInt(4)
        for (i in 0 until (b.getShort(ifd).toInt() and 65535)) {
            val p = ifd + 2 + i*12
            if ((b.getShort(p).toInt() and 65535) == id) {
                assertEquals(7, b.getShort(p+2).toInt())
                val count = b.getInt(p+4); val offset = b.getInt(p+8)
                return bytes.copyOfRange(offset, offset+count)
            }
        }
        return null
    }

    @Test fun nativeTinyDngCarriesOpcodeList3() {
        val m = metadata()
        val tags = TinyDngMetadata.create(m, DngMetadataOverrides(), BayerPattern.RGGB,
            DoubleArray(4), 1023, RawCrop(2,2,8,6))
        val index = tags.descriptors.chunked(3).indexOfFirst { it[0] == 51022 }
        assertTrue(index >= 0)
        assertArrayEquals(DngLensCorrection.opcodeList(m, RawCrop(2,2,8,6)), tags.payloads[index])
    }

    @Test fun floatAndCroppedHdrWritersCarryTheSameActiveAreaCalibration() {
        val m = metadata()
        val full = UnpackedRawCfa(12,10,BayerPattern.RGGB,FloatArray(120),RawCrop(2,2,8,6))
        val cropped = UnpackedRawCfa(8,6,BayerPattern.RGGB,FloatArray(48),RawCrop(0,0,8,6))
        val floatBytes = ByteArrayOutputStream().also { FloatCfaDngWriter.write(it,full,m) }.toByteArray()
        val hdrBytes = ByteArrayOutputStream().also { HdrPlusDngWriter.write(it,cropped,m) }.toByteArray()
        val expected = requireNotNull(DngLensCorrection.opcodeList(m, RawCrop(2,2,8,6)))
        assertArrayEquals(expected, tag(floatBytes,51022))
        assertArrayEquals(expected, tag(hdrBytes,51022))
    }

    @Test fun unprovenResizedFloatImageDoesNotReceiveWrongCalibration() {
        val m = metadata()
        val resized = UnpackedRawCfa(24,20,BayerPattern.RGGB,FloatArray(480),RawCrop(0,0,24,20))
        val bytes = ByteArrayOutputStream().also { FloatCfaDngWriter.write(it,resized,m) }.toByteArray()
        assertNull(tag(bytes,51022))
    }
}
