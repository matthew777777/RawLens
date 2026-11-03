// SPDX-License-Identifier: GPL-3.0-or-later
// Host compatibility adapter only; existing photo reference is not the oracle.
import com.matthew.rawlens.RcdReference
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main(args: Array<String>) {
    val w=args[0].toInt();val h=args[1].toInt();val pattern=args[2]
    val input=ByteBuffer.wrap(File(args[3]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val codes=IntArray(w*h) { input.int }
    val greens=pattern.indices.filter { pattern[it]=='G' }
    val channels=intArrayOf(pattern.indexOf('R'),greens[0],greens[1],pattern.indexOf('B'))
    val rgb=RcdReference.demosaic(codes,w,h,w,0,0,w,h,channels,FloatArray(4),65536f)
    val output=ByteBuffer.allocate(rgb.size*4).order(ByteOrder.LITTLE_ENDIAN)
    rgb.forEach { output.putFloat(it) };File(args[4]).writeBytes(output.array())
}
