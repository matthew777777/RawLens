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
            val nx = 3; val ny = 2; val ow = w * 2; val oh = height * 2
            val raw = FloatArray(w * height) { i -> (i % w + 2 * (i / w)) / 100f }
            val cov = FloatArray(w / 2 * (height / 2) * 4) { if (it % 4 == 0 || it % 4 == 2) 1f else 0f }
            val input = resident(raw); val kernels = resident(cov)
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
                    w, height, ow, oh, cov.size, w / 2 * (height / 2), tile, 2f, 2, nx, ny, 1f))
                val nb = SrpBridge.directFloats(n); val db = SrpBridge.directFloats(n)
                assertEquals(0, SrpBridge.downloadResident(h, num, nb, n.toLong()))
                assertEquals(0, SrpBridge.downloadResident(h, den, db, n.toLong()))
                val gotN = nb.asFloatBuffer(); val gotD = db.asFloatBuffer()
                for (y in 0 until oh) for (x in 0 until ow) {
                    val lx = (x + .5) / 2; val ly = (y + .5) / 2
                    // Closed-form affine field on tile centres, extended constantly at edges.
                    val mx = lx + if (affine) .1 + .2 * ((lx - 8) / 16).coerceIn(0.0, 2.0) else offset.toDouble()
                    val my = ly + if (affine) -.1 + .15 * ((ly - 8) / 16).coerceIn(0.0, 1.0) else offset.toDouble()
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
                            val weight = exp(-.5 * (dx*dx + dy*dy)) * taper(dx) * taper(dy)
                            expectedN[c] += weight * raw[yy*w+xx]; expectedD[c] += weight
                        }
                    }
                    for (c in 0..2) {
                        val i = (y * ow + x) * 3 + c
                        assertEquals("num ($x,$y,$c)", expectedN[c], gotN.get(i).toDouble(), 1e-5)
                        assertEquals("den ($x,$y,$c)", expectedD[c], gotD.get(i).toDouble(), 1e-5)
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
        } finally { SrpBridge.nativeDestroy(h) }
    }
}
