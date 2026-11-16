package com.matthew.rawlens

import android.graphics.ImageFormat
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShadedFloatDngWriterTest {
    companion object {
        @BeforeClass @JvmStatic fun nativeLibrary() = HostDngLib.assumeAvailable()
    }
    private val width = 8; private val height = 70; private val stride = 20
    private fun metadata(): RawFrameMetadata = mock(RawFrameMetadata::class.java).also { m ->
        `when`(m.cameraId).thenReturn("test"); `when`(m.exifOrientation).thenReturn(1)
        `when`(m.imageWidth).thenReturn(width); `when`(m.imageHeight).thenReturn(height)
        `when`(m.cfaPattern).thenReturn(BayerPattern.RGGB)
        `when`(m.activeArray).thenReturn(IntRectSnapshot(3,5,11,75))
        `when`(m.preCorrectionActiveArray).thenReturn(IntRectSnapshot(3,5,11,75))
        `when`(m.bufferGeometry).thenReturn(RawBufferGeometry.Supported(3,5,RawCrop(0,0,width,height),"test"))
        `when`(m.blackLevels).thenReturn(ImmutableFloatValues(floatArrayOf(64f,65f,66f,67f)))
        `when`(m.whiteLevel).thenReturn(1023f)
        `when`(m.lensShadingMap).thenReturn(LensShadingSnapshot(1,1,
            ImmutableFloatValues(floatArrayOf(2f,3f,4f,5f))))
    }
    private fun source(): ByteBuffer = ByteBuffer.allocate(7+height*stride).order(ByteOrder.LITTLE_ENDIAN).apply {
        for (y in 0 until height) for (x in 0 until width) putShort(7+y*stride+x*2, (if(x==0) 20 else 1023).toShort())
        position(7)
    }
    private fun fields(bytes: ByteArray): Map<Int, Pair<Int,Int>> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val offset = b.getInt(4)
        return (0 until (b.getShort(offset).toInt() and 65535)).associate { i ->
            val p = offset+2+i*12
            (b.getShort(p).toInt() and 65535) to (b.getInt(p+4) to b.getInt(p+8))
        }
    }
    @Test fun streamsShadingOnceAcrossStripBoundariesWithoutClippingOrModifyingInput() {
        val m = metadata(); val input = source(); val original = input.array().copyOf()
        val bytes = ByteArrayOutputStream().also { ShadedFloatDngWriter.write(it,input,stride,2,
            ImageFormat.RAW_SENSOR,m,DngMetadataOverrides()) }.toByteArray()
        assertArrayEquals(original,input.array()); assertEquals(7,input.position())
        val tags = fields(bytes); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertFalse(tags.containsKey(51009)) // no second shading correction by other editors
        assertEquals(32,tags.getValue(258).second)
        assertEquals(3,tags.getValue(339).second)
        assertEquals(1,tags.getValue(50717).second)
        assertEquals(3,tags.getValue(273).first)
        val offsets = IntArray(3) { b.getInt(tags.getValue(273).second+it*4) }
        for(y in 0 until height) for(x in 0 until width) {
            val sx = x+3; val sy = y+5
            val black = 64f+((sy and 1)*2+(sx and 1))
            val gain = when(BayerPattern.RGGB.colorAt(sx,sy)) {
                CfaColor.RED -> 2f; CfaColor.BLUE -> 5f
                CfaColor.GREEN -> if(sy and 1 == 0) 3f else 4f
            }
            val expected = ((if(x==0) 20f else 1023f)-black)/(1023f-black)*gain
            val actual = b.getFloat(offsets[y/32]+((y%32)*width+x)*4)
            assertEquals(expected,actual,1e-6f)
        }
    }
    @Test fun alreadyAppliedShadingIsNotMultipliedAgainAndMissingMapIsRejected() {
        val m = metadata()
        `when`(m.lensShadingMap).thenReturn(null)
        assertThrows(IllegalArgumentException::class.java) {
            ShadedFloatDngWriter.write(ByteArrayOutputStream(),source(),stride,2,ImageFormat.RAW_SENSOR,m,DngMetadataOverrides())
        }
        `when`(m.lensShadingAlreadyApplied).thenReturn(true)
        val bytes = ByteArrayOutputStream().also { ShadedFloatDngWriter.write(it,source(),stride,2,
            ImageFormat.RAW_SENSOR,m,DngMetadataOverrides()) }.toByteArray()
        val tags = fields(bytes); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val offset = b.getInt(tags.getValue(273).second)
        assertEquals(1f,b.getFloat(offset+4),0f)
    }
}
