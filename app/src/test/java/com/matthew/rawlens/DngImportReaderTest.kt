// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DngImportReaderTest {
    private fun fixture(cycle: Boolean=false): File {
        val root=TiffFields().apply {
            longs(256,4);longs(257,3);shorts(262,2);shorts(274,6)
            bytes(50706,byteArrayOf(1,4,0,0));rationals(50728,doubleArrayOf(.5,1.0,.7))
            srationals(50721,doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0))
        }
        val rootCount=root.payloads.size+1
        val subOffset=8+2+rootCount*12+4
        root.longs(330,if(cycle) 8 else subOffset)
        val sub=TiffFields().apply {
            longs(256,32);longs(257,24);shorts(262,32803);shorts(277,1)
            shorts(33421,2,2);bytes(33422,byteArrayOf(0,1,1,2))
        }
        val b=ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
        b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        var extra=subOffset+2+sub.payloads.size*12+4
        fun ifd(offset: Int,fields: TiffFields) {
            b.position(offset);b.putShort(fields.payloads.size.toShort())
            fields.payloads.forEachIndexed { index,payload ->
                b.putShort(fields.descriptors[index*3].toShort()).putShort(fields.descriptors[index*3+1].toShort()).putInt(fields.descriptors[index*3+2])
                if(payload.size<=4) { b.put(payload);repeat(4-payload.size) { b.put(0) } }
                else { b.putInt(extra);val saved=b.position();b.position(extra);b.put(payload);extra+=payload.size;b.position(saved) }
            }
            b.putInt(0)
        }
        ifd(8,root);ifd(subOffset,sub)
        return File.createTempFile("import-subifd",".dng").apply { writeBytes(b.array().copyOf(extra)) }
    }
    @Test fun choosesRawSubIfdInsteadOfPreviewAndInheritsProfile() {
        val f=fixture()
        try {
            val tags=DngImportReader.readTags(f)
            assertEquals(32.0,tags.getValue(256)[0],0.0)
            assertEquals(32803.0,tags.getValue(262)[0],0.0)
            assertArrayEquals(doubleArrayOf(.5,1.0,.7),tags.getValue(50728),1e-6)
            assertEquals(6.0,tags.getValue(274)[0],0.0)
            assertEquals(9,tags.getValue(50721).size)
        } finally { f.delete() }
    }
    @Test fun rejectsDirectoryCyclesBeforeReadingPixels() {
        val f=fixture(cycle=true)
        try { assertThrows(IllegalArgumentException::class.java) { DngImportReader.readTags(f) } }
        finally { f.delete() }
    }
    @Test fun truncatedInputFailsClearly() {
        val f=File.createTempFile("import-invalid",".dng").apply { writeBytes(byteArrayOf(73,73,42)) }
        try { assertThrows(IllegalArgumentException::class.java) { DngImportReader.readTags(f) } }
        finally { f.delete() }
    }
}
