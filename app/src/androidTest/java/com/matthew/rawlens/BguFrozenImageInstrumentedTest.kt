// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Replay a preserved linear-guide baseline; never substitute a new camera frame. */
class BguFrozenImageInstrumentedTest {
    @Test fun encodedGuideReducesMeasuredShadowLift() {
        val inst=InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass -e rawlensA5Frozen true",InstrumentationRegistry.getArguments()
            .getString("rawlensA5Frozen")=="true")
        val dir=File(inst.targetContext.getExternalFilesDir(null),"a5-linear-baseline")
        val report=StringBuilder()
        for(mode in listOf("RAW","JPEG")) {
            val data=ByteBuffer.wrap(File(dir,"$mode-pairs.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val lw=data.int;val lh=data.int;val w=data.int;val h=data.int
            val lowN=lw*lh;val n=w*h
            val low=FloatArray(lowN*3) { data.float }
            val target=FloatArray(lowN*3) { data.float }
            val full=FloatArray(n*3) { data.float }
            val direct=FloatArray(n*3) { data.float }
            val conditioned=FloatArray(low.size) { BguGuideEncoding.encode(low[it]) }
            val grid=BguFit.fit(conditioned,target,lw,lh)!!
            val guide=ByteBuffer.allocateDirect(n*4)
            for(i in 0 until n) {
                guide.put((full[i]*255+0.5f).toInt().coerceIn(0,255).toByte())
                // Restore the exact half-code green average (two Bayer greens).
                val green=(full[n+i]*510+0.5f).toInt().coerceIn(0,510)
                guide.put((green/2).toByte()).put((green-green/2).toByte())
                guide.put((full[2*n+i]*255+0.5f).toInt().coerceIn(0,255).toByte())
            }
            guide.rewind()
            val output=ByteBuffer.allocateDirect(n*4)
            assertEquals(0,BguSliceCpu.slice(guide,w,h,grid.coeffs,grid.gw,grid.gh,grid.gz,
                lw/16f,lh/16f,8f,false,output))
            val baseline=BitmapFactory.decodeFile(File(dir,"$mode-bgu-8.png").path)
            assertEquals(w,baseline.width);assertEquals(h,baseline.height)
            val old=IntArray(n);baseline.getPixels(old,0,w,0,0,w,h);baseline.recycle()
            val colors=IntArray(n)
            var oldAbs=0.0;var newAbs=0.0;var oldDark=0.0;var newDark=0.0;var darkN=0
            for(i in 0 until n) {
                val channels=FloatArray(3) { direct[it*n+i] }
                val neutralDark=channels.max()<0.25f && channels.max()-channels.min()<0.05f
                var argb=0xff000000.toInt()
                for(ch in 0..2) {
                    val code=output.get(i*4+if(ch==2)3 else ch).toInt() and 255
                    val previous=(old[i] ushr (16-ch*8)) and 255
                    val error=code/255.0-channels[ch]
                    val before=previous/255.0-channels[ch]
                    oldAbs+=kotlin.math.abs(before);newAbs+=kotlin.math.abs(error)
                    if(neutralDark) { oldDark+=before;newDark+=error;darkN++ }
                    argb=argb or (code shl (16-ch*8))
                }
                colors[i]=argb
            }
            assertTrue("No neutral shadow population",darkN>100)
            val oldBias=oldDark/darkN;val newBias=newDark/darkN
            val line="$mode meanAbs old=${oldAbs/(n*3)} new=${newAbs/(n*3)} neutralDarkBias old=$oldBias new=$newBias count=$darkN"
            report.appendLine(line);android.util.Log.i("RawLensBguFrozen",line)
            assertTrue("$mode mean error did not improve",newAbs<oldAbs*0.85)
            assertTrue("$mode shadow lift not at least halved: $line",kotlin.math.abs(newBias)<kotlin.math.abs(oldBias)*0.5)
            val bm=Bitmap.createBitmap(colors,w,h,Bitmap.Config.ARGB_8888)
            File(dir,"$mode-bgu-encoded.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG,100,it) }
            bm.recycle()
        }
        File(dir,"encoded-report.txt").writeText(report.toString())
    }
}
