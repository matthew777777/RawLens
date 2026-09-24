package com.matthew.rawlens

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DualRawDngMetadataTest {
    @Test fun updatesLevelsWithoutMovingPixelsOrCalibrationInEitherByteOrder() {
        for(order in listOf(ByteOrder.LITTLE_ENDIAN,ByteOrder.BIG_ENDIAN)) {
            val f=File.createTempFile("dual-dng-test", ".tif")
            try {
                val b=ByteBuffer.allocate(256).order(order)
                b.put(if(order==ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte())
                b.put(b.get(0)).putShort(42).putInt(8).putShort(5)
                fun entry(tag:Int,type:Int,count:Int,value:Int) { b.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value) }
                entry(273,4,1,200); entry(50714,5,4,100); entry(50717,4,1,1023)
                entry(50721,10,9,140); entry(51041,12,6,0)
                b.putInt(0); b.position(100); repeat(4) { b.putInt(64).putInt(1) }
                for(i in 140 until 256) b.put(i,(i%127).toByte())
                val before=b.array().copyOf(); f.writeBytes(before)
                DualRawDngMetadata.patch(f,4.0)
                val after=f.readBytes(); val parsed=ByteBuffer.wrap(after).order(order)
                val root=parsed.getInt(4)
                assertEquals(5,parsed.getShort(root).toInt())
                assertEquals(65535,parsed.getInt(root+2+2*12+8))
                repeat(4) { assertEquals(1024,parsed.getInt(100+it*8)); assertEquals(1,parsed.getInt(104+it*8)) }
                assertEquals(200,parsed.getInt(root+10))
                assertEquals(0,parsed.getInt(root+2+5*12))
                assertEquals(50730,parsed.getShort(root+2+4*12).toInt() and 65535)
                val evOffset=parsed.getInt(root+2+4*12+8)
                assertEquals(2.0,parsed.getInt(evOffset).toDouble()/parsed.getInt(evOffset+4),1e-6)
                assertArrayEquals(before.copyOfRange(140,256),after.copyOfRange(140,256))
            } finally { f.delete() }
        }
    }
}
