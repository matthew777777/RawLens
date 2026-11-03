// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.hardware.HardwareBuffer
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real clipped-water CFA through shipping shaders, plus 2x2 chroma regression. */
class MhcHighlightDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun asset(name: String) = context.assets.open("shaders/vf/$name.spv").use { it.readBytes() }
    private fun submit(block: () -> Int) {
        val end = SystemClock.elapsedRealtime() + 5000
        var fd = block()
        while (fd == -VfVulkan.BUSY && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(1); fd = block()
        }
        assertTrue("submit $fd", fd >= 0)
        try {
            while (!VfEglImport.pollSyncFd(fd) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(1)
            assertTrue("fence timeout", VfEglImport.pollSyncFd(fd))
        } finally { VfEglImport.closeSyncFd(fd) }
    }

    @Test fun clippedWaterAndChromaCells() {
        val fixture = File("/data/local/tmp/rawlens_mhc_highlights/water_64x64.u16")
        assumeTrue("push extracted DNG fixture", fixture.exists())
        val bytes = fixture.readBytes()
        val n = 64
        assertEquals(n*n*2, bytes.size)
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val codes = IntArray(n*n) { shorts.get(it).toInt() and 65535 }
        val channels = intArrayOf(2,0,3,1)
        val gains = floatArrayOf(1.9296875f,1f,1.60546875f,1f)
        val cpu = MhcReference.demosaicCoherent(codes,n,n,n,0,0,n,n,channels,FloatArray(4){64f},1023f)
        val protected = MhcReference.desaturateHighlights(cpu,gains)
        val matrix = floatArrayOf(1.652374f,-.5976887f,-.05468537f,-.12891701f,1.3691307f,-.24021374f,.01562188f,-.499987f,1.4843651f)
        val encoded = Array(n*n) { i ->
            val v = FloatArray(3) { k -> protected[i*3+k]*gains[k] }
            VfVideoGrade.encodePixel709(FloatArray(3) { k -> (0..2).sumOf { j -> (matrix[k*3+j]*v[j]).toDouble() }.toFloat() })
        }
        fun yuv(i: Int): FloatArray {
            val v=encoded[i]; val y=.2126f*v[0]+.7152f*v[1]+.0722f*v[2]
            return floatArrayOf(y,(v[2]-y)/1.8556f+.5f,(v[0]-y)/1.5748f+.5f)
        }
        val report=StringBuilder()
        for (variant in listOf("vf_mhcyuv","vf_mhcyuv_f16")) {
            assertEquals(0,VfVulkan.initNative(asset("vf_superpixel")))
            if (variant.endsWith("f16") && !VfVulkan.supportsF16MathNative()) { VfVulkan.resetNative(); continue }
            val raw=requireNotNull(VfEglImport.createBlobBayerInput(n,n))
            val data=ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
            assertEquals(0,VfEglImport.blitBytesToBlob(raw,data,bytes.size))
            val p010=requireNotNull(VfEglImport.createP010(n,n))
            try {
                assertEquals(0,VfVideoGrade.initGradeNative(asset("vf_videograde")))
                assertEquals(0,VfVideoGrade.initFusedYuvNative(asset(variant)))
                assertEquals(0,VfVideoGrade.gradeLutUploadNative(VfVideoGrade.bakeLut(1f)))
                val layout=requireNotNull(VfEglImport.describeP010(p010))
                val dims=intArrayOf(n,n)
                val (mi,mf)=VfMhc.packMhc(n,n,0,0,channels,n,FloatArray(4){64f},1023f)
                val (_,gf)=VfVideoGrade.packGrade(dims,gains,matrix,0f,false,profile=EncodedVideoProfile.BT709)
                repeat(2) { submit { VfVideoGrade.fusedYuvSubmitNative(raw,p010,mi,mf,dims,gf,intArrayOf(layout[0],layout[1],n,n),0,null,null) } }
                var maxError=0
                for (y in 2 until n-2 step 2) for (x in 2 until n-2 step 2) {
                    val samples=listOf(yuv(y*n+x),yuv(y*n+x+1),yuv((y+1)*n+x),yuv((y+1)*n+x+1))
                    for (dy in 0..1) for (dx in 0..1) {
                        val actual=requireNotNull(VfEglImport.sampleP010Center(p010,layout[0],layout[1],n,n,x+dx,y+dy))
                        for (k in 0..2) {
                            val value=if(k==0) samples[dy*2+dx][0] else samples.sumOf { it[k].toDouble() }.toFloat()*.25f
                            val expected=(64+value.coerceIn(0f,1f)*(if(k==0)876 else 896)).roundToInt()
                            val error=abs((actual[k] ushr 6)-expected); maxError=maxOf(maxError,error)
                            assertTrue("$variant ($x,$y)+($dx,$dy) ch=$k actual=${actual[k] ushr 6} expected=$expected",error<=if(variant.endsWith("f16"))8 else 2)
                        }
                    }
                }
                report.append("$variant max10BitError=$maxError\n")
            } finally { VfVulkan.resetNative(); p010.close(); raw.close() }
        }
        Log.i("MhcHighlights",report.toString())
        File(context.getExternalFilesDir(null),"mhc-highlight-validation.txt").writeText(report.toString())
    }
    @Test fun sustainedHeadlessFrameBudgets() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("-e rawlensMhcPerf true",args.getString("rawlensMhcPerf")=="true")
        val iterations=args.getString("iterations","300").toInt().coerceIn(60,3000)
        val report=StringBuilder()
        val violations=mutableListOf<String>()
        for ((width,height,fps) in listOf(Triple(1920,1080,30),Triple(2560,1440,30),Triple(3840,2160,24))) {
            assertEquals(0,VfVulkan.initNative(asset("vf_superpixel")))
            val f16=VfVulkan.supportsF16MathNative()
            val variant=if(f16 && args.getString("precision","fp32")!="fp32") "vf_mhcyuv_f16" else "vf_mhcyuv"
            val sw=4080; val sh=3060
            val raw=requireNotNull(VfEglImport.createBlobBayerInput(sw,sh))
            assertEquals(sw,VfEglImport.fillBlobBayer(raw,sw,sh))
            val p010=requireNotNull(VfEglImport.createP010(width,height))
            try {
                assertEquals(0,VfVideoGrade.initGradeNative(asset("vf_videograde")))
                assertEquals(0,VfVideoGrade.initFusedYuvNative(asset(variant)))
                assertEquals(0,VfVideoGrade.gradeLutUploadNative(VfVideoGrade.bakeLut(1f)))
                val layout=requireNotNull(VfEglImport.describeP010(p010))
                val dims=intArrayOf(width,height)
                val (mi,mf)=VfMhc.packMhc(3840,2160,120,450,intArrayOf(0,1,2,3),sw,FloatArray(4),1023f)
                val (_,gf)=VfVideoGrade.packGrade(dims,floatArrayOf(1.93f,1f,1.61f,1f),VfVideoGrade.IDENTITY_CCM,0f,false,profile=EncodedVideoProfile.ACES)
                val strides=intArrayOf(layout[0],layout[1],width,height)
                fun frame() {
                    val end=System.nanoTime()+5_000_000_000L
                    var fd=VfVideoGrade.fusedYuvSubmitNative(raw,p010,mi,mf,dims,gf,strides,0,null,null)
                    while(fd == -VfVulkan.BUSY && System.nanoTime()<end) {
                        Thread.yield();fd=VfVideoGrade.fusedYuvSubmitNative(raw,p010,mi,mf,dims,gf,strides,0,null,null)
                    }
                    assertTrue("perf submit $fd",fd>=0)
                    try {
                        while(!VfEglImport.pollSyncFd(fd) && System.nanoTime()<end) Thread.yield()
                        assertTrue("perf fence timeout",VfEglImport.pollSyncFd(fd))
                    } finally { VfEglImport.closeSyncFd(fd) }
                }
                repeat(30) { frame() }
                val ms=DoubleArray(iterations) {
                    val t=System.nanoTime();frame();(System.nanoTime()-t)/1e6
                }.sorted()
                val budget=1000.0/fps
                val over=ms.count { it>budget }
                val line="${width}x$height@$fps variant=$variant frames=$iterations mean=${ms.average()} p50=${ms[iterations/2]} p95=${ms[(iterations*.95).toInt()]} p99=${ms[(iterations*.99).toInt()]} max=${ms.last()} budget=$budget over=$over"
                report.append(line).append('\n');Log.i("MhcHighlights",line)
                if(over>0) violations.add(line)
            } finally { VfVulkan.resetNative();p010.close();raw.close() }
        }
        File(context.getExternalFilesDir(null),"mhc-headless-performance.txt").writeText(report.toString())
        assertTrue("Headless budget misses: ${violations.joinToString()}",violations.isEmpty())
    }

}
