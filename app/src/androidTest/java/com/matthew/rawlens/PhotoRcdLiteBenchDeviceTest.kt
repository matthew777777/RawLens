// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Headless recording-RCD benchmark: the fused RAW16 -> P010 frame of the
 * recorder (4080x3060 sensor, 3840x2160 crop) without camera or encoder.
 * Reports per-pass GPU timestamps and optionally dumps the last P010 frame.
 * Optional files in the app's external files dir:
 *  - lite.spv: frame-global SPIR-V to benchmark instead of the packaged one;
 *  - frame.raw16: 4080x3060 little-endian RAW16 (else a synthetic pattern);
 * Arguments: frames (default 40), modes ("3840x2160,2560x1440"), dump (label),
 * black/white (levels for frame.raw16), channels ("0,1,2,3").
 * RCD-lite is the default; `setprop debug.rawlens.rcd_exact 1` selects the
 * exact frame-global passes.
 */
@RunWith(AndroidJUnit4::class)
class PhotoRcdLiteBenchDeviceTest {
    @Test fun benchmark() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext;val args=InstrumentationRegistry.getArguments()
        check(VfEglImport.available)
        val dir=checkNotNull(context.getExternalFilesDir(null))
        PhotoRcdRenderer.frameGlobalShaderOverride=File(dir,"lite.spv").takeIf { it.exists() }?.readBytes()
        val sw=4080;val sh=3060;val row=sw*2;val left=120;val top=450;val w=3840;val h=2160
        val raw=ByteBuffer.allocateDirect(row*sh).order(ByteOrder.LITTLE_ENDIAN)
        val real=File(dir,"frame.raw16")
        var black=floatArrayOf(64f,64.5f,63.25f,65f);var white=4095f
        if(real.exists()) {
            check(real.length()==(row*sh).toLong())
            real.inputStream().channel.use { while(raw.hasRemaining() && it.read(raw)>=0) {} };raw.clear()
            val level=args.getString("black","64").toFloat();black=FloatArray(4) { level };white=args.getString("white","1023").toFloat()
        } else {
            // Smooth ramps, edges in both directions and fine texture.
            repeat(sh) { y -> repeat(sw) { x ->
                val v=300+((x*3+y*2) and 1023)+(if(((x shr 6)+(y shr 5)) and 1==0) 900 else 0)+((x*137+y*991+17)%211)
                raw.putShort(y*row+x*2,v.coerceAtMost(4000).toShort()) } }
        }
        val channels=args.getString("channels","0,1,2,3").split(",").map { it.toInt() }.toIntArray()
        val frames=args.getString("frames","40").toInt();val dump=args.getString("dump")
        val wb=floatArrayOf(1.9f,1f,1.6f)
        val ccm=floatArrayOf(1.6f,-.45f,-.15f,-.2f,1.4f,-.2f,0f,-.5f,1.5f)
        val report=JSONObject();val text=StringBuilder()
        for(mode in args.getString("modes","3840x2160,2560x1440,1920x1080").split(",")) {
            val ow=mode.substringBefore("x").toInt();val oh=mode.substringAfter("x").toInt()
            val strides=intArrayOf(ow*2,ow*2,ow,oh)
            val (_,params)=VfVideoGrade.packGrade(intArrayOf(ow,oh),floatArrayOf(1f,1f,1f,1f),ccm,0f,false,profile=EncodedVideoProfile.BT709)
            PhotoRcdRenderer.open(context,requirementsInput=true,normalizeOnce=true,tileLocal=true,frameGlobal=true).use { engine ->
                engine.setOutputSampling(ow,oh)
                checkNotNull(VfEglImport.createLinearP010(ow,oh)).use { output ->
                    val costs=ArrayList<DoubleArray>();val wall=ArrayList<Double>()
                    repeat(frames+1) {
                        val start=System.nanoTime()
                        val fd=engine.renderP010RawBuffer(raw,sw,sh,row,2,left,top,w,h,channels,black,white,wb,null,null,output,params,strides)
                        assertTrue(fd>=0)
                        try { assertTrue(VfEglImport.awaitSyncFd(fd,10_000)) } finally { VfEglImport.closeSyncFd(fd) }
                        wall.add((System.nanoTime()-start)/1e6)
                        // Costs are collected by the next submit: this is the previous frame.
                        costs.add(engine.frameCosts())
                    }
                    val stages=JSONObject();var sumMedian=0.0;var sumMin=0.0
                    val line=StringBuilder("$mode")
                    for(stage in costs.last().indices) {
                        val values=costs.drop(3).map { it[stage] }.filter { it>0 }.sorted()
                        if(values.isEmpty()) continue
                        val median=values[values.size/2];sumMedian+=median;sumMin+=values.first()
                        stages.put("$stage",JSONObject().put("median_ms",median).put("min_ms",values.first()))
                        line.append("  s$stage %.1f/%.1f".format(median,values.first()))
                    }
                    val sorted=wall.drop(3).sorted()
                    line.append("  GPU sum med %.1f min %.1f  wall med %.1f min %.1f".format(sumMedian,sumMin,sorted[sorted.size/2],sorted.first()))
                    text.append(line).append('\n')
                    report.put(mode,JSONObject().put("stages",stages).put("gpu_sum_median_ms",sumMedian).put("gpu_sum_min_ms",sumMin).put("wall_median_ms",sorted[sorted.size/2]))
                    if(dump!=null) {
                        val y=ByteBuffer.allocateDirect(ow*2*oh);val u=ByteBuffer.allocateDirect(ow*oh/2);val v=ByteBuffer.allocateDirect(ow*oh/2)
                        check(VfEglImport.copyP010ToCodec(output,y,ow*2,u,ow,2,v,ow,2,ow,oh,ow*2,ow*2)==0)
                        File(dir,"bench-$dump-$mode.yuv").outputStream().channel.use { for(plane in listOf(y,u,v)) { plane.clear();while(plane.hasRemaining()) it.write(plane) } }
                    }
                }
            }
        }
        File(dir,"lite-bench.json").writeText(report.put("frames",frames).put("real_frame",real.exists()).put("shader_override",PhotoRcdRenderer.frameGlobalShaderOverride!=null).toString(2))
        PhotoRcdRenderer.frameGlobalShaderOverride=null
        instrumentation.sendStatus(0,Bundle().apply { putString("stream","\n"+text) })
    }
}
