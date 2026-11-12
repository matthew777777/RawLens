// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class LapisDngValidationTest {
    @Test fun acceptsCompleteGbrgDngWithFullFrameMetadata() = fixture().let { file ->
        try { LapisDngValidation.validate(file,4,4) } finally { file.delete() }
    }
    @Test fun rejectsTruncatedPixelPayload() = fixture(truncate = true).let { file ->
        try { assertThrows(IllegalArgumentException::class.java) { LapisDngValidation.validate(file,4,4) } } finally { file.delete() }; Unit
    }
    @Test fun rejectsBinnedCropMetadataOnLargerRaster() = fixture(crop = 2).let { file ->
        try { assertThrows(IllegalArgumentException::class.java) { LapisDngValidation.validate(file,4,4) } } finally { file.delete() }; Unit
    }
    @Test fun rejectsFalseFourteenBitWhiteLevel() = fixture(white = 16383).let { file ->
        try { assertThrows(IllegalArgumentException::class.java) { LapisDngValidation.validate(file,4,4) } } finally { file.delete() }; Unit
    }

    private fun fixture(truncate: Boolean = false, crop: Int = 4, white: Int = 1023): java.io.File {
        fun longs(vararg values: Int) = ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putInt) }.array()
        fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size*2).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()
        fun rationals(vararg values: Int) = ByteBuffer.allocate(values.size*8).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it);putInt(1) } }.array()
        data class Tag(val id: Int,val type: Int,val bytes: ByteArray)
        val fields = listOf(Tag(256,4,longs(4)),Tag(257,4,longs(4)),Tag(258,3,shorts(16)),Tag(259,3,shorts(1)),
            Tag(262,3,shorts(32803)),Tag(273,4,longs(0)),Tag(279,4,longs(32)),Tag(33421,3,shorts(2,2)),
            Tag(33422,1,byteArrayOf(1,2,0,1)),Tag(50713,3,shorts(2,2)),Tag(50714,5,rationals(64,64,64,64)),
            Tag(50717,4,longs(white)),Tag(50719,4,longs(0,0)),Tag(50720,4,longs(crop,crop)),
            Tag(50721,10,rationals(1,0,0,0,1,0,0,0,1)),Tag(50728,5,rationals(1,1,1)),Tag(50829,4,longs(0,0,4,4)))
        val buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0x4949.toShort()).putShort(42).putInt(8).putShort(fields.size.toShort())
        var extra = 8+2+fields.size*12+4
        var stripEntry = 0
        fields.forEach { tag ->
            buffer.putShort(tag.id.toShort()).putShort(tag.type.toShort())
            val unit = when(tag.type) { 3->2;4->4;5,10->8;else->1 }
            buffer.putInt(tag.bytes.size/unit)
            if(tag.id==273)stripEntry=buffer.position()
            if(tag.bytes.size<=4) { buffer.put(tag.bytes);repeat(4-tag.bytes.size){buffer.put(0)} }
            else { buffer.putInt(extra);val at=buffer.position();buffer.position(extra);buffer.put(tag.bytes);extra+=tag.bytes.size;buffer.position(at) }
        }
        buffer.putInt(0);buffer.putInt(stripEntry,extra)
        buffer.position(extra);repeat(if(truncate)16 else 32){buffer.put(64)}
        return Files.createTempFile("lapis-dng-", ".dng").toFile().apply { writeBytes(buffer.array().copyOf(buffer.position())) }
    }
}
