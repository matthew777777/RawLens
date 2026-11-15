package com.rawlens.srpipeline

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp

/** An affine displacement must stay continuous across reconstruction tiles. */
class KernelNetFlowTest {
    @Test fun `kernelnet reconstructs affine flow through tile boundaries and clamps borders`() {
        val lib = System.getProperty("srpipeline.lib", "")
        val spv = System.getProperty("srpipeline.spv_merge_splat", "")
        assumeTrue(lib.isNotEmpty() && spv.isNotEmpty())
        SrpBridge.loadPath(lib)
        val h = SrpBridge.nativeCreate()
        // With explicit native inputs, failure to get a GPU must fail this proof.
        assertTrue("Vulkan device required", h != 0L)
        try {
            fun pack(a: FloatArray) = SrpBridge.directFloats(a.size).apply { asFloatBuffer().put(a) }
            fun resident(a: FloatArray): Long = SrpBridge.createResident(h, a.size.toLong()).also {
                assertEquals(0, SrpBridge.uploadResident(h, it, pack(a), a.size.toLong()))
            }
            // Non-square, partial last tiles exercise both edge clamps.
            val w = 38; val height = 26; val tile = 16
            val nx = 3; val ny = 2
            val raw = FloatArray(w * height) { i -> (i % w + 2 * (i / w)) / 100f }
            val cov = FloatArray(w / 2 * (height / 2) * 4) { if (it % 4 == 0 || it % 4 == 2) 1f else 0f }
            val input = resident(raw); val kernels = resident(cov)
            // Legacy KernelNet (2), detail comparison (10),
            // detail reference (14). Zero flow does not imply
            // reference: the explicit flag must control its fallback width.
            val legacy = HashMap<String,Pair<FloatArray,FloatArray>>()
            for (mode in listOf(2,10,14)) for (scale in listOf(1,2)) {
                val ow = w*scale; val oh = height*scale
                val crossings = ArrayList<Double>()
                for ((affine, offset) in listOf(false to 0f, true to 0f,
                        false to -.2501f, false to -.2499f)) {
                    val flow = FloatArray(nx * ny * 2) { i ->
                        val x = i / 2 % nx; val y = i / 2 / nx
                        if (!affine) offset else if (i % 2 == 0) .1f + .2f * x else -.1f + .15f * y
                    }
                    val n = ow * oh * 3
                    val num = SrpBridge.createResident(h, n.toLong())
                    val den = SrpBridge.createResident(h, n.toLong())
                    assertEquals(0, SrpBridge.nativeMergeSplatFrameRes(h, input, pack(flow), kernels,
                        pack(FloatArray(w / 2 * (height / 2)) { 1f }), num, den, File(spv).readBytes(),
                        w, height, ow, oh, cov.size, w / 2 * (height / 2), tile, scale.toFloat(), mode, nx, ny, 1f))
                    val nb = SrpBridge.directFloats(n); val db = SrpBridge.directFloats(n)
                    assertEquals(0, SrpBridge.downloadResident(h, num, nb, n.toLong()))
                    assertEquals(0, SrpBridge.downloadResident(h, den, db, n.toLong()))
                    val gotN = nb.asFloatBuffer(); val gotD = db.asFloatBuffer()
                    val key = "$scale/$affine/$offset"
                    if(mode==2) legacy[key] = FloatArray(n) { gotN.get(it) } to FloatArray(n) { gotD.get(it) }
                    else {
                        val (baseN,baseD)=legacy.getValue(key)
                        for(i in 0 until n) if(mode==14 || i%3!=1) {
                            assertEquals("reference/chroma numerator unchanged",baseN[i],gotN.get(i),0f)
                            assertEquals("reference/chroma denominator unchanged",baseD[i],gotD.get(i),0f)
                        }
                    }
                    for (y in 0 until oh) for (x in 0 until ow) {
                        val lx = (x + .5) / scale; val ly = (y + .5) / scale
                        // Closed-form affine field on tile centres, extended constantly at edges.
                        // Analytic mean index of a cubic B-spline on a constant-
                        // extended linear ramp (including both border corrections).
                        fun indexMean(g: Double, last: Double): Double {
                            fun bump(x: Double): Double = maxOf(0.0, 1.0-kotlin.math.abs(x)).let { it*it*it/6 }
                            return g.coerceIn(0.0,last)+bump(g)-bump(g-last)
                        }
                        val mx = lx + if (affine) .1 + .2 * indexMean((lx - 8) / 16, 2.0) else offset.toDouble()
                        val my = ly + if (affine) -.1 + .15 * indexMean((ly - 8) / 16, 1.0) else offset.toDouble()
                        val expectedN = DoubleArray(3); val expectedD = DoubleArray(3)
                        if (mx >= 0 && mx < w && my >= 0 && my < height) {
                            for (yy in my.toInt()-3..my.toInt()+3) for (xx in mx.toInt()-3..mx.toInt()+3) {
                                if (xx !in 0 until w || yy !in 0 until height) continue
                                val c = yy % 2 + xx % 2
                                val dx = xx - (mx - .5); val dy = yy - (my - .5)
                                fun taper(d: Double): Double {
                                    val t = (kotlin.math.abs(d) - 2.5).coerceIn(0.0, 1.0)
                                    return 1.0 - t*t*(3 - 2*t)
                                }
                                val area = if(mode==10 && c==1) (4*scale*scale).toDouble() else 1.0
                                val weight = area * exp(-.5 * area * (dx*dx + dy*dy)) * taper(dx) * taper(dy)
                                expectedN[c] += weight * raw[yy*w+xx]; expectedD[c] += weight
                            }
                        }
                        for (c in 0..2) {
                            val i = (y * ow + x) * 3 + c
                            // Narrower kernels amplify fp32 coordinate rounding;
                            // the peak density also grows by the Gaussian area ratio.
                            val tolerance = if(mode==10 && c==1) 1e-5*4*scale*scale else 1e-5
                            assertEquals("num ($x,$y,$c)", expectedN[c], gotN.get(i).toDouble(), tolerance)
                            assertEquals("den ($x,$y,$c)", expectedD[c], gotD.get(i).toDouble(), tolerance)
                        }
                    }
                    if (offset != 0f) {
                        val i = (20 * ow + 20) * 3 + 1
                        crossings += gotN.get(i).toDouble() / gotD.get(i)
                    }
                    SrpBridge.freeResident(h, num); SrpBridge.freeResident(h, den)
                }
                // Tiny translation across an integer gather centre must not
                // introduce a finite jump. The previous hard 3x3 window fails.
                assertEquals("continuous gather at sensor-pixel boundary", crossings[0], crossings[1], 1e-5)
            }
        } finally { SrpBridge.nativeDestroy(h) }
    }
}
