package com.matthew.rawlens

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object DualRawDngMetadata {
    /** Keep Camera2 calibration/crop/opcodes, update levels and drop stale source noise. */
    fun patch(file:File, headroom:Double = 1.0) {
        require(headroom.isFinite() && headroom >= 1.0)
        RandomAccessFile(file,"rw").use { f ->
            val header=ByteArray(8); f.readFully(header)
            val order=if(header[0]=='I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val h=ByteBuffer.wrap(header).order(order)
            check(h.getShort(2).toInt()==42)
            val ifd=h.getInt(4).toLong() and 0xffffffffL
            f.seek(ifd); val countBytes=ByteArray(2); f.readFully(countBytes)
            val count=ByteBuffer.wrap(countBytes).order(order).short.toInt() and 65535
            val entries=ByteArray(count*12); f.readFully(entries)
            val next=ByteArray(4); f.readFully(next)
            val kept=mutableListOf<ByteArray>(); var blackFound=false; var whiteFound=false
            var baseline=0.0
            for(i in 0 until count) {
                val entry=entries.copyOfRange(i*12,(i+1)*12); val b=ByteBuffer.wrap(entry).order(order)
                when(b.getShort(0).toInt() and 65535) {
                    50714 -> {
                        check(b.getShort(2).toInt()==5 && b.getInt(4)==4)
                        val offset=b.getInt(8).toLong() and 0xffffffffL
                        f.seek(offset); val black=ByteBuffer.allocate(32).order(order)
                        repeat(4) { black.putInt(1024).putInt(1) }; f.write(black.array()); blackFound=true
                    }
                    50717 -> { check(b.getShort(2).toInt()==4 && b.getInt(4)==1); b.putInt(8,65535); whiteFound=true }
                    50730 -> {
                        check(b.getShort(2).toInt()==10 && b.getInt(4)==1)
                        f.seek(b.getInt(8).toLong() and 0xffffffffL)
                        val value=ByteArray(8); f.readFully(value)
                        val rational=ByteBuffer.wrap(value).order(order)
                        val numerator=rational.int; val denominator=rational.int
                        check(denominator != 0)
                        baseline=numerator.toDouble()/denominator
                        continue
                    }
                    51041 -> continue // NoiseProfile belongs to the unmerged reference.
                }
                kept.add(entry)
            }
            check(blackFound && whiteFound)
            // Append a new root IFD, retaining all existing payload offsets.
            // BaselineExposure compensates the storage scaling, without clipping HDR.
            val ev=baseline+kotlin.math.log2(headroom)
            require(ev.isFinite() && kotlin.math.abs(ev) < 2000.0)
            val payload=(f.length()+3L) and -4L
            val root=payload+8
            check(root+2+(kept.size+1)*12+4 < 0xffffffffL)
            f.seek(payload)
            f.write(ByteBuffer.allocate(8).order(order)
                .putInt(kotlin.math.round(ev*1_000_000).toInt()).putInt(1_000_000).array())
            kept.add(ByteBuffer.allocate(12).order(order).putShort(50730.toShort())
                .putShort(10).putInt(1).putInt(payload.toInt()).array())
            kept.sortBy { ByteBuffer.wrap(it).order(order).getShort(0).toInt() and 65535 }
            f.seek(root); f.write(ByteBuffer.allocate(2).order(order).putShort(kept.size.toShort()).array())
            kept.forEach { f.write(it) }; f.write(next)
            f.seek(4); f.write(ByteBuffer.allocate(4).order(order).putInt(root.toInt()).array())
        }
    }
}
