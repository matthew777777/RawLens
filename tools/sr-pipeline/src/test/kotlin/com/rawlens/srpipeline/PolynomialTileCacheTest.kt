package com.rawlens.srpipeline

import com.rawlens.srpipeline.stages.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class PolynomialTileCacheTest {
    @Test fun `bounded tiles preserve global fit including fractional flow and image boundaries`() {
        fun pack(v:FloatArray)=ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).apply { asFloatBuffer().put(v) }
        val w=32;val h=24
        for(scale in listOf(1f,1.5f,2f)) {
            val ow=(w*scale).toInt();val oh=(h*scale).toInt()
            val config=PipelineConfig(scale=scale,tileSizes=listOf(8),kernelAlpha=FloatArray(4),kernelBeta=FloatArray(4),colourDiff=floatArrayOf(2f,1.6f),grainRetention=0f)
            val full=PolynomialReconstruction(ow,oh,scale,8,0f,0f)
            val fallback=Array(oh) { Array(ow) { floatArrayOf(.01f,.3f,-.02f) } }
            val rgb=pack(FloatArray(ow*oh*3) { i -> when(i%3) { 0 -> .155f;1 -> .3f;else -> .175f } })
            PolynomialTileCache(w,h,config).use { cache ->
                repeat(8) { n ->
                    val frame=Array(h) { y -> FloatArray(w) { x -> when((x and 1)+(y and 1)) {
                        0 -> (.02+.01*sin(x*.3)).toFloat()
                        1 -> (.3+.05*sin((x+y)*.2)).toFloat()
                        else -> (-.01+.01*sin(y*.4)).toFloat()
                    } } }
                    val flow=Array(h/8) { y -> Array(w/8) { x -> floatArrayOf((n%3-1)*.23f+x*.01f,(n%2)*.31f-y*.02f) } }
                    val rob=Array(h/2) { y -> FloatArray(w/2) { x -> if(x==0 && y==0) .1f else .8f } }
                    full.add(frame,flow,rob,1f)
                    cache.record(pack(frame.flatMap { it.asIterable() }.toFloatArray()),flow,pack(rob.flatMap { it.asIterable() }.toFloatArray()))
                }
                full.finish(fallback)
                ColourDiff.reconstruct(fallback,2f,1.6f)
                cache.finish(rgb,8)
                val actual=rgb.asFloatBuffer()
                for(y in 0 until oh) for(x in 0 until ow) for(c in 0..2)
                    assertEquals("scale=$scale x=$x y=$y c=$c",fallback[y][x][c],actual[(y*ow+x)*3+c],2e-6f)
            }
        }
    }
}
