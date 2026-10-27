// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class OpposedHighlightsDeviceTest {
    @Test fun compareEveryOutputAndRowStatisticAcrossPatternsAndBorders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        VulkanHub.openVulkan10().use { vk ->
            for (pattern in BayerPattern.entries) for ((w,h) in listOf(4 to 4, 50 to 46, 192 to 144)) {
                val values = FloatArray(w*h) { i ->
                    val x=i%w; val y=i/w
                    val c=pattern.colorOrdinalAt(x,y)
                    val level = if (x>w/4 && x<3*w/4 && y>h/4 && y<3*h/4) 2.2f else 0.6f
                    minOf(1f, level * floatArrayOf(0.5f,1f,1.6f)[c])
                }
                val cfa=UnpackedRawCfa(w,h,pattern,values,RawCrop(0,0,w,h))
                val cpu=OpposedHighlights.cpu(cfa)
                val analysis=OpposedHighlights.analyze(cfa)
                val start=SystemClock.elapsedRealtimeNanos()
                val result=OpposedHighlightsVulkan.run(vk,context.assets,cfa)
                var maxError=0f; var unequalBits=0
                for(i in values.indices) {
                    val error=abs(cpu.values[i]-result.cfa.values[i]); maxError=maxOf(maxError,error)
                    if(cpu.values[i].toRawBits()!=result.cfa.values[i].toRawBits()) unequalBits++
                    assertTrue("finite $pattern $w $i", result.cfa.values[i].isFinite())
                    assertTrue("error $pattern $w $i = $error",error<=0.00001f)
                    if(values[i]<OpposedHighlights.CLIP) assertEquals(values[i].toRawBits(),result.cfa.values[i].toRawBits())
                    assertTrue(result.cfa.values[i]>=values[i])
                }
                assertEquals("bit parity $pattern ${w}x$h",0,unequalBits)
                for(y in 0 until h) for(c in 0..2)
                    assertEquals(analysis.rows[y*8+4+c],result.rows[y*8+4+c],0f)
                for(c in 0..2) assertEquals(analysis.chroma[c],result.chroma[c],0.00001f)
                Log.i("OpposedParity","$pattern ${w}x$h maxError=$maxError unequalBits=$unequalBits totalMs=${(SystemClock.elapsedRealtimeNanos()-start)/1e6}")
            }
        }
    }
    @Test fun shadingDoesNotManufactureClippingAndFullResolutionTiming() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        VulkanHub.openVulkan10().use { vk ->
            val w=96; val h=72
            val input=UnpackedRawCfa(w,h,BayerPattern.RGGB,FloatArray(w*h) { 1.5f },RawCrop(0,0,w,h))
            val shading=LensShadingModel(2,2,FloatArray(16) { 2f },IntRectSnapshot(0,0,w,h))
            val output=OpposedHighlightsVulkan.run(vk,context.assets,input,shading).cfa
            for(i in input.values.indices) assertEquals(input.values[i].toRawBits(),output.values[i].toRawBits())
            val bigW=4096; val bigH=3072
            val big=UnpackedRawCfa(bigW,bigH,BayerPattern.RGGB,FloatArray(bigW*bigH) { i ->
                val x=i%bigW; val y=i/bigW
                if(x in 1900..2200 && y in 1400..1700) 1f
                else floatArrayOf(0.3f,0.6f,0.6f,0.96f)[2*(y and 1)+(x and 1)]
            },RawCrop(0,0,bigW,bigH))
            val cold=SystemClock.elapsedRealtimeNanos()
            OpposedHighlightsVulkan.Session(vk,context.assets).use { session ->
                val start=SystemClock.elapsedRealtimeNanos()
                val result=session.run(big)
                Log.i("OpposedParity","OPT roi=${result.roi.toList()} peakImageMiB=${result.peakImageBytes/1048576.0} phasesMs=${result.phaseMs.toList()}")
                assertEquals(big.values.size,result.cfa.values.size)
                assertTrue(result.cfa.values.all { it.isFinite() })
                Log.i("OpposedParity","FULL 4096x3072 warmMs=${(SystemClock.elapsedRealtimeNanos()-start)/1e6} coldMs=${(SystemClock.elapsedRealtimeNanos()-cold)/1e6}")
                val small=session.run(input,shading).cfa
                for(i in input.values.indices) assertEquals(input.values[i].toRawBits(),small.values[i].toRawBits())
            }
        }
    }

    @Test fun randomizedChannelClippingAndVaryingLensShading() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        VulkanHub.openVulkan10().use { vk ->
            for(pattern in BayerPattern.entries) {
                val w=96; val h=78
                val random=java.util.Random(742L)
                val samples=FloatArray(w*h) { i ->
                    if(i%7==0) 1f else random.nextFloat()*0.98f
                }
                val raw=UnpackedRawCfa(w,h,pattern,samples,RawCrop(0,0,w,h),3,5)
                val model=LensShadingModel(3,4,FloatArray(48) { 1f+(it%11)*0.15f },IntRectSnapshot(0,0,120,100))
                for(shading in listOf(null,model)) {
                    val cfa=if(shading==null) raw else LensShadingCorrector.apply(raw,shading).cfa
                    val cpu=OpposedHighlights.cpu(cfa,shading)
                    val result=OpposedHighlightsVulkan.run(vk,context.assets,cfa,shading)
                    var unequal=0; var maxError=0f
                    for(i in samples.indices) {
                        if(cpu.values[i].toRawBits()!=result.cfa.values[i].toRawBits()) unequal++
                        maxError=maxOf(maxError,abs(cpu.values[i]-result.cfa.values[i]))
                        assertTrue("$pattern shaded=${shading!=null} pixel=$i",abs(cpu.values[i]-result.cfa.values[i])<0.00001f)
                        if(samples[i]<OpposedHighlights.CLIP) assertEquals(cfa.values[i].toRawBits(),result.cfa.values[i].toRawBits())
                    }
                    assertEquals("random bit parity $pattern shaded=${shading!=null}",0,unequal)
                    Log.i("OpposedParity","RANDOM $pattern shaded=${shading!=null} unequalBits=$unequal maxError=$maxError")
                }
            }
        }
    }

    @Test fun diskCacheAndOwnedArrayReusePreserveEveryBit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File(context.cacheDir,"opposed-parity-cache.bin")
        val cfa=UnpackedRawCfa(96,72,BayerPattern.GRBG,FloatArray(96*72) { i ->
            if(i%9==0) 1f else 0.4f+(i%19)*0.03f
        },RawCrop(0,0,96,72))
        val expected=OpposedHighlights.cpu(cfa)
        try {
            file.writeBytes(byteArrayOf(1,2,3)) // corrupt/truncated caches must be ignored
            repeat(2) { pass ->
                VulkanHub.openVulkan10().use { vk ->
                    vk.loadPipelineCache(file)
                    val start=SystemClock.elapsedRealtimeNanos()
                    OpposedHighlightsVulkan.Session(vk,context.assets).use { session ->
                        val ms=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                        vk.savePipelineCache(file)
                        assertTrue(file.length()>=32)
                        val owned=cfa.copy(values=cfa.values.copyOf())
                        val result=session.run(owned,owned=true)
                        assertSame(owned.values,result.cfa.values)
                        for(i in expected.values.indices)
                            assertEquals(expected.values[i].toRawBits(),result.cfa.values[i].toRawBits())
                        Log.i("OpposedParity","CACHE pass=$pass compileMs=$ms bytes=${file.length()}")
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test fun full12MpDenseClippingAndShadingStayWithinMemoryBudget() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        VulkanHub.openVulkan10().use { vk ->
            OpposedHighlightsVulkan.Session(vk,context.assets).use { session ->
                val w=4096; val h=3072
                for(dense in listOf(false,true)) {
                    val values=FloatArray(w*h) { i ->
                        if(dense || (i%w in 1900..2200 && i/w in 1400..1700)) 1f else 0.7f
                    }
                    val cfa=UnpackedRawCfa(w,h,BayerPattern.RGGB,values,RawCrop(0,0,w,h))
                    val model=LensShadingModel(2,2,FloatArray(16) { 2f },IntRectSnapshot(0,0,w,h))
                    for(i in values.indices) values[i]*=2f
                    val start=SystemClock.elapsedRealtimeNanos()
                    val result=session.run(cfa,model,owned=true)
                    assertSame(values,result.cfa.values)
                    for(i in values.indices) {
                        val clipped=dense || (i%w in 1900..2200 && i/w in 1400..1700)
                        if(!clipped) assertEquals(1.4f.toRawBits(),values[i].toRawBits())
                        else assertTrue(values[i].isFinite() && values[i]>=2f)
                    }
                    assertTrue(result.peakImageBytes<=w.toLong()*h*8+1048576)
                    Log.i("OpposedParity","DENSE=$dense shaded=true totalMs=${(SystemClock.elapsedRealtimeNanos()-start)/1e6} roi=${result.roi.toList()} peakImageMiB=${result.peakImageBytes/1048576.0} phasesMs=${result.phaseMs.toList()}")
                }
            }
        }
    }

}
