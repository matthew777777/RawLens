// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/** The baked VF tone LUT against the direct stills pipeline, in 8-bit encoded codes. */
class VfToneLutTest {
    private fun samples(): List<FloatArray> {
        val rnd = java.util.Random(7)
        return List(4000) {
            // Log-uniform luminance over -12..+4 EV around 1.0, random chroma inside ACEScg.
            val y = 2.0.pow(-12.0 + 16.0 * rnd.nextDouble()).toFloat()
            val sat = rnd.nextFloat().pow(1.5f)
            FloatArray(3) { y * (1f - sat + sat * 2f * rnd.nextFloat()) }
        } + listOf(floatArrayOf(.18f, .18f, .18f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    }

    private fun stats(settings: JpegOutputSettings, si: Int, sc: Int = VfToneLut.SIZE_C): Pair<Float, Float> {
        val lut = VfToneLut.bake(settings, si, sc)
        val errs = samples().map { a ->
            val d = VfToneLut.encode(a, settings); val l = VfToneLut.lookup(lut, a, si, sc)
            (0..2).maxOf { abs(d[it] - l[it]) } * 255f
        }.sorted()
        return errs[(errs.size * 0.99).toInt()] to errs.last()
    }

    private val mappers = PhotoToneMapper.entries.flatMap { m ->
        listOf(JpegOutputSettings(toneMapper = m), JpegOutputSettings(toneMapper = m, displayP3 = true))
    } + JpegOutputSettings(toneMapper = PhotoToneMapper.OPENDRT,
        toneLook = ToneLookSettings(openDrt = OpenDrtSettings().withLook(5)))

    @Test fun lutTracksDirectPipelineForEveryMapper() {
        for (s in mappers) {
            val (p99, max) = stats(s, VfToneLut.SIZE_I)
            println("VF LUT ${s.toneMapper} p3=${s.displayP3}: p99 %.2f max %.2f codes".format(p99, max))
            // Synthetic colours deliberately include extreme ACEScg saturation (well outside
            // real photos, where 48x48 measures p99 <= 2.1 codes for every mapper).
            assertTrue("${s.toneMapper} p3=${s.displayP3} p99 $p99", p99 < 6f)
        }
    }

    @Test fun bakeTiming() {
        if (System.getenv("VF_LUT_SWEEP") == null) return
        for (m in PhotoToneMapper.entries) {
            val s = JpegOutputSettings(toneMapper = m)
            VfToneLut.bake(s) // warm up
            val t0 = System.nanoTime(); VfToneLut.bake(s); val ms = (System.nanoTime() - t0) / 1e6
            println("BAKE $m %.0f ms".format(ms))
        }
    }

    @Test fun halfFloatRoundTripsLutRange() {
        for (v in floatArrayOf(0f, 1e-5f, 0.0031308f, 0.5f, 1f, -0.25f, 1.7f, -1f)) {
            val back = VfToneLutGl.fromHalf(VfToneLutGl.toHalf(v))
            assertTrue("$v -> $back", abs(back - v) <= max(1e-7f, abs(v) * 1e-3f))
        }
    }

    @Test fun shaperRoundTrips() {
        for (x in floatArrayOf(0f, 1e-4f, .18f, 1f, 50f))
            assertTrue(abs(VfToneLut.inverseShaper(VfToneLut.shaper(x)) - x) <= 1e-4f * (1f + x))
    }

    @Test fun worst() {
        if (System.getenv("VF_LUT_SWEEP") == null) return
        for (m in listOf(PhotoToneMapper.BASIC, PhotoToneMapper.SIGMOID)) {
            val st = JpegOutputSettings(toneMapper = m); val lut = VfToneLut.bake(st)
            samples().map { a -> val d = VfToneLut.encode(a, st); val l = VfToneLut.lookup(lut, a)
                Triple(a, d, l) }.sortedByDescending { (_, d, l) -> (0..2).maxOf { abs(d[it] - l[it]) } }.take(6).forEach { (a, d, l) ->
                println("W $m in=" + a.joinToString { "%.5f".format(it) } + " direct=" + d.joinToString { "%.0f".format(it * 255) } + " lut=" + l.joinToString { "%.0f".format(it * 255) }) }
        }
    }

    @Test fun realPhotoSweep() {
        val path = System.getenv("VF_REAL") ?: return
        val b = java.nio.ByteBuffer.wrap(java.io.File(path).readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val px = List(b.capacity() / 12) { FloatArray(3) { b.float } }
        for ((ni, nc) in listOf(48 to 48)) for (m in PhotoToneMapper.entries) {
            val n = "$ni/$nc"
            val st = JpegOutputSettings(toneMapper = m); val lut = VfToneLut.bake(st, ni, nc)
            val e = px.map { a -> val d = VfToneLut.encode(a, st); val l = VfToneLut.lookup(lut, a, ni, nc); (0..2).maxOf { abs(d[it] - l[it]) } * 255f }.sorted()
            println("R N=$n $m: p99 %.2f p99.9 %.2f max %.2f".format(e[(e.size * .99).toInt()], e[(e.size * .999).toInt()], e.last()))
        }
    }

    @Test fun sizeSweep() {
        if (System.getenv("VF_LUT_SWEEP") == null) return
        for (n in intArrayOf(32, 48, 64)) for (s in mappers.take(PhotoToneMapper.entries.size * 2).filter { !it.displayP3 }) {
            val (p99, max) = stats(s, n); println("N=$n ${s.toneMapper}: p99 %.2f max %.2f".format(p99, max))
        }
    }
}
