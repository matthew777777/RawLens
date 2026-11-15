package com.rawlens.srpipeline.stages

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MotionFieldTest {
    private fun hessian(w: Int, h: Int, strength: (Int, Int) -> Float) = FloatArray(w*h*4) { i ->
        if (i%4 == 0 || i%4 == 2) strength(i/4%w, i/4/w) else 0f
    }
    private fun field(w: Int, h: Int, f: (Int, Int) -> Pair<Float, Float>) = Array(h) { y ->
        Array(w) { x -> val v = f(x,y); floatArrayOf(v.first, v.second) }
    }
    @Test fun `constant translations remain constant including borders and singular tiles`() {
        for ((w,h) in listOf(1 to 1, 1 to 9, 13 to 1, 17 to 11)) {
            val f = field(w,h) { _,_ -> 3.25f to -1.5f }
            MotionField(w,h,hessian(w,h) { _,_ -> 0f }).regularize(f)
            f.forEach { row -> row.forEach { assertEquals(3.25f,it[0],1e-5f); assertEquals(-1.5f,it[1],1e-5f) } }
        }
    }
    @Test fun `weak noisy tiles inherit textured neighbours without moving reliable anchors`() {
        val w=33; val h=25
        val f=field(w,h) { x,y ->
            if (x in 10..22 && y in 7..17) (.25f + ((x*19+y*7)%11-5)*.2f) to (-.5f + ((x*3+y*17)%11-5)*.2f)
            else .25f to -.5f
        }
        val H=hessian(w,h) { x,y -> if (x in 10..22 && y in 7..17) .001f else .1f }
        // Give the confidence scale a substantial weak background population.
        for (y in 0..6) for (x in 0 until w) { H[(y*w+x)*4]=.001f; H[(y*w+x)*4+2]=.001f }
        for (y in 18 until h) for (x in 0 until w) { H[(y*w+x)*4]=.001f; H[(y*w+x)*4+2]=.001f }
        MotionField(w,h,H).regularize(f)
        var err=0.0;var n=0
        for (y in 7..17) for (x in 10..22) { err+=abs(f[y][x][0]-.25)+abs(f[y][x][1]+.5);n++ }
        assertTrue("weak area error $err / $n",err/n < .08)
        assertEquals(.25f,f[12][3][0],.01f);assertEquals(-.5f,f[12][3][1],.01f)
    }
    @Test fun `supported independent motion stays distinct from the background`() {
        val w=41;val h=31
        val f=field(w,h) { x,y -> if(x in 15..25 && y in 10..20) 4f to -2f else 0f to 0f }
        val H=hessian(w,h) { x,y -> if(x in 15..25 && y in 10..20) 1f else .001f }
        MotionField(w,h,H).regularize(f)
        assertEquals(4f,f[15][20][0],.01f); assertEquals(-2f,f[15][20][1],.01f)
        assertTrue(f[15][15][0]>3.8f)
        assertTrue(abs(f[0][0][0])<.1f)
    }
    @Test fun `affine camera motion is preserved in the interior`() {
        val w=41;val h=31
        val f=field(w,h) { x,y -> (.03f*x+.02f*y) to (-.01f*x+.04f*y) }
        MotionField(w,h,hessian(w,h) { x,y -> if(x%2==0 && y%2==0) 1f else .001f }).regularize(f)
        for (y in 10..20) for (x in 10..30) {
            assertEquals(.03f*x+.02f*y,f[y][x][0],.01f)
            assertEquals(-.01f*x+.04f*y,f[y][x][1],.01f)
        }
    }
}
