package com.rawlens.srpipeline

import com.rawlens.srpipeline.stages.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class PolynomialReconstructionTest {
    @Test fun `pooled quadratic reduces known texture error without inventing flat noise`() {
        val w=32;val h=32;val scale=2f
        val fit=PolynomialReconstruction(w*2,h*2,scale,16,0f,0f)
        val num=Array(h*2) { Array(w*2) { FloatArray(3) } }
        val den=Array(h*2) { Array(w*2) { FloatArray(3) } }
        val rng=Random(71)
        fun truth(x:Double,y:Double)=.4+.04*cos(.65*x)+.025*sin(.85*y)
        val cov=Array(h/2) { Array(w/2) { arrayOf(floatArrayOf(.81f,0f),floatArrayOf(0f,.81f)) } }
        for(frame in 0 until 20) {
            val fx=(frame*.6180339887%1).toFloat();val fy=(frame*.4142135623%1).toFloat()
            val raw=Array(h) { y -> FloatArray(w) { x ->
                if((x+y) and 1 == 1) (truth(x+.5-fx,y+.5-fy)+rng.nextGaussian()*.008).toFloat() else 0f
            } }
            val flow=Array(2) { Array(2) { floatArrayOf(fx,fy) } }
            val rob=Array(h/2) { FloatArray(w/2) { 1f } }
            accumulate(raw,flow,cov,rob,true,scale,16,num,den)
            fit.add(raw,flow,rob,1f)
        }
        divide(num,den)
        val result=Array(h*2) { y -> Array(w*2) { x -> num[y][x].copyOf() } }
        fit.finish(result)
        var before=0.0;var after=0.0
        for(y in 12 until h*2-12) for(x in 12 until w*2-12) {
            val target=truth((x+.5)/2,(y+.5)/2)
            before+=(num[y][x][1]-target).pow(2)
            after+=(result[y][x][1]-target).pow(2)
        }
        println("Polynomial known-truth MSE ratio=${after/before}")
        assertTrue("reconstruction improves texture, MSE ratio=${after/before}",after<before*.7)
    }

    @Test fun `constant colour differences and neutral luma stay constant`() {
        val fit=PolynomialReconstruction(24,24,1f,16,0f,0f)
        val raw=Array(24) { y -> FloatArray(24) { x -> when((x and 1)+(y and 1)) { 0 -> .03f;1 -> .4f;else -> -.02f } } }
        repeat(4) { fit.add(raw,Array(2) { Array(2) { floatArrayOf(0f,0f) } },Array(12) { FloatArray(12) { 1f } },1f) }
        val out=Array(24) { Array(24) { floatArrayOf(.03f,.4f,-.02f) } }
        fit.finish(out)
        for(row in out) for(p in row) assertArrayEquals(floatArrayOf(.03f,.4f,-.02f),p,1e-5f)
    }

    @Test fun `colour edge at equal green does not spread into the adjacent surface`() {
        val fit=PolynomialReconstruction(32,32,1f,16,0f,0f)
        val raw=Array(32) { y -> FloatArray(32) { x ->
            when((x and 1)+(y and 1)) { 1 -> .4f;0 -> if(x<16) .08f else -.08f;else -> 0f }
        } }
        repeat(8) { fit.add(raw,Array(2) { Array(2) { floatArrayOf(0f,0f) } },Array(16) { FloatArray(16) { 1f } },1f) }
        val out=Array(32) { Array(32) { floatArrayOf(0f,.4f,0f) } }
        fit.finish(out)
        for(y in 8 until 24) {
            for(x in 8..13) assertEquals("left hue at $x",.08f,out[y][x][0],.003f)
            for(x in 18..23) assertEquals("right hue at $x",-.08f,out[y][x][0],.003f)
        }
    }

    @Test fun `one accepted frame cannot enable burst reconstruction`() {
        val fit=PolynomialReconstruction(16,16,1f,16,0f,0f)
        fit.add(Array(16) { FloatArray(16) { .9f } },Array(1) { Array(1) { floatArrayOf(0f,0f) } },Array(8) { FloatArray(8) { 1f } },1f)
        val out=Array(16) { Array(16) { floatArrayOf(.1f,.2f,.3f) } }
        fit.finish(out)
        for(row in out) for(p in row) assertArrayEquals(floatArrayOf(.1f,.2f,.3f),p,0f)
    }

    @Test fun `rejected support retains fallback`() {
        val fit=PolynomialReconstruction(16,16,1f,16,0f,0f)
        fit.add(Array(16) { FloatArray(16) { .9f } },Array(1) { Array(1) { floatArrayOf(0f,0f) } },Array(8) { FloatArray(8) },1f)
        val out=Array(16) { Array(16) { floatArrayOf(.1f,.2f,.3f) } }
        fit.finish(out)
        for(row in out) for(p in row) assertArrayEquals(floatArrayOf(.1f,.2f,.3f),p,0f)
    }
}
