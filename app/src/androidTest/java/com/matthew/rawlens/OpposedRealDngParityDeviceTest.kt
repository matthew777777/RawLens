// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Opt-in full-resolution buffers dumped by the actual darktable highlight module. */
class OpposedRealDngParityDeviceTest {
    private fun read(file: File, count: Int): FloatArray {
        val out=FloatArray(count)
        val bytes=ByteBuffer.allocateDirect(256*1024).order(ByteOrder.LITTLE_ENDIAN)
        FileInputStream(file).channel.use { channel ->
            var offset=0
            while(offset<count) {
                val n=minOf(bytes.capacity()/4,count-offset)
                bytes.clear(); bytes.limit(n*4)
                while(bytes.hasRemaining()) check(channel.read(bytes)>0)
                bytes.flip(); bytes.asFloatBuffer().get(out,offset,n); offset+=n
            }
        }
        return out
    }
    private fun write(file: File, values: FloatArray) {
        val bytes=ByteBuffer.allocateDirect(256*1024).order(ByteOrder.LITTLE_ENDIAN)
        FileOutputStream(file).channel.use { channel ->
            var offset=0
            while(offset<values.size) {
                val n=minOf(bytes.capacity()/4,values.size-offset)
                bytes.clear(); bytes.limit(n*4); bytes.asFloatBuffer().put(values,offset,n)
                while(bytes.hasRemaining()) channel.write(bytes)
                offset+=n
            }
        }
    }
    @Test fun compareActualDarktableStageDump() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name=InstrumentationRegistry.getArguments().getString("opposedCase")
        org.junit.Assume.assumeTrue("Pass -e opposedCase <case directory>",name!=null)
        val dir=File(context.getExternalFilesDir(null),"opposed-real-dng/$name")
        val manifest=JSONObject(File(dir,"manifest.json").readText())
        val w=manifest.getInt("width"); val h=manifest.getInt("height")
        val pattern=BayerPattern.valueOf(manifest.getString("pattern"))
        val input=read(File(dir,"input.f32"),w*h)
        val expected=read(File(dir,"darktable-output.f32"),w*h)
        val cfa=UnpackedRawCfa(w,h,pattern,input,RawCrop(0,0,w,h))
        val start=SystemClock.elapsedRealtimeNanos()
        val result=VulkanHub.openVulkan10().use { vk ->
            OpposedHighlightsVulkan.Session(vk,context.assets).use { it.run(cfa) }
        }
        var bits=0; var changed=0; var maxError=0f; var sum=0.0
        for(i in input.indices) {
            val a=result.cfa.values[i]; val e=expected[i]
            val error=kotlin.math.abs(a-e)
            maxError=maxOf(maxError,error); sum+=error
            if(a.toRawBits()!=e.toRawBits()) bits++
            if(a.toRawBits()!=input[i].toRawBits()) changed++
            assertTrue(a.isFinite())
            if(input[i]<OpposedHighlights.CLIP) assertEquals(input[i].toRawBits(),a.toRawBits())
        }
        val report=JSONObject().put("case",name).put("pixels",input.size).put("bit_mismatches",bits)
            .put("max_abs_error",maxError).put("mean_abs_error",sum/input.size).put("changed_pixels",changed)
            .put("chroma",org.json.JSONArray(result.chroma.toList()))
            .put("elapsed_ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6)
        File(dir,"vulkan-report.json").writeText(report.toString(2))
        write(File(dir,"vulkan-output.f32"),result.cfa.values)
        Log.i("OpposedDng",report.toString())
        // Numerical bound is separate from the strict bit-mismatch report.
        assertTrue("Unexpected reconstruction divergence: $report",maxError<0.0001f)
    }
}
